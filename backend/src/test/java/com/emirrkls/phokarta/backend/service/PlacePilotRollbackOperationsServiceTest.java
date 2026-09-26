package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlacePilotRollbackOperationsServiceTest {
    private static final UUID RUN = UUID.fromString("d70adea5-6e3f-4c32-92c0-49695eeeb9ce");
    private static final String HASH = "a".repeat(64);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PlacePilotRollbackService rollback = mock(PlacePilotRollbackService.class);
    private PlacePilotRollbackOperationsService operations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("pg_trigger") ? 13 : sql.contains("pg_constraint") ? 6 : 9;
        });
        when(jdbc.queryForObject(anyString(), eq(OffsetDateTime.class)))
                .thenReturn(OffsetDateTime.parse("2026-09-26T16:00:00Z"));
        when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (!sql.contains("SELECT pilot_run_key")) return List.of();
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("pilot_run_key")).thenReturn("didim-core-ops-test");
            when(rs.getString("manifest_hash")).thenReturn(HASH);
            when(rs.getString("status")).thenReturn("SUCCEEDED");
            when(rs.getString("method_version")).thenReturn("didim-autonomous-validation-v2");
            when(rs.getString("canary_stage")).thenReturn("STAGE_1");
            when(rs.getString("scope_name")).thenReturn("didim_core");
            when(rs.getDouble("scope_center_latitude")).thenReturn(37.3751);
            when(rs.getDouble("scope_center_longitude")).thenReturn(27.2678);
            when(rs.getDouble("scope_radius_meters")).thenReturn(6000d);
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
        when(rollback.inspectRun(RUN)).thenReturn(plan(1, Set.of(RUN)));
        operations = new PlacePilotRollbackOperationsService(jdbc, transactions, rollback, new ObjectMapper());
    }

    @Test
    void inspectionUsesReadOnlyTransactionAndNeverDelegatesAMutation() {
        var result = operations.inspect(RUN, HASH);
        assertThat(result.dryRun()).isTrue();
        assertThat(result.result()).isEqualTo("INSPECTED");
        verify(transactions).getTransaction(argThat(definition -> definition.isReadOnly()
                && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(rollback, never()).retireRun(any(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void executeDelegatesAuthoritativeRetirementAndRecordsThreeAtomicEvents() {
        when(rollback.inspectRun(RUN)).thenReturn(plan(1, Set.of(RUN)), plan(0, Set.of(RUN)));
        when(rollback.retireRun(eq(RUN), any())).thenReturn(
                new PlacePilotRollbackService.RollbackResult(RUN, 1, 0, 0, false));
        var result = operations.execute(RUN, HASH, PlacePilotRollbackOperationsService.Reason.PRODUCT_ACCEPTANCE_FAILURE);
        assertThat(result.result()).isEqualTo("CONTAINED");
        verify(rollback).retireRun(eq(RUN), any());
        verify(jdbc, times(3)).update(contains("INSERT INTO place_pilot_operational_events"),
                any(UUID.class), eq(RUN), eq(HASH), startsWith("PILOT_"), any(OffsetDateTime.class), anyString());
        verify(transactions).commit(any());
        verify(transactions, never()).rollback(any());
    }

    @Test
    void rejectsWrongManifestBeforeDomainMutation() {
        assertThatThrownBy(() -> operations.execute(RUN, "b".repeat(64),
                PlacePilotRollbackOperationsService.Reason.PRODUCT_ACCEPTANCE_FAILURE))
                .hasMessage("MANIFEST_MISMATCH");
        verify(rollback, never()).retireRun(any(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsCrossRunPlanAndIncompatibleSchemaBeforeEventsOrMutation() {
        when(rollback.inspectRun(RUN)).thenReturn(plan(1, Set.of(RUN, UUID.randomUUID())));
        assertThatThrownBy(() -> operations.execute(RUN, HASH,
                PlacePilotRollbackOperationsService.Reason.OPERATOR_CONTAINMENT))
                .hasMessage("CROSS_RUN_PLAN");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        assertThatThrownBy(() -> operations.inspect(RUN, HASH)).hasMessage("SCHEMA_INCOMPATIBLE");
        verify(rollback, never()).retireRun(any(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void domainFailureRollsBackEventsAndNeverDisclosesRawFailure() {
        when(rollback.retireRun(eq(RUN), any())).thenThrow(new IllegalStateException("password=secret"));
        assertThatThrownBy(() -> operations.execute(RUN, HASH,
                PlacePilotRollbackOperationsService.Reason.INTEGRITY_FAILURE))
                .hasMessage("OPERATION_FAILED").hasNoCause();
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    private static PlacePilotRollbackService.RollbackInspection plan(int pending, Set<UUID> runs) {
        return new PlacePilotRollbackService.RollbackInspection(RUN, "didim-core-ops-test",
                "STAGE_1", "SUCCEEDED", HASH, false, 1, 2, pending * 2, 2,
                pending * 2, 0, pending, 0, 0, pending, runs, List.of(), true);
    }
}

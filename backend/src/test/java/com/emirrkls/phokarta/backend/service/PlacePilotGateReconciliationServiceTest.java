package com.emirrkls.phokarta.backend.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacePilotGateReconciliationServiceTest {
    private static final UUID RUN_ID =
            UUID.fromString("73000000-0000-0000-0000-000000000701");
    private static final OffsetDateTime DEADLINE =
            OffsetDateTime.of(2026, 9, 26, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void derivesFullSelectedCoverageBudgetAndRejectsMoreThanTwentyFourHours() {
        assertThat(PlacePilotGateReconciliationService.requiredProbeLease(
                400, 20, Duration.ofSeconds(30)))
                .isEqualTo(Duration.ofMinutes(855));
        assertThatThrownBy(() -> PlacePilotGateReconciliationService.requiredProbeLease(
                10_000, 20, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("24-hour");
    }

    @Test
    @SuppressWarnings("unchecked")
    void renewsOnlyAnUnexpiredUngatedSuccessfulRun() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlacePilotCanaryGateService gates = mock(PlacePilotCanaryGateService.class);
        PlatformTransactionManager transactionManager = transactionManager();
        PlacePilotGateReconciliationService service =
                new PlacePilotGateReconciliationService(
                        jdbc, gates, transactionManager);

        ResultSet identity = mock(ResultSet.class);
        when(identity.getString("pilot_run_key")).thenReturn("didim-lease-test");
        when(jdbc.query(contains("SELECT pilot_run_key"), any(RowMapper.class), eq(RUN_ID)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper<Object>) invocation.getArgument(1)).mapRow(identity, 0)));
        when(jdbc.query(contains("pg_advisory_xact_lock"), any(RowMapper.class),
                eq("didim-lease-test"))).thenReturn(List.of());
        Duration derivedLease = Duration.ofSeconds(18).plusMinutes(5);
        when(jdbc.update(contains("gate_deadline = GREATEST"),
                eq(derivedLease.toMillis()), eq(RUN_ID))).thenReturn(1);
        when(jdbc.queryForObject(contains("SELECT gate_deadline"),
                eq(OffsetDateTime.class), eq(RUN_ID))).thenReturn(DEADLINE);

        PlacePilotGateReconciliationService.LeaseResult result =
                service.renewLeaseForProbes(RUN_ID, 1, 1, Duration.ofSeconds(2));

        assertThat(result.gateDeadline()).isEqualTo(DEADLINE);
        verify(jdbc).update(contains("gate_deadline > clock_timestamp()"),
                eq(derivedLease.toMillis()), eq(RUN_ID));
        verify(gates, never()).record(any(), anyBoolean(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredRunIsRecheckedUnderPilotLockThenFailedThroughGateService() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlacePilotCanaryGateService gates = mock(PlacePilotCanaryGateService.class);
        PlacePilotGateReconciliationService service =
                new PlacePilotGateReconciliationService(
                        jdbc, gates, transactionManager());

        ResultSet candidate = mock(ResultSet.class);
        when(candidate.getObject("id", UUID.class)).thenReturn(RUN_ID);
        when(candidate.getString("pilot_run_key")).thenReturn("didim-expired-test");
        when(candidate.getObject("gate_deadline", OffsetDateTime.class)).thenReturn(DEADLINE);
        when(jdbc.query(contains("SELECT run.id"), any(RowMapper.class), eq(50)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper<Object>) invocation.getArgument(1)).mapRow(candidate, 0)));
        when(jdbc.query(contains("pg_advisory_xact_lock"), any(RowMapper.class),
                eq("didim-expired-test"))).thenReturn(List.of());
        when(jdbc.queryForObject(contains("SELECT count(*)"),
                eq(Integer.class), eq(RUN_ID))).thenReturn(1);
        when(gates.record(eq(RUN_ID), eq(false), any(), eq(null)))
                .thenReturn(new PlacePilotCanaryGateService.GateResult(
                        UUID.randomUUID(), RUN_ID, "didim-expired-test",
                        "STAGE_1", "FAILED", false));

        PlacePilotGateReconciliationService.ReconciliationResult result =
                service.reconcileExpired();

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.containedCount()).isEqualTo(1);
        InOrder order = inOrder(jdbc, gates);
        order.verify(jdbc).query(contains("pg_advisory_xact_lock"),
                any(RowMapper.class), eq("didim-expired-test"));
        order.verify(jdbc).queryForObject(contains("SELECT count(*)"),
                eq(Integer.class), eq(RUN_ID));
        order.verify(gates).record(eq(RUN_ID), eq(false), any(), eq(null));
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}

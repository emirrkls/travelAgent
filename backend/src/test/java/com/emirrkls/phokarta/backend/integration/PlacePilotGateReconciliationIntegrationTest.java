package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.repository.PlaceGraphProtectionRepository;
import com.emirrkls.phokarta.backend.service.PlacePilotCanaryGateService;
import com.emirrkls.phokarta.backend.service.PlacePilotGateReconciliationService;
import com.emirrkls.phokarta.backend.service.PlacePilotRollbackService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PlacePilotGateReconciliationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void expiredSuccessfulRunsFailClosedAndAreContained() {
        Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration/schema")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        PlacePilotRollbackService rollback = new PlacePilotRollbackService(
                jdbc, new PlaceGraphProtectionRepository(jdbc));
        PlacePilotCanaryGateService gates = new PlacePilotCanaryGateService(
                jdbc, rollback, transactionManager);
        PlacePilotGateReconciliationService reconciliation =
                new PlacePilotGateReconciliationService(jdbc, gates, transactionManager);

        OffsetDateTime databaseNow = jdbc.queryForObject(
                "select clock_timestamp()", OffsetDateTime.class);
        UUID latePassRun = seedSuccessfulRun(
                jdbc, 1, databaseNow.minusMinutes(1), databaseNow);
        PlacePilotCanaryGateService.GateResult latePass = gates.record(
                latePassRun, true, JsonNodeFactory.instance.objectNode(), null);
        assertThat(latePass.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                select diagnostics ->> 'gate_failure_reason'
                  from place_pilot_canary_gates where sync_run_id = ?
                """, String.class, latePassRun)).contains("durable deadline");

        UUID expiredRun = seedSuccessfulRun(
                jdbc, 2, databaseNow.minusSeconds(1), databaseNow);
        UUID futureRun = seedSuccessfulRun(
                jdbc, 3, databaseNow.plusMinutes(30), databaseNow);
        PlacePilotGateReconciliationService.ReconciliationResult reconciled =
                reconciliation.reconcileExpired();
        assertThat(reconciled.containedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select gate_status from place_pilot_canary_gates where sync_run_id = ?
                """, String.class, expiredRun)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                select count(*) from place_pilot_canary_gates where sync_run_id = ?
                """, Integer.class, futureRun)).isZero();

        UUID triggerProtectedRun = seedSuccessfulRun(
                jdbc, 4, databaseNow.minusSeconds(1), databaseNow);
        assertThatThrownBy(() -> jdbc.update("""
                insert into place_pilot_canary_gates (
                    id, sync_run_id, pilot_run_key, canary_stage,
                    gate_status, diagnostics, checked_at
                ) values (?, ?, ?, 'STAGE_1', 'PASSED', '{}'::jsonb, clock_timestamp())
                """, UUID.randomUUID(), triggerProtectedRun, pilotKey(4)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("missed its durable deadline");
    }

    private UUID seedSuccessfulRun(
            JdbcTemplate jdbc,
            int sequence,
            OffsetDateTime deadline,
            OffsetDateTime databaseNow
    ) {
        UUID runId = UUID.fromString(String.format(
                "74000000-0000-0000-0000-%012d", sequence));
        jdbc.update("""
                insert into place_provider_sync_runs (
                    id, pilot_run_key, canary_stage, authorization_reference,
                    provider, resolved_release, method_version, scope_name,
                    scope_center_latitude, scope_center_longitude, scope_radius_meters,
                    started_at, completed_at, gate_deadline, status,
                    manifest_hash, plan_digest
                ) values (?, ?, 'STAGE_1', ?, 'MULTI_SOURCE', 'fixture',
                    'didim-autonomous-validation-v2', 'didim_core',
                    37.3751, 27.2678, 6000, ?, ?, ?, 'SUCCEEDED', ?, ?)
                """, runId, pilotKey(sequence), "AUTH-DEADLINE-" + sequence,
                databaseNow.minusHours(2), databaseNow.minusMinutes(90), deadline,
                hash((char) ('0' + sequence)), hash((char) ('a' + sequence)));
        return runId;
    }

    private String pilotKey(int sequence) {
        return "didim-gate-deadline-" + sequence;
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}

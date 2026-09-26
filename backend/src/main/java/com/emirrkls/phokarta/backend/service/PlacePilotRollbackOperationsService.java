package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Private, single-run operational containment; the existing domain service owns all rules. */
@Service
public class PlacePilotRollbackOperationsService {
    private final JdbcTemplate jdbc;
    private final PlacePilotRollbackService rollback;
    private final ObjectMapper mapper;
    private final TransactionTemplate inspections;
    private final TransactionTemplate executions;

    public PlacePilotRollbackOperationsService(
            JdbcTemplate jdbc, PlatformTransactionManager transactions,
            PlacePilotRollbackService rollback, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.rollback = rollback;
        this.mapper = mapper;
        inspections = new TransactionTemplate(transactions);
        inspections.setReadOnly(true);
        inspections.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        executions = new TransactionTemplate(transactions);
    }

    public OperationResult inspect(UUID syncRunId, String expectedManifestHash) {
        validateInputs(syncRunId, expectedManifestHash);
        return safely(() -> inspections.execute(transaction -> {
            validateSchema();
            checkedRun(syncRunId, expectedManifestHash);
            PlacePilotRollbackService.RollbackInspection plan = checkedPlan(syncRunId);
            return new OperationResult(syncRunId, "INSPECTED", true,
                    plan.pendingWriteCount() == 0, plan, null);
        }));
    }

    public OperationResult execute(UUID syncRunId, String expectedManifestHash, Reason reason) {
        validateInputs(syncRunId, expectedManifestHash);
        if (reason == null) throw new OperationalFailure("INVALID_INPUT");
        return safely(() -> executions.execute(transaction -> {
            validateSchema();
            RunIdentity target = checkedRun(syncRunId, expectedManifestHash);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                    (rs, rowNum) -> rs.getObject(1), target.pilotRunKey());
            jdbc.query("SELECT pg_advisory_xact_lock(5517, 917)",
                    (rs, rowNum) -> rs.getObject(1));
            // Lock the exact run and recheck after serializing with imports and reference changes.
            jdbc.query("SELECT id FROM place_provider_sync_runs WHERE id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getObject(1), syncRunId);
            checkedRun(syncRunId, expectedManifestHash);
            PlacePilotRollbackService.RollbackInspection plan = checkedPlan(syncRunId);
            List<EventIdentity> events = jdbc.query("""
                    SELECT event_type, manifest_hash FROM place_pilot_operational_events WHERE sync_run_id = ?
                    """, (rs, rowNum) -> new EventIdentity(
                    rs.getString("event_type"), rs.getString("manifest_hash")), syncRunId);
            if (events.size() == 3) {
                Set<String> types = events.stream().map(EventIdentity::type)
                        .collect(java.util.stream.Collectors.toSet());
                if (!types.equals(Set.of("PILOT_CONTAINMENT_REQUESTED", "PILOT_ROLLBACK_STARTED",
                        "PILOT_ROLLBACK_COMPLETED")) || events.stream().anyMatch(
                        event -> !expectedManifestHash.equals(event.manifestHash()))) {
                    throw new OperationalFailure("AUDIT_INCONSISTENT");
                }
                if (plan.pendingWriteCount() != 0) throw new OperationalFailure("AUDIT_INCONSISTENT");
                return new OperationResult(syncRunId, "ALREADY_CONTAINED", false, true, plan,
                        new PlacePilotRollbackService.RollbackResult(syncRunId, 0, 0, 0, true));
            }
            if (!events.isEmpty()) throw new OperationalFailure("AUDIT_INCONSISTENT");
            OffsetDateTime occurredAt = jdbc.queryForObject(
                    "SELECT clock_timestamp()", OffsetDateTime.class);
            if (occurredAt == null) throw new OperationalFailure("OPERATION_FAILED");
            recordEvent(syncRunId, expectedManifestHash, "PILOT_CONTAINMENT_REQUESTED",
                    occurredAt, details(reason, plan, null));
            recordEvent(syncRunId, expectedManifestHash, "PILOT_ROLLBACK_STARTED",
                    occurredAt, details(reason, plan, null));
            PlacePilotRollbackService.RollbackResult result = rollback.retireRun(syncRunId, occurredAt);
            PlacePilotRollbackService.RollbackInspection after = checkedPlan(syncRunId);
            if (after.pendingWriteCount() != 0) throw new OperationalFailure("CONTAINMENT_INCOMPLETE");
            OffsetDateTime completedAt = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            if (completedAt == null || completedAt.isBefore(occurredAt)) {
                throw new OperationalFailure("OPERATION_FAILED");
            }
            recordEvent(syncRunId, expectedManifestHash, "PILOT_ROLLBACK_COMPLETED",
                    completedAt, details(reason, after, result));
            return new OperationResult(syncRunId,
                    result.alreadyRolledBack() ? "ALREADY_CONTAINED" : "CONTAINED",
                    false, result.alreadyRolledBack(), after, result);
        }));
    }

    private PlacePilotRollbackService.RollbackInspection checkedPlan(UUID syncRunId) {
        PlacePilotRollbackService.RollbackInspection plan = rollback.inspectRun(syncRunId);
        if (!plan.ownershipProvenanceValid()) throw new OperationalFailure("OWNERSHIP_INVALID");
        if (!plan.affectedRunIds().isEmpty() && !plan.affectedRunIds().equals(Set.of(syncRunId))) {
            throw new OperationalFailure("CROSS_RUN_PLAN");
        }
        return plan;
    }

    private RunIdentity checkedRun(UUID syncRunId, String expectedManifestHash) {
        List<RunIdentity> rows = jdbc.query("""
                SELECT pilot_run_key, manifest_hash, status, method_version, canary_stage,
                       scope_name, scope_center_latitude, scope_center_longitude, scope_radius_meters
                  FROM place_provider_sync_runs WHERE id = ?
                """, (rs, rowNum) -> new RunIdentity(rs.getString("pilot_run_key"),
                rs.getString("manifest_hash"), rs.getString("status"),
                rs.getString("method_version"), rs.getString("canary_stage"),
                rs.getString("scope_name"), rs.getDouble("scope_center_latitude"),
                rs.getDouble("scope_center_longitude"), rs.getDouble("scope_radius_meters")), syncRunId);
        if (rows.isEmpty()) throw new OperationalFailure("RUN_NOT_FOUND");
        if (rows.size() != 1) throw new OperationalFailure("RUN_IDENTITY_INVALID");
        RunIdentity run = rows.getFirst();
        if (!expectedManifestHash.equals(run.manifestHash())) throw new OperationalFailure("MANIFEST_MISMATCH");
        if (!Set.of("SUCCEEDED", "FAILED").contains(run.status())) {
            throw new OperationalFailure("RUN_NOT_COMPLETED");
        }
        if (!"didim-autonomous-validation-v2".equals(run.methodVersion())
                || !Set.of("STAGE_1", "STAGE_2", "STAGE_3").contains(run.canaryStage())
                || !"didim_core".equals(run.scopeName())
                || run.latitude() != 37.3751 || run.longitude() != 27.2678 || run.radius() != 6000) {
            throw new OperationalFailure("RUN_IDENTITY_INVALID");
        }
        return run;
    }

    private void validateSchema() {
        Boolean compatible = jdbc.queryForObject("""
                SELECT current_schema() = 'public'
                   AND current_setting('session_replication_role') = 'origin'
                   AND EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '17' AND success)
                   AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success)
                   AND NOT EXISTS (SELECT 1 FROM flyway_schema_history
                                    WHERE version IS NOT NULL AND version::numeric > 17)
                """, Boolean.class);
        if (!Boolean.TRUE.equals(compatible)) throw new OperationalFailure("SCHEMA_INCOMPATIBLE");
        Integer tableCount = jdbc.queryForObject("""
                SELECT count(*) FROM (VALUES
                    ('place_provider_sync_runs'), ('place_source_records'),
                    ('place_external_refs'), ('place_external_ref_events'),
                    ('place_validation_decisions'), ('place_pilot_catalog_writes'),
                    ('place_pilot_canary_gates'), ('place_pilot_operational_events'), ('places')
                ) expected(name) WHERE to_regclass('public.' || name) IS NOT NULL
                """, Integer.class);
        Integer triggerCount = jdbc.queryForObject("""
                SELECT count(*) FROM (VALUES
                    ('place_source_records', 'trg_place_source_records_append_only'),
                    ('place_external_refs', 'trg_place_external_ref_redirect_serial'),
                    ('place_external_refs', 'trg_place_external_ref_source_snapshot'),
                    ('place_external_ref_events', 'trg_place_external_ref_events_append_only'),
                    ('place_validation_decisions', 'trg_place_validation_decisions_append_only'),
                    ('place_pilot_catalog_writes', 'trg_place_pilot_catalog_write_integrity'),
                    ('place_pilot_catalog_writes', 'trg_place_pilot_catalog_writes_one_way'),
                    ('place_pilot_canary_gates', 'trg_place_pilot_canary_gates_immutable'),
                    ('place_pilot_authorization_bindings', 'trg_place_pilot_authorization_bindings_immutable'),
                    ('place_provider_sync_runs', 'trg_place_provider_sync_run_identity'),
                    ('place_external_refs', 'trg_place_external_ref_redirect_integrity'),
                    ('place_pilot_operational_events', 'trg_place_pilot_operational_event_valid'),
                    ('place_pilot_operational_events', 'trg_place_pilot_operational_events_append_only')
                ) expected(table_name, trigger_name)
                WHERE EXISTS (SELECT 1 FROM pg_trigger t
                               WHERE t.tgrelid = to_regclass('public.' || table_name)
                                 AND t.tgname = trigger_name AND NOT t.tgisinternal
                                 AND t.tgenabled IN ('O', 'A'))
                """, Integer.class);
        Integer constraintCount = jdbc.queryForObject("""
                SELECT count(*) FROM (VALUES
                    ('place_provider_sync_runs', 'place_sync_run_authorization_binding_fk'),
                    ('place_external_refs', 'place_external_ref_current_source_identity_fk'),
                    ('place_external_refs', 'place_external_ref_redirect_target_fk'),
                    ('place_pilot_catalog_writes', 'place_pilot_catalog_write_run_decision_fk'),
                    ('place_pilot_catalog_writes', 'place_pilot_catalog_write_run_stage_fk'),
                    ('place_pilot_operational_events', 'place_pilot_operational_event_run_manifest_fk')
                ) expected(table_name, constraint_name)
                WHERE EXISTS (SELECT 1 FROM pg_constraint c
                               WHERE c.conrelid = to_regclass('public.' || table_name)
                                 AND c.conname = constraint_name AND c.contype = 'f' AND c.convalidated)
                """, Integer.class);
        if (!Integer.valueOf(9).equals(tableCount) || !Integer.valueOf(13).equals(triggerCount)
                || !Integer.valueOf(6).equals(constraintCount)) {
            throw new OperationalFailure("SCHEMA_INCOMPATIBLE");
        }
    }

    private ObjectNode details(Reason reason, PlacePilotRollbackService.RollbackInspection plan,
                               PlacePilotRollbackService.RollbackResult result) {
        ObjectNode details = mapper.createObjectNode();
        details.put("reason_code", reason.name());
        details.put("canonical_places_created", plan.createdPlaceCount());
        details.put("source_observations", plan.sourceObservationCount());
        details.put("external_references", plan.externalReferenceCount());
        details.put("graph_protected_places", plan.graphProtectedCount());
        details.put("pending_writes", plan.pendingWriteCount());
        if (result != null) {
            details.put("retired_count", result.retiredCount());
            details.put("graph_protected_count", result.graphProtectedCount());
            details.put("contained_by_newer_references_count", result.containedByNewerReferencesCount());
            details.put("already_contained", result.alreadyRolledBack());
        }
        return details;
    }

    private void recordEvent(UUID syncRunId, String manifestHash, String type,
                             OffsetDateTime occurredAt, ObjectNode details) {
        UUID eventId = UUID.nameUUIDFromBytes((syncRunId + "\n" + type)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO place_pilot_operational_events
                    (id, sync_run_id, manifest_hash, event_type, occurred_at, details)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, eventId, syncRunId, manifestHash, type, occurredAt, details.toString());
    }

    private static void validateInputs(UUID syncRunId, String manifestHash) {
        if (syncRunId == null || manifestHash == null || !manifestHash.matches("^[0-9a-f]{64}$")) {
            throw new OperationalFailure("INVALID_INPUT");
        }
    }

    private static <T> T safely(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (OperationalFailure safe) {
            throw safe;
        } catch (RuntimeException unsafe) {
            throw new OperationalFailure("OPERATION_FAILED");
        }
    }

    public enum Reason { PRODUCT_ACCEPTANCE_FAILURE, INTEGRITY_FAILURE, OPERATOR_CONTAINMENT }

    public record OperationResult(UUID syncRunId, String result, boolean dryRun,
            boolean alreadyContained, PlacePilotRollbackService.RollbackInspection inspection,
            PlacePilotRollbackService.RollbackResult rollbackResult) { }

    public static final class OperationalFailure extends IllegalStateException {
        private final String category;
        public OperationalFailure(String category) { super(category); this.category = category; }
        public String category() { return category; }
    }

    private record RunIdentity(String pilotRunKey, String manifestHash, String status,
            String methodVersion, String canaryStage, String scopeName,
            double latitude, double longitude, double radius) { }
    private record EventIdentity(String type, String manifestHash) { }
}

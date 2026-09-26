package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Records the immutable pass/fail result that permits or blocks canary-stage expansion. */
@Service
public class PlacePilotCanaryGateService {
    private static final double MAX_RELATIVE_REGRESSION = 0.10;
    private static final double RELATIVE_CHANGE_TOLERANCE = 1.0e-9;
    private static final Duration MAX_CHECKED_AT_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final int MAX_CALLER_DIAGNOSTICS_BYTES = 48 * 1024;
    private final JdbcTemplate jdbc;
    private final PlacePilotRollbackService rollback;
    private final TransactionTemplate transactions;

    public PlacePilotCanaryGateService(
            JdbcTemplate jdbc,
            PlacePilotRollbackService rollback,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.rollback = rollback;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public GateResult record(
            UUID syncRunId,
            boolean passed,
            JsonNode diagnostics,
            OffsetDateTime checkedAt
    ) {
        return transactions.execute(transaction ->
                recordLocked(syncRunId, passed, diagnostics, checkedAt));
    }

    private GateResult recordLocked(
            UUID syncRunId,
            boolean passed,
            JsonNode diagnostics,
            OffsetDateTime checkedAt
    ) {
        List<String> pilotKeys = jdbc.query(
                "SELECT pilot_run_key FROM place_provider_sync_runs WHERE id = ?",
                (rs, rowNum) -> rs.getString("pilot_run_key"), syncRunId);
        if (pilotKeys.size() != 1) {
            throw new IllegalArgumentException("canary gate requires a successful import run");
        }
        // The importer uses the same transaction-scoped lock before every stage claim/write.
        // Keep it through the immutable gate insert and a failed gate's full rollback so a later
        // stage can observe only the before-state or the fully committed final state.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                (rs, rowNum) -> rs.getObject(1), pilotKeys.getFirst());
        // Provider-state changes and database redirect-validation triggers share this graph lock.
        // Holding it through every server-derived read and the immutable gate insert prevents a
        // PASS from being based on reference state that is removed concurrently.
        jdbc.query("SELECT pg_advisory_xact_lock(5517, 917)",
                (rs, rowNum) -> rs.getObject(1));
        List<RunIdentity> runs = jdbc.query("""
                SELECT pilot_run_key, canary_stage, status, completed_at, gate_deadline
                  FROM place_provider_sync_runs WHERE id = ?
                """, (rs, rowNum) -> new RunIdentity(
                rs.getString("pilot_run_key"), rs.getString("canary_stage"),
                rs.getString("status"), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("gate_deadline", OffsetDateTime.class)),
                syncRunId);
        if (runs.size() != 1 || !"SUCCEEDED".equals(runs.getFirst().status())) {
            throw new IllegalArgumentException("canary gate requires a successful import run");
        }
        RunIdentity run = runs.getFirst();
        OffsetDateTime databaseNow = jdbc.queryForObject(
                "SELECT clock_timestamp()", OffsetDateTime.class);
        if (databaseNow == null) {
            throw new IllegalStateException("database clock is unavailable for canary gate");
        }
        OffsetDateTime effectiveCheckedAt = checkedAt == null ? databaseNow : checkedAt;
        if (effectiveCheckedAt.isBefore(run.completedAt())) {
            throw new IllegalArgumentException(
                    "canary gate checked_at cannot precede run completion");
        }
        if (effectiveCheckedAt.isAfter(databaseNow.plus(MAX_CHECKED_AT_FUTURE_SKEW))) {
            throw new IllegalArgumentException(
                    "canary gate checked_at is materially in the future");
        }
        UUID gateId = UUID.nameUUIDFromBytes((syncRunId + "\nCANARY_GATE")
                .getBytes(StandardCharsets.UTF_8));
        boolean diagnosticsObject = diagnostics != null && diagnostics.isObject();
        boolean diagnosticsBounded = diagnosticsObject
                && diagnostics.toString().getBytes(StandardCharsets.UTF_8).length
                <= MAX_CALLER_DIAGNOSTICS_BYTES;
        ObjectNode submittedDiagnostics;
        if (diagnosticsObject && diagnosticsBounded) {
            submittedDiagnostics = ((ObjectNode) diagnostics).deepCopy();
        } else {
            submittedDiagnostics = JsonNodeFactory.instance.objectNode();
            submittedDiagnostics.put("submitted_diagnostics_invalid", true);
            submittedDiagnostics.put("submitted_diagnostics_reason", diagnosticsObject
                    ? "EXCEEDS_48_KIB" : "NOT_AN_OBJECT");
        }
        String requestDiagnosticsHash = hashRequestedDiagnostics(diagnostics);
        submittedDiagnostics.put("requested_gate_pass", passed);
        submittedDiagnostics.put("requested_diagnostics_sha256", requestDiagnosticsHash);
        List<ExistingGate> existingGates = jdbc.query("""
                SELECT gate_status, checked_at,
                       diagnostics ->> 'requested_diagnostics_sha256' AS request_hash,
                       diagnostics ->> 'requested_gate_pass' AS requested_pass
                  FROM place_pilot_canary_gates WHERE id = ?
                """, (rs, rowNum) -> new ExistingGate(
                rs.getString("gate_status"),
                rs.getObject("checked_at", OffsetDateTime.class),
                rs.getString("request_hash"),
                rs.getString("requested_pass")), gateId);
        if (existingGates.size() == 1) {
            ExistingGate existing = existingGates.getFirst();
            boolean sameTime = checkedAt == null || existing.checkedAt().isEqual(checkedAt);
            if (sameTime && requestDiagnosticsHash.equals(existing.requestHash())
                    && Boolean.toString(passed).equals(existing.requestedPass())) {
                return new GateResult(gateId, syncRunId, run.pilotRunKey(),
                        run.canaryStage(), existing.status(), true);
            }
        }
        JsonNode verifiedDiagnostics = submittedDiagnostics;
        boolean effectivePassed = passed;
        if (passed) {
            try {
                if (run.gateDeadline() == null
                        || !databaseNow.isBefore(run.gateDeadline())
                        || !effectiveCheckedAt.isBefore(run.gateDeadline())) {
                    throw new IllegalArgumentException(
                            "passing canary gate missed its durable deadline");
                }
                if (!diagnosticsObject) {
                    throw new IllegalArgumentException(
                            "canary gate diagnostics must be an object");
                }
                if (!diagnosticsBounded) {
                    throw new IllegalArgumentException(
                            "canary gate diagnostics exceed the 48 KiB limit");
                }
                validatePassingExternalDiagnostics(submittedDiagnostics);
                verifiedDiagnostics = attachDatabaseSafety(syncRunId, submittedDiagnostics);
                validateDatabaseSafety(verifiedDiagnostics);
            } catch (IllegalArgumentException unsafeGate) {
                effectivePassed = false;
                ObjectNode failedDiagnostics = verifiedDiagnostics instanceof ObjectNode object
                        ? object.deepCopy() : submittedDiagnostics.deepCopy();
                failedDiagnostics.put("requested_gate_status", "PASSED");
                failedDiagnostics.put("gate_failure_reason", unsafeGate.getMessage());
                verifiedDiagnostics = failedDiagnostics;
            }
        }
        String status = effectivePassed ? "PASSED" : "FAILED";
        if (!existingGates.isEmpty() && !status.equals(existingGates.getFirst().status())) {
            throw new IllegalStateException("canary gate already has a different final result");
        }
        int inserted = jdbc.update("""
                INSERT INTO place_pilot_canary_gates (
                    id, sync_run_id, pilot_run_key, canary_stage,
                    gate_status, diagnostics, checked_at
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (id) DO NOTHING
                """, gateId, syncRunId, run.pilotRunKey(), run.canaryStage(),
                status, verifiedDiagnostics.toString(), effectiveCheckedAt);
        if (inserted == 0) {
            Integer same = jdbc.queryForObject("""
                    SELECT count(*) FROM place_pilot_canary_gates
                     WHERE id = ? AND sync_run_id = ? AND gate_status = ?
                       AND diagnostics = ?::jsonb AND checked_at = ?
                    """, Integer.class, gateId, syncRunId, status,
                    verifiedDiagnostics.toString(), effectiveCheckedAt);
            if (!Integer.valueOf(1).equals(same)) {
                throw new IllegalStateException("canary gate already has a different final result");
            }
        }
        GateResult result = new GateResult(gateId, syncRunId, run.pilotRunKey(),
                run.canaryStage(), status, inserted == 0);
        if (!effectivePassed) {
            try {
                rollback.retireRun(syncRunId, effectiveCheckedAt);
            } catch (RuntimeException rollbackFailure) {
                throw new IllegalStateException(
                        "failed canary gate rollback failed; the gate result was not committed",
                        rollbackFailure);
            }
        }
        return result;
    }

    private void validatePassingExternalDiagnostics(JsonNode diagnostics) {
        if (!"AUTONOMOUS_HTTP_AND_DATABASE_V1".equals(
                diagnostics.path("probe_mode").asText())
                || !diagnostics.path("baseline_captured_before_import").asBoolean(false)
                || !diagnostics.path("measured_pass").asBoolean(false)
                || !diagnostics.path("http_probes").path("before")
                .path("passed").asBoolean(false)
                || !diagnostics.path("http_probes").path("after")
                .path("passed").asBoolean(false)
                || !diagnostics.path("catalog_anomaly_report")
                .path("passed").asBoolean(false)) {
            throw new IllegalArgumentException(
                    "passing canary gate requires autonomous measured probes and anomaly audit");
        }
        for (String check : List.of(
                "search_correctness", "map_bounded_behavior", "backend_health",
                "same_manifest_idempotency",
                "duplicate_canonical_diagnostics", "same_coordinate_diagnostics",
                "place_detail_correctness")) {
            if (!"PASS".equals(diagnostics.path(check).asText())) {
                throw new IllegalArgumentException("passing canary gate lacks " + check);
            }
        }
        for (String zeroCount : List.of(
                "duplicate_canonical_violations", "same_coordinate_anomalies")) {
            JsonNode value = diagnostics.path(zeroCount);
            if (!value.isIntegralNumber() || value.longValue() != 0) {
                throw new IllegalArgumentException("passing canary gate has unsafe " + zeroCount);
            }
        }
        Set<UUID> selectedPlaceIds = selectedProbeIds(diagnostics);
        JsonNode selectedCount = diagnostics.path("selected_place_count");
        JsonNode coverage = diagnostics.path("selected_place_coverage");
        JsonNode afterSurfaces = diagnostics.path("http_probes")
                .path("after").path("surfaces");
        if (!selectedCount.isIntegralNumber()
                || selectedCount.longValue() != selectedPlaceIds.size()
                || selectedPlaceIds.isEmpty() || !coverage.isObject()
                || !afterSurfaces.isObject()) {
            throw new IllegalArgumentException(
                    "passing canary gate lacks exact selected Place probe coverage");
        }
        for (String surface : List.of(
                "search", "map_nearby", "map_bounds", "place_detail")) {
            JsonNode reportedCount = coverage.path(surface);
            JsonNode result = afterSurfaces.path(surface + "_coverage");
            if (!reportedCount.isIntegralNumber()
                    || reportedCount.longValue() != selectedPlaceIds.size()
                    || !result.isObject()
                    || !result.path("sample_count").isIntegralNumber()
                    || result.path("sample_count").longValue() != selectedPlaceIds.size()
                    || !result.path("error_count").isIntegralNumber()
                    || result.path("error_count").longValue() != 0
                    || !result.path("passed").asBoolean(false)) {
                throw new IllegalArgumentException(
                        "passing canary gate has incomplete " + surface + " Place coverage");
            }
        }
        JsonNode performance = diagnostics.path("performance");
        if (!performance.isObject()) {
            throw new IllegalArgumentException("passing canary gate lacks per-surface performance results");
        }
        for (String surface : List.of("search", "map", "place_detail")) {
            validateMetric(performance.path(surface), "performance." + surface, false);
        }
        validateMetric(diagnostics.path("api_error_rate"), "api_error_rate", true);
    }

    /**
     * Core catalog/provenance safety is measured from committed state. Callers still supply
     * search, map, API and latency probes, but cannot self-attest database invariants.
     */
    private ObjectNode attachDatabaseSafety(UUID syncRunId, JsonNode diagnostics) {
        long missingOrUnvalidatedConstraints = scalarLong("""
                SELECT count(*)
                  FROM (VALUES
                      ('places'::regclass, 'places_origin_valid'),
                      ('places'::regclass, 'places_catalog_status_valid'),
                      ('place_external_refs'::regclass,
                          'place_external_ref_current_source_identity_fk'),
                      ('place_external_refs'::regclass,
                          'place_external_ref_redirect_target_fk'),
                      ('place_pilot_catalog_writes'::regclass,
                          'place_pilot_catalog_write_run_decision_fk'),
                      ('place_pilot_catalog_writes'::regclass,
                          'place_pilot_catalog_write_predecessor_fk'),
                      ('place_provider_sync_runs'::regclass,
                          'place_sync_run_reauthorization_fk'),
                      ('place_provider_sync_runs'::regclass,
                          'place_sync_run_completion_order_valid'),
                      ('place_provider_sync_runs'::regclass,
                          'place_sync_run_gate_deadline_valid')
                  ) required_constraint(table_oid, name)
                  LEFT JOIN pg_constraint constraint_definition
                    ON constraint_definition.conrelid = required_constraint.table_oid
                   AND constraint_definition.conname = required_constraint.name
                 WHERE constraint_definition.oid IS NULL
                    OR NOT constraint_definition.convalidated
                """);
        long missingSafetyTriggers = scalarLong("""
                SELECT count(*)
                  FROM (VALUES
                      ('place_external_refs'::regclass,
                          'trg_place_external_ref_source_snapshot'),
                      ('place_external_refs'::regclass,
                          'trg_place_external_ref_redirect_serial'),
                      ('place_external_refs'::regclass,
                          'trg_place_external_ref_redirect_integrity'),
                      ('place_pilot_canary_gates'::regclass,
                          'trg_place_pilot_canary_gate_chronology')
                  ) required_trigger(table_oid, name)
                  LEFT JOIN pg_trigger trigger_definition
                    ON trigger_definition.tgrelid = required_trigger.table_oid
                   AND trigger_definition.tgname = required_trigger.name
                   AND NOT trigger_definition.tgisinternal
                 WHERE trigger_definition.oid IS NULL
                    OR trigger_definition.tgenabled = 'D'
                """);
        long missingSafetyIndexes = scalarLong("""
                SELECT count(*)
                  FROM (VALUES
                      ('uq_place_pilot_catalog_write_active_place'),
                      ('uq_place_external_ref_event_command'),
                      ('uq_place_validation_decision_selection_rank')
                  ) required_index(name)
                  LEFT JOIN pg_index index_definition
                    ON index_definition.indexrelid = to_regclass(required_index.name)
                 WHERE index_definition.indexrelid IS NULL
                    OR NOT index_definition.indisvalid
                    OR NOT index_definition.indisunique
                """);
        long duplicateExternalRefs = scalarLong("""
                SELECT count(*) FROM (
                    SELECT provider, external_id
                      FROM place_external_refs
                     GROUP BY provider, external_id HAVING count(*) > 1
                ) collisions
                """);
        long sourceOrphans = scalarLong("""
                SELECT count(*)
                  FROM place_validation_decisions decision
                  CROSS JOIN LATERAL unnest(decision.source_record_ids)
                      AS source_ids(source_id)
                  LEFT JOIN place_source_records source
                    ON source.id = source_ids.source_id
                 WHERE decision.sync_run_id = ? AND source.id IS NULL
                """, syncRunId);
        long canonicalWithoutProvenance = scalarLong("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                  JOIN place_validation_decisions decision
                    ON decision.id = write.validation_decision_id
                 WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                   AND NOT EXISTS (
                       SELECT 1 FROM place_external_refs ref
                        WHERE ref.place_id = write.place_id
                          AND ref.status IN ('ACTIVE', 'MERGED')
                          AND ref.last_sync_run_id = ?
                          AND ref.current_source_record_id = ANY(decision.source_record_ids)
                   )
                """, syncRunId, syncRunId);
        long unlinkedDecisionSources = scalarLong("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                  JOIN place_validation_decisions decision
                    ON decision.id = write.validation_decision_id
                  CROSS JOIN LATERAL unnest(decision.source_record_ids)
                      AS source_ids(source_id)
                  LEFT JOIN place_source_records source
                    ON source.id = source_ids.source_id
                  LEFT JOIN place_external_refs ref
                    ON ref.place_id = write.place_id
                   AND ref.provider = source.provider
                   AND ref.external_id = source.external_id
                   AND ref.current_source_record_id = source.id
                   AND ref.last_sync_run_id = ?
                   AND ref.status IN ('ACTIVE', 'MERGED')
                 WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                   AND (source.id IS NULL OR ref.provider IS NULL)
                """, syncRunId, syncRunId);
        long providerIsolationViolations = scalarLong("""
                SELECT count(*)
                  FROM place_external_refs ref
                  LEFT JOIN place_source_records source
                    ON source.id = ref.current_source_record_id
                 WHERE ref.last_sync_run_id = ?
                   AND (source.id IS NULL OR source.provider <> ref.provider
                        OR source.external_id <> ref.external_id)
                """, syncRunId);
        long sourceSnapshotMismatches = scalarLong("""
                SELECT count(*)
                  FROM place_external_refs ref
                  LEFT JOIN place_source_records source
                    ON source.id = ref.current_source_record_id
                 WHERE ref.last_sync_run_id = ?
                   AND (source.id IS NULL
                        OR ref.source_hash <> source.source_hash
                        OR ref.source_release <> source.source_release
                        OR ref.snapshot_id IS DISTINCT FROM source.snapshot_id)
                """, syncRunId);
        long orphanRunReferences = scalarLong("""
                SELECT count(*) FROM place_external_refs ref
                 WHERE ref.last_sync_run_id = ?
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_catalog_writes write
                        WHERE write.sync_run_id = ?
                          AND write.place_id = ref.place_id
                          AND write.rollback_state = 'NONE'
                   )
                """, syncRunId, syncRunId);
        long quarantineImported = scalarLong("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                  JOIN place_validation_decisions decision
                    ON decision.id = write.validation_decision_id
                 WHERE write.sync_run_id = ? AND decision.decision_state = 'QUARANTINE'
                """, syncRunId);
        long hardBlockersImported = scalarLong("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                  JOIN place_validation_decisions decision
                    ON decision.id = write.validation_decision_id
                 WHERE write.sync_run_id = ?
                   AND jsonb_array_length(decision.hard_blockers) > 0
                """, syncRunId);
        long duplicateCanonicalUuids = scalarLong("""
                SELECT count(*) FROM (
                    SELECT place_id FROM place_pilot_catalog_writes
                     WHERE sync_run_id = ? GROUP BY place_id HAVING count(*) > 1
                ) duplicates
                """, syncRunId);
        duplicateCanonicalUuids += scalarLong("""
                SELECT count(*) FROM (
                    SELECT canonical_place_id FROM place_validation_decisions
                     WHERE sync_run_id = ? AND selected_for_stage
                     GROUP BY canonical_place_id HAVING count(*) > 1
                ) duplicates
                """, syncRunId);
        long runCreatedCount = scalarLong(
                "SELECT created_count FROM place_provider_sync_runs WHERE id = ?", syncRunId);
        long runSourceCount = scalarLong(
                "SELECT source_count FROM place_provider_sync_runs WHERE id = ?", syncRunId);
        long runEligibleCount = scalarLong("""
                SELECT canary_eligible_count FROM place_provider_sync_runs WHERE id = ?
                """, syncRunId);
        long decisionSourceCount = scalarLong("""
                SELECT count(DISTINCT source_ids.source_id)
                  FROM place_validation_decisions decision
                  CROSS JOIN LATERAL unnest(decision.source_record_ids)
                      AS source_ids(source_id)
                 WHERE decision.sync_run_id = ?
                """, syncRunId);
        long eligibleDecisions = scalarLong("""
                SELECT count(*) FROM place_validation_decisions
                 WHERE sync_run_id = ? AND canary_eligible
                """, syncRunId);
        long selectedDecisions = scalarLong("""
                SELECT count(*) FROM place_validation_decisions
                 WHERE sync_run_id = ? AND selected_for_stage
                """, syncRunId);
        List<UUID> selectedDatabasePlaceIds = jdbc.query("""
                SELECT canonical_place_id
                  FROM place_validation_decisions
                 WHERE sync_run_id = ? AND selected_for_stage
                 ORDER BY selection_rank
                """, (rs, rowNum) -> rs.getObject("canonical_place_id", UUID.class),
                syncRunId);
        Set<UUID> uniqueDatabasePlaceIds = new HashSet<>(selectedDatabasePlaceIds);
        Set<UUID> probedPlaceIds = selectedProbeIds(diagnostics);
        long selectedProbeCoverageViolations =
                selectedDatabasePlaceIds.size() != uniqueDatabasePlaceIds.size()
                        || !uniqueDatabasePlaceIds.equals(probedPlaceIds) ? 1 : 0;
        long currentWrites = scalarLong("""
                SELECT count(*) FROM place_pilot_catalog_writes
                 WHERE sync_run_id = ? AND rollback_state = 'NONE'
                """, syncRunId);
        long invalidCatalogPlaces = scalarLong("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes write
                  JOIN places place ON place.id = write.place_id
                 WHERE write.sync_run_id = ? AND write.rollback_state = 'NONE'
                   AND (place.origin <> 'EXTERNAL_IMPORT'
                        OR place.catalog_status <> 'ACTIVE')
                """, syncRunId);
        long rankShapeViolations = scalarLong("""
                SELECT CASE
                    WHEN count(*) = 0 OR min(selection_rank) <> 1
                      OR max(selection_rank) <> count(*)
                      OR count(DISTINCT selection_rank) <> count(*)
                    THEN 1 ELSE 0 END
                  FROM place_validation_decisions
                 WHERE sync_run_id = ? AND canary_eligible
                """, syncRunId);
        long selectedRankViolations = scalarLong("""
                WITH summary AS (
                    SELECT run.canary_stage,
                           count(*) FILTER (WHERE decision.canary_eligible) AS eligible_count
                      FROM place_provider_sync_runs run
                      JOIN place_validation_decisions decision ON decision.sync_run_id = run.id
                     WHERE run.id = ? GROUP BY run.canary_stage
                )
                SELECT count(*)
                  FROM place_validation_decisions decision CROSS JOIN summary
                 WHERE decision.sync_run_id = ?
                   AND decision.selected_for_stage IS DISTINCT FROM (
                       decision.canary_eligible AND CASE summary.canary_stage
                           WHEN 'STAGE_1' THEN decision.selection_rank BETWEEN 1
                               AND LEAST(100, summary.eligible_count)
                           WHEN 'STAGE_2' THEN decision.selection_rank BETWEEN 101
                               AND LEAST(500, summary.eligible_count)
                           WHEN 'STAGE_3' THEN decision.selection_rank BETWEEN 501
                               AND summary.eligible_count
                           ELSE FALSE
                       END
                   )
                """, syncRunId, syncRunId);
        long databaseConstraintViolations = missingOrUnvalidatedConstraints
                + missingSafetyIndexes + missingSafetyTriggers + invalidCatalogPlaces
                + orphanRunReferences + rankShapeViolations + selectedRankViolations;
        if (runCreatedCount != selectedDecisions || selectedDecisions != currentWrites) {
            databaseConstraintViolations++;
        }
        if (runSourceCount != decisionSourceCount || runEligibleCount != eligibleDecisions) {
            databaseConstraintViolations++;
        }
        long provenanceLinkageViolations = sourceOrphans
                + canonicalWithoutProvenance + unlinkedDecisionSources
                + providerIsolationViolations + sourceSnapshotMismatches;

        ObjectNode verified = ((ObjectNode) diagnostics).deepCopy();
        verified.put("database_constraints",
                databaseConstraintViolations == 0 ? "PASS" : "FAIL");
        verified.put("provider_id_isolation",
                providerIsolationViolations == 0 ? "PASS" : "FAIL");
        verified.put("canonical_uuid_uniqueness",
                duplicateCanonicalUuids == 0 ? "PASS" : "FAIL");
        verified.put("selected_place_probe_coverage",
                selectedProbeCoverageViolations == 0 ? "PASS" : "FAIL");
        verified.put("provenance_linkage",
                provenanceLinkageViolations == 0 ? "PASS" : "FAIL");
        verified.put("duplicate_external_ref_violations", duplicateExternalRefs);
        verified.put("quarantine_imported", quarantineImported);
        verified.put("hard_blockers_imported", hardBlockersImported);
        verified.put("source_orphans", sourceOrphans);
        verified.put("canonical_without_provenance", canonicalWithoutProvenance);
        ObjectNode serverDerived = verified.putObject("server_derived");
        serverDerived.put("database_constraint_violations", databaseConstraintViolations);
        serverDerived.put("missing_or_unvalidated_constraints",
                missingOrUnvalidatedConstraints);
        serverDerived.put("missing_safety_indexes", missingSafetyIndexes);
        serverDerived.put("missing_safety_triggers", missingSafetyTriggers);
        serverDerived.put("duplicate_external_ref_violations", duplicateExternalRefs);
        serverDerived.put("provider_isolation_violations", providerIsolationViolations);
        serverDerived.put("duplicate_canonical_uuid_violations", duplicateCanonicalUuids);
        serverDerived.put("selected_probe_coverage_violations",
                selectedProbeCoverageViolations);
        serverDerived.put("source_orphans", sourceOrphans);
        serverDerived.put("canonical_without_provenance", canonicalWithoutProvenance);
        serverDerived.put("unlinked_decision_sources", unlinkedDecisionSources);
        serverDerived.put("source_snapshot_mismatches", sourceSnapshotMismatches);
        serverDerived.put("provenance_linkage_violations", provenanceLinkageViolations);
        serverDerived.put("orphan_run_references", orphanRunReferences);
        serverDerived.put("selection_rank_violations",
                rankShapeViolations + selectedRankViolations);
        serverDerived.put("quarantine_imported", quarantineImported);
        serverDerived.put("hard_blockers_imported", hardBlockersImported);
        return verified;
    }

    private void validateDatabaseSafety(JsonNode verifiedDiagnostics) {
        JsonNode derived = verifiedDiagnostics.path("server_derived");
        requireZero("database_constraints",
                derived.path("database_constraint_violations").longValue());
        requireZero("duplicate_external_ref_violations",
                derived.path("duplicate_external_ref_violations").longValue());
        requireZero("provider_id_isolation",
                derived.path("provider_isolation_violations").longValue());
        requireZero("canonical_uuid_uniqueness",
                derived.path("duplicate_canonical_uuid_violations").longValue());
        requireZero("selected_place_probe_coverage",
                derived.path("selected_probe_coverage_violations").longValue());
        requireZero("provenance_linkage",
                derived.path("provenance_linkage_violations").longValue());
        requireZero("quarantine_imported", derived.path("quarantine_imported").longValue());
        requireZero("hard_blockers_imported",
                derived.path("hard_blockers_imported").longValue());
    }

    private long scalarLong(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private String hashRequestedDiagnostics(JsonNode diagnostics) {
        try {
            byte[] content = (diagnostics == null ? "null" : diagnostics.toString())
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void requireZero(String label, long value) {
        if (value != 0) {
            throw new IllegalArgumentException(
                    "passing canary gate has unsafe server-derived " + label);
        }
    }

    private void validateMetric(JsonNode metric, String label, boolean rate) {
        String baselineField = rate ? "baseline_rate" : "baseline_ms";
        String afterField = rate ? "after_rate" : "after_ms";
        JsonNode baseline = metric.path(baselineField);
        JsonNode after = metric.path(afterField);
        JsonNode relativeChange = metric.path("relative_change");
        if (!metric.isObject()
                || !isFiniteNonNegative(baseline)
                || !isFiniteNonNegative(after)
                || !relativeChange.isNumber()
                || !Double.isFinite(relativeChange.doubleValue())
                || metric.path("material_regression").asBoolean(true)) {
            throw new IllegalArgumentException("passing canary gate lacks safe " + label + " results");
        }
        if (rate && (baseline.doubleValue() > 1.0 || after.doubleValue() > 1.0)) {
            throw new IllegalArgumentException("passing canary gate has invalid " + label + " results");
        }
        double baselineValue = baseline.doubleValue();
        double afterValue = after.doubleValue();
        double computedChange;
        if (baselineValue == 0.0) {
            if (afterValue != 0.0) {
                throw new IllegalArgumentException(
                        "passing canary gate has a regression from zero for " + label);
            }
            computedChange = 0.0;
        } else {
            computedChange = (afterValue - baselineValue) / baselineValue;
        }
        if (Math.abs(relativeChange.doubleValue() - computedChange)
                    > RELATIVE_CHANGE_TOLERANCE
                || computedChange > MAX_RELATIVE_REGRESSION) {
            throw new IllegalArgumentException(
                    "passing canary gate has inconsistent or regressed " + label + " results");
        }
    }

    private boolean isFiniteNonNegative(JsonNode value) {
        return value.isNumber() && Double.isFinite(value.doubleValue()) && value.doubleValue() >= 0;
    }

    private Set<UUID> selectedProbeIds(JsonNode diagnostics) {
        JsonNode values = diagnostics.path("selected_place_ids_checked");
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException(
                    "passing canary gate lacks selected Place probe identities");
        }
        Set<UUID> ids = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(
                        "passing canary gate has an invalid selected Place probe identity");
            }
            UUID id;
            try {
                id = UUID.fromString(value.textValue());
            } catch (IllegalArgumentException invalidUuid) {
                throw new IllegalArgumentException(
                        "passing canary gate has an invalid selected Place probe identity",
                        invalidUuid);
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException(
                        "passing canary gate repeats a selected Place probe identity");
            }
        }
        return ids;
    }

    private record RunIdentity(
            String pilotRunKey,
            String canaryStage,
            String status,
            OffsetDateTime completedAt,
            OffsetDateTime gateDeadline
    ) {}

    private record ExistingGate(
            String status,
            OffsetDateTime checkedAt,
            String requestHash,
            String requestedPass
    ) {}

    public record GateResult(
            UUID gateId,
            UUID syncRunId,
            String pilotRunKey,
            String canaryStage,
            String status,
            boolean alreadyRecorded
    ) {}
}

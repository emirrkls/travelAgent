package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.repository.PlaceGraphProtectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/**
 * Graph-safe containment for an autonomous canary run.
 *
 * <p>Rollback is deliberately retirement-only. It removes catalog/provider exposure while
 * retaining the canonical row, provenance, validation evidence and all Phokarta-owned graph
 * references. A graph-protected Place is marked separately in the write journal so a later
 * recovery cannot mistake it for disposable provider data.</p>
 */
@Service
public class PlacePilotRollbackService {
    private static final int MAX_INSPECTED_WRITES = 1000;
    private static final String PENDING_WRITES_SQL = """
            SELECT w.id, w.sync_run_id, w.place_id, w.validation_decision_id, w.rollback_state
              FROM place_pilot_catalog_writes w
              JOIN place_provider_sync_runs r ON r.id = w.sync_run_id
             WHERE r.pilot_run_key = ? AND w.rollback_state = 'NONE'
               AND (
                   r.id = ?
                   OR CASE r.canary_stage
                        WHEN 'STAGE_1' THEN 1 WHEN 'STAGE_2' THEN 2 WHEN 'STAGE_3' THEN 3
                      END > CASE ?
                        WHEN 'STAGE_1' THEN 1 WHEN 'STAGE_2' THEN 2 WHEN 'STAGE_3' THEN 3
                      END
               )
               AND NOT EXISTS (
                   SELECT 1 FROM place_provider_sync_runs successor
                    WHERE successor.reauthorizes_run_id = r.id
               )
             ORDER BY CASE r.canary_stage
                        WHEN 'STAGE_3' THEN 3 WHEN 'STAGE_2' THEN 2 ELSE 1
                      END DESC, w.imported_at, w.id
            """;
    private static final String EXPOSED_REFERENCES_SQL = """
            WITH RECURSIVE preserved(provider, external_id) AS (
                SELECT ref.provider, ref.external_id
                  FROM place_external_refs ref
                 WHERE ref.place_id = ?
                   AND ref.status IN ('ACTIVE', 'MERGED')
                   AND ref.last_sync_run_id <> ?
                UNION
                SELECT target.provider, target.external_id
                  FROM preserved keep_ref
                  JOIN place_external_refs source
                    ON source.provider = keep_ref.provider
                   AND source.external_id = keep_ref.external_id
                   AND source.status = 'MERGED'
                  JOIN place_external_refs target
                    ON target.provider = source.redirected_provider
                   AND target.external_id = source.redirected_external_id
            )
            SELECT ref.provider, ref.external_id, ref.current_source_record_id,
                   (ref.last_sync_run_id = ?) AS owned_by_run,
                   EXISTS (
                       SELECT 1 FROM preserved keep_ref
                        WHERE keep_ref.provider = ref.provider
                          AND keep_ref.external_id = ref.external_id
                   ) AS redirect_protected
              FROM place_external_refs ref
             WHERE ref.place_id = ? AND ref.status IN ('ACTIVE', 'MERGED')
             ORDER BY ref.provider, ref.external_id
            """;
    private final JdbcTemplate jdbc;
    private final PlaceGraphProtectionRepository graphProtection;

    public PlacePilotRollbackService(
            JdbcTemplate jdbc,
            PlaceGraphProtectionRepository graphProtection
    ) {
        this.jdbc = jdbc;
        this.graphProtection = graphProtection;
    }

    @Transactional
    public RollbackResult retireRun(UUID syncRunId, OffsetDateTime occurredAt) {
        RunIdentity target = requireCompletedRun(syncRunId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                (rs, rowNum) -> rs.getObject(1), target.pilotRunKey());
        // Redirect dependency decisions and provider-state mutations share one global graph
        // lock. Taking it before reading any reference closes the merge-vs-rollback race.
        jdbc.query("SELECT pg_advisory_xact_lock(5517, 917)",
                (rs, rowNum) -> rs.getObject(1));

        if (hasSuccessor(syncRunId)) {
            // A replacement can exist only after this attempt's writes were safely contained.
            // Retrying containment for the superseded attempt must never cross the lineage
            // boundary and retire the replacement (or stages subsequently based on it).
            return new RollbackResult(syncRunId, 0, 0, 0, true);
        }

        List<PilotWrite> writes = pendingWrites(target, syncRunId, false);
        int retired = 0;
        int graphProtected = 0;
        int containedByNewerReferences = 0;
        for (PilotWrite write : writes) {
            String origin = jdbc.queryForObject(
                    "SELECT origin FROM places WHERE id = ? FOR UPDATE",
                    String.class, write.placeId());
            if (!"EXTERNAL_IMPORT".equals(origin)) {
                throw new IllegalStateException("pilot write points at a non-imported Place");
            }
            boolean protectedGraph = graphProtection.hasPhokartaOwnedGraph(write.placeId());
            jdbc.query("""
                    SELECT provider, external_id
                      FROM place_external_refs
                     WHERE place_id = ?
                     ORDER BY provider, external_id
                     FOR UPDATE
                    """, (rs, rowNum) -> rs.getString("provider") + "\n"
                    + rs.getString("external_id"), write.placeId());
            List<ExternalRef> exposedRefs = exposedReferences(write);
            for (ExternalRef ref : exposedRefs) {
                if (!referenceCanBeContained(ref)) continue;
                int updatedRef = jdbc.update("""
                        UPDATE place_external_refs
                           SET status = 'INACTIVE', last_seen_at = ?, last_sync_run_id = ?,
                                redirected_provider = NULL, redirected_external_id = NULL
                         WHERE provider = ? AND external_id = ? AND place_id = ?
                           AND last_sync_run_id = ?
                           AND current_source_record_id = ?
                        """, occurredAt, write.syncRunId(), ref.provider(), ref.externalId(),
                        write.placeId(), write.syncRunId(), ref.sourceRecordId());
                if (updatedRef != 1) {
                    throw new IllegalStateException(
                            "provider reference changed during pilot containment");
                }
                UUID eventId = UUID.nameUUIDFromBytes((write.syncRunId() + "\n"
                        + ref.provider() + "\n"
                        + ref.externalId() + "\nPILOT_ROLLBACK")
                        .getBytes(StandardCharsets.UTF_8));
                String details = protectedGraph
                        ? "{\"graph_protected\":true}" : "{\"graph_protected\":false}";
                int eventInserted = jdbc.update("""
                        INSERT INTO place_external_ref_events (
                            id, provider, external_id, place_id, event_type, source_record_id,
                            sync_run_id, occurred_at, details
                        ) VALUES (?, ?, ?, ?, 'PILOT_ROLLBACK', ?, ?, ?, ?::jsonb)
                        ON CONFLICT (id) DO NOTHING
                        """, eventId, ref.provider(), ref.externalId(), write.placeId(),
                        ref.sourceRecordId(), write.syncRunId(), occurredAt, details);
                if (eventInserted == 0) {
                    Integer sameEvent = jdbc.queryForObject("""
                            SELECT count(*) FROM place_external_ref_events
                             WHERE id = ? AND provider = ? AND external_id = ?
                               AND place_id = ? AND event_type = 'PILOT_ROLLBACK'
                               AND source_record_id = ? AND sync_run_id = ?
                               AND redirected_provider IS NULL
                               AND redirected_external_id IS NULL
                               AND occurred_at = ? AND details = ?::jsonb
                            """, Integer.class, eventId, ref.provider(), ref.externalId(),
                            write.placeId(), ref.sourceRecordId(), write.syncRunId(), occurredAt,
                            details);
                    if (!Integer.valueOf(1).equals(sameEvent)) {
                        throw new IllegalStateException(
                                "pilot rollback event UUID payload collision");
                    }
                }
            }
            Integer remainingExposedReferences = jdbc.queryForObject("""
                    SELECT count(*) FROM place_external_refs
                     WHERE place_id = ? AND status IN ('ACTIVE', 'MERGED')
                    """, Integer.class, write.placeId());
            boolean hasNewerExposedReference = remainingExposedReferences != null
                    && remainingExposedReferences > 0;
            String rollbackState = expectedRollbackState(protectedGraph, hasNewerExposedReference);
            if (hasNewerExposedReference) {
                containedByNewerReferences++;
            } else {
                jdbc.update("""
                        UPDATE places SET catalog_status = 'RETIRED', updated_at = ? WHERE id = ?
                        """, occurredAt, write.placeId());
                retired++;
            }
            int journaled = jdbc.update("""
                    UPDATE place_pilot_catalog_writes
                       SET rollback_state = ?, rolled_back_at = ?
                     WHERE id = ? AND rollback_state = 'NONE'
                    """, rollbackState, occurredAt, write.id());
            if (journaled != 1) {
                throw new IllegalStateException("pilot write changed during containment");
            }
            if (protectedGraph) graphProtected++;
        }
        return new RollbackResult(syncRunId, retired, graphProtected,
                containedByNewerReferences, writes.isEmpty());
    }

    /**
     * Inspect the authoritative retirement plan without locks, writes or lifecycle changes.
     * Counts of source observations describe preserved provenance, never rows to delete.
     * A dependency on a later stage is reported rather than silently narrowed; a single-run
     * operational caller must refuse such a plan before delegating to {@link #retireRun}.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RollbackInspection inspectRun(UUID syncRunId) {
        RunIdentity target = requireCompletedRun(syncRunId);
        boolean superseded = hasSuccessor(syncRunId);
        List<PilotWrite> ownWrites = jdbc.query("""
                SELECT id, sync_run_id, place_id, validation_decision_id, rollback_state
                  FROM place_pilot_catalog_writes WHERE sync_run_id = ?
                 ORDER BY imported_at, id LIMIT ?
                """, (rs, rowNum) -> new PilotWrite(
                rs.getObject("id", UUID.class), rs.getObject("sync_run_id", UUID.class),
                rs.getObject("place_id", UUID.class),
                rs.getObject("validation_decision_id", UUID.class),
                rs.getString("rollback_state")), syncRunId, MAX_INSPECTED_WRITES + 1);
        requireBoundedInspection(ownWrites.size());
        List<PilotWrite> pending = superseded ? List.of() : pendingWrites(target, syncRunId, true);
        requireBoundedInspection(pending.size());
        var inspected = new LinkedHashMap<UUID, PilotWrite>();
        ownWrites.forEach(write -> inspected.put(write.id(), write));
        pending.forEach(write -> inspected.put(write.id(), write));
        requireBoundedInspection(inspected.size());
        Set<UUID> pendingIds = new HashSet<>();
        Set<UUID> affectedRunIds = new HashSet<>();
        affectedRunIds.add(syncRunId);
        for (PilotWrite write : pending) {
            pendingIds.add(write.id());
            affectedRunIds.add(write.syncRunId());
        }
        List<PlaceRollbackInspection> places = new ArrayList<>();
        Set<UUID> affectedSources = new HashSet<>();
        int exposedCount = 0;
        int referencesToContain = 0;
        int graphProtectedCount = 0;
        int eligibleRetirementCount = 0;
        int retainedGraphSafeCount = 0;
        int containedByNewerReferencesCount = 0;
        boolean ownershipValid = true;
        for (PilotWrite write : inspected.values()) {
            List<String> catalogStates = jdbc.query(
                    "SELECT catalog_status FROM places WHERE id = ?",
                    (rs, rowNum) -> rs.getString("catalog_status"), write.placeId());
            if (catalogStates.size() != 1) {
                throw new IllegalStateException("pilot write canonical identity is missing or ambiguous");
            }
            boolean validProvenance = hasSafeWriteProvenance(write);
            boolean protectedGraph = graphProtection.hasPhokartaOwnedGraph(write.placeId());
            if (protectedGraph) graphProtectedCount++;
            List<ExternalRef> refs = exposedReferences(write);
            validProvenance &= hasSafeReferenceProvenance(write);
            ownershipValid &= validProvenance;
            boolean willProcess = pendingIds.contains(write.id());
            List<ExternalRef> containedRefs = willProcess
                    ? refs.stream().filter(PlacePilotRollbackService::referenceCanBeContained).toList()
                    : List.of();
            containedRefs.forEach(ref -> affectedSources.add(ref.sourceRecordId()));
            exposedCount += refs.size();
            referencesToContain += containedRefs.size();
            int preservedRefs = refs.size() - containedRefs.size();
            String expected = willProcess
                    ? expectedRollbackState(protectedGraph, preservedRefs > 0)
                    : superseded ? "UNCHANGED_SUPERSEDED" : "UNCHANGED_" + write.rollbackState();
            if (willProcess && preservedRefs > 0) containedByNewerReferencesCount++;
            else if (willProcess && protectedGraph) retainedGraphSafeCount++;
            else if (willProcess) eligibleRetirementCount++;
            Integer sourceCount = jdbc.queryForObject("""
                    SELECT cardinality(source_record_ids) FROM place_validation_decisions WHERE id = ?
                    """, Integer.class, write.validationDecisionId());
            places.add(new PlaceRollbackInspection(
                    write.id(), write.syncRunId(), write.placeId(), catalogStates.getFirst(),
                    write.rollbackState(), expected, protectedGraph,
                    sourceCount == null ? 0 : sourceCount, refs.size(), containedRefs.size(),
                    preservedRefs, validProvenance));
        }
        Integer sourceObservationCount = jdbc.queryForObject(
                "SELECT count(*) FROM place_source_records WHERE sync_run_id = ?",
                Integer.class, syncRunId);
        return new RollbackInspection(syncRunId, target.pilotRunKey(), target.canaryStage(),
                target.status(), target.manifestHash(), superseded, ownWrites.size(),
                sourceObservationCount == null ? 0 : sourceObservationCount, affectedSources.size(),
                exposedCount, referencesToContain, graphProtectedCount, eligibleRetirementCount,
                retainedGraphSafeCount, containedByNewerReferencesCount, pending.size(),
                Set.copyOf(affectedRunIds), List.copyOf(places), ownershipValid);
    }

    private RunIdentity requireCompletedRun(UUID syncRunId) {
        if (syncRunId == null) throw new IllegalArgumentException("pilot run identity is required");
        List<RunIdentity> rows = jdbc.query("""
                SELECT pilot_run_key, canary_stage, status, manifest_hash
                  FROM place_provider_sync_runs WHERE id = ?
                """, (rs, rowNum) -> new RunIdentity(rs.getString("pilot_run_key"),
                rs.getString("canary_stage"), rs.getString("status"), rs.getString("manifest_hash")),
                syncRunId);
        if (rows.size() != 1 || !Set.of("SUCCEEDED", "FAILED").contains(rows.getFirst().status())) {
            throw new IllegalArgumentException("rollback requires a completed pilot run");
        }
        return rows.getFirst();
    }

    private boolean hasSuccessor(UUID syncRunId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM place_provider_sync_runs WHERE reauthorizes_run_id = ?",
                Integer.class, syncRunId);
        return count != null && count > 0;
    }

    private List<PilotWrite> pendingWrites(RunIdentity target, UUID syncRunId, boolean bounded) {
        String sql = PENDING_WRITES_SQL + (bounded ? " LIMIT " + (MAX_INSPECTED_WRITES + 1) : "");
        return jdbc.query(sql, (rs, rowNum) -> new PilotWrite(
                rs.getObject("id", UUID.class), rs.getObject("sync_run_id", UUID.class),
                rs.getObject("place_id", UUID.class),
                rs.getObject("validation_decision_id", UUID.class), rs.getString("rollback_state")),
                target.pilotRunKey(), syncRunId, target.canaryStage());
    }

    private List<ExternalRef> exposedReferences(PilotWrite write) {
        return jdbc.query(EXPOSED_REFERENCES_SQL, (rs, rowNum) -> new ExternalRef(
                rs.getString("provider"), rs.getString("external_id"),
                rs.getObject("current_source_record_id", UUID.class),
                rs.getBoolean("owned_by_run"), rs.getBoolean("redirect_protected")),
                write.placeId(), write.syncRunId(), write.syncRunId(), write.placeId());
    }

    private static boolean referenceCanBeContained(ExternalRef ref) {
        return ref.ownedByRun() && !ref.redirectProtected();
    }

    private static String expectedRollbackState(boolean graphProtected, boolean newerReferences) {
        return newerReferences ? "CONTAINED_NEWER_REFERENCES"
                : graphProtected ? "RETIRED_GRAPH_PROTECTED" : "RETIRED";
    }

    private static void requireBoundedInspection(int count) {
        if (count > MAX_INSPECTED_WRITES) {
            throw new IllegalArgumentException("pilot rollback inspection exceeds the 1000-Place bound");
        }
    }

    private boolean hasSafeWriteProvenance(PilotWrite write) {
        Integer valid = jdbc.queryForObject("""
                SELECT count(*)
                  FROM place_pilot_catalog_writes w
                  JOIN place_validation_decisions d ON d.id = w.validation_decision_id
                  JOIN place_provider_sync_runs r ON r.id = w.sync_run_id
                  JOIN places p ON p.id = w.place_id
                 WHERE w.id = ? AND w.sync_run_id = ? AND w.place_id = ?
                   AND w.write_action = 'AUTO_CREATE' AND p.origin = 'EXTERNAL_IMPORT'
                   AND d.sync_run_id = w.sync_run_id AND d.canonical_place_id = w.place_id
                   AND w.canary_stage = r.canary_stage
                   AND d.pilot_run_key = r.pilot_run_key AND d.decision_state = 'AUTO_CREATE'
                   AND d.validation_method_version = r.method_version
                   AND d.canary_eligible AND d.selected_for_stage AND d.hard_blockers = '[]'::jsonb
                   AND cardinality(d.source_record_ids) > 0
                   AND cardinality(d.source_record_ids) = (
                       SELECT count(DISTINCT source.id)
                         FROM place_source_records source
                         JOIN place_provider_sync_runs source_run ON source_run.id = source.sync_run_id
                        WHERE source.id = ANY(d.source_record_ids)
                          AND source_run.pilot_run_key = r.pilot_run_key
                          AND source_run.canary_stage <> 'DRY_RUN'
                          AND source_run.status IN ('SUCCEEDED', 'FAILED')
                          AND source.method_version IN (
                              'didim-canonicalization-v2', 'didim-canonicalization-v3'
                          )
                          AND source.source_release <> '' AND source.license_identifier <> ''
                          AND source.source_release = CASE source.provider
                              WHEN 'OVERTURE' THEN split_part(split_part(
                                  source_run.resolved_release, 'overture=', 2), ';fsq=', 1)
                              WHEN 'FSQ' THEN split_part(source_run.resolved_release, ';fsq=', 2)
                          END
                          AND (source.provider <> 'FSQ'
                              OR source.snapshot_id IS NOT DISTINCT FROM source_run.snapshot_id)
                   )
                """, Integer.class, write.id(), write.syncRunId(), write.placeId());
        return Integer.valueOf(1).equals(valid);
    }

    private boolean hasSafeReferenceProvenance(PilotWrite write) {
        Integer invalid = jdbc.queryForObject("""
                SELECT count(*) FROM place_external_refs ref
                 WHERE ref.place_id = ? AND ref.status IN ('ACTIVE', 'MERGED')
                   AND NOT EXISTS (
                       SELECT 1 FROM place_source_records source
                        WHERE source.id = ref.current_source_record_id
                          AND source.provider = ref.provider AND source.external_id = ref.external_id
                          AND source.source_hash = ref.source_hash
                          AND source.source_release = ref.source_release
                          AND source.snapshot_id IS NOT DISTINCT FROM ref.snapshot_id
                          AND (ref.last_sync_run_id <> ? OR source.id = ANY(
                              SELECT unnest(source_record_ids) FROM place_validation_decisions
                               WHERE id = ?
                          ))
                   )
                """, Integer.class, write.placeId(), write.syncRunId(), write.validationDecisionId());
        return Integer.valueOf(0).equals(invalid);
    }

    private record RunIdentity(String pilotRunKey, String canaryStage, String status, String manifestHash) {}

    private record PilotWrite(UUID id, UUID syncRunId, UUID placeId,
                              UUID validationDecisionId, String rollbackState) {}

    private record ExternalRef(
            String provider,
            String externalId,
            UUID sourceRecordId,
            boolean ownedByRun,
            boolean redirectProtected
    ) {}

    public record RollbackInspection(
            UUID syncRunId, String pilotRunKey, String canaryStage, String status,
            String manifestHash, boolean superseded, int createdPlaceCount,
            int sourceObservationCount, int affectedSourceObservationCount,
            int externalReferenceCount, int referencesToContainCount, int graphProtectedCount,
            int eligibleRetirementCount, int retainedGraphSafeCount,
            int containedByNewerReferencesCount, int pendingWriteCount,
            Set<UUID> affectedRunIds, List<PlaceRollbackInspection> places,
            boolean ownershipProvenanceValid
    ) {}

    public record PlaceRollbackInspection(
            UUID writeId, UUID syncRunId, UUID placeId, String catalogStatus,
            String rollbackState, String expectedFinalState, boolean graphProtected,
            int sourceObservationCount, int externalReferenceCount,
            int referencesToContainCount, int protectedReferenceCount,
            boolean ownershipProvenanceValid
    ) {}

    public record RollbackResult(
            UUID syncRunId,
            int retiredCount,
            int graphProtectedCount,
            int containedByNewerReferencesCount,
            boolean alreadyRolledBack
    ) {}
}

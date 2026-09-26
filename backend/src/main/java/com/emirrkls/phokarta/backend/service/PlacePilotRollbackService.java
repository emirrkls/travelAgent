package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.repository.PlaceGraphProtectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
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
        List<RunIdentity> targetRows = jdbc.query("""
                SELECT pilot_run_key, canary_stage, status
                  FROM place_provider_sync_runs WHERE id = ?
                """, (rs, rowNum) -> new RunIdentity(
                rs.getString("pilot_run_key"), rs.getString("canary_stage"),
                rs.getString("status")), syncRunId);
        if (targetRows.size() != 1
                || !Set.of("SUCCEEDED", "FAILED").contains(targetRows.getFirst().status())) {
            throw new IllegalArgumentException("rollback requires a completed pilot run");
        }
        RunIdentity target = targetRows.getFirst();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 5517))",
                (rs, rowNum) -> rs.getObject(1), target.pilotRunKey());
        // Redirect dependency decisions and provider-state mutations share one global graph
        // lock. Taking it before reading any reference closes the merge-vs-rollback race.
        jdbc.query("SELECT pg_advisory_xact_lock(5517, 917)",
                (rs, rowNum) -> rs.getObject(1));

        Integer successorCount = jdbc.queryForObject("""
                SELECT count(*) FROM place_provider_sync_runs
                 WHERE reauthorizes_run_id = ?
                """, Integer.class, syncRunId);
        if (successorCount != null && successorCount > 0) {
            // A replacement can exist only after this attempt's writes were safely contained.
            // Retrying containment for the superseded attempt must never cross the lineage
            // boundary and retire the replacement (or stages subsequently based on it).
            return new RollbackResult(syncRunId, 0, 0, 0, true);
        }

        List<PilotWrite> writes = jdbc.query("""
                SELECT w.id, w.sync_run_id, w.place_id
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
                """, (rs, rowNum) -> new PilotWrite(
                rs.getObject("id", UUID.class), rs.getObject("sync_run_id", UUID.class),
                rs.getObject("place_id", UUID.class)),
                target.pilotRunKey(), syncRunId, target.canaryStage());
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
            List<ExternalRef> exposedRefs = jdbc.query("""
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
                    """, (rs, rowNum) -> new ExternalRef(
                    rs.getString("provider"), rs.getString("external_id"),
                    rs.getObject("current_source_record_id", UUID.class),
                    rs.getBoolean("owned_by_run"), rs.getBoolean("redirect_protected")),
                    write.placeId(), write.syncRunId(), write.syncRunId(), write.placeId());
            for (ExternalRef ref : exposedRefs) {
                if (!ref.ownedByRun() || ref.redirectProtected()) continue;
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
            String rollbackState;
            if (hasNewerExposedReference) {
                rollbackState = "CONTAINED_NEWER_REFERENCES";
                containedByNewerReferences++;
            } else {
                jdbc.update("""
                        UPDATE places SET catalog_status = 'RETIRED', updated_at = ? WHERE id = ?
                        """, occurredAt, write.placeId());
                rollbackState = protectedGraph ? "RETIRED_GRAPH_PROTECTED" : "RETIRED";
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

    private record RunIdentity(String pilotRunKey, String canaryStage, String status) {}

    private record PilotWrite(UUID id, UUID syncRunId, UUID placeId) {}

    private record ExternalRef(
            String provider,
            String externalId,
            UUID sourceRecordId,
            boolean ownedByRun,
            boolean redirectProtected
    ) {}

    public record RollbackResult(
            UUID syncRunId,
            int retiredCount,
            int graphProtectedCount,
            int containedByNewerReferencesCount,
            boolean alreadyRolledBack
    ) {}
}

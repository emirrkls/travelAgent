package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.repository.PlaceGraphProtectionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PlaceProviderStateService {
    private final JdbcTemplate jdbc;
    private final PlaceGraphProtectionRepository graphProtection;

    public PlaceProviderStateService(
            JdbcTemplate jdbc,
            PlaceGraphProtectionRepository graphProtection
    ) {
        this.jdbc = jdbc;
        this.graphProtection = graphProtection;
    }

    @Transactional
    public RemovalResult markRemoved(
            String provider,
            String externalId,
            UUID syncRunId,
            OffsetDateTime occurredAt
    ) {
        ExternalRef ref = requireRef(provider, externalId);
        jdbc.update("""
                UPDATE place_external_refs
                   SET status = 'INACTIVE', last_seen_at = ?, last_sync_run_id = ?,
                       redirected_provider = NULL, redirected_external_id = NULL
                 WHERE provider = ? AND external_id = ?
                """, occurredAt, syncRunId, provider, externalId);
        insertEvent(provider, externalId, ref.placeId(), "REMOVED", syncRunId, occurredAt,
                null, null);

        Integer activeRefs = jdbc.queryForObject("""
                SELECT count(*) FROM place_external_refs
                 WHERE place_id = ? AND status = 'ACTIVE'
                """, Integer.class, ref.placeId());
        String origin = jdbc.queryForObject(
                "SELECT origin FROM places WHERE id = ?", String.class, ref.placeId());
        boolean graphProtected = graphProtection.hasPhokartaOwnedGraph(ref.placeId());
        boolean retired = Integer.valueOf(0).equals(activeRefs)
                && "EXTERNAL_IMPORT".equals(origin)
                && !graphProtected;
        if (retired) {
            jdbc.update("UPDATE places SET catalog_status = 'RETIRED', updated_at = ? WHERE id = ?",
                    occurredAt, ref.placeId());
        }
        return new RemovalResult(ref.placeId(), graphProtected, retired);
    }

    @Transactional
    public void markMerged(
            String provider,
            String externalId,
            String survivingExternalId,
            UUID syncRunId,
            OffsetDateTime occurredAt
    ) {
        ExternalRef source = requireRef(provider, externalId);
        ExternalRef target = requireRef(provider, survivingExternalId);
        if (!source.placeId().equals(target.placeId())) {
            throw new IllegalStateException(
                    "provider redirect crosses canonical Place UUIDs and requires human review");
        }
        jdbc.update("""
                UPDATE place_external_refs
                   SET status = 'MERGED', redirected_provider = ?, redirected_external_id = ?,
                       last_seen_at = ?, last_sync_run_id = ?
                 WHERE provider = ? AND external_id = ?
                """, provider, survivingExternalId, occurredAt, syncRunId, provider, externalId);
        insertEvent(provider, externalId, source.placeId(), "MERGED", syncRunId, occurredAt,
                provider, survivingExternalId);
    }

    private ExternalRef requireRef(String provider, String externalId) {
        List<ExternalRef> refs = jdbc.query("""
                SELECT place_id FROM place_external_refs
                 WHERE provider = ? AND external_id = ?
                """, (rs, rowNum) -> new ExternalRef(rs.getObject("place_id", UUID.class)),
                provider, externalId);
        if (refs.size() != 1) {
            throw new IllegalArgumentException("unknown provider external reference");
        }
        return refs.getFirst();
    }

    private void insertEvent(
            String provider,
            String externalId,
            UUID placeId,
            String eventType,
            UUID syncRunId,
            OffsetDateTime occurredAt,
            String redirectedProvider,
            String redirectedExternalId
    ) {
        UUID eventId = UUID.nameUUIDFromBytes((syncRunId + "\n" + provider + "\n" + externalId
                + "\n" + eventType).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO place_external_ref_events (
                    id, provider, external_id, place_id, event_type, sync_run_id,
                    redirected_provider, redirected_external_id, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, eventId, provider, externalId, placeId, eventType, syncRunId,
                redirectedProvider, redirectedExternalId, occurredAt);
    }

    private record ExternalRef(UUID placeId) {}

    public record RemovalResult(UUID placeId, boolean graphProtected, boolean retired) {}
}

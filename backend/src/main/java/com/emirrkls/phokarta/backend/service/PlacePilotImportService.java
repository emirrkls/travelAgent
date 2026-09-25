package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Private one-shot importer for an approved, non-secret M5.5B manifest.
 *
 * <p>This service intentionally has no controller. Provider credentials are not accepted and
 * provider IDs remain only in provenance tables. Each source observation and canonical candidate
 * is committed independently, so a failed candidate cannot leave a partial Place/ref group and a
 * retry can continue idempotently.</p>
 */
@Service
public class PlacePilotImportService {
    private static final String EXPECTED_SCOPE = "didim_core";
    private static final double EXPECTED_CENTER_LATITUDE = 37.3751;
    private static final double EXPECTED_CENTER_LONGITUDE = 27.2678;
    private static final double EXPECTED_RADIUS_METERS = 6000.0;
    private static final String EXPECTED_OVERTURE_RELEASE = "2026-09-23.0";
    private static final String EXPECTED_FSQ_RELEASE = "2026-09-15 20:07:45.157000";
    private static final String EXPECTED_FSQ_SNAPSHOT = "2325979374271449319";
    private static final long MAX_MANIFEST_BYTES = 128L * 1024L * 1024L;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public PlacePilotImportService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ImportResult importApproved(Path manifestPath) throws IOException {
        Path resolved = manifestPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(resolved) || !resolved.getFileName().toString().endsWith(".json")) {
            throw new IllegalArgumentException("approved import manifest must be a regular JSON file");
        }
        if (Files.size(resolved) > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("approved import manifest exceeds the 128 MiB limit");
        }
        return importApproved(objectMapper.readTree(Files.readAllBytes(resolved)));
    }

    public ImportResult importApproved(JsonNode envelope) {
        JsonNode manifest = requiredObject(envelope, "manifest");
        String suppliedHash = requiredText(envelope, "manifest_hash");
        String actualHash = hashManifest(manifest);
        if (!actualHash.equals(suppliedHash)) {
            throw new IllegalArgumentException("approved import manifest hash does not match content");
        }
        validateManifest(manifest);

        ExistingRun existing = findRunByHash(actualHash);
        if (existing != null && "SUCCEEDED".equals(existing.status())) {
            return existing.toResult(true);
        }

        UUID runId = UUID.fromString(requiredText(manifest, "run_id"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        startRun(manifest, actualHash, runId, now);
        Counters counters = new Counters();
        try {
            for (JsonNode source : requiredArray(manifest, "source_records")) {
                transactions.executeWithoutResult(status -> insertSourceRecord(runId, manifest, source));
                counters.sourceCount++;
                if (source.path("usable").asBoolean(true)) counters.usableCount++;
                else counters.rejectedCount++;
            }
            for (JsonNode candidate : requiredArray(manifest, "candidates")) {
                String decision = requiredText(candidate, "decision");
                switch (decision) {
                    case "AUTO_LINK" -> {
                        requireApproved(candidate);
                        transactions.executeWithoutResult(status -> importCandidate(runId, candidate, false));
                        counters.linkedCount++;
                    }
                    case "CREATE_NEW" -> {
                        requireApproved(candidate);
                        transactions.executeWithoutResult(status -> importCandidate(runId, candidate, true));
                        counters.createdCount++;
                    }
                    case "REVIEW_REQUIRED" -> counters.reviewCount++;
                    case "REJECT" -> { }
                    default -> throw new IllegalArgumentException("unsupported candidate decision: " + decision);
                }
            }
            finishRun(runId, "SUCCEEDED", counters, null);
            return new ImportResult(runId, false, "SUCCEEDED", counters.sourceCount,
                    counters.usableCount, counters.createdCount, counters.linkedCount,
                    counters.reviewCount, counters.rejectedCount);
        } catch (RuntimeException failure) {
            finishRun(runId, "FAILED", counters, sanitizeFailure(failure));
            throw failure;
        }
    }

    public String hashManifest(JsonNode manifest) {
        try {
            JsonNode canonical = canonicalize(manifest);
            byte[] encoded = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("unable to hash import manifest", exception);
        }
    }

    private void validateManifest(JsonNode manifest) {
        if (!"APPROVED".equals(requiredText(manifest, "status"))) {
            throw new IllegalArgumentException("manifest status must be APPROVED");
        }
        if (requiredText(manifest, "method_version").isBlank()) {
            throw new IllegalArgumentException("manifest method version is required");
        }
        JsonNode scope = requiredObject(manifest, "scope");
        if (!EXPECTED_SCOPE.equals(requiredText(scope, "name"))
                || Double.compare(requiredFiniteDouble(scope, "center_latitude"),
                        EXPECTED_CENTER_LATITUDE) != 0
                || Double.compare(requiredFiniteDouble(scope, "center_longitude"),
                        EXPECTED_CENTER_LONGITUDE) != 0
                || Double.compare(requiredFiniteDouble(scope, "radius_meters"),
                        EXPECTED_RADIUS_METERS) != 0) {
            throw new IllegalArgumentException(
                    "only the frozen Didim Core center and 6000 m scope may be imported");
        }
        JsonNode providers = requiredObject(manifest, "providers");
        JsonNode overture = requiredObject(providers, "overture");
        JsonNode fsq = requiredObject(providers, "fsq");
        if (!EXPECTED_OVERTURE_RELEASE.equals(requiredText(overture, "release"))
                || !EXPECTED_FSQ_RELEASE.equals(requiredText(fsq, "release"))
                || !EXPECTED_FSQ_SNAPSHOT.equals(requiredText(fsq, "snapshot_id"))) {
            throw new IllegalArgumentException("manifest provider versions do not match the reviewed dry run");
        }

        Set<UUID> sourceIds = new HashSet<>();
        for (JsonNode source : requiredArray(manifest, "source_records")) {
            UUID sourceId = UUID.fromString(requiredText(source, "source_record_id"));
            if (!sourceIds.add(sourceId)) {
                throw new IllegalArgumentException("duplicate source record UUID in manifest");
            }
            String provider = requiredText(source, "provider").toUpperCase();
            String expectedRelease = switch (provider) {
                case "OVERTURE" -> EXPECTED_OVERTURE_RELEASE;
                case "FSQ" -> EXPECTED_FSQ_RELEASE;
                default -> throw new IllegalArgumentException("unsupported source provider");
            };
            if (!expectedRelease.equals(requiredText(source, "source_release"))) {
                throw new IllegalArgumentException("source release does not match reviewed provider version");
            }
            if ("FSQ".equals(provider)
                    && !EXPECTED_FSQ_SNAPSHOT.equals(requiredText(source, "snapshot_id"))) {
                throw new IllegalArgumentException("FSQ source snapshot does not match reviewed dry run");
            }
            validateOptionalScopedPoint(source, "source record");
        }

        Set<UUID> assignedSourceIds = new HashSet<>();
        for (JsonNode candidate : requiredArray(manifest, "candidates")) {
            String decision = requiredText(candidate, "decision");
            if (!decision.equals("AUTO_LINK") && !decision.equals("CREATE_NEW")) continue;
            requireApproved(candidate);
            UUID.fromString(requiredText(candidate, "canonical_place_id"));
            JsonNode canonical = requiredObject(candidate, "canonical");
            validateRequiredScopedPoint(canonical, "canonical candidate");
            requiredText(canonical, "name");
            requiredText(canonical, "category");
            requiredText(canonical, "city");
            requiredText(canonical, "region");
            requiredText(canonical, "country");
            int candidateSourceCount = 0;
            for (JsonNode sourceIdNode : requiredArray(candidate, "source_record_ids")) {
                if (!sourceIdNode.isTextual()) {
                    throw new IllegalArgumentException("candidate source record UUID must be text");
                }
                UUID sourceId = UUID.fromString(sourceIdNode.asText());
                if (!sourceIds.contains(sourceId)) {
                    throw new IllegalArgumentException("candidate references a source outside the manifest");
                }
                if (!assignedSourceIds.add(sourceId)) {
                    throw new IllegalArgumentException("source record is assigned to multiple candidates");
                }
                candidateSourceCount++;
            }
            if (candidateSourceCount == 0) {
                throw new IllegalArgumentException("write candidate must contain a source record");
            }
        }
    }

    private void startRun(JsonNode manifest, String manifestHash, UUID runId, OffsetDateTime now) {
        JsonNode scope = requiredObject(manifest, "scope");
        JsonNode providers = requiredObject(manifest, "providers");
        String resolvedRelease = "overture="
                + requiredText(requiredObject(providers, "overture"), "release")
                + ";fsq=" + requiredText(requiredObject(providers, "fsq"), "release");
        String snapshot = requiredText(requiredObject(providers, "fsq"), "snapshot_id");
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO place_provider_sync_runs (
                        id, provider, requested_release, resolved_release, snapshot_id,
                        method_version, scope_name, scope_center_latitude,
                        scope_center_longitude, scope_radius_meters, started_at, status,
                        manifest_hash
                    ) VALUES (?, 'MULTI_SOURCE', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STARTED', ?)
                    ON CONFLICT (id) DO NOTHING
                    """, runId, textOrNull(manifest, "requested_release"), resolvedRelease,
                    snapshot, requiredText(manifest, "method_version"), requiredText(scope, "name"),
                    scope.path("center_latitude").asDouble(), scope.path("center_longitude").asDouble(),
                    scope.path("radius_meters").asDouble(), now, manifestHash);
            if (inserted == 0) {
                String storedHash = jdbc.queryForObject(
                        "SELECT manifest_hash FROM place_provider_sync_runs WHERE id = ?",
                        String.class, runId);
                if (!manifestHash.equals(storedHash)) {
                    throw new IllegalArgumentException("run UUID is already bound to another manifest");
                }
                jdbc.update("""
                        UPDATE place_provider_sync_runs
                           SET status = 'STARTED', completed_at = NULL, failure_reason = NULL
                         WHERE id = ? AND status = 'FAILED'
                        """, runId);
            }
        });
    }

    private void insertSourceRecord(UUID runId, JsonNode manifest, JsonNode source) {
        UUID sourceId = UUID.fromString(requiredText(source, "source_record_id"));
        String provider = requiredText(source, "provider").toUpperCase();
        if (!provider.equals("OVERTURE") && !provider.equals("FSQ")) {
            throw new IllegalArgumentException("unsupported source provider");
        }
        Double latitude = optionalFiniteDouble(source, "latitude");
        Double longitude = optionalFiniteDouble(source, "longitude");
        String location = latitude == null || longitude == null
                ? "NULL"
                : "ST_SetSRID(ST_MakePoint(" + longitude + "," + latitude + "),4326)";
        String sql = """
                INSERT INTO place_source_records (
                    id, sync_run_id, provider, external_id, source_release, snapshot_id,
                    method_version, normalized_name, location, address, locality, region,
                    country_code, provider_categories, proposed_place_category, phone, website,
                    operating_status, source_hash, license_identifier, provenance,
                    observed_at, retrieved_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?,
                    ?::jsonb, ?, ?)
                ON CONFLICT DO NOTHING
                """.formatted(location);
        OffsetDateTime observed = parseTime(source, "observed_at");
        OffsetDateTime retrieved = parseTime(source, "retrieved_at");
        int inserted = jdbc.update(sql, sourceId, runId, provider, requiredText(source, "external_id"),
                requiredText(source, "source_release"), textOrNull(source, "snapshot_id"),
                requiredText(manifest, "method_version"), textOrNull(source, "normalized_name"),
                textOrNull(source, "address"), textOrNull(source, "locality"),
                textOrNull(source, "region"), textOrNull(source, "country_code"),
                source.path("provider_categories").isArray()
                        ? source.path("provider_categories").toString() : "[]",
                textOrNull(source, "proposed_place_category"), textOrNull(source, "phone"),
                textOrNull(source, "website"), textOrNull(source, "operating_status"),
                requiredText(source, "source_hash"), requiredText(source, "license_identifier"),
                source.path("provenance").isObject() ? source.path("provenance").toString() : "{}",
                observed, retrieved);
        if (inserted == 0) {
            Integer same = jdbc.queryForObject("""
                    SELECT count(*) FROM place_source_records
                     WHERE id = ? AND provider = ? AND external_id = ? AND source_hash = ?
                    """, Integer.class, sourceId, provider, requiredText(source, "external_id"),
                    requiredText(source, "source_hash"));
            if (!Integer.valueOf(1).equals(same)) {
                throw new IllegalArgumentException("source record UUID or identity collision");
            }
        }
    }

    private void importCandidate(UUID runId, JsonNode candidate, boolean createNew) {
        UUID placeId = UUID.fromString(requiredText(candidate, "canonical_place_id"));
        JsonNode canonical = requiredObject(candidate, "canonical");
        if (createNew) insertCanonicalPlace(placeId, canonical);
        else requireActivePlace(placeId);

        for (JsonNode sourceIdNode : requiredArray(candidate, "source_record_ids")) {
            UUID sourceId = UUID.fromString(sourceIdNode.asText());
            SourceRef source = requireSource(sourceId);
            int linked = jdbc.update("""
                    INSERT INTO place_external_refs (
                        provider, external_id, place_id, current_source_record_id,
                        source_release, snapshot_id, first_seen_at, last_seen_at, status,
                        source_hash, last_sync_run_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    ON CONFLICT (provider, external_id) DO UPDATE SET
                        current_source_record_id = EXCLUDED.current_source_record_id,
                        source_release = EXCLUDED.source_release,
                        snapshot_id = EXCLUDED.snapshot_id,
                        last_seen_at = EXCLUDED.last_seen_at,
                        status = 'ACTIVE', redirected_provider = NULL,
                        redirected_external_id = NULL, source_hash = EXCLUDED.source_hash,
                        last_sync_run_id = EXCLUDED.last_sync_run_id
                    WHERE place_external_refs.place_id = EXCLUDED.place_id
                    """, source.provider(), source.externalId(), placeId, sourceId,
                    source.release(), source.snapshotId(), source.observedAt(), source.retrievedAt(),
                    source.sourceHash(), runId);
            if (linked != 1) {
                throw new IllegalStateException("provider external ID is linked to another canonical Place");
            }
            UUID eventId = UUID.nameUUIDFromBytes((runId + "\n" + source.provider() + "\n"
                    + source.externalId() + "\nLINKED").getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO place_external_ref_events (
                        id, provider, external_id, place_id, event_type, source_record_id,
                        sync_run_id, occurred_at
                    ) VALUES (?, ?, ?, ?, 'LINKED', ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, eventId, source.provider(), source.externalId(), placeId, sourceId,
                    runId, source.retrievedAt());
        }
        JsonNode overrides = candidate.path("overrides");
        if (overrides.isArray()) {
            for (JsonNode override : overrides) recordOverride(placeId, override);
        }
    }

    private void insertCanonicalPlace(UUID placeId, JsonNode canonical) {
        int inserted = jdbc.update("""
                INSERT INTO places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, origin, catalog_status,
                    created_at, updated_at
                ) VALUES (?, ?, '', ?, '{}', ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, ?, ?,
                    '', '{}', 0, 'EXTERNAL_IMPORT', 'ACTIVE', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, placeId, requiredText(canonical, "name"), requiredText(canonical, "category"),
                requiredFiniteDouble(canonical, "longitude"),
                requiredFiniteDouble(canonical, "latitude"),
                requiredText(canonical, "city"), requiredText(canonical, "region"),
                requiredText(canonical, "country"), canonical.path("address").asText(""));
        if (inserted == 0) {
            String origin = jdbc.queryForObject("SELECT origin FROM places WHERE id = ?",
                    String.class, placeId);
            if (!"EXTERNAL_IMPORT".equals(origin)) {
                throw new IllegalArgumentException("canonical UUID collides with a non-imported Place");
            }
        }
    }

    private void recordOverride(UUID placeId, JsonNode override) {
        UUID overrideId = UUID.fromString(requiredText(override, "override_id"));
        String field = requiredText(override, "field");
        if (!List.of("name", "category", "location", "address", "city", "region", "country")
                .contains(field)) {
            throw new IllegalArgumentException("unsupported canonical override field");
        }
        JsonNode value = override.get("value");
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("canonical override value is required");
        }
        String actorType = requiredText(override, "actor_type");
        String reason = requiredText(override, "reason");
        Integer sameActiveOverride = jdbc.queryForObject("""
                SELECT count(*) FROM place_canonical_overrides
                 WHERE id = ? AND place_id = ? AND field_name = ?
                   AND override_value = ?::jsonb AND actor_type = ? AND reason = ?
                   AND superseded_at IS NULL
                """, Integer.class, overrideId, placeId, field, value.toString(), actorType, reason);
        if (Integer.valueOf(1).equals(sameActiveOverride)) {
            return;
        }
        Integer collidingOverride = jdbc.queryForObject(
                "SELECT count(*) FROM place_canonical_overrides WHERE id = ?",
                Integer.class, overrideId);
        if (!Integer.valueOf(0).equals(collidingOverride)) {
            throw new IllegalArgumentException(
                    "canonical override UUID is already bound or has been superseded");
        }
        String priorJson = currentCanonicalValue(placeId, field).toString();
        jdbc.update("""
                UPDATE place_canonical_overrides SET superseded_at = now()
                 WHERE place_id = ? AND field_name = ? AND superseded_at IS NULL
                """, placeId, field);
        applyCanonicalValue(placeId, field, value);
        UUID sourceId = override.path("source_record_id").isTextual()
                ? UUID.fromString(override.path("source_record_id").asText()) : null;
        jdbc.update("""
                INSERT INTO place_canonical_overrides (
                    id, place_id, field_name, override_value, prior_value, actor_type,
                    actor_reference, reason, source_record_id, created_at
                ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, now())
                """, overrideId, placeId, field, value.toString(), priorJson,
                actorType, textOrNull(override, "actor_reference"), reason, sourceId);
    }

    private JsonNode currentCanonicalValue(UUID placeId, String field) {
        return switch (field) {
            case "location" -> objectMapper.valueToTree(Map.of(
                    "latitude", jdbc.queryForObject("SELECT ST_Y(location) FROM places WHERE id = ?",
                            Double.class, placeId),
                    "longitude", jdbc.queryForObject("SELECT ST_X(location) FROM places WHERE id = ?",
                            Double.class, placeId)));
            default -> objectMapper.valueToTree(jdbc.queryForObject(
                    "SELECT " + field + " FROM places WHERE id = ?", String.class, placeId));
        };
    }

    private void applyCanonicalValue(UUID placeId, String field, JsonNode value) {
        if ("location".equals(field)) {
            validateRequiredScopedPoint(value, "canonical location override");
            jdbc.update("""
                    UPDATE places SET location = ST_SetSRID(ST_MakePoint(?, ?), 4326), updated_at = now()
                     WHERE id = ?
                    """, requiredFiniteDouble(value, "longitude"),
                    requiredFiniteDouble(value, "latitude"), placeId);
        } else {
            jdbc.update("UPDATE places SET " + field + " = ?, updated_at = now() WHERE id = ?",
                    value.asText(), placeId);
        }
    }

    private SourceRef requireSource(UUID sourceId) {
        List<SourceRef> rows = jdbc.query("""
                SELECT provider, external_id, source_release, snapshot_id, source_hash,
                       observed_at, retrieved_at
                  FROM place_source_records WHERE id = ?
                """, (rs, rowNum) -> new SourceRef(
                rs.getString("provider"), rs.getString("external_id"),
                rs.getString("source_release"), rs.getString("snapshot_id"),
                rs.getString("source_hash"), rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("retrieved_at", OffsetDateTime.class)), sourceId);
        if (rows.size() != 1) throw new IllegalArgumentException("unknown source record UUID");
        return rows.getFirst();
    }

    private void requireActivePlace(UUID placeId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM places WHERE id = ? AND catalog_status = 'ACTIVE'",
                Integer.class, placeId);
        if (!Integer.valueOf(1).equals(count)) {
            throw new IllegalArgumentException("AUTO_LINK target must be an active canonical Place");
        }
    }

    private void finishRun(UUID runId, String status, Counters counters, String failure) {
        transactions.executeWithoutResult(transaction -> jdbc.update("""
                UPDATE place_provider_sync_runs
                   SET completed_at = now(), status = ?, source_count = ?, usable_count = ?,
                       created_count = ?, linked_count = ?, review_count = ?, rejected_count = ?,
                       failure_reason = ?
                 WHERE id = ?
                """, status, counters.sourceCount, counters.usableCount, counters.createdCount,
                counters.linkedCount, counters.reviewCount, counters.rejectedCount, failure, runId));
    }

    private ExistingRun findRunByHash(String hash) {
        List<ExistingRun> rows = jdbc.query("""
                SELECT id, status, source_count, usable_count, created_count, linked_count,
                       review_count, rejected_count
                  FROM place_provider_sync_runs WHERE manifest_hash = ?
                """, (rs, rowNum) -> new ExistingRun(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getLong("source_count"), rs.getLong("usable_count"),
                rs.getLong("created_count"), rs.getLong("linked_count"),
                rs.getLong("review_count"), rs.getLong("rejected_count")), hash);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            fields.forEach(entry -> result.set(entry.getKey(), canonicalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node;
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private Iterable<JsonNode> requiredArray(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return value;
    }

    private String requiredText(JsonNode parent, String field) {
        String value = textOrNull(parent, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private String textOrNull(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Double optionalFiniteDouble(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return value.doubleValue();
    }

    private double requiredFiniteDouble(JsonNode parent, String field) {
        Double value = optionalFiniteDouble(parent, field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private void validateOptionalScopedPoint(JsonNode node, String label) {
        Double latitude = optionalFiniteDouble(node, "latitude");
        Double longitude = optionalFiniteDouble(node, "longitude");
        if (latitude == null && longitude == null) return;
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException(label + " must provide both coordinates or neither");
        }
        validateScopedPoint(latitude, longitude, label);
    }

    private void validateRequiredScopedPoint(JsonNode node, String label) {
        validateScopedPoint(requiredFiniteDouble(node, "latitude"),
                requiredFiniteDouble(node, "longitude"), label);
    }

    private void validateScopedPoint(double latitude, double longitude, String label) {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(label + " coordinates are out of range");
        }
        double latitudeDelta = Math.toRadians(latitude - EXPECTED_CENTER_LATITUDE);
        double longitudeDelta = Math.toRadians(longitude - EXPECTED_CENTER_LONGITUDE);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(EXPECTED_CENTER_LATITUDE))
                * Math.cos(Math.toRadians(latitude))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        double clamped = Math.max(0, Math.min(1, a));
        double distanceMeters = 6_371_008.8 * 2
                * Math.atan2(Math.sqrt(clamped), Math.sqrt(1 - clamped));
        if (distanceMeters > EXPECTED_RADIUS_METERS + 0.01) {
            throw new IllegalArgumentException(label + " lies outside Didim Core");
        }
    }

    private OffsetDateTime parseTime(JsonNode parent, String field) {
        String value = requiredText(parent, field);
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException notOffsetDateTime) {
            try {
                return LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException notDate) {
                throw new IllegalArgumentException(field + " must be an ISO date or offset timestamp");
            }
        }
    }

    private void requireApproved(JsonNode candidate) {
        if (!candidate.path("approved").asBoolean(false)) {
            throw new IllegalArgumentException("canonical write candidate lacks explicit approval");
        }
    }

    private String sanitizeFailure(RuntimeException failure) {
        String name = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return name;
        return (name + ": " + message).substring(0, Math.min(500, name.length() + 2 + message.length()));
    }

    private static final class Counters {
        long sourceCount;
        long usableCount;
        long createdCount;
        long linkedCount;
        long reviewCount;
        long rejectedCount;
    }

    private record SourceRef(
            String provider,
            String externalId,
            String release,
            String snapshotId,
            String sourceHash,
            OffsetDateTime observedAt,
            OffsetDateTime retrievedAt
    ) {}

    private record ExistingRun(
            UUID runId,
            String status,
            long sourceCount,
            long usableCount,
            long createdCount,
            long linkedCount,
            long reviewCount,
            long rejectedCount
    ) {
        ImportResult toResult(boolean alreadyImported) {
            return new ImportResult(runId, alreadyImported, status, sourceCount, usableCount,
                    createdCount, linkedCount, reviewCount, rejectedCount);
        }
    }

    public record ImportResult(
            UUID runId,
            boolean alreadyImported,
            String status,
            long sourceCount,
            long usableCount,
            long createdCount,
            long linkedCount,
            long reviewCount,
            long rejectedCount
    ) {}
}

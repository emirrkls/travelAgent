package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Private operations policy. No endpoint, scheduler, datasource, probe or mutation capability. */
public final class PlacePilotV3Policy {
    public static final String V2 = "didim-autonomous-validation-v2";
    public static final String V3 = "didim-autonomous-validation-v3";
    public static final UUID CONTAINED_RUN = UUID.fromString("d70adea5-6e3f-4c32-92c0-49695eeeb9ce");
    public static final String SENTINEL = "aa000000-0000-4000-8000-000000000001";
    public static final int PILOT_SIZE = 71;
    public static final int SAMPLES = 20;
    public static final List<String> SURFACES = List.of("search", "nearby", "bounds", "detail");
    public static final Map<String, String> PATHS = Map.of(
            "search", "/api/v1/places?search=Staging+Harbor+Cafe&page=0&size=100&sort=name%2Casc",
            "nearby", "/api/v1/places/nearby?lat=41.0220000&lon=28.9784000&radiusMeters=250.0&limit=200",
            "bounds", "/api/v1/places/bounds?west=28.9684000&south=41.0120000&east=28.9884000&north=41.0320000&limit=200",
            "detail", "/api/v1/places/" + SENTINEL);
    public static final List<String> PRODUCT_CHECKS = List.of(
            "search", "nearby", "bounds", "selected_place_coverage",
            "turkish_search", "zero_experience_detail", "want_to_go", "collections",
            "synthetic_v2_experience", "detail_after_publication", "experience_first_explore",
            "public_provider_id_isolation", "rollback_safety", "graph_safety");

    private PlacePilotV3Policy() {}

    public static boolean isV3(JsonNode manifest) {
        return V3.equals(manifest.path("method_version").asText());
    }

    static void validateEnvelopePolicy(JsonNode manifest) {
        if (!isV3(manifest)) return;
        require("STAGE_1".equals(manifest.path("canary_stage").asText()), "v3 is Stage 1 only");
        require(CONTAINED_RUN.toString().equals(manifest.path("reauthorizes_run_id").asText()),
                "v3 requires the immutable contained v2 predecessor");
        require(!CONTAINED_RUN.toString().equals(manifest.path("run_id").asText()), "v3 requires a new run UUID");
        require(V2.equals(manifest.path("canonical_identity_method_version").asText()),
                "v3 must preserve the v2 canonical UUID basis");
        require("ADVISORY_ONLY".equals(manifest.path("performance_policy").asText()),
                "v3 relative performance is advisory only");
        require(manifest.path("predecessor_manifest_hash").asText().matches("[0-9a-f]{64}"),
                "v3 requires the sealed predecessor manifest hash");
        int eligible = 0;
        int selected = 0;
        for (JsonNode candidate : manifest.path("candidates")) {
            if (candidate.path("canary_eligible").asBoolean()) eligible++;
            if (candidate.path("selected_for_stage").asBoolean()) selected++;
        }
        require(eligible == PILOT_SIZE && selected == PILOT_SIZE, "v3 must re-adopt exactly 71 eligible Places");
    }

    /** Typed receipt cannot be constructed from a self-attested advisory status. */
    public static final class Evidence {
        private final UUID runId;
        private final String manifestHash;
        private final Instant baselineStart, baselineEnd, afterStart, afterEnd;
        private final ObjectNode summary;

        private Evidence(UUID runId, String hash, JsonNode before, JsonNode after, ObjectNode summary) {
            this.runId = runId;
            this.manifestHash = hash;
            baselineStart = time(before, "started_at"); baselineEnd = time(before, "completed_at");
            afterStart = time(after, "started_at"); afterEnd = time(after, "completed_at");
            this.summary = summary.deepCopy();
        }

        public ObjectNode summary() { return summary.deepCopy(); }

        void validateBinding(UUID run, String hash, Instant importStart, Instant importEnd) {
            require(runId.equals(run) && manifestHash.equals(hash), "persistent evidence run/hash mismatch");
            require(!baselineEnd.isAfter(importStart) && !afterStart.isBefore(importEnd),
                    "persistent baseline/after does not bracket catalog mutation");
            require(!baselineEnd.isBefore(importStart.minusSeconds(900)), "persistent PRE completion is stale");
            require(!afterEnd.isBefore(afterStart), "persistent observation time is invalid");
        }
    }

    /** Validates all 168 individual observations; relative delta NEVER controls safety. */
    public static Evidence verify(UUID runId, String manifestHash, JsonNode before, JsonNode after) {
        require(runId != null && !CONTAINED_RUN.equals(runId), "persistent evidence needs a new run");
        require(manifestHash != null && manifestHash.matches("[0-9a-f]{64}"), "persistent evidence hash invalid");
        validateSnapshot(before, "PRE", runId, manifestHash);
        validateSnapshot(after, "POST", runId, manifestHash);
        require(before.path("target").equals(after.path("target")),
                "persistent process/path/health identity changed");
        if (before.has("process_start_time_seconds") || after.has("process_start_time_seconds")) {
            require(before.path("process_start_time_seconds").isNumber()
                    && after.path("process_start_time_seconds").isNumber()
                    && before.path("process_start_time_seconds").equals(after.path("process_start_time_seconds")),
                    "persistent JVM start changed");
        }
        require(!time(before, "completed_at").isAfter(time(after, "started_at")),
                "persistent observations out of order");
        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("policy", "ADVISORY_ONLY");
        summary.put("baseline_sha256", sha256(before.toString()));
        summary.put("after_sha256", sha256(after.toString()));
        summary.set("target", before.path("target").deepCopy());
        summary.put("sample_count_per_surface", SAMPLES);
        ObjectNode surfaces = summary.putObject("surfaces");
        boolean comparable = true, degraded = false;
        for (String surface : SURFACES) {
            List<JsonNode> b = timed(before, surface), a = timed(after, surface);
            ObjectNode result = surfaces.putObject(surface);
            double bm = median(b), am = median(a), bp = p90(b), ap = p90(a);
            List<String> beforeIds = stableIdentity(before, surface), afterIds = stableIdentity(after, surface);
            boolean same = beforeIds.size() == 1 && afterIds.size() == 1 && beforeIds.equals(afterIds);
            comparable &= same;
            degraded |= am > bm || ap > bp;
            result.put("path", PATHS.get(surface));
            result.put("baseline_median_ms", bm).put("after_median_ms", am);
            result.put("baseline_p90_ms", bp).put("after_p90_ms", ap);
            result.put("comparable", same);
            result.put("median_relative_delta", (am - bm) / bm);
            result.put("p90_relative_delta", (ap - bp) / bp);
            result.put("baseline_result_count", b.getFirst().path("result_count").intValue());
            result.put("after_result_count", a.getFirst().path("result_count").intValue());
            result.put("baseline_id_digest", b.getFirst().path("id_digest").asText());
            result.put("after_id_digest", a.getFirst().path("id_digest").asText());
            result.put("baseline_payload_bytes_median", bytesMedian(b));
            result.put("after_payload_bytes_median", bytesMedian(a));
        }
        summary.put("PERFORMANCE_ADVISORY", !comparable ? "INCOMPARABLE" : degraded ? "DEGRADED" : "NORMAL");
        return new Evidence(runId, manifestHash, before, after, summary);
    }

    public static void verifyBaseline(UUID runId, String manifestHash, JsonNode baseline) {
        validateSnapshot(baseline, "PRE", runId, manifestHash);
    }

    public static void verifySnapshot(UUID runId, String hash, String role, JsonNode snapshot) {
        require(List.of("PRE", "POST").contains(role), "persistent role invalid");
        validateSnapshot(snapshot, role, runId, hash);
    }

    public static void verifyFreshPre(UUID runId, String hash, JsonNode snapshot, Instant now) {
        verifyBaseline(runId, hash, snapshot);
        Instant end = time(snapshot, "completed_at");
        require(!end.isAfter(now) && !end.isBefore(now.minusSeconds(900)), "persistent PRE completion is stale/future");
    }

    private static void validateSnapshot(JsonNode value, String role, UUID runId, String hash) {
        require(value != null && value.isObject(), "persistent snapshot missing");
        require("persistent-pilot-v3-v1".equals(value.path("version").asText()), "persistent format invalid");
        require(role.equals(value.path("role").asText()) && runId.toString().equals(value.path("run_id").asText())
                && hash.equals(value.path("manifest_hash").asText()), "persistent identity invalid");
        Instant start = time(value, "started_at"), end = time(value, "completed_at");
        require(!end.isBefore(start) && end.minusSeconds(900).isBefore(start), "persistent block time invalid");
        JsonNode target = value.path("target");
        require("LONG_LIVED_PERSISTENT".equals(target.path("kind").asText()), "persistent target kind invalid");
        require(target.path("detail_observability_enabled").isBoolean()
                && target.path("detail_observability_enabled").booleanValue()
                && target.path("detail_slow_threshold_ms").isIntegralNumber()
                && target.path("detail_slow_threshold_ms").intValue() == 350,
                "persistent Detail observability contract changed");
        java.util.Set<String> safeFields = java.util.Set.of("kind", "origin", "route_id", "container_id",
                "image_sha", "java_identity", "container_started_at", "restart_count", "oom",
                "backend_healthy", "database_healthy", "caddy_running", "detail_observability_enabled", "detail_slow_threshold_ms", "network_identity");
        target.fieldNames().forEachRemaining(field -> require(safeFields.contains(field), "persistent unexpected target metadata"));
        for (String field : List.of("origin", "route_id", "container_id", "image_sha", "java_identity", "container_started_at")) {
            require(target.path(field).isTextual() && !target.path(field).asText().isBlank(), "persistent target lacks " + field);
        }
        require(target.path("origin").asText().matches("https?://[^/@?#]+(?::[0-9]+)?"), "persistent origin contains unsafe metadata");
        require(target.path("restart_count").isIntegralNumber() && target.path("restart_count").intValue() == 0
                && target.path("oom").isBoolean() && !target.path("oom").booleanValue(), "persistent restart/OOM failure");
        for (String health : List.of("backend_healthy", "database_healthy", "caddy_running")) {
            require(target.path(health).isBoolean() && target.path(health).booleanValue(), "persistent unhealthy " + health);
        }
        JsonNode requests = value.path("requests");
        require(requests.isArray() && requests.size() == 4 * (SAMPLES + 1), "persistent request count invalid");
        int index = 0;
        Instant previous = start;
        for (String surface : SURFACES) {
            for (int sample = 0; sample <= SAMPLES; sample++) {
                JsonNode request = requests.get(index++);
                require(surface.equals(request.path("surface").asText()) && PATHS.get(surface).equals(request.path("path").asText()),
                        "persistent surface/path changed");
                require(request.path("preconditioning").isBoolean()
                        && request.path("preconditioning").booleanValue() == (sample == 0), "persistent preconditioning order invalid");
                Instant timestamp = time(request, "utc_start");
                require(!timestamp.isBefore(previous) && !timestamp.isAfter(end), "persistent request timestamp invalid");
                previous = timestamp;
                require(request.path("status").isIntegralNumber() && request.path("status").intValue() == 200
                        && "VALID".equals(request.path("validation").asText()), "persistent HTTP/semantic hard failure");
                double duration = finite(request, "latency_ms");
                require(duration > 0 && duration < 5000, "persistent five-second hard failure");
                require(request.path("response_bytes").isIntegralNumber() && request.path("response_bytes").longValue() > 0,
                        "persistent payload evidence missing");
                TreeSet<String> ids = ids(request);
                require(ids.contains(SENTINEL) && (surface.equals("detail") ? ids.size() == 1 : ids.size() <= (surface.equals("search") ? 100 : 200)),
                        "persistent wrong identity/unbounded result");
                require(request.path("result_count").isIntegralNumber() && request.path("result_count").intValue() == ids.size()
                        && sha256(String.join("\n", ids)).equals(request.path("id_digest").asText()), "persistent result digest/count invalid");
            }
        }
    }

    private static TreeSet<String> ids(JsonNode request) {
        require(request.path("canonical_ids").isArray(), "persistent canonical IDs missing");
        TreeSet<String> ids = new TreeSet<>();
        for (JsonNode id : request.path("canonical_ids")) {
            require(id.isTextual(), "persistent ID invalid");
            String canonical = UUID.fromString(id.asText()).toString();
            require(canonical.equals(id.asText()) && ids.add(canonical), "persistent duplicate/noncanonical ID");
        }
        return ids;
    }

    private static List<String> stableIdentity(JsonNode snapshot, String surface) {
        return requests(snapshot, surface).stream().map(r -> r.path("id_digest").asText()).distinct().sorted().toList();
    }
    private static List<JsonNode> requests(JsonNode snapshot, String surface) {
        List<JsonNode> records = new ArrayList<>();
        for (JsonNode request : snapshot.path("requests")) if (surface.equals(request.path("surface").asText())) records.add(request);
        return records;
    }
    private static List<JsonNode> timed(JsonNode snapshot, String surface) {
        return requests(snapshot, surface).stream().filter(r -> !r.path("preconditioning").asBoolean()).toList();
    }
    private static double median(List<JsonNode> values) {
        List<Double> sorted = values.stream().map(r -> finite(r, "latency_ms")).sorted().toList();
        return (sorted.get(9) + sorted.get(10)) / 2;
    }
    private static double p90(List<JsonNode> values) {
        return values.stream().map(r -> finite(r, "latency_ms")).sorted().toList().get(17); // nearest-rank p90; NOT max
    }
    private static double bytesMedian(List<JsonNode> values) {
        List<Long> bytes = values.stream().map(r -> r.path("response_bytes").longValue()).sorted().toList();
        return (bytes.get(9) + bytes.get(10)) / 2.0;
    }
    private static double finite(JsonNode node, String field) {
        require(node.path(field).isNumber() && Double.isFinite(node.path(field).doubleValue()), "persistent invalid " + field);
        return node.path(field).doubleValue();
    }
    private static Instant time(JsonNode node, String field) {
        try { return Instant.parse(node.path(field).asText()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("persistent invalid timestamp " + field); }
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

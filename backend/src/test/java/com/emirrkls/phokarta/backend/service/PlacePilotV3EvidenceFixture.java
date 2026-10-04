package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

/** Entirely synthetic evidence. Never sent to beta and never a sealed operational manifest. */
public final class PlacePilotV3EvidenceFixture {
    private PlacePilotV3EvidenceFixture() {}
    public static ObjectNode snapshot(UUID run, String hash, String role, Instant start, double latency) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("version", "persistent-pilot-v3-v1").put("run_id", run.toString())
                .put("manifest_hash", hash).put("role", role)
                .put("started_at", start.toString()).put("completed_at", start.plusSeconds(1).toString());
        value.putObject("target").put("kind", "LONG_LIVED_PERSISTENT")
                .put("detail_observability_enabled", true).put("detail_slow_threshold_ms", 350)
                .put("origin", "http://persistent-backend:8080")
                .put("route_id", "PRIVATE_DOCKER_HEALTH_ROUTE").put("container_id", "same-container")
                .put("image_sha", "same-image").put("java_identity", "same-pid-and-start-ticks")
                .put("container_started_at", "2026-09-29T15:14:33Z")
                .put("restart_count", 0).put("oom", false)
                .put("backend_healthy", true).put("database_healthy", true).put("caddy_running", true);
        var records = value.putArray("requests");
        for (String surface : PlacePilotV3Policy.SURFACES) {
            for (int sample = 0; sample <= PlacePilotV3Policy.SAMPLES; sample++) {
                ObjectNode request = records.addObject();
                request.put("surface", surface).put("path", PlacePilotV3Policy.PATHS.get(surface))
                        .put("preconditioning", sample == 0).put("utc_start", start.toString())
                        .put("latency_ms", latency).put("status", 200).put("validation", "VALID")
                        .put("response_bytes", 600).put("result_count", 1)
                        .put("id_digest", PlacePilotV3Policy.sha256(PlacePilotV3Policy.SENTINEL));
                request.putArray("canonical_ids").add(PlacePilotV3Policy.SENTINEL);
            }
        }
        return value;
    }
}

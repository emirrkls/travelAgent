package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotV3Policy;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static com.emirrkls.phokarta.backend.operations.PlacePilotPrivateArtifacts.*;

/** Independently verifies the producer bytes AND separate read-only coordinator attestations. */
public final class PlacePilotPersistentArtifacts {
    private PlacePilotPersistentArtifacts() {}
    public static JsonNode read(Path directory, String role, UUID run, String hash, JsonNode approvedTarget) throws IOException {
        require(Set.of("PRE", "POST").contains(role));
        JsonNode receipt = PlacePilotPrivateArtifacts.read(directory.resolve(role + "_RECEIPT.json"), null).document();
        fields(receipt, Set.of("version", "run_id", "manifest_hash", "role", "artifact_sha256", "artifact_bytes",
                "before", "after"));
        require("persistent-telemetry-receipt-v1".equals(receipt.path("version").asText()));
        require(run.toString().equals(receipt.path("run_id").asText()) && hash.equals(receipt.path("manifest_hash").asText())
                && role.equals(receipt.path("role").asText()));
        var artifact = PlacePilotPrivateArtifacts.read(directory.resolve(role + ".json"), receipt.path("artifact_sha256").asText());
        require(receipt.path("artifact_bytes").isIntegralNumber() && artifact.bytes() == receipt.path("artifact_bytes").asLong());
        JsonNode document = artifact.document();
        fields(document, Set.of("version", "run_id", "manifest_hash", "role", "started_at", "completed_at", "target",
                "requests", "health_checks", "process_start_time_seconds", "outcome"));
        require("COMPLETE".equals(document.path("outcome").asText()));
        require(validTarget(document.path("target")) && approvedTarget.equals(document.path("target")));
        PlacePilotV3Policy.verifySnapshot(run, hash, role, document);
        Instant start = Instant.parse(document.path("started_at").asText()), end = Instant.parse(document.path("completed_at").asText());
        attestation(receipt.path("before"), approvedTarget, start.minusSeconds(30), start);
        attestation(receipt.path("after"), approvedTarget, end, end.plusSeconds(30));
        for (JsonNode record : document.path("requests")) requestFields(record, false);
        JsonNode checks = document.path("health_checks");
        require(checks.isArray() && checks.size() == 6);
        for (int i = 0; i < 6; i++) {
            JsonNode check = checks.get(i); boolean process = i % 3 == 2;
            requestFields(check, process);
            String path = List.of("/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus").get(i % 3);
            require(path.equals(check.path("path").asText()) && (process ? "process" : "health").equals(check.path("surface").asText())
                    && !check.path("preconditioning").asBoolean());
            require(check.path("status").asInt() == 200 && "VALID".equals(check.path("validation").asText())
                    && check.path("response_bytes").asLong() > 0);
            double latency = check.path("latency_ms").asDouble(Double.NaN);
            require(Double.isFinite(latency) && latency > 0 && latency < 5000);
            Instant at = Instant.parse(check.path("utc_start").asText());
            require(!at.isBefore(start) && !at.isAfter(end));
            if (process) require(check.path("process_start_time_seconds").isNumber()
                    && check.path("process_start_time_seconds").equals(document.path("process_start_time_seconds")));
        }
        double processStart = document.path("process_start_time_seconds").asDouble(Double.NaN);
        require(Double.isFinite(processStart) && processStart >= Instant.parse(approvedTarget.path("container_started_at").asText()).getEpochSecond()
                && processStart <= start.getEpochSecond());
        return document;
    }

    private static void requestFields(JsonNode record, boolean process) throws IOException {
        Set<String> allowed = new HashSet<>(Set.of("surface", "path", "preconditioning", "utc_start", "validation", "status",
                "headers_ms", "body_ms", "response_bytes", "result_count", "canonical_ids", "id_digest", "latency_ms"));
        if (process) allowed.add("process_start_time_seconds");
        fields(record, allowed);
        double latency = record.path("latency_ms").asDouble(Double.NaN);
        double headers = record.path("headers_ms").asDouble(Double.NaN), body = record.path("body_ms").asDouble(Double.NaN);
        require(record.path("headers_ms").isNumber() && record.path("body_ms").isNumber()
                && Double.isFinite(headers) && Double.isFinite(body) && headers >= 0 && headers <= body && body <= latency);
        require(record.path("response_bytes").isIntegralNumber() && record.path("response_bytes").asLong() <= 4 * 1024 * 1024);
    }

    public static void attestation(JsonNode value, JsonNode target, Instant earliest, Instant latest) throws IOException {
        fields(value, Set.of("observed_at", "target"));
        Instant observed = Instant.parse(value.path("observed_at").asText());
        require(!observed.isBefore(earliest) && !observed.isAfter(latest) && value.path("target").equals(target));
    }

    public static boolean validTarget(JsonNode value) throws IOException {
        fields(value, Set.of("kind", "origin", "route_id", "container_id", "image_sha", "java_identity", "container_started_at",
                "restart_count", "oom", "backend_healthy", "database_healthy", "caddy_running",
                "detail_observability_enabled", "detail_slow_threshold_ms"));
        require("LONG_LIVED_PERSISTENT".equals(value.path("kind").asText())
                && PlacePilotPersistentProbe.ROUTE.equals(value.path("route_id").asText())
                && PlacePilotPersistentProbe.ORIGIN.equals(value.path("origin").asText())
                && value.path("container_id").asText().matches("[0-9a-f]{64}")
                && value.path("image_sha").asText().matches("sha256:[0-9a-f]{64}")
                && value.path("java_identity").asText().matches("[1-9][0-9]*:[1-9][0-9]*"));
        require(!Instant.parse(value.path("container_started_at").asText()).isAfter(Instant.now())
                && value.path("restart_count").isIntegralNumber() && value.path("restart_count").asInt() == 0
                && value.path("oom").isBoolean() && !value.path("oom").asBoolean()
                && value.path("detail_slow_threshold_ms").isIntegralNumber() && value.path("detail_slow_threshold_ms").asInt() == 350);
        for (String health : List.of("backend_healthy", "database_healthy", "caddy_running", "detail_observability_enabled"))
            require(value.path(health).isBoolean() && value.path(health).asBoolean());
        return true;
    }
}

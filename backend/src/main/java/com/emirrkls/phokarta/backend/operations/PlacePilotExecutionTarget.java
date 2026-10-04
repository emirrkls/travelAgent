package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotV3Policy;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import static com.emirrkls.phokarta.backend.operations.PlacePilotPrivateArtifacts.*;

/** Immutable pre-deploy policy, separate from a single independently reopened actual-process attestation. */
public final class PlacePilotExecutionTarget {
    private PlacePilotExecutionTarget() {}
    public static void policy(JsonNode p) throws IOException {
        fields(p, Set.of("source_sha", "image_sha", "image_ref", "origin", "management_origin", "route_id",
                "sentinel", "samples", "preconditioning", "deadline_ms", "detail_slow_threshold_ms", "roles"));
        require(p.path("source_sha").asText().matches("[0-9a-f]{40}")
                && p.path("image_sha").asText().matches("sha256:[0-9a-f]{64}")
                && ("phokarta-backend:" + p.path("source_sha").asText()).equals(p.path("image_ref").asText())
                && PlacePilotPersistentProbe.ORIGIN.equals(p.path("origin").asText())
                && PlacePilotPersistentProbe.HEALTH_ORIGIN.equals(p.path("management_origin").asText())
                && PlacePilotPersistentProbe.ROUTE.equals(p.path("route_id").asText())
                && PlacePilotV3Policy.SENTINEL.equals(p.path("sentinel").asText()));
        for (var e : java.util.Map.of("samples",20,"preconditioning",1,"deadline_ms",5000,"detail_slow_threshold_ms",350).entrySet())
            require(p.path(e.getKey()).isIntegralNumber() && p.path(e.getKey()).asInt() == e.getValue());
        require(p.path("roles").isArray() && p.path("roles").size() == 2
                && "PRE".equals(p.path("roles").get(0).asText()) && "POST".equals(p.path("roles").get(1).asText()));
    }
    public static JsonNode read(Path directory, JsonNode plan, String planSha) throws IOException {
        policy(plan.path("target_policy"));
        JsonNode receipt = PlacePilotPrivateArtifacts.read(directory.resolve("EXECUTION_TARGET_RECEIPT.json"), null).document();
        fields(receipt, Set.of("run_id","manifest_hash","plan_sha256","artifact_sha256","artifact_bytes"));
        var artifact = PlacePilotPrivateArtifacts.read(directory.resolve("EXECUTION_TARGET.json"), receipt.path("artifact_sha256").asText());
        require(receipt.path("artifact_bytes").isIntegralNumber() && artifact.bytes() == receipt.path("artifact_bytes").asLong());
        JsonNode a = artifact.document();
        fields(a, Set.of("version","run_id","manifest_hash","plan_sha256","observed_at","source_sha","image_ref","target","health_checks"));
        require("v3-execution-target-v1".equals(a.path("version").asText()));
        for (String f : Set.of("run_id","manifest_hash")) require(plan.path(f).equals(a.path(f)) && plan.path(f).equals(receipt.path(f)));
        require(planSha.equals(a.path("plan_sha256").asText()) && planSha.equals(receipt.path("plan_sha256").asText())
                && plan.path("target_policy").path("source_sha").equals(a.path("source_sha"))
                && plan.path("target_policy").path("image_ref").equals(a.path("image_ref"))
                && plan.path("target_policy").path("image_sha").equals(a.path("target").path("image_sha")));
        require(PlacePilotPersistentArtifacts.validTarget(a.path("target")));
        health(a.path("health_checks"));
        Instant at = Instant.parse(a.path("observed_at").asText());
        require(!at.isAfter(Instant.now()) && !at.isBefore(Instant.parse(a.path("target").path("container_started_at").asText())));
        // One-time files have no overwrite/re-attest path. Every later observation must equal this target.
        return a;
    }
    public static void health(JsonNode checks) throws IOException {
        require(checks.isArray() && checks.size()==2);
        var paths=new java.util.HashSet<String>();
        for(var c:checks) {
            fields(c,Set.of("path","status","validation","latency_ms"));
            require(paths.add(c.path("path").asText()) && c.path("status").isIntegralNumber() && c.path("status").asInt()==200
                    && "VALID".equals(c.path("validation").asText()) && c.path("latency_ms").isNumber()
                    && Double.isFinite(c.path("latency_ms").asDouble()) && c.path("latency_ms").asDouble()>0 && c.path("latency_ms").asDouble()<5000);
        }
        require(paths.equals(Set.of("/actuator/health/liveness","/actuator/health/readiness")));
    }
}

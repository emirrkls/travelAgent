package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotAutonomousCanaryService;
import com.emirrkls.phokarta.backend.service.PlacePilotHttpProbeService;
import com.emirrkls.phokarta.backend.service.PlacePilotV3Policy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static com.emirrkls.phokarta.backend.operations.PlacePilotPrivateArtifacts.*;

/** Private file IPC adapter. No HTTP/Docker credentials, datasource, Spring context or mutation services. */
public final class PlacePilotPersistentOperationsAdapter implements PlacePilotAutonomousCanaryService.V3Observations {
    private final Path directory;
    private final String approvedPlanSha256;
    private JsonNode plan;
    private UUID run;
    private String hash;
    private Instant postRequested;
    private final ObjectNode receipts = new ObjectMapper().createObjectNode();

    public PlacePilotPersistentOperationsAdapter(Path directory, String approvedPlanSha256) {
        this.directory = directory; this.approvedPlanSha256 = approvedPlanSha256;
    }

    public void preflight(UUID runId, String manifestHash, List<PlacePilotHttpProbeService.ProbeTarget> targets) throws IOException {
        directory(directory);
        plan = read(directory.resolve("V3_OPERATIONS_PLAN.json"), approvedPlanSha256).document();
        fields(plan, Set.of("version", "validation_method", "run_id", "manifest_hash", "target", "selected_canonical_ids", "product_checks"));
        require("v3-private-operations-plan-v1".equals(plan.path("version").asText())
                && PlacePilotV3Policy.V3.equals(plan.path("validation_method").asText())
                && runId.toString().equals(plan.path("run_id").asText()) && manifestHash.equals(plan.path("manifest_hash").asText())
                && !runId.equals(PlacePilotV3Policy.CONTAINED_RUN));
        require(PlacePilotPersistentArtifacts.validTarget(plan.path("target")));
        List<String> expected = targets.stream().map(t -> t.placeId().toString()).sorted().toList();
        require(expected.size() == 71 && new HashSet<>(expected).size() == 71 && strings(plan.path("selected_canonical_ids")).equals(expected));
        require(strings(plan.path("product_checks")).equals(PlacePilotV3Policy.PRODUCT_CHECKS.stream().sorted().toList()));
        run = runId; hash = manifestHash;
        // No operational readiness is inferred merely from a plan. Both producer and product worker must be ready.
        ready("TELEMETRY_WORKER_READY.json", "persistent-telemetry-worker-v1");
        ready("PRODUCT_WORKER_READY.json", "v3-authorized-product-workflow-v1");
        JsonNode before = PlacePilotPersistentArtifacts.read(directory, "PRE", run, hash, plan.path("target"));
        PlacePilotV3Policy.verifyFreshPre(run, hash, before, Instant.now());
        currentTarget();
    }

    public JsonNode capturePersistent(String role, UUID runId, String manifestHash) throws IOException {
        binding(runId, manifestHash);
        require(Set.of("PRE", "POST").contains(role));
        require(!Files.exists(directory.resolve("WORKER_FAILED.json")));
        if (role.equals("PRE")) {
            ready("TELEMETRY_WORKER_READY.json", "persistent-telemetry-worker-v1");
            ready("PRODUCT_WORKER_READY.json", "v3-authorized-product-workflow-v1");
        }
        if (role.equals("POST")) {
            postRequested = Instant.now(); // Called only AFTER importApproved returned SUCCEEDED.
            ObjectNode request = request("persistent-post-request-v1", postRequested);
            write(directory.resolve("POST_REQUEST.json"), request);
            await("POST_RECEIPT.json");
        }
        JsonNode snapshot = PlacePilotPersistentArtifacts.read(directory, role, run, hash, plan.path("target"));
        currentTarget();
        if (role.equals("PRE")) PlacePilotV3Policy.verifyFreshPre(run, hash, snapshot, Instant.now());
        else require(!Instant.parse(snapshot.path("started_at").asText()).isBefore(postRequested));
        var artifact = read(directory.resolve(role + ".json"), null);
        receipts.putObject(role).put("sha256", artifact.sha256()).put("bytes", artifact.bytes());
        return snapshot;
    }

    public ObjectNode productAcceptance(UUID runId, String manifestHash,
                                        List<PlacePilotHttpProbeService.ProbeTarget> targets) throws IOException {
        binding(runId, manifestHash);
        require(postRequested != null);
        Instant requested = Instant.now();
        write(directory.resolve("PRODUCT_REQUEST.json"), request("v3-product-request-v1", requested));
        await("PRODUCT_RECEIPT.json");
        JsonNode receipt = read(directory.resolve("PRODUCT_RECEIPT.json"), null).document();
        fields(receipt, Set.of("run_id", "manifest_hash", "artifact_sha256", "artifact_bytes"));
        require(run.toString().equals(receipt.path("run_id").asText()) && hash.equals(receipt.path("manifest_hash").asText()));
        var artifact = read(directory.resolve("PRODUCT.json"), receipt.path("artifact_sha256").asText());
        require(receipt.path("artifact_bytes").isIntegralNumber() && artifact.bytes() == receipt.path("artifact_bytes").asLong());
        JsonNode product = artifact.document();
        fields(product, Set.of("version", "run_id", "manifest_hash", "started_at", "completed_at", "selected_canonical_ids", "checks"));
        require("v3-product-evidence-v1".equals(product.path("version").asText())
                && run.toString().equals(product.path("run_id").asText()) && hash.equals(product.path("manifest_hash").asText())
                && strings(product.path("selected_canonical_ids")).equals(strings(plan.path("selected_canonical_ids"))));
        Instant start = Instant.parse(product.path("started_at").asText()), end = Instant.parse(product.path("completed_at").asText());
        require(!start.isBefore(requested) && !end.isBefore(start) && !end.isAfter(Instant.now()));
        fields(product.path("checks"), new HashSet<>(PlacePilotV3Policy.PRODUCT_CHECKS));
        ObjectNode result = new ObjectMapper().createObjectNode();
        for (String name : PlacePilotV3Policy.PRODUCT_CHECKS) {
            JsonNode check = product.path("checks").path(name);
            fields(check, Set.of("status", "observed_at", "canonical_place_ids", "evidence"));
            require("PASS".equals(check.path("status").asText()));
            Instant at = Instant.parse(check.path("observed_at").asText());
            require(!at.isBefore(start) && !at.isAfter(end));
            List<String> ids = strings(check.path("canonical_place_ids"));
            require(!ids.isEmpty() && strings(plan.path("selected_canonical_ids")).containsAll(ids));
            JsonNode evidence = check.path("evidence");
            require(evidence.isArray() && !evidence.isEmpty() && evidence.size() <= 1000);
            Set<String> operations = new HashSet<>();
            for (JsonNode observation : evidence) {
                fields(observation, Set.of("operation", "status", "validation", "latency_ms", "request_id"));
                String op = observation.path("operation").asText();
                operations.add(op);
                require(Set.of("GET", "POST", "PUT", "DELETE", "READ_ONLY_GRAPH_INSPECTION").contains(op)
                        && observation.path("status").isIntegralNumber() && observation.path("status").asInt() >= 200
                        && observation.path("status").asInt() < 300 && "VALID".equals(observation.path("validation").asText())
                        && observation.path("latency_ms").isNumber() && Double.isFinite(observation.path("latency_ms").doubleValue())
                        && observation.path("latency_ms").doubleValue() > 0 && observation.path("latency_ms").doubleValue() < 5000
                        && observation.path("request_id").asText().matches("[A-Za-z0-9_-]{1,100}"));
            }
            require(operations.containsAll(requiredProductOperations(name)));
            // Individual observations are retained in the private artifact; gate stores its verified digest.
            result.put(name, "PASS");
        }
        currentTarget();
        receipts.putObject("PRODUCT").put("sha256", artifact.sha256()).put("bytes", artifact.bytes());
        result.set("verified_artifacts", receipts.deepCopy());
        return result;
    }

    static Set<String> requiredProductOperations(String check) {
        return switch (check) {
            case "want_to_go", "collections", "synthetic_v2_experience" -> Set.of("POST", "GET");
            case "rollback_safety", "graph_safety" -> Set.of("READ_ONLY_GRAPH_INSPECTION");
            default -> Set.of("GET");
        };
    }

    private void ready(String filename, String version) throws IOException {
        JsonNode ready = read(directory.resolve(filename), null).document();
        fields(ready, Set.of("version", "run_id", "manifest_hash", "observed_at"));
        require(version.equals(ready.path("version").asText()) && run.toString().equals(ready.path("run_id").asText())
                && hash.equals(ready.path("manifest_hash").asText()));
        fresh(Instant.parse(ready.path("observed_at").asText()));
    }
    private void currentTarget() throws IOException {
        JsonNode observation = read(directory.resolve("CURRENT_TARGET.json"), null).document();
        PlacePilotPersistentArtifacts.attestation(observation, plan.path("target"), Instant.now().minusSeconds(30), Instant.now());
    }
    private void fresh(Instant at) throws IOException { require(!at.isAfter(Instant.now()) && !at.isBefore(Instant.now().minusSeconds(30))); }
    private ObjectNode request(String version, Instant at) {
        return new ObjectMapper().createObjectNode().put("version", version).put("run_id", run.toString())
                .put("manifest_hash", hash).put("requested_at", at.toString());
    }
    private void binding(UUID runId, String manifestHash) throws IOException { require(plan != null && runId.equals(run) && manifestHash.equals(hash)); }
    private void await(String filename) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(9);
        // Filesystem IPC wait, not retries/probes/rebaseline. Bounded; abort is surfaced to run-aware containment.
        while (!Files.exists(directory.resolve(filename))) {
            require(!Files.exists(directory.resolve("WORKER_FAILED.json")) && System.nanoTime() < deadline);
            try { Thread.sleep(100); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("PRIVATE_WORKER_INTERRUPTED"); }
        }
    }
    private static List<String> strings(JsonNode value) throws IOException {
        require(value.isArray()); List<String> list = new ArrayList<>();
        for (JsonNode node : value) { require(node.isTextual()); list.add(node.asText()); }
        require(new HashSet<>(list).size() == list.size()); return list.stream().sorted().toList();
    }
}

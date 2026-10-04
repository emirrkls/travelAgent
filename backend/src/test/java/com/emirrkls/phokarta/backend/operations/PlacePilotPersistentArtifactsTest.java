package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotHttpProbeService;
import com.emirrkls.phokarta.backend.service.PlacePilotV3Policy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.emirrkls.phokarta.backend.operations.PlacePilotPersistentProbeTest.*;

class PlacePilotPersistentArtifactsTest {
    @TempDir Path directory;
    ObjectNode target;
    ObjectNode snapshot;
    ObjectNode receipt;
    String planHash;
    List<PlacePilotHttpProbeService.ProbeTarget> targets;

    void prepare() throws Exception {
        target = target();
        try (Server server = new Server()) { snapshot = server.probe().capture(RUN, HASH, "PRE", target); }
        persist("PRE", snapshot);
        targets = new ArrayList<>();
        for (int i = 0; i < 71; i++) targets.add(new PlacePilotHttpProbeService.ProbeTarget(new UUID(5, i), "Fixture", "CAFE", 37.38, 27.26));
        ObjectNode plan = MAPPER.createObjectNode().put("version", "v3-private-operations-plan-v1")
                .put("validation_method", PlacePilotV3Policy.V3).put("run_id", RUN.toString()).put("manifest_hash", HASH);
        plan.set("target", target);
        var ids = plan.putArray("selected_canonical_ids"); targets.forEach(t -> ids.add(t.placeId().toString()));
        var checks = plan.putArray("product_checks"); PlacePilotV3Policy.PRODUCT_CHECKS.forEach(checks::add);
        planHash = PlacePilotPrivateArtifacts.write(directory.resolve("V3_OPERATIONS_PLAN.json"), plan).sha256();
        fresh("CURRENT_TARGET.json", MAPPER.createObjectNode().put("observed_at", Instant.now().toString()).set("target", target));
        for (String worker : List.of("TELEMETRY", "PRODUCT")) {
            fresh(worker + "_WORKER_READY.json", MAPPER.createObjectNode()
                    .put("version", worker.equals("TELEMETRY") ? "persistent-telemetry-worker-v1" : "v3-authorized-product-workflow-v1")
                    .put("run_id", RUN.toString()).put("manifest_hash", HASH).put("observed_at", Instant.now().toString()));
        }
    }
    void fresh(String filename, JsonNode value) throws Exception {
        Path path = directory.resolve(filename);
        if (Files.exists(path)) Files.delete(path); // Local @TempDir fixture only.
        PlacePilotPrivateArtifacts.write(path, value);
    }
    void persist(String role, ObjectNode document) throws Exception {
        var artifact = PlacePilotPrivateArtifacts.write(directory.resolve(role + ".json"), document);
        receipt = MAPPER.createObjectNode().put("version", "persistent-telemetry-receipt-v1").put("run_id", RUN.toString())
                .put("manifest_hash", HASH).put("role", role).put("artifact_sha256", artifact.sha256()).put("artifact_bytes", artifact.bytes());
        receipt.putObject("before").put("observed_at", Instant.parse(document.path("started_at").asText()).minusMillis(1).toString()).set("target", target);
        receipt.putObject("after").put("observed_at", Instant.parse(document.path("completed_at").asText()).plusMillis(1).toString()).set("target", target);
        PlacePilotPrivateArtifacts.write(directory.resolve(role + "_RECEIPT.json"), receipt);
    }
    PlacePilotPersistentOperationsAdapter adapter() { return new PlacePilotPersistentOperationsAdapter(directory, planHash); }

    @Test void finalizedBytesIndependentlyReopenedAndBoundBeforeAnyMutation() throws Exception {
        prepare();
        assertThat(PlacePilotPersistentArtifacts.read(directory, "PRE", RUN, HASH, target)).isEqualTo(snapshot);
        var adapter = adapter(); adapter.preflight(RUN, HASH, targets);
        assertThat(adapter.capturePersistent("PRE", RUN, HASH)).isEqualTo(snapshot);
        assertThat(Files.exists(directory.resolve("POST_REQUEST.json"))).isFalse();
        assertThat(Files.exists(directory.resolve("PRODUCT_REQUEST.json"))).isFalse();
    }
    @ParameterizedTest @ValueSource(strings = {"PRE.json", "PRE_RECEIPT.json", "V3_OPERATIONS_PLAN.json", "TELEMETRY_WORKER_READY.json", "PRODUCT_WORKER_READY.json", "CURRENT_TARGET.json"})
    void everyMissingMandatoryEvidenceBlocksPreflight(String missing) throws Exception {
        prepare(); Files.delete(directory.resolve(missing));
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
        assertThat(Files.exists(directory.resolve("POST_REQUEST.json"))).isFalse();
    }
    @Test void tamperedActualSnapshotBytesCannotBeAccepted() throws Exception {
        prepare(); Files.writeString(directory.resolve("PRE.json"), "{}\n");
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
    }
    @Test void truncatedReceiptAndWrongDigestFailClosed() throws Exception {
        prepare(); receipt.put("artifact_sha256", "0".repeat(64)); fresh("PRE_RECEIPT.json", receipt);
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
        Files.writeString(directory.resolve("PRE_RECEIPT.json"), "{\"role\":");
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
    }
    @ParameterizedTest @ValueSource(strings = {"container_id", "image_sha", "java_identity", "container_started_at", "origin", "route_id", "backend_healthy", "database_healthy", "oom", "restart_count"})
    void actualTargetAttestationChangesAreHardNotResultIncomparable(String field) throws Exception {
        prepare(); var observation = MAPPER.createObjectNode().put("observed_at", Instant.now().toString());
        var changed = target.deepCopy(); changed.put(field, "CHANGED"); observation.set("target", changed);
        fresh("CURRENT_TARGET.json", observation);
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
    }
    @Test void staleIdentityAttestationOrChangedPlanHashBlocksBeforeWrites() throws Exception {
        prepare(); fresh("CURRENT_TARGET.json", MAPPER.createObjectNode().put("observed_at", Instant.now().minusSeconds(31).toString()).set("target", target));
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new PlacePilotPersistentOperationsAdapter(directory, "0".repeat(64)).preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
    }
    @Test void predecessorUuidAndWrongRunHashAreNeverAccepted() throws Exception {
        prepare();
        assertThatThrownBy(() -> adapter().preflight(PlacePilotV3Policy.CONTAINED_RUN, HASH, targets)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> adapter().preflight(RUN, "0".repeat(64), targets)).isInstanceOf(Exception.class);
    }
    @Test void selectionMismatchAndDuplicateSelectedUuidFail() throws Exception {
        prepare();
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets.subList(0, 70))).isInstanceOf(Exception.class);
        var duplicate = new ArrayList<>(targets); duplicate.set(70, targets.getFirst());
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, duplicate)).isInstanceOf(Exception.class);
    }
    @Test void arbitraryRawBodyOrSensitiveMetadataCannotBeReadBack() throws Exception {
        prepare(); snapshot.put("raw_body", "FORBIDDEN");
        fresh("PRE.json", snapshot); receipt.put("artifact_sha256", PlacePilotPrivateArtifacts.read(directory.resolve("PRE.json"), null).sha256())
                .put("artifact_bytes", Files.size(directory.resolve("PRE.json"))); fresh("PRE_RECEIPT.json", receipt);
        assertThatThrownBy(() -> adapter().preflight(RUN, HASH, targets)).isInstanceOf(Exception.class);
    }
    @Test void duplicateJsonFieldsOrTrailingDocumentsRejectedOnIndependentRead() throws Exception {
        Path file = directory.resolve("bad.json"); Files.writeString(file, "{\"role\":\"PRE\",\"role\":\"POST\"}");
        assertThatThrownBy(() -> PlacePilotPrivateArtifacts.read(file, null)).isInstanceOf(Exception.class);
        Files.writeString(file, "{} {}");
        assertThatThrownBy(() -> PlacePilotPrivateArtifacts.read(file, null)).isInstanceOf(Exception.class);
    }
    @Test void atomicFinalizationNeverOverwritesPriorObservation() throws Exception {
        Path file = directory.resolve("immutable.json"); var value = MAPPER.createObjectNode().put("safe", 1);
        var read = PlacePilotPrivateArtifacts.write(file, value);
        assertThat(read.sha256()).isEqualTo(PlacePilotPrivateArtifacts.hash(Files.readAllBytes(file)));
        assertThatThrownBy(() -> PlacePilotPrivateArtifacts.write(file, MAPPER.createObjectNode().put("safe", 2))).isInstanceOf(Exception.class);
        assertThat(PlacePilotPrivateArtifacts.read(file, read.sha256()).document()).isEqualTo(value);
    }
    @Test void failedWorkerAbortsPostWaitWithoutRetryOrReplacement() throws Exception {
        prepare(); var adapter = adapter(); adapter.preflight(RUN, HASH, targets);
        PlacePilotPrivateArtifacts.write(directory.resolve("WORKER_FAILED.json"), MAPPER.createObjectNode().put("reason", "HARD_FAILURE"));
        assertThatThrownBy(() -> adapter.capturePersistent("POST", RUN, HASH)).isInstanceOf(Exception.class);
        assertThat(Files.exists(directory.resolve("POST_REQUEST.json"))).isFalse(); // known-dead worker stops even before IPC
        assertThat(Files.exists(directory.resolve("POST.json"))).isFalse();
    }
    @Test void symlinkFinalArtifactOrPublicDirectoryRejectedOnLinux() throws Exception {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
        Path regular = directory.resolve("regular.json"); Files.writeString(regular, "{}");
        Path link = directory.resolve("link.json"); Files.createSymbolicLink(link, regular);
        assertThatThrownBy(() -> PlacePilotPrivateArtifacts.read(link, null)).isInstanceOf(Exception.class);
        Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThatThrownBy(() -> PlacePilotPrivateArtifacts.read(regular, null)).isInstanceOf(Exception.class);
    }
    @Test void preAlreadyValidatedCannotOutliveProducerOrProductWorkerReadiness() throws Exception {
        prepare(); var adapter = adapter(); adapter.preflight(RUN, HASH, targets);
        fresh("PRODUCT_WORKER_READY.json", MAPPER.createObjectNode().put("version", "v3-authorized-product-workflow-v1")
                .put("run_id", RUN.toString()).put("manifest_hash", HASH).put("observed_at", Instant.now().minusSeconds(31).toString()));
        assertThatThrownBy(() -> adapter.capturePersistent("PRE", RUN, HASH)).isInstanceOf(Exception.class);
        assertThat(Files.exists(directory.resolve("POST_REQUEST.json"))).isFalse();
    }
    @Test void postCannotBeFabricatedBeforeSuccessfulImportSignal() throws Exception {
        prepare(); var adapter = adapter(); adapter.preflight(RUN, HASH, targets);
        var post = snapshot.deepCopy(); post.put("role", "POST"); persist("POST", post);
        assertThatThrownBy(() -> adapter.capturePersistent("POST", RUN, HASH)).isInstanceOf(Exception.class);
    }
    @Test void concreteAdapterPrePostAndProductArtifactsAreReadBackWithoutAutomaticLatencyPass() throws Exception {
        prepare(); var adapter = adapter(); adapter.preflight(RUN, HASH, targets);
        var pre = adapter.capturePersistent("PRE", RUN, HASH);
        java.util.concurrent.CompletableFuture<Void> producer = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                awaitLocal("POST_REQUEST.json");
                ObjectNode post;
                try (Server server = new Server()) { post = server.probe().capture(RUN, HASH, "POST", target); }
                persist("POST", post);
                fresh("CURRENT_TARGET.json", MAPPER.createObjectNode().put("observed_at", Instant.now().toString()).set("target", target));
                awaitLocal("PRODUCT_REQUEST.json");
                Instant at = Instant.now();
                var document = MAPPER.createObjectNode().put("version", "v3-product-evidence-v1").put("run_id", RUN.toString())
                        .put("manifest_hash", HASH).put("started_at", at.toString()).put("completed_at", at.toString());
                var ids = document.putArray("selected_canonical_ids"); targets.forEach(t -> ids.add(t.placeId().toString()));
                var checks = document.putObject("checks");
                for (String name : PlacePilotV3Policy.PRODUCT_CHECKS) {
                    var check = checks.putObject(name).put("status", "PASS").put("observed_at", at.toString());
                    check.putArray("canonical_place_ids").add(targets.getFirst().placeId().toString());
                    var observations = check.putArray("evidence");
                    for (String operation : PlacePilotPersistentOperationsAdapter.requiredProductOperations(name))
                        observations.addObject().put("operation", operation).put("status", 200)
                                .put("validation", "VALID").put("latency_ms", 10).put("request_id", "local-fixture-request");
                }
                var artifact = PlacePilotPrivateArtifacts.write(directory.resolve("PRODUCT.json"), document);
                PlacePilotPrivateArtifacts.write(directory.resolve("PRODUCT_RECEIPT.json"), MAPPER.createObjectNode()
                        .put("run_id", RUN.toString()).put("manifest_hash", HASH).put("artifact_sha256", artifact.sha256()).put("artifact_bytes", artifact.bytes()));
            } catch (Exception failed) { throw new java.util.concurrent.CompletionException(failed); }
        });
        var post = adapter.capturePersistent("POST", RUN, HASH);
        var product = adapter.productAcceptance(RUN, HASH, targets);
        producer.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(PlacePilotV3Policy.verify(RUN, HASH, pre, post).summary().has("passed")).isFalse();
        assertThat(product.path("verified_artifacts").size()).isEqualTo(3);
        for (String check : PlacePilotV3Policy.PRODUCT_CHECKS) assertThat(product.path(check).asText()).isEqualTo("PASS");
    }
    private void awaitLocal(String filename) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(directory.resolve(filename))) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("local IPC test timeout");
            Thread.sleep(1);
        }
    }
}

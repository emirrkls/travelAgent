package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlacePilotImportMemoryRegressionTest {
    static PlacePilotImportService importer() {
        PlatformTransactionManager noTransactions = (PlatformTransactionManager) Proxy.newProxyInstance(
                PlacePilotImportMemoryRegressionTest.class.getClassLoader(),
                new Class<?>[]{PlatformTransactionManager.class},
                (proxy, method, args) -> { throw new AssertionError("hashing must not start a transaction"); });
        return new PlacePilotImportService(new JdbcTemplate(), new ObjectMapper(), noTransactions, null);
    }

    /** Sharing payload is allowed; cloning entire trees or mutating the original is not. */
    static final class NoDeepCopy extends ObjectNode {
        NoDeepCopy() { super(JsonNodeFactory.instance); }
        @Override public ObjectNode deepCopy() { throw new AssertionError("whole payload deep copy"); }
    }

    @Test void planAndPredecessorProjectionDoNotDeepCopyOrMutatePayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode m = new NoDeepCopy();
        m.put("run_id", "ignored").put("method_version", PlacePilotV3Policy.V3)
                .put("predecessor_manifest_hash", "old-hash")
                .put("canonical_identity_method_version", PlacePilotV3Policy.V2)
                .put("performance_policy", "ADVISORY_ONLY");
        ObjectNode source = new NoDeepCopy();
        source.put("raw", "unchanged complete source payload İ😀");
        m.putArray("source_records").add(source);
        ObjectNode candidate = new NoDeepCopy();
        candidate.put("selected_for_stage", true).put("selection_rank", 1)
                .put("evidence", "complete candidate payload");
        m.putArray("candidates").add(candidate);
        String before = m.toString();
        var service = importer();
        String plan = service.hashPlan(m);
        ObjectNode expected = (ObjectNode) mapper.readTree(before);
        expected.remove("run_id");
        ((ObjectNode) expected.path("candidates").get(0)).remove("selected_for_stage");
        assertThat(plan).isEqualTo(service.hashManifest(expected));
        expected.put("method_version", PlacePilotV3Policy.V2);
        expected.remove(List.of("predecessor_manifest_hash", "canonical_identity_method_version", "performance_policy"));
        assertThat(service.hashPredecessorPlan(m)).isEqualTo(service.hashManifest(expected));
        assertThat(m.toString()).isEqualTo(before);
        assertThat(m.path("source_records").get(0)).isSameAs(source);
        assertThat(m.path("candidates").get(0)).isSameAs(candidate);
    }

    @Test void completePredecessorHashStillIncludesUnselectedEvidenceAndSources() {
        ObjectNode m = JsonNodeFactory.instance.objectNode().put("method_version", PlacePilotV3Policy.V3);
        ObjectNode q = m.putArray("candidates").addObject().put("selected_for_stage", false)
                .put("decision", "QUARANTINE").put("evidence", "first");
        ObjectNode source = m.putArray("source_records").addObject().put("raw", "original");
        var service = importer();
        String original = service.hashPredecessorPlan(m);
        q.put("evidence", "changed unselected payload");
        assertThat(service.hashPredecessorPlan(m)).isNotEqualTo(original);
        q.put("evidence", "first");
        source.put("raw", "changed source payload");
        assertThat(service.hashPredecessorPlan(m)).isNotEqualTo(original);
    }

    @Test void streamingCanonicalHashStillRejectsInvalidUnicodeAndNonfiniteNumbers() {
        ObjectNode m = JsonNodeFactory.instance.objectNode().put("value", "x" + '\ud800');
        assertThatThrownBy(() -> importer().hashManifest(m)).isInstanceOf(IllegalArgumentException.class);
        m.removeAll().put("value", Double.NaN);
        assertThatThrownBy(() -> importer().hashManifest(m)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void canonicalHashDoesNotMaterializeFullCanonicalStringOrUtf8Array() throws Exception {
        // Operational CHILD heap is bounded explicitly, not Maven's heap. No DB/container needed.
        // Logical canonical payload is ~112 MiB (<128 MiB input limit), with shared test text.
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx96m", "-XX:+ExitOnOutOfMemoryError", "-cp", System.getProperty("java.class.path"),
                HashChild.class.getName()).redirectErrorStream(true).start();
        try {
            assertThat(child.waitFor(90, TimeUnit.SECONDS)).as("bounded digest child completes").isTrue();
            String result = new String(child.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
            assertThat(child.exitValue()).as(result).isZero();
            assertThat(result).contains("BOUNDED_CANONICAL_HASH_PASS");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    public static final class HashChild {
        public static void main(String[] args) throws Exception {
            ObjectNode m = JsonNodeFactory.instance.objectNode();
            String text = "İ😀x".repeat(262144);
            var values = m.putArray("values");
            for (int i = 0; i < 64; i++) values.add(text);
            MessageDigest expected = MessageDigest.getInstance("SHA-256");
            try (Writer out = new BufferedWriter(new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), expected), StandardCharsets.UTF_8))) {
                out.write("{\"values\":[");
                for (int i = 0; i < 64; i++) {
                    if (i > 0) out.write(',');
                    out.write('"'); out.write(text); out.write('"');
                }
                out.write("]}");
            }
            String hash = importer().hashManifest(m);
            if (!hash.equals(HexFormat.of().formatHex(expected.digest()))) throw new AssertionError("byte mismatch");
            System.out.println("BOUNDED_CANONICAL_HASH_PASS");
        }
    }
}

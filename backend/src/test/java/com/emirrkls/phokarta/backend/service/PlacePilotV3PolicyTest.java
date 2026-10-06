package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PlacePilotV3PolicyTest {
    private static final UUID RUN = UUID.fromString("12345678-1234-4234-8234-123456789012");
    private static final String HASH = "1".repeat(64);
    private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");
    private ObjectNode before() { return PlacePilotV3EvidenceFixture.snapshot(RUN, HASH, "PRE", START, 10); }
    private ObjectNode after() { return PlacePilotV3EvidenceFixture.snapshot(RUN, HASH, "POST", START.plusSeconds(10), 20); }
    private ObjectNode first(ObjectNode value) { return (ObjectNode) value.path("requests").get(0); }

    @Test void largeRelativeIncreaseIsOnlyDegradedAdvisory() {
        ObjectNode after = after();
        for (var request : after.path("requests")) ((ObjectNode) request).put("latency_ms", 4999);
        var evidence = PlacePilotV3Policy.verify(RUN, HASH, before(), after);
        assertThat(evidence.summary().path("PERFORMANCE_ADVISORY").asText()).isEqualTo("DEGRADED");
        assertThat(evidence.summary().path("surfaces").path("detail").path("median_relative_delta").asDouble()).isGreaterThan(400);
        assertThat(evidence.summary().has("passed")).isFalse();
    }
    @Test void equalOrFasterIsNormalNotLatencyPass() {
        var after = PlacePilotV3EvidenceFixture.snapshot(RUN, HASH, "POST", START.plusSeconds(10), 5);
        assertThat(PlacePilotV3Policy.verify(RUN, HASH, before(), after).summary().path("PERFORMANCE_ADVISORY").asText()).isEqualTo("NORMAL");
    }
    @Test void changedResultCardinalityIsIncomparableNotFabricatedEquivalence() {
        var after = after();
        for (var node : after.path("requests")) {
            if ("detail".equals(node.path("surface").asText())) continue;
            ObjectNode r = (ObjectNode) node;
            r.withArray("canonical_ids").add("aa000000-0000-4000-8000-000000000002");
            r.put("result_count", 2).put("id_digest", PlacePilotV3Policy.sha256(PlacePilotV3Policy.SENTINEL
                    + "\naa000000-0000-4000-8000-000000000002"));
        }
        assertThat(PlacePilotV3Policy.verify(RUN, HASH, before(), after).summary().path("PERFORMANCE_ADVISORY").asText()).isEqualTo("INCOMPARABLE");
    }
    @Test void preconditioningIsExcludedFromMedianAndNearestRankP90IsNotMax() {
        var before = before(); var after = after();
        for (int surface = 0; surface < 4; surface++) {
            ((ObjectNode) before.path("requests").get(surface * 21)).put("latency_ms", 4500);
            for (int sample = 1; sample <= 20; sample++) {
                ((ObjectNode) before.path("requests").get(surface * 21 + sample)).put("latency_ms", sample);
            }
        }
        var result = PlacePilotV3Policy.verify(RUN, HASH, before, after).summary().path("surfaces").path("search");
        assertThat(result.path("baseline_median_ms").asDouble()).isEqualTo(10.5);
        assertThat(result.path("baseline_p90_ms").asDouble()).isEqualTo(18);
    }
    @ParameterizedTest @ValueSource(doubles = {5000, 5000.598227, 8222, -1, 0, Double.NaN})
    void deadlineOrInvalidLatencyIsHardFailureEvenForPreconditioning(double latency) {
        var after = after(); first(after).put("latency_ms", latency);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @ParameterizedTest @ValueSource(ints = {0, 404, 500, 503})
    void httpFailureIsHard(int status) {
        var after = after(); first(after).put("status", status);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @Test void invalidSemanticsCannotBeAdvisory() {
        var after = after(); first(after).put("validation", "INVALID");
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @Test void wrongCanonicalIdentityCannotBeAdvisory() {
        var after = after(); first(after).putArray("canonical_ids").add(RUN.toString());
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @ParameterizedTest @ValueSource(strings = {"backend_healthy", "database_healthy", "caddy_running"})
    void unhealthyIsHard(String field) {
        var after = after(); after.withObject("target").put(field, false);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @Test void restartAndOomAreHard() {
        var after = after(); after.withObject("target").put("restart_count", 1);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
        after.withObject("target").put("restart_count", 0).put("oom", true);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @ParameterizedTest @ValueSource(strings = {"container_id", "image_sha", "java_identity", "route_id", "origin"})
    void processOrPathChangeIsHard(String field) {
        var after = after(); after.withObject("target").put(field, "http://changed:8080");
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @Test void digestAndCountMustBeComputedFromCanonicalIds() {
        var after = after(); first(after).put("id_digest", "0".repeat(64));
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @Test void missingOrReorderedRecordsCannotHideFailedObservation() {
        var after = after(); ((ArrayNode) after.path("requests")).remove(0);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
        var reordered = after(); first(reordered).put("preconditioning", false);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), reordered));
    }
    @Test void receiptBindsRunHashAndMutationWindow() {
        var receipt = PlacePilotV3Policy.verify(RUN, HASH, before(), after());
        receipt.validateBinding(RUN, HASH, START.plusSeconds(2), START.plusSeconds(9));
        assertThatIllegalArgumentException().isThrownBy(() -> receipt.validateBinding(RUN, HASH, START, START.plusSeconds(9)));
        assertThatIllegalArgumentException().isThrownBy(() -> receipt.validateBinding(RUN, "0".repeat(64), START.plusSeconds(2), START.plusSeconds(9)));
    }
    @Test void returnedSummaryCannotMutateVerifiedReceipt() {
        var receipt = PlacePilotV3Policy.verify(RUN, HASH, before(), after());
        receipt.summary().put("PERFORMANCE_ADVISORY", "NORMAL");
        assertThat(receipt.summary().path("PERFORMANCE_ADVISORY").asText()).isEqualTo("DEGRADED");
    }
    @ParameterizedTest @ValueSource(strings={"run_id","manifest_hash"})
    void wrongRunOrHashCannotBindCoverageToPersistentEvidence(String field) {
        var post=after(); post.put(field,field.equals("run_id")?UUID.randomUUID().toString():"0".repeat(64));
        assertThatIllegalArgumentException().isThrownBy(()->PlacePilotV3Policy.verify(RUN,HASH,before(),post));
    }
    @Test void oneShotTargetOrChangedInstrumentationIsHard() {
        var after = after(); after.withObject("target").put("kind", "ONE_SHOT");
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
        var changed = after(); changed.withObject("target").put("detail_slow_threshold_ms", 500);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), changed));
    }
    @Test void arbitraryTargetMetadataCannotLeakIntoSanitizedReceipt() {
        var after = after(); after.withObject("target").put("unexpected_field", "not-for-report");
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, before(), after));
    }
    @Test void freshnessBindsPreCompletionNotBeginning() {
        var pre = before();
        PlacePilotV3Policy.verifyFreshPre(RUN, HASH, pre, START.plusSeconds(901)); // completion exactly 15 min ago
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verifyFreshPre(RUN, HASH, pre, START.plusSeconds(902)));
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verifyFreshPre(RUN, HASH, pre, START));
        var post = PlacePilotV3EvidenceFixture.snapshot(RUN, HASH, "POST", START.plusSeconds(910), 10);
        var receipt = PlacePilotV3Policy.verify(RUN, HASH, pre, post);
        receipt.validateBinding(RUN, HASH, START.plusSeconds(901), START.plusSeconds(905));
        assertThatIllegalArgumentException().isThrownBy(() -> receipt.validateBinding(RUN, HASH, START.plusSeconds(902), START.plusSeconds(905)));
    }
    @Test void actualPrometheusProcessStartCannotChangeBetweenSnapshots() {
        var pre = before(); var post = after();
        pre.put("process_start_time_seconds", 1000); post.put("process_start_time_seconds", 1001);
        assertThatIllegalArgumentException().isThrownBy(() -> PlacePilotV3Policy.verify(RUN, HASH, pre, post));
    }
}

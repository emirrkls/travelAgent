package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PlacePilotV3GatePolicyTest {
    private final PlacePilotCanaryGateService gate = new PlacePilotCanaryGateService(
            mock(JdbcTemplate.class), mock(PlacePilotRollbackService.class), mock(PlatformTransactionManager.class));
    static Stream<String> products() { return PlacePilotV3Policy.PRODUCT_CHECKS.stream(); }
    private ObjectNode diagnostics() {
        ObjectNode d = JsonNodeFactory.instance.objectNode();
        d.put("probe_mode", "AUTONOMOUS_V3_PERSISTENT_ADVISORY").put("baseline_captured_before_import", true)
                .put("measured_pass", true).put("selected_place_count", 71)
                .put("duplicate_canonical_violations", 0).put("same_coordinate_anomalies", 0);
        for (String check : java.util.List.of("search_correctness", "map_bounded_behavior", "backend_health",
                "same_manifest_idempotency", "duplicate_canonical_diagnostics", "same_coordinate_diagnostics", "place_detail_correctness")) d.put(check, "PASS");
        var ids = d.putArray("selected_place_ids_checked");
        for (int i = 0; i < 71; i++) ids.add(new UUID(1, i).toString());
        var coverage = d.putObject("selected_place_coverage");
        var http = d.putObject("http_probes");
        http.putObject("before").put("passed", true);
        var after = http.putObject("after").put("passed", true).putObject("surfaces");
        for (String surface : java.util.List.of("search", "map_nearby", "map_bounds", "place_detail")) {
            coverage.put(surface, 71);
            after.putObject(surface + "_coverage").put("sample_count", 71).put("error_count", 0).put("passed", true);
            d.withObject("selected_place_http_coverage").set(surface,ids.deepCopy());
        }
        d.putObject("catalog_anomaly_report").put("passed", true);
        var product = d.putObject("product_acceptance");
        PlacePilotV3Policy.PRODUCT_CHECKS.forEach(check -> product.put(check, "PASS"));
        d.putObject("api_error_rate").put("baseline_rate", 0).put("after_rate", 0);
        var perf = d.putObject("performance");
        for (String surface : java.util.List.of("search", "map", "place_detail")) {
            perf.putObject(surface).put("baseline_ms", 10).put("after_ms", 100)
                    .put("relative_change", 9).put("material_regression", true);
        }
        return d;
    }
    @Test void v3RelativePerformanceCannotFailSafetyButV2StillRejectsIt() {
        var d = diagnostics();
        assertThatNoException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(d, true));
        d.put("probe_mode", "AUTONOMOUS_HTTP_AND_DATABASE_V1");
        assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(d, false));
    }
    @ParameterizedTest @MethodSource("products")
    void everyProductAndGraphGateRemainsHard(String check) {
        var d = diagnostics(); d.withObject("product_acceptance").put(check, "FAIL");
        assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(d, true));
    }
    @Test void anomalyIdempotencyCoverageAndHttpRemainHard() {
        for (String check : java.util.List.of("same_manifest_idempotency", "duplicate_canonical_diagnostics", "map_bounded_behavior", "place_detail_correctness")) {
            var d = diagnostics(); d.put(check, "FAIL");
            assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(d, true));
        }
        var anomaly = diagnostics(); anomaly.withObject("catalog_anomaly_report").put("passed", false);
        assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(anomaly, true));
        var http = diagnostics(); http.withObject("api_error_rate").put("after_rate", 0.01);
        assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(http, true));
        var coverage = diagnostics(); coverage.withObject("selected_place_coverage").put("place_detail", 70);
        assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(coverage, true));
    }

    @Test void plannedIdsOrCountsAloneCannotSatisfyTerminalHttpCoverage() {
        var d=diagnostics(); d.remove("selected_place_http_coverage");
        d.set("selected_place_ids",d.path("selected_place_ids_checked").deepCopy());
        assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(d,true));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING","DUPLICATE","WRONG"})
    void terminalCoverageRejectsMissingDuplicateAndSameCountWrongIdentity(String fault) {
        var d=diagnostics();
        var ids=(com.fasterxml.jackson.databind.node.ArrayNode)d.path("selected_place_http_coverage").path("search");
        if(fault.equals("MISSING")) ids.remove(70);
        else ids.set(70,JsonNodeFactory.instance.textNode(fault.equals("DUPLICATE")?ids.get(0).asText():new UUID(2,999).toString()));
        assertThatIllegalArgumentException().isThrownBy(() -> gate.validatePassingExternalDiagnostics(d,true));
    }
}

package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlacePilotAutonomousCanaryServiceTest {
    private static final UUID RUN_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000701");
    private static final UUID BASELINE_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000702");
    private static final UUID SELECTED_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000703");
    private static final String HASH = "a".repeat(64);
    private static final String AUTHORIZATION = "M5.5B-TEST-AUTHORIZATION";

    @TempDir
    Path temporaryDirectory;

    @Test
    void capturesBaselineBeforeImportThenReplaysAuditsAndGatesMeasuredResults()
            throws Exception {
        Fixture fixture = fixture();
        PlacePilotImportService.ImportResult imported = importResult(false);
        PlacePilotImportService.ImportResult replay = importResult(true);
        when(fixture.importer().importApproved(
                any(Path.class), eq(HASH), eq(AUTHORIZATION)))
                .thenReturn(imported, replay);
        when(fixture.probes().captureBaseline(any(), any()))
                .thenReturn(probeSuite(100.0, false));
        when(fixture.probes().captureAfter(any(), any()))
                .thenReturn(probeSuite(105.0, true));
        when(fixture.anomalies().captureDidimBaseline()).thenReturn(snapshot());
        when(fixture.anomalies().audit(eq(RUN_ID), any())).thenReturn(passingAudit());
        PlacePilotCanaryGateService.GateResult passedGate =
                new PlacePilotCanaryGateService.GateResult(
                        UUID.randomUUID(), RUN_ID, "didim-run", "STAGE_1", "PASSED", false);
        when(fixture.gates().record(eq(RUN_ID), eq(true), any(), eq(null)))
                .thenReturn(passedGate);

        PlacePilotAutonomousCanaryService.CanaryExecution result =
                fixture.service().run(configuration(fixture.manifestPath()));

        assertThat(result.gateResult()).isEqualTo(passedGate);
        assertThat(result.diagnostics().path("measured_pass").asBoolean()).isTrue();
        assertThat(result.diagnostics().path("probe_mode").asText())
                .isEqualTo("AUTONOMOUS_HTTP_AND_DATABASE_V1");
        assertThat(result.diagnostics().path("http_probes").path("before")
                .path("request_count").asInt()).isEqualTo(5);
        assertThat(result.diagnostics().path("performance").path("search")
                .path("relative_change").asDouble()).isEqualTo(0.05);
        assertThat(result.diagnostics().path("selected_place_count").asInt()).isEqualTo(1);
        assertThat(result.diagnostics().path("selected_place_coverage")
                .path("place_detail").asInt()).isEqualTo(1);
        verify(fixture.importer(), times(2))
                .importApproved(fixture.manifestPath(), HASH, AUTHORIZATION);

        InOrder order = inOrder(fixture.anomalies(), fixture.probes(),
                fixture.importer(), fixture.reconciliation(), fixture.gates());
        order.verify(fixture.anomalies()).captureDidimBaseline();
        order.verify(fixture.probes()).captureBaseline(any(), any());
        order.verify(fixture.importer(), times(2)).importApproved(
                fixture.manifestPath(), HASH, AUTHORIZATION);
        order.verify(fixture.reconciliation()).renewLeaseForProbes(
                RUN_ID, 1, 1, Duration.ofSeconds(2));
        order.verify(fixture.probes()).captureAfter(any(), any());
        order.verify(fixture.anomalies()).audit(eq(RUN_ID), any());
        order.verify(fixture.gates()).record(eq(RUN_ID), eq(true), any(), eq(null));
    }

    @Test
    void postImportProbeFailureRecordsFailedGateForContainment() throws Exception {
        Fixture fixture = fixture();
        when(fixture.importer().importApproved(
                any(Path.class), eq(HASH), eq(AUTHORIZATION)))
                .thenReturn(importResult(false), importResult(true));
        when(fixture.probes().captureBaseline(any(), any()))
                .thenReturn(probeSuite(100.0, false));
        when(fixture.probes().captureAfter(any(), any()))
                .thenThrow(new IllegalStateException("probe transport failed"));
        when(fixture.anomalies().captureDidimBaseline()).thenReturn(snapshot());
        when(fixture.gates().record(eq(RUN_ID), eq(false), any(), eq(null)))
                .thenReturn(new PlacePilotCanaryGateService.GateResult(
                        UUID.randomUUID(), RUN_ID, "didim-run", "STAGE_1", "FAILED", false));

        assertThatThrownBy(() -> fixture.service().run(configuration(fixture.manifestPath())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("probe transport failed");

        verify(fixture.gates()).record(eq(RUN_ID), eq(false),
                any(ObjectNode.class), eq(null));
    }

    private Fixture fixture() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path manifestPath = temporaryDirectory.resolve(UUID.randomUUID() + ".json");
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("run_id", RUN_ID.toString());
        manifest.put("authorization_reference", AUTHORIZATION);
        ObjectNode candidate = manifest.putArray("candidates").addObject();
        candidate.put("selected_for_stage", true);
        candidate.put("selection_rank", 1);
        candidate.put("canonical_place_id", SELECTED_ID.toString());
        ObjectNode canonical = candidate.putObject("canonical");
        canonical.put("name", "Canary Cafe");
        canonical.put("category", "CAFE");
        canonical.put("latitude", 37.3751);
        canonical.put("longitude", 27.2678);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("manifest_hash", HASH);
        envelope.set("manifest", manifest);
        Files.writeString(manifestPath, objectMapper.writeValueAsString(envelope));

        PlacePilotImportService importer = mock(PlacePilotImportService.class);
        PlacePilotCanaryGateService gates = mock(PlacePilotCanaryGateService.class);
        PlacePilotCatalogAnomalyService anomalies = mock(PlacePilotCatalogAnomalyService.class);
        PlacePilotHttpProbeService probes = mock(PlacePilotHttpProbeService.class);
        PlacePilotGateReconciliationService reconciliation =
                mock(PlacePilotGateReconciliationService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(importer.hashManifest(any())).thenReturn(HASH);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(BASELINE_ID)))
                .thenReturn(1);
        return new Fixture(new PlacePilotAutonomousCanaryService(
                importer, gates, anomalies, probes, reconciliation, jdbc, objectMapper), importer,
                gates, anomalies, probes, reconciliation, manifestPath);
    }

    private PlacePilotAutonomousCanaryService.Configuration configuration(Path manifestPath) {
        return new PlacePilotAutonomousCanaryService.Configuration(
                manifestPath, HASH, AUTHORIZATION, URI.create("http://127.0.0.1:8080"),
                URI.create("http://127.0.0.1:8081/actuator"), BASELINE_ID, 1,
                Duration.ofSeconds(2));
    }

    private PlacePilotImportService.ImportResult importResult(boolean alreadyImported) {
        return new PlacePilotImportService.ImportResult(
                RUN_ID, alreadyImported, "SUCCEEDED", 1, 1, 0,
                1, 0, 0, 0, 0, 1);
    }

    private PlacePilotHttpProbeService.ProbeSuite probeSuite(
            double p95,
            boolean withCoverage
    ) {
        Map<String, PlacePilotHttpProbeService.SurfaceResult> surfaces =
                new LinkedHashMap<>();
        for (String name : List.of(
                "health", "search", "map_nearby", "map_bounds", "place_detail")) {
            surfaces.put(name, new PlacePilotHttpProbeService.SurfaceResult(
                    1, 0, p95, p95, true, List.of()));
        }
        if (withCoverage) {
            for (String name : List.of(
                    "search_coverage", "map_nearby_coverage",
                    "map_bounds_coverage", "place_detail_coverage")) {
                surfaces.put(name, new PlacePilotHttpProbeService.SurfaceResult(
                        1, 0, p95, p95, true, List.of()));
            }
        }
        return new PlacePilotHttpProbeService.ProbeSuite(
                Map.copyOf(surfaces), withCoverage ? 9 : 5, 0, true);
    }

    private PlacePilotCatalogAnomalyService.CatalogSnapshot snapshot() {
        return new PlacePilotCatalogAnomalyService.CatalogSnapshot(
                10, 0, 0, 0, 0, 1, 0, Map.of("CAFE", 10L));
    }

    private PlacePilotCatalogAnomalyService.AuditResult passingAudit() {
        ObjectNode report = JsonNodeFactory.instance.objectNode();
        report.put("passed", true);
        return new PlacePilotCatalogAnomalyService.AuditResult(
                report, true, 0, 0, 0);
    }

    private record Fixture(
            PlacePilotAutonomousCanaryService service,
            PlacePilotImportService importer,
            PlacePilotCanaryGateService gates,
            PlacePilotCatalogAnomalyService anomalies,
            PlacePilotHttpProbeService probes,
            PlacePilotGateReconciliationService reconciliation,
            Path manifestPath
    ) {}
}

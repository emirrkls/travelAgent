package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.spy;

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
    void v3WithoutExplicitPersistentAdapterStopsBeforeAnyProbeOrWrite() throws Exception {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode envelope = (ObjectNode) mapper.readTree(fixture.manifestPath().toFile());
        ((ObjectNode) envelope.path("manifest")).put("method_version", PlacePilotV3Policy.V3);
        Files.writeString(fixture.manifestPath(), mapper.writeValueAsString(envelope));
        assertThatThrownBy(() -> fixture.service().run(configuration(fixture.manifestPath())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no one-shot fallback");
        verifyNoInteractions(fixture.importer(), fixture.probes(), fixture.gates(), fixture.anomalies(), fixture.artifacts());
    }

    @Test
    void explicitV3AdapterUsesPersistentTelemetryNotOneShotRelativeGate() throws Exception {
        Fixture fixture = fixture();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode envelope = (ObjectNode) mapper.readTree(fixture.manifestPath().toFile());
        ((ObjectNode) envelope.path("manifest")).put("method_version", PlacePilotV3Policy.V3);
        Files.writeString(fixture.manifestPath(), mapper.writeValueAsString(envelope));
        when(fixture.importer().importApproved(any(Path.class), eq(HASH), eq(AUTHORIZATION)))
                .thenReturn(importResult(false), importResult(true));
        when(fixture.probes().captureBaseline(any(), any())).thenReturn(probeSuite(100, false));
        when(fixture.probes().captureAfter(any(), any())).thenReturn(probeSuite(900, true));
        when(fixture.anomalies().captureDidimBaseline()).thenReturn(snapshot());
        when(fixture.anomalies().audit(eq(RUN_ID), any())).thenReturn(passingAudit());
        var adapter = mock(PlacePilotAutonomousCanaryService.V3Observations.class);
        Instant start = Instant.now().minusSeconds(10);
        when(adapter.capturePersistent("PRE", RUN_ID, HASH)).thenReturn(
                PlacePilotV3EvidenceFixture.snapshot(RUN_ID, HASH, "PRE", start, 10));
        when(adapter.capturePersistent("POST", RUN_ID, HASH)).thenReturn(
                PlacePilotV3EvidenceFixture.snapshot(RUN_ID, HASH, "POST", start.plusSeconds(5), 40));
        ObjectNode products = mapper.createObjectNode();
        PlacePilotV3Policy.PRODUCT_CHECKS.forEach(check -> products.put(check, "PASS"));
        when(adapter.productAcceptance(eq(RUN_ID), eq(HASH), any())).thenReturn(products);
        var passed = new PlacePilotCanaryGateService.GateResult(UUID.randomUUID(), RUN_ID,
                "didim-run", "STAGE_1", "PASSED", false);
        when(fixture.gates().record(eq(RUN_ID), eq(true), any(), eq(null), any(), any())).thenReturn(passed);
        var result = fixture.service().run(configuration(fixture.manifestPath()), adapter);
        assertThat(result.gateResult()).isEqualTo(passed);
        assertThat(result.diagnostics().path("measured_pass").asBoolean()).isTrue();
        assertThat(result.diagnostics().has("performance")).isFalse();
        assertThat(result.diagnostics().path("persistent_performance").path("PERFORMANCE_ADVISORY").asText()).isEqualTo("DEGRADED");
        InOrder order = inOrder(adapter, fixture.importer());
        order.verify(fixture.importer()).validateApprovedAccounting(any(), eq(HASH), eq(AUTHORIZATION));
        order.verify(adapter).capturePersistent("PRE", RUN_ID, HASH);
        order.verify(fixture.importer(), times(2)).importApproved(any(Path.class), eq(HASH), eq(AUTHORIZATION));
        order.verify(adapter).capturePersistent("POST", RUN_ID, HASH);
        order.verify(adapter).productAcceptance(eq(RUN_ID), eq(HASH), any());
    }

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
        when(fixture.gates().record(eq(RUN_ID), eq(true), any(), eq(null), any()))
                .thenReturn(passedGate);

        PlacePilotAutonomousCanaryService.CanaryExecution result =
                fixture.service().run(configuration(fixture.manifestPath()));

        assertThat(result.gateResult()).isEqualTo(passedGate);
        assertThat(result.diagnostics().path("measured_pass").asBoolean()).isTrue();
        assertThat(result.diagnostics().path("probe_mode").asText())
                .isEqualTo("AUTONOMOUS_HTTP_AND_DATABASE_V1");
        assertThat(result.diagnostics().path("http_probes").path("before")
                .path("request_count").asInt()).isEqualTo(25);
        assertThat(result.diagnostics().path("performance").path("search")
                .path("relative_change").asDouble()).isEqualTo(0.05);
        assertThat(result.diagnostics().path("selected_place_count").asInt()).isEqualTo(1);
        assertThat(result.diagnostics().path("selected_place_coverage")
                .path("place_detail").asInt()).isEqualTo(1);
        verify(fixture.importer(), times(2))
                .importApproved(fixture.manifestPath(), HASH, AUTHORIZATION);

        InOrder order = inOrder(fixture.anomalies(), fixture.probes(),
                fixture.artifacts(), fixture.importer(), fixture.reconciliation(), fixture.gates());
        order.verify(fixture.importer()).validateApprovedAccounting(any(), eq(HASH), eq(AUTHORIZATION));
        order.verify(fixture.anomalies()).captureDidimBaseline();
        order.verify(fixture.probes()).captureBaseline(any(), any());
        order.verify(fixture.artifacts()).persistAndVerify(any(), any(), any(), any());
        order.verify(fixture.importer(), times(2)).importApproved(
                fixture.manifestPath(), HASH, AUTHORIZATION);
        order.verify(fixture.reconciliation()).renewLeaseForProbes(
                RUN_ID, 1, 5, Duration.ofSeconds(5));
        order.verify(fixture.probes()).captureAfter(any(), any());
        order.verify(fixture.anomalies()).audit(eq(RUN_ID), any());
        order.verify(fixture.gates()).record(eq(RUN_ID), eq(true), any(), eq(null), any());
    }

    @Test
    void baselineFailureEmitsSafeDiagnosticsAndNeverImportsOrPersistsGates() throws Exception {
        Fixture fixture = fixture();
        var healthy = probeSuite(100.0, false);
        var surfaces = new LinkedHashMap<>(healthy.surfaces());
        surfaces.put("health", PlacePilotHttpProbeService.SurfaceResult.from(
                java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(index ->
                        new PlacePilotHttpProbeService.ProbeDiagnostic("health", index, "GET",
                                PlacePilotHttpProbeService.pathTemplate("health"), Instant.EPOCH, 100,
                                503, false, "RESPONSE_RECEIVED", "NOT_EVALUATED", "HTTP_503")).toList()));
        var failed = PlacePilotHttpProbeService.ProbeSuite.from(surfaces);
        when(fixture.anomalies().captureDidimBaseline()).thenReturn(snapshot());
        when(fixture.probes().captureBaseline(any(),any())).thenReturn(failed);
        assertThatThrownBy(() -> fixture.service().run(configuration(fixture.manifestPath())))
                .isInstanceOf(PlacePilotBaselineDiagnostics.BaselineFailure.class)
                .satisfies(error -> {
                    var diagnostics=((PlacePilotBaselineDiagnostics.BaselineFailure)error).diagnostics();
                    assertThat(diagnostics.path("surfaces").size()).isEqualTo(5);
                    assertThat(diagnostics.path("failing_surfaces").get(0).asText()).isEqualTo("health");
                    assertThat(diagnostics.path("surfaces").path("search").path("passed").asBoolean()).isTrue();
                });
        verify(fixture.importer(),never()).importApproved(any(Path.class),anyString(),anyString());
        verify(fixture.probes(),never()).captureAfter(any(),any());
        verifyNoInteractions(fixture.gates(),fixture.reconciliation());
    }

    @Test
    void accountingFailureStopsBeforeBaselineAndImport() throws Exception {
        Fixture fixture = fixture();
        when(fixture.importer().validateApprovedAccounting(any(), eq(HASH), eq(AUTHORIZATION)))
                .thenThrow(new IllegalArgumentException("source accounting mismatch"));
        assertThatThrownBy(() -> fixture.service().run(configuration(fixture.manifestPath())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accounting");
        verify(fixture.importer(), never()).importApproved(any(Path.class), anyString(), anyString());
        verifyNoInteractions(fixture.probes(), fixture.anomalies(), fixture.gates(), fixture.reconciliation());
    }

    @Test
    void failedBaselineStillEmitsAllDetailedDiagnosticsWhenDurableWriteFails() throws Exception {
        Fixture fixture = fixture(new BaselineArtifactTestSupport.FaultFiles("FORCE"));
        when(fixture.anomalies().captureDidimBaseline()).thenReturn(snapshot());
        when(fixture.probes().captureBaseline(any(), any())).thenReturn(BaselineArtifactTestSupport.suite(false));
        var output = new java.io.ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> fixture.service().run(configuration(fixture.manifestPath())))
                    .hasMessage("PREIMPORT_BASELINE_ARTIFACT_GATE_FAILED");
        } finally {
            System.setOut(original);
        }
        String line = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines()
                .filter(value -> value.startsWith("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS=")).findFirst().orElseThrow();
        var stored = new ObjectMapper().readTree(line.substring(line.indexOf('=') + 1));
        assertThat(stored.path("overall").asText()).isEqualTo("FAIL");
        assertThat(stored.path("request_count").asInt()).isEqualTo(25);
        assertThat(stored.path("surfaces").size()).isEqualTo(5);
        for (var surface : stored.path("surfaces")) assertThat(surface.path("probes").size()).isEqualTo(5);
        verify(fixture.importer(), never()).importApproved(any(Path.class), anyString(), anyString());
        verifyNoInteractions(fixture.gates(), fixture.reconciliation());
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
        return fixture(new PlacePilotBaselineArtifactService.FileAccess());
    }

    @ParameterizedTest
    @ValueSource(strings={"CREATE","OPEN","WRITE","FORCE","CLOSE","FINALIZE","DIRECTORY_FORCE","REOPEN",
            "MALFORMED","VERSION","RUN","EXECUTION","MANIFEST","ENVELOPE","METHOD","OVERALL","TIMESTAMP",
            "SURFACE_MISSING","PROBE_MISSING","PROBE_DUPLICATE","SUMMARY","STATUS","TIMEOUT","DURATION",
            "NONFINITE","UNSAFE_PATH","EXTRA_FIELD","HASH_ONLY","OVERSIZE","DUPLICATE_JSON_KEY"})
    void everyArtifactGateFailurePreventsFirstImportAndCreatesNoDatabaseGate(String fault) throws Exception {
        Fixture fixture = fixture(new BaselineArtifactTestSupport.FaultFiles(fault));
        when(fixture.anomalies().captureDidimBaseline()).thenReturn(snapshot());
        when(fixture.probes().captureBaseline(any(), any())).thenReturn(probeSuite(100, false));
        assertThatThrownBy(() -> fixture.service().run(configuration(fixture.manifestPath())))
                .hasMessage("PREIMPORT_BASELINE_ARTIFACT_GATE_FAILED").hasNoCause();
        verify(fixture.importer(), never()).importApproved(any(Path.class), anyString(), anyString());
        verify(fixture.probes(), never()).captureAfter(any(), any());
        verifyNoInteractions(fixture.gates(), fixture.reconciliation());
    }

    private Fixture fixture(PlacePilotBaselineArtifactService.FileAccess fileAccess) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path manifestPath = temporaryDirectory.resolve(UUID.randomUUID() + ".json");
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("run_id", RUN_ID.toString());
        manifest.put("method_version", "didim-autonomous-validation-v2");
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
        when(importer.validateApprovedAccounting(any(), eq(HASH), eq(AUTHORIZATION)))
                .thenReturn(mock(PlacePilotSourceAccounting.Approved.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(BASELINE_ID)))
                .thenReturn(1);
        var artifacts = spy(new PlacePilotBaselineArtifactService(objectMapper, fileAccess));
        return new Fixture(new PlacePilotAutonomousCanaryService(
                importer, gates, anomalies, probes, reconciliation, jdbc, objectMapper, artifacts), importer,
                gates, anomalies, probes, reconciliation, manifestPath, artifacts);
    }

    private PlacePilotAutonomousCanaryService.Configuration configuration(Path manifestPath) {
        return new PlacePilotAutonomousCanaryService.Configuration(
                manifestPath, HASH, AUTHORIZATION, URI.create("http://127.0.0.1:8080"),
                URI.create("http://127.0.0.1:8081/actuator"), BASELINE_ID, 5,
                Duration.ofSeconds(5), temporaryDirectory);
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
            surfaces.put(name, PlacePilotHttpProbeService.SurfaceResult.from(
                    java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(index ->
                            new PlacePilotHttpProbeService.ProbeDiagnostic(name, index, "GET",
                                    PlacePilotHttpProbeService.pathTemplate(name), Instant.EPOCH,
                                    p95, 200, false, "RESPONSE_RECEIVED", "VALID", null)).toList()));
        }
        if (withCoverage) {
            for (String name : List.of(
                    "search_coverage", "map_nearby_coverage",
                    "map_bounds_coverage", "place_detail_coverage")) {
                surfaces.put(name, new PlacePilotHttpProbeService.SurfaceResult(
                        1, 0, p95, p95, true, List.of()));
            }
        }
        return PlacePilotHttpProbeService.ProbeSuite.from(surfaces);
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
            Path manifestPath,
            PlacePilotBaselineArtifactService artifacts
    ) {}
}

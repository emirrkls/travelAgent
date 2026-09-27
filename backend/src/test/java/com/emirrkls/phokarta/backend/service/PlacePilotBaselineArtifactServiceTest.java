package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import static org.assertj.core.api.Assertions.*;
import static com.emirrkls.phokarta.backend.service.BaselineArtifactTestSupport.*;

class PlacePilotBaselineArtifactServiceTest {
    @TempDir Path directory;

    @Test void passingArtifactIsFinalizedReadBackBoundHasExactlyFiveSurfacesAndTwentyFiveProbes() throws Exception {
        var receipt = new PlacePilotBaselineArtifactService(new ObjectMapper())
                .persistAndVerify(directory, suite(true), identity(), TIMEOUT);
        byte[] stored = Files.readAllBytes(receipt.path());
        var actual = new ObjectMapper().readTree(stored);
        assertThat(actual.path("overall").asText()).isEqualTo("PASS");
        assertThat(actual.path("surfaces").size()).isEqualTo(5);
        assertThat(actual.path("request_count").asInt()).isEqualTo(25);
        for (var surface : actual.path("surfaces")) {
            assertThat(surface.path("probes").size()).isEqualTo(5);
            assertThat(surface.path("sample_count").asInt()).isEqualTo(5);
            assertThat(surface.path("status_distribution").path("200").asInt()).isEqualTo(5);
        }
        assertThat(receipt.identity()).isEqualTo(identity());
        assertThat(receipt.path()).isEqualTo(directory.resolve(RUN.toString()).resolve(EXECUTION.toString())
                .resolve(PlacePilotBaselineArtifactService.FILENAME));
        assertThat(receipt.sha256()).isEqualTo(PlacePilotBaselineArtifactService.sha256(stored));
        assertThat(receipt.byteCount()).isEqualTo(stored.length).isLessThan(131072);
        assertThat(receipt.toJson().path("read_back_verified").asBoolean()).isTrue();
        try (var paths = Files.list(receipt.path().getParent())) {
            assertThat(paths.map(p -> p.getFileName().toString()).toList())
                    .containsExactly(PlacePilotBaselineArtifactService.FILENAME);
        }
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(receipt.path())).isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(receipt.path().getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test void failureSharesDetailedSchemaIsDurableAndRetainsHealthySurfaceSamples() throws Exception {
        var receipt = new PlacePilotBaselineArtifactService(new ObjectMapper())
                .persistAndVerify(directory, suite(false), identity(), TIMEOUT);
        var actual = new ObjectMapper().readTree(Files.readAllBytes(receipt.path()));
        assertThat(receipt.overall()).isEqualTo("FAIL");
        assertThat(actual.path("surfaces").path("health").path("status_distribution").path("503").asInt()).isEqualTo(5);
        assertThat(actual.path("surfaces").path("search").path("probes").size()).isEqualTo(5);
        assertThat(actual.path("surfaces").path("search").path("passed").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings={"CREATE","OPEN","WRITE","FORCE","CLOSE","FINALIZE","DIRECTORY_FORCE","REOPEN",
            "MALFORMED","VERSION","RUN","EXECUTION","MANIFEST","ENVELOPE","METHOD","OVERALL","SURFACE_MISSING",
            "PROBE_MISSING","PROBE_DUPLICATE","SUMMARY","STATUS","TIMEOUT","DURATION","NONFINITE","UNSAFE_PATH",
            "EXTRA_FIELD","HASH_ONLY","OVERSIZE","DUPLICATE_JSON_KEY"})
    void everyFilesystemOrReadBackFaultFailsClosedWithoutLeakingExceptionText(String fault) throws Exception {
        var files = new FaultFiles(fault);
        var service = new PlacePilotBaselineArtifactService(new ObjectMapper(), files);
        assertThatThrownBy(() -> service.persistAndVerify(directory, suite(true), identity(), TIMEOUT))
                .hasMessage("PREIMPORT_BASELINE_ARTIFACT_GATE_FAILED").hasNoCause();
        if (java.util.Set.of("CREATE","OPEN","WRITE","FORCE","CLOSE","FINALIZE").contains(fault)) {
            try (var paths = Files.walk(directory)) {
                assertThat(paths.filter(path -> path.getFileName().toString().equals(PlacePilotBaselineArtifactService.FILENAME))
                        .toList()).isEmpty();
            }
        }
    }

    @Test void finalizedExecutionCannotBeReusedOrOverwritten() throws Exception {
        var service = new PlacePilotBaselineArtifactService(new ObjectMapper());
        var receipt = service.persistAndVerify(directory, suite(true), identity(), TIMEOUT);
        byte[] original = Files.readAllBytes(receipt.path());
        assertThatThrownBy(() -> service.persistAndVerify(directory, suite(true), identity(), TIMEOUT))
                .hasMessage("PREIMPORT_BASELINE_ARTIFACT_GATE_FAILED");
        assertThat(Files.readAllBytes(receipt.path())).isEqualTo(original);
    }

    @Test void secretSentinelsInActualHttpResponsesQueriesAndEnvironmentNeverEnterArtifact() throws Exception {
        var client = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any())).thenAnswer(call -> {
            java.net.http.HttpRequest request = call.getArgument(0);
            var response = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
            String path = request.uri().getPath();
            String body = path.equals("/actuator/health") ? "{\"status\":\"UP\",\"token\":\"PRIVATE_SECRET_SENTINEL\"}"
                    : path.equals("/api/v1/places") ? "{\"content\":[],\"cookie\":\"PRIVATE_SECRET_SENTINEL\"}"
                    : path.endsWith("nearby") || path.endsWith("bounds") ? "[]"
                    : "{\"id\":\"aa000000-0000-4000-8000-000000000001\",\"name\":\"stable\",\"category\":\"CAFE\",\"latitude\":41.022,\"longitude\":28.9784,\"Authorization\":\"PRIVATE_SECRET_SENTINEL\"}";
            org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
            org.mockito.Mockito.when(response.body()).thenReturn(body);
            return response;
        });
        var realSuite = new PlacePilotHttpProbeService(new ObjectMapper(), client).captureBaseline(
                PlacePilotBaselineDiagnosticsTest.CONFIG, new PlacePilotHttpProbeService.ProbeTarget(
                        java.util.UUID.randomUUID(), "PRIVATE_SECRET_SENTINEL", "BEACH", 37.3751, 27.2678));
        var receipt = new PlacePilotBaselineArtifactService(new ObjectMapper())
                .persistAndVerify(directory, realSuite, identity(), TIMEOUT);
        assertThat(Files.readString(receipt.path())).doesNotContain("PRIVATE_SECRET_SENTINEL",
                "Authorization", "cookie", "127.0.0.1", "password", "jdbc:");
    }
}

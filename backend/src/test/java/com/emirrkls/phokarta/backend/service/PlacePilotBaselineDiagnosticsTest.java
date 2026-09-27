package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlacePilotBaselineDiagnosticsTest {
    static final UUID STABLE = UUID.fromString("aa000000-0000-4000-8000-000000000001");
    static final PlacePilotHttpProbeService.ProbeTarget TARGET = new PlacePilotHttpProbeService.ProbeTarget(
            UUID.randomUUID(), "unprinted-private-query-Authorization-cookie-password", "BEACH", 37.3751, 27.2678);
    static final PlacePilotHttpProbeService.ProbeConfiguration CONFIG = new PlacePilotHttpProbeService.ProbeConfiguration(
            URI.create("http://127.0.0.1:8080"), URI.create("http://127.0.0.1:8081/actuator"), STABLE, 5, Duration.ofSeconds(5));
    @TempDir Path directory;

    @Test void allFiveSurfacesHaveExactlyFiveImmutableSuccessfulSamples() throws Exception {
        HttpClient client = healthyClient();
        var suite = service(client).captureBaseline(CONFIG, TARGET);
        assertThat(suite.passed()).isTrue();
        assertThat(suite.requestCount()).isEqualTo(25);
        assertThat(suite.errorCount()).isZero();
        assertThat(suite.surfaces()).containsOnlyKeys("health", "search", "map_nearby", "map_bounds", "place_detail");
        for (var surface : suite.surfaces().values()) {
            assertThat(surface.sampleCount()).isEqualTo(5);
            assertThat(surface.observations()).hasSize(5);
            assertThat(surface.observations()).extracting(PlacePilotHttpProbeService.ProbeDiagnostic::sampleIndex)
                    .containsExactly(1,2,3,4,5);
            assertThatThrownBy(() -> surface.observations().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        verify(client,times(25)).send(any(), handler());
    }

    @ParameterizedTest @ValueSource(ints={500,404,401,403,302})
    void nonSuccessHealthRetainsExactStatusAndAllSuccessfulSurfaces(int status) throws Exception {
        HttpClient client = healthyClient();
        when(client.send(any(),handler())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            return response(request.uri().getPath().equals("/actuator/health") ? status : 200, body(request));
        });
        var suite = service(client).captureBaseline(CONFIG,TARGET);
        assertThat(suite.passed()).isFalse();
        assertThat(suite.errorCount()).isEqualTo(5);
        var document = PlacePilotBaselineDiagnostics.document(suite,CONFIG.timeout());
        assertThat(document.path("failing_surfaces")).containsExactly(new ObjectMapper().getNodeFactory().textNode("health"));
        assertThat(document.path("surfaces").path("health").path("status_distribution").path(""+status).asInt()).isEqualTo(5);
        assertThat(suite.surface("health").observations()).allSatisfy(probe -> {
            assertThat(probe.httpStatus()).isEqualTo(status);
            assertThat(probe.failureCategory()).isEqualTo("HTTP_"+status);
            assertThat(probe.responseValidation()).isEqualTo("NOT_EVALUATED");
        });
        for(String surface:List.of("search","map_nearby","map_bounds","place_detail"))
            assertThat(suite.surface(surface).passed()).isTrue();
    }

    @Test void timeoutIsRetainedWithoutExceptionTextOrRetries() throws Exception {
        HttpClient client = healthyClient();
        when(client.send(any(),handler())).thenAnswer(call -> {
            HttpRequest request=call.getArgument(0);
            if(request.uri().getPath().endsWith("nearby")) throw new HttpTimeoutException("password=DO_NOT_PRINT");
            return response(200,body(request));
        });
        var suite=service(client).captureBaseline(CONFIG,TARGET);
        assertThat(suite.surface("map_nearby").observations()).allSatisfy(probe -> {
            assertThat(probe.timeout()).isTrue(); assertThat(probe.httpStatus()).isNull();
            assertThat(probe.failureCategory()).isEqualTo("HTTP_TIMEOUT");
        });
        assertThat(suite.toDiagnosticJson().path("surfaces").path("map_nearby").path("timeout_count").asInt()).isEqualTo(5);
        assertThat(suite.toDiagnosticJson().toString()).doesNotContain("DO_NOT_PRINT","password=");
        verify(client,times(25)).send(any(),handler());
    }

    @Test void transportErrorUsesClosedSafeCategoryAndKeepsOtherSurfaces() throws Exception {
        HttpClient client=healthyClient();
        when(client.send(any(),handler())).thenAnswer(call -> {
            HttpRequest request=call.getArgument(0);
            if(request.uri().getPath().endsWith("bounds")) throw new IOException("Bearer SECRET https://secret:secret@host");
            return response(200,body(request));
        });
        var suite=service(client).captureBaseline(CONFIG,TARGET);
        assertThat(suite.surface("map_bounds").failures()).containsExactly("TRANSPORT_ERROR");
        assertThat(suite.toDiagnosticJson().toString()).doesNotContain("Bearer","SECRET","https://");
        assertThat(suite.surface("search").passed()).isTrue();
    }

    @Test void invalidJsonAndWrongContractHaveDifferentDiagnostics() throws Exception {
        HttpClient client=healthyClient();
        when(client.send(any(),handler())).thenAnswer(call -> {
            HttpRequest request=call.getArgument(0);
            return response(200, request.uri().getPath().endsWith("bounds") ? "PRIVATE_INVALID_BODY"
                    : request.uri().getPath().equals("/api/v1/places") ? "[]" : body(request));
        });
        var suite=service(client).captureBaseline(CONFIG,TARGET);
        assertThat(suite.surface("map_bounds").failures()).containsExactly("INVALID_JSON");
        assertThat(suite.surface("search").failures()).containsExactly("SEARCH_RESPONSE_SHAPE");
        assertThat(suite.toDiagnosticJson().toString()).doesNotContain("PRIVATE_INVALID_BODY");
    }

    @Test void noQueryPayloadHeaderOrPrivatePrefixLeaksAndTimeoutStaysFiveSeconds() throws Exception {
        HttpClient client=healthyClient();
        when(client.send(any(),handler())).thenAnswer(call -> {
            HttpRequest request=call.getArgument(0);
            assertThat(request.timeout()).contains(Duration.ofSeconds(5));
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().firstValue("Authorization")).isEmpty();
            assertThat(request.headers().firstValue("Cookie")).isEmpty();
            assertThat(request.headers().firstValue("Accept")).contains("application/json");
            assertThat(request.headers().firstValue("User-Agent")).contains("phokarta-autonomous-canary/1");
            return response(200,body(request));
        });
        var suite=service(client).captureBaseline(CONFIG,TARGET);
        String json=suite.toDiagnosticJson().toString();
        assertThat(json).doesNotContain(TARGET.name(),"unprinted-private-response","127.0.0.1","Authorization","cookie-password");
        assertThat(PlacePilotHttpProbeService.createHttpClient().connectTimeout()).contains(Duration.ofSeconds(5));
        assertThat(PlacePilotHttpProbeService.createHttpClient().followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test void summariesComputeMinMedianP95MaxAndKeepFirstFailureInProbeOrder() {
        var samples=java.util.stream.IntStream.rangeClosed(1,5).mapToObj(index -> new PlacePilotHttpProbeService.ProbeDiagnostic(
                "health",index,"GET","{management}/health",Instant.EPOCH,index,
                index==1?500:200,false,"RESPONSE_RECEIVED",index==1?"NOT_EVALUATED":"VALID",index==1?"HTTP_500":null)).toList();
        var surface=PlacePilotHttpProbeService.SurfaceResult.from(samples);
        var summary=surface.toJson();
        assertThat(summary.path("min_ms").asDouble()).isEqualTo(1);
        assertThat(summary.path("median_ms").asDouble()).isEqualTo(3);
        assertThat(summary.path("p95_ms").asDouble()).isEqualTo(5);
        assertThat(summary.path("max_ms").asDouble()).isEqualTo(5);
        assertThat(summary.path("success_count").asInt()).isEqualTo(4);
        assertThat(summary.path("failure_count").asInt()).isEqualTo(1);
        assertThat(summary.path("first_failure_category").asText()).isEqualTo("HTTP_500");
        assertThat(summary.path("status_distribution").path("200").asInt()).isEqualTo(4);
    }

    @Test void artifactAndImmutableDiagnosticsSurviveFailureExitPath() throws Exception {
        var suite=service(healthyClient()).captureBaseline(CONFIG,TARGET);
        var document=PlacePilotBaselineDiagnostics.document(suite,CONFIG.timeout());
        Path artifact=directory.resolve("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json");
        PlacePilotBaselineDiagnostics.emit(document,artifact);
        var failure=new PlacePilotBaselineDiagnostics.BaselineFailure(document);
        document.removeAll();
        assertThat(failure.diagnostics().path("request_count").asInt()).isEqualTo(25);
        ((com.fasterxml.jackson.databind.node.ObjectNode)failure.diagnostics()).removeAll();
        assertThat(failure.diagnostics().path("request_count").asInt()).isEqualTo(25);
        assertThat(new ObjectMapper().readTree(Files.readString(artifact)).path("surfaces").size()).isEqualTo(5);
    }

    @Test void artifactDoesNotOverwriteExistingOperatorFile() throws Exception {
        Path artifact=directory.resolve("existing.json"); Files.writeString(artifact,"preserve");
        var document=PlacePilotBaselineDiagnostics.document(service(healthyClient()).captureBaseline(CONFIG,TARGET),CONFIG.timeout());
        assertThatThrownBy(() -> PlacePilotBaselineDiagnostics.emit(document,artifact))
                .hasMessage("PREIMPORT_BASELINE_ARTIFACT_WRITE_FAILED").hasNoCause();
        assertThat(Files.readString(artifact)).isEqualTo("preserve");
    }

    private HttpClient healthyClient() throws Exception {
        HttpClient client=mock(HttpClient.class);
        when(client.send(any(),handler())).thenAnswer(call -> response(200,body(call.getArgument(0)))); return client;
    }
    private PlacePilotHttpProbeService service(HttpClient client) { return new PlacePilotHttpProbeService(new ObjectMapper(),client); }
    private String body(HttpRequest request) {
        String path=request.uri().getPath();
        if(path.equals("/actuator/health")) return "{\"status\":\"UP\",\"ignored\":\"unprinted-private-response\"}";
        if(path.equals("/api/v1/places")) return "{\"content\":[]}";
        if(path.endsWith("nearby")||path.endsWith("bounds")) return "[]";
        assertThat(path).isEqualTo("/api/v1/places/"+STABLE);
        return "{\"id\":\""+STABLE+"\",\"name\":\"Staging Harbor Cafe\",\"category\":\"CAFE\",\"latitude\":41.022,\"longitude\":28.9784}";
    }
    @SuppressWarnings("unchecked") private static HttpResponse.BodyHandler<String> handler(){ return any(HttpResponse.BodyHandler.class); }
    @SuppressWarnings("unchecked") private HttpResponse<String> response(int status,String body){
        HttpResponse<String> result=mock(HttpResponse.class); when(result.statusCode()).thenReturn(status); when(result.body()).thenReturn(body); return result;
    }
}

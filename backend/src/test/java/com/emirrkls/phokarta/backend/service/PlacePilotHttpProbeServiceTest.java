package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlacePilotHttpProbeServiceTest {
    private static final UUID BASELINE_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000701");
    private static final UUID SELECTED_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000702");
    private static final PlacePilotHttpProbeService.ProbeTarget TARGET =
            new PlacePilotHttpProbeService.ProbeTarget(
                    SELECTED_ID, "Canary Cafe", "CAFE", 37.3751, 27.2678);
    private static final PlacePilotHttpProbeService.ProbeConfiguration CONFIGURATION =
            new PlacePilotHttpProbeService.ProbeConfiguration(
                    URI.create("http://127.0.0.1:8080"),
                    URI.create("http://127.0.0.1:8081/actuator"), BASELINE_ID,
                    1, Duration.ofSeconds(2));

    @Test
    void productionClientDoesNotFollowRedirects() {
        assertThat(PlacePilotHttpProbeService.createHttpClient().followRedirects())
                .isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void springUsesTheProductionConstructorWhenTheTestConstructorIsAlsoPresent() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(PlacePilotHttpProbeService.class);
            context.refresh();

            assertThat(context.getBean(PlacePilotHttpProbeService.class)).isNotNull();
        }
    }

    @Test
    void constructsPassingResultsOnlyFromSuccessfulSemanticallyValidResponses() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), anyResponseHandler()))
                .thenAnswer(invocation -> responseFor(
                        invocation.getArgument(0, HttpRequest.class), false));
        PlacePilotHttpProbeService service =
                new PlacePilotHttpProbeService(new ObjectMapper(), client);

        PlacePilotHttpProbeService.ProbeSuite baseline =
                service.captureBaseline(CONFIGURATION, TARGET);
        PlacePilotHttpProbeService.ProbeSuite after =
                service.captureAfter(CONFIGURATION, List.of(TARGET));

        assertThat(baseline.passed()).isTrue();
        assertThat(after.passed()).isTrue();
        assertThat(after.requestCount()).isEqualTo(9);
        assertThat(after.errorCount()).isZero();
        assertThat(after.surfaces()).containsOnlyKeys(
                "health", "search", "map_nearby", "map_bounds", "place_detail",
                "search_coverage", "map_nearby_coverage", "map_bounds_coverage",
                "place_detail_coverage");
        assertThat(after.toJson().path("error_rate").doubleValue()).isZero();
    }

    @Test
    void failsTheBoundsSurfaceWhenTheApiReturnsAnOutOfBoundsPlace() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), anyResponseHandler()))
                .thenAnswer(invocation -> responseFor(
                        invocation.getArgument(0, HttpRequest.class), true));
        PlacePilotHttpProbeService service =
                new PlacePilotHttpProbeService(new ObjectMapper(), client);

        PlacePilotHttpProbeService.ProbeSuite result =
                service.captureAfter(CONFIGURATION, List.of(TARGET));

        assertThat(result.passed()).isFalse();
        assertThat(result.errorCount()).isEqualTo(2);
        assertThat(result.surface("map_bounds").failures())
                .containsExactly("BOUNDS_OUT_OF_BOUNDS_RESULT");
        assertThat(result.surface("map_bounds_coverage").failures())
                .containsExactly("BOUNDS_OUT_OF_BOUNDS_RESULT");
    }

    @Test
    void probesEverySelectedPlaceAcrossEveryCorrectnessSurface() throws Exception {
        UUID secondId = UUID.fromString("70000000-0000-0000-0000-000000000703");
        PlacePilotHttpProbeService.ProbeTarget second =
                new PlacePilotHttpProbeService.ProbeTarget(
                        secondId, "Second Canary Cafe", "CAFE", 37.3752, 27.2679);
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), anyResponseHandler()))
                .thenAnswer(invocation -> responseForTargets(
                        invocation.getArgument(0, HttpRequest.class), TARGET, second));
        PlacePilotHttpProbeService service =
                new PlacePilotHttpProbeService(new ObjectMapper(), client);

        PlacePilotHttpProbeService.ProbeSuite result =
                service.captureAfter(CONFIGURATION, List.of(TARGET, second));

        assertThat(result.passed()).isTrue();
        for (String surface : List.of(
                "search_coverage", "map_nearby_coverage",
                "map_bounds_coverage", "place_detail_coverage")) {
            assertThat(result.surface(surface).sampleCount()).isEqualTo(2);
            assertThat(result.surface(surface).errorCount()).isZero();
        }
    }

    @Test
    void rejectsRemoteOrCredentialBearingProbeOrigins() {
        PlacePilotHttpProbeService service =
                new PlacePilotHttpProbeService(new ObjectMapper(), mock(HttpClient.class));

        for (String baseUrl : List.of(
                "https://example.com:8080",
                "http://operator:secret@127.0.0.1:8080",
                "http://127.0.0.1:8080?target=other")) {
            PlacePilotHttpProbeService.ProbeConfiguration configuration =
                    new PlacePilotHttpProbeService.ProbeConfiguration(
                            URI.create(baseUrl), URI.create("http://127.0.0.1:8081/actuator"),
                            BASELINE_ID, 1, Duration.ofSeconds(2));
            assertThatThrownBy(() -> service.captureAfter(configuration, List.of(TARGET)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback");
        }

        PlacePilotHttpProbeService.ProbeConfiguration remoteHealth =
                new PlacePilotHttpProbeService.ProbeConfiguration(
                        URI.create("http://127.0.0.1:8080"),
                        URI.create("https://example.com:8081/actuator"),
                        BASELINE_ID, 1, Duration.ofSeconds(2));
        assertThatThrownBy(() -> service.captureAfter(remoteHealth, List.of(TARGET)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<String> anyResponseHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

    private HttpResponse<String> responseFor(HttpRequest request, boolean invalidBounds) {
        String path = request.uri().getPath();
        String body;
        if (path.equals("/actuator/health")) {
            assertThat(request.uri().getPort()).isEqualTo(8081);
            body = "{\"status\":\"UP\"}";
        } else if (path.equals("/api/v1/places/nearby")) {
            assertThat(request.uri().getPort()).isEqualTo(8080);
            body = "[{\"place\":{\"id\":\"" + SELECTED_ID
                    + "\",\"name\":\"Canary Cafe\",\"category\":\"CAFE\""
                    + ",\"latitude\":" + TARGET.latitude() + ",\"longitude\":"
                    + TARGET.longitude() + "},\"distanceMeters\":0.0}]";
        } else if (path.equals("/api/v1/places/bounds")) {
            double latitude = invalidBounds ? 38.0 : TARGET.latitude();
            body = "[{\"id\":\"" + SELECTED_ID
                    + "\",\"name\":\"Canary Cafe\",\"category\":\"CAFE\""
                    + ",\"latitude\":"
                    + latitude + ",\"longitude\":" + TARGET.longitude() + "}]";
        } else if (path.equals("/api/v1/places/" + BASELINE_ID)) {
            body = "{\"id\":\"" + BASELINE_ID
                    + "\",\"name\":\"Stable Baseline\",\"category\":\"ATTRACTION\""
                    + ",\"latitude\":37.37,\"longitude\":27.26}";
        } else if (path.equals("/api/v1/places/" + SELECTED_ID)) {
            body = "{\"id\":\"" + SELECTED_ID
                    + "\",\"name\":\"Canary Cafe\",\"category\":\"CAFE\""
                    + ",\"latitude\":" + TARGET.latitude() + ",\"longitude\":"
                    + TARGET.longitude() + "}";
        } else {
            body = "{\"content\":[{\"id\":\"" + SELECTED_ID
                    + "\",\"name\":\"Canary Cafe\",\"category\":\"CAFE\""
                    + ",\"latitude\":" + TARGET.latitude() + ",\"longitude\":"
                    + TARGET.longitude() + "}]}";
        }
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private HttpResponse<String> responseForTargets(
            HttpRequest request,
            PlacePilotHttpProbeService.ProbeTarget first,
            PlacePilotHttpProbeService.ProbeTarget second
    ) {
        String path = request.uri().getPath();
        String query = request.uri().getRawQuery();
        PlacePilotHttpProbeService.ProbeTarget target =
                (path.endsWith(second.placeId().toString())
                        || (query != null && (query.contains("Second+Canary+Cafe")
                        || query.contains("lat=37.3752000")
                        || query.contains("west=27.2579000"))))
                        ? second : first;
        String place = "{\"id\":\"" + target.placeId()
                + "\",\"name\":\"" + target.name() + "\",\"category\":\"CAFE\""
                + ",\"latitude\":" + target.latitude()
                + ",\"longitude\":" + target.longitude() + "}";
        String body;
        if (path.equals("/actuator/health")) {
            body = "{\"status\":\"UP\"}";
        } else if (path.equals("/api/v1/places/nearby")) {
            body = "[{\"place\":" + place + ",\"distanceMeters\":0.0}]";
        } else if (path.equals("/api/v1/places/bounds")) {
            body = "[" + place + "]";
        } else if (path.startsWith("/api/v1/places/")) {
            body = place;
        } else {
            body = "{\"content\":[" + place + "]}";
        }
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }
}

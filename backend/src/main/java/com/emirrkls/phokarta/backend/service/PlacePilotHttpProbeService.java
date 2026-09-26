package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Executes bounded, real HTTP probes against the public Place surfaces. */
@Service
public class PlacePilotHttpProbeService {
    private static final double MAP_RADIUS_METERS = 250.0;
    private static final double BOUNDS_DELTA_DEGREES = 0.01;
    private static final Set<String> PLACE_CATEGORIES = Set.of(
            "BEACH", "RESTAURANT", "CAFE", "HOTEL", "BAR",
            "NIGHTLIFE", "ATTRACTION", "ACTIVITY", "NATURE");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PlacePilotHttpProbeService(ObjectMapper objectMapper) {
        this(objectMapper, createHttpClient());
    }

    static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    PlacePilotHttpProbeService(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public ProbeSuite captureBaseline(ProbeConfiguration configuration, ProbeTarget primary) {
        configuration.validate();
        primary.validate();
        Map<String, SurfaceResult> surfaces = new LinkedHashMap<>();
        surfaces.put("health", probeRepeated(configuration, configuration.healthBaseUrl(),
                "/health",
                configuration.sampleCount(), this::validHealth));
        surfaces.put("search", probeRepeated(configuration, searchPath(primary),
                configuration.sampleCount(), body -> validSearch(body, null)));
        surfaces.put("map_nearby", probeRepeated(configuration, nearbyPath(primary),
                configuration.sampleCount(), body -> validNearby(body, primary, null)));
        surfaces.put("map_bounds", probeRepeated(configuration, boundsPath(primary),
                configuration.sampleCount(), body -> validBounds(body, primary, null)));
        surfaces.put("place_detail", probeRepeated(configuration,
                "/api/v1/places/" + configuration.baselinePlaceId(),
                configuration.sampleCount(),
                body -> validDetail(body, configuration.baselinePlaceId())));
        return ProbeSuite.from(surfaces);
    }

    public ProbeSuite captureAfter(
            ProbeConfiguration configuration,
            List<ProbeTarget> selectedTargets
    ) {
        configuration.validate();
        if (selectedTargets == null || selectedTargets.isEmpty()) {
            throw new IllegalArgumentException("post-import probes require selected Places");
        }
        selectedTargets.forEach(ProbeTarget::validate);
        ProbeTarget primary = selectedTargets.getFirst();
        Map<String, SurfaceResult> surfaces = new LinkedHashMap<>();
        surfaces.put("health", probeRepeated(configuration, configuration.healthBaseUrl(),
                "/health",
                configuration.sampleCount(), this::validHealth));
        surfaces.put("search", probeRepeated(configuration, searchPath(primary),
                configuration.sampleCount(), body -> validSearch(body, primary)));
        surfaces.put("map_nearby", probeRepeated(configuration, nearbyPath(primary),
                configuration.sampleCount(), body -> validNearby(body, primary, primary)));
        surfaces.put("map_bounds", probeRepeated(configuration, boundsPath(primary),
                configuration.sampleCount(), body -> validBounds(body, primary, primary)));
        surfaces.put("place_detail", probeDetails(configuration, List.of(primary)));
        surfaces.put("search_coverage", probeCoverage(selectedTargets, target ->
                request(configuration, searchPath(target), body -> validSearch(body, target))));
        surfaces.put("map_nearby_coverage", probeCoverage(selectedTargets, target ->
                request(configuration, nearbyPath(target),
                        body -> validNearby(body, target, target))));
        surfaces.put("map_bounds_coverage", probeCoverage(selectedTargets, target ->
                request(configuration, boundsPath(target),
                        body -> validBounds(body, target, target))));
        surfaces.put("place_detail_coverage", probeCoverage(selectedTargets, target ->
                request(configuration, "/api/v1/places/" + target.placeId(),
                        body -> validDetail(body, target))));
        return ProbeSuite.from(surfaces);
    }

    private SurfaceResult probeCoverage(
            List<ProbeTarget> targets,
            Function<ProbeTarget, Observation> probe
    ) {
        return SurfaceResult.from(targets.stream().map(probe).toList());
    }

    private SurfaceResult probeDetails(
            ProbeConfiguration configuration,
            List<ProbeTarget> targets
    ) {
        List<Observation> observations = new ArrayList<>();
        for (int index = 0; index < configuration.sampleCount(); index++) {
            ProbeTarget target = targets.get(index % targets.size());
            observations.add(request(configuration,
                    "/api/v1/places/" + target.placeId(),
                    body -> validDetail(body, target)));
        }
        return SurfaceResult.from(observations);
    }

    private SurfaceResult probeRepeated(
            ProbeConfiguration configuration,
            String path,
            int count,
            BodyValidator validator
    ) {
        return probeRepeated(configuration, configuration.baseUrl(), path, count, validator);
    }

    private SurfaceResult probeRepeated(
            ProbeConfiguration configuration,
            URI baseUrl,
            String path,
            int count,
            BodyValidator validator
    ) {
        List<Observation> observations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            observations.add(request(configuration, baseUrl, path, validator));
        }
        return SurfaceResult.from(observations);
    }

    private Observation request(
            ProbeConfiguration configuration,
            String path,
            BodyValidator validator
    ) {
        return request(configuration, configuration.baseUrl(), path, validator);
    }

    private Observation request(
            ProbeConfiguration configuration,
            URI baseUrl,
            String path,
            BodyValidator validator
    ) {
        URI uri = resolve(baseUrl, path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(configuration.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", "phokarta-autonomous-canary/1")
                .build();
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new Observation(elapsedMs, false,
                        "HTTP_" + response.statusCode());
            }
            JsonNode body;
            try {
                body = objectMapper.readTree(response.body());
            } catch (IOException invalidJson) {
                return new Observation(elapsedMs, false, "INVALID_JSON");
            }
            String validationError = validator.validate(body);
            return new Observation(elapsedMs, validationError == null,
                    validationError == null ? null : truncate(validationError));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Observation((System.nanoTime() - started) / 1_000_000.0,
                    false, "INTERRUPTED");
        } catch (IOException | RuntimeException failure) {
            return new Observation((System.nanoTime() - started) / 1_000_000.0,
                    false, truncate(failure.getClass().getSimpleName()));
        }
    }

    private String validHealth(JsonNode body) {
        return body.isObject() && "UP".equals(body.path("status").asText())
                ? null : "HEALTH_NOT_UP";
    }

    private String validSearch(JsonNode body, ProbeTarget requiredTarget) {
        JsonNode content = body.path("content");
        if (!body.isObject() || !content.isArray()) return "SEARCH_RESPONSE_SHAPE";
        for (JsonNode place : content) {
            if (!validPlaceShape(place)) return "SEARCH_RESULT_SHAPE";
        }
        if (requiredTarget != null) {
            JsonNode selected = findPlace(content, requiredTarget.placeId(), false);
            if (selected == null) return "SEARCH_MISSING_SELECTED_PLACE";
            if (!canonicalMatches(selected, requiredTarget)) {
                return "SEARCH_SELECTED_PLACE_MISMATCH";
            }
        }
        return null;
    }

    private String validNearby(
            JsonNode body,
            ProbeTarget center,
            ProbeTarget requiredTarget
    ) {
        if (!body.isArray()) return "NEARBY_RESPONSE_SHAPE";
        boolean found = requiredTarget == null;
        double priorDistance = -1.0;
        for (JsonNode row : body) {
            JsonNode place = row.path("place");
            double distance = row.path("distanceMeters").asDouble(Double.NaN);
            double latitude = place.path("latitude").asDouble(Double.NaN);
            double longitude = place.path("longitude").asDouble(Double.NaN);
            double computedDistance = haversineMeters(
                    center.latitude(), center.longitude(), latitude, longitude);
            if (!validPlaceShape(place) || !Double.isFinite(distance)
                    || !Double.isFinite(computedDistance)
                    || distance < 0 || distance > MAP_RADIUS_METERS + 1.0
                    || computedDistance > MAP_RADIUS_METERS + 2.0
                    || Math.abs(distance - computedDistance) > 5.0
                    || distance + 0.01 < priorDistance) {
                return "NEARBY_OUT_OF_BOUNDS_RESULT";
            }
            priorDistance = distance;
            if (requiredTarget != null
                    && requiredTarget.placeId().toString().equals(place.path("id").asText())) {
                if (!canonicalMatches(place, requiredTarget)) {
                    return "NEARBY_SELECTED_PLACE_MISMATCH";
                }
                found = true;
            }
        }
        return found ? null : "NEARBY_MISSING_SELECTED_PLACE";
    }

    private String validBounds(
            JsonNode body,
            ProbeTarget center,
            ProbeTarget requiredTarget
    ) {
        if (!body.isArray()) return "BOUNDS_RESPONSE_SHAPE";
        boolean found = requiredTarget == null;
        double west = center.longitude() - BOUNDS_DELTA_DEGREES;
        double east = center.longitude() + BOUNDS_DELTA_DEGREES;
        double south = center.latitude() - BOUNDS_DELTA_DEGREES;
        double north = center.latitude() + BOUNDS_DELTA_DEGREES;
        for (JsonNode row : body) {
            double latitude = row.path("latitude").asDouble(Double.NaN);
            double longitude = row.path("longitude").asDouble(Double.NaN);
            if (!validPlaceShape(row)
                    || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < south || latitude > north
                    || longitude < west || longitude > east) {
                return "BOUNDS_OUT_OF_BOUNDS_RESULT";
            }
            if (requiredTarget != null
                    && requiredTarget.placeId().toString().equals(row.path("id").asText())) {
                if (!canonicalMatches(row, requiredTarget)) {
                    return "BOUNDS_SELECTED_PLACE_MISMATCH";
                }
                found = true;
            }
        }
        return found ? null : "BOUNDS_MISSING_SELECTED_PLACE";
    }

    private String validDetail(JsonNode body, UUID expectedPlaceId) {
        return validPlaceShape(body)
                && expectedPlaceId.toString().equals(body.path("id").asText())
                ? null : "DETAIL_IDENTITY_MISMATCH";
    }

    private String validDetail(JsonNode body, ProbeTarget expected) {
        if (!validPlaceShape(body)
                || !expected.placeId().toString().equals(body.path("id").asText())) {
            return "DETAIL_IDENTITY_MISMATCH";
        }
        return canonicalMatches(body, expected) ? null : "DETAIL_CANONICAL_MISMATCH";
    }

    private JsonNode findPlace(JsonNode rows, UUID placeId, boolean nestedPlace) {
        for (JsonNode row : rows) {
            JsonNode value = nestedPlace ? row.path("place") : row;
            if (placeId.toString().equals(value.path("id").asText())) return value;
        }
        return null;
    }

    private boolean canonicalMatches(JsonNode place, ProbeTarget expected) {
        double latitude = place.path("latitude").asDouble(Double.NaN);
        double longitude = place.path("longitude").asDouble(Double.NaN);
        return expected.placeId().toString().equals(place.path("id").asText())
                && expected.name().equals(place.path("name").asText())
                && expected.category().equals(place.path("category").asText())
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude - expected.latitude()) <= 1.0e-6
                && Math.abs(longitude - expected.longitude()) <= 1.0e-6;
    }

    private boolean validPlaceShape(JsonNode place) {
        if (!place.isObject() || !place.path("id").isTextual()
                || !place.path("name").isTextual() || place.path("name").asText().isBlank()
                || !PLACE_CATEGORIES.contains(place.path("category").asText())) {
            return false;
        }
        try {
            UUID.fromString(place.path("id").asText());
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
        double latitude = place.path("latitude").asDouble(Double.NaN);
        double longitude = place.path("longitude").asDouble(Double.NaN);
        return Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180;
    }

    private double haversineMeters(
            double firstLatitude,
            double firstLongitude,
            double secondLatitude,
            double secondLongitude
    ) {
        if (!Double.isFinite(secondLatitude) || !Double.isFinite(secondLongitude)
                || secondLatitude < -90 || secondLatitude > 90
                || secondLongitude < -180 || secondLongitude > 180) {
            return Double.NaN;
        }
        double latitudeDelta = Math.toRadians(secondLatitude - firstLatitude);
        double longitudeDelta = Math.toRadians(secondLongitude - firstLongitude);
        double firstRadians = Math.toRadians(firstLatitude);
        double secondRadians = Math.toRadians(secondLatitude);
        double haversine = Math.sin(latitudeDelta / 2.0) * Math.sin(latitudeDelta / 2.0)
                + Math.cos(firstRadians) * Math.cos(secondRadians)
                * Math.sin(longitudeDelta / 2.0) * Math.sin(longitudeDelta / 2.0);
        haversine = Math.max(0.0, Math.min(1.0, haversine));
        return 6_371_008.8 * 2.0 * Math.atan2(
                Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
    }

    private String searchPath(ProbeTarget target) {
        return "/api/v1/places?search="
                + URLEncoder.encode(target.name(), StandardCharsets.UTF_8)
                + "&page=0&size=100&sort=name%2Casc";
    }

    private String nearbyPath(ProbeTarget target) {
        return String.format(Locale.ROOT,
                "/api/v1/places/nearby?lat=%.7f&lon=%.7f&radiusMeters=%.1f&limit=200",
                target.latitude(), target.longitude(), MAP_RADIUS_METERS);
    }

    private String boundsPath(ProbeTarget target) {
        return String.format(Locale.ROOT,
                "/api/v1/places/bounds?west=%.7f&south=%.7f&east=%.7f&north=%.7f&limit=200",
                target.longitude() - BOUNDS_DELTA_DEGREES,
                target.latitude() - BOUNDS_DELTA_DEGREES,
                target.longitude() + BOUNDS_DELTA_DEGREES,
                target.latitude() + BOUNDS_DELTA_DEGREES);
    }

    private URI resolve(URI baseUrl, String path) {
        String root = baseUrl.toString().replaceAll("/+$", "");
        return URI.create(root + path);
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    @FunctionalInterface
    private interface BodyValidator {
        String validate(JsonNode body);
    }

    private record Observation(double elapsedMs, boolean passed, String failure) {}

    public record ProbeTarget(
            UUID placeId,
            String name,
            String category,
            double latitude,
            double longitude
    ) {
        void validate() {
            if (placeId == null || name == null || name.isBlank()
                    || category == null || category.isBlank()
                    || !Double.isFinite(latitude) || latitude < -90 || latitude > 90
                    || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("HTTP probe target is invalid");
            }
        }
    }

    public record ProbeConfiguration(
            URI baseUrl,
            URI healthBaseUrl,
            UUID baselinePlaceId,
            int sampleCount,
            Duration timeout
    ) {
        void validate() {
            if (!isCredentialFreeLoopbackEndpoint(baseUrl)
                    || !isCredentialFreeLoopbackEndpoint(healthBaseUrl)
                    || baselinePlaceId == null) {
                throw new IllegalArgumentException(
                        "canary probe URLs must be credential-free loopback HTTP(S) endpoints");
            }
            if (sampleCount < 1 || sampleCount > 20) {
                throw new IllegalArgumentException("canary probe samples must be between 1 and 20");
            }
            if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException(
                        "canary probe timeout must be between 1 and 30 seconds");
            }
        }

        private boolean isCredentialFreeLoopbackEndpoint(URI value) {
            String scheme = value == null ? null : value.getScheme();
            String host = value == null ? null : value.getHost();
            boolean loopbackHost = host != null && Set.of(
                    "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1"
            ).contains(host.toLowerCase(Locale.ROOT));
            return scheme != null && (scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))
                    && loopbackHost && value.getUserInfo() == null
                    && value.getRawQuery() == null && value.getRawFragment() == null;
        }
    }

    public record SurfaceResult(
            int sampleCount,
            int errorCount,
            double averageMs,
            double p95Ms,
            boolean passed,
            List<String> failures
    ) {
        static SurfaceResult from(List<Observation> observations) {
            List<Double> latencies = observations.stream()
                    .map(Observation::elapsedMs).sorted(Comparator.naturalOrder()).toList();
            double average = latencies.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0.0);
            int p95Index = Math.max(0,
                    (int) Math.ceil(latencies.size() * 0.95) - 1);
            double p95 = latencies.isEmpty() ? 0.0 : latencies.get(p95Index);
            List<String> failures = observations.stream()
                    .filter(value -> !value.passed()).map(Observation::failure)
                    .filter(value -> value != null).distinct().limit(10).toList();
            int errors = (int) observations.stream().filter(value -> !value.passed()).count();
            return new SurfaceResult(observations.size(), errors, average, p95,
                    errors == 0, failures);
        }

        ObjectNode toJson() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("sample_count", sampleCount);
            value.put("error_count", errorCount);
            value.put("average_ms", averageMs);
            value.put("p95_ms", p95Ms);
            value.put("passed", passed);
            ArrayNode errors = value.putArray("failures");
            failures.forEach(errors::add);
            return value;
        }
    }

    public record ProbeSuite(
            Map<String, SurfaceResult> surfaces,
            int requestCount,
            int errorCount,
            boolean passed
    ) {
        static ProbeSuite from(Map<String, SurfaceResult> surfaces) {
            int requests = surfaces.values().stream()
                    .mapToInt(SurfaceResult::sampleCount).sum();
            int errors = surfaces.values().stream().mapToInt(SurfaceResult::errorCount).sum();
            return new ProbeSuite(Map.copyOf(surfaces), requests, errors,
                    requests > 0 && errors == 0);
        }

        public SurfaceResult surface(String name) {
            SurfaceResult result = surfaces.get(name);
            if (result == null) throw new IllegalArgumentException("unknown probe surface: " + name);
            return result;
        }

        public double errorRate() {
            return requestCount == 0 ? 1.0 : (double) errorCount / requestCount;
        }

        public ObjectNode toJson() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("request_count", requestCount);
            value.put("error_count", errorCount);
            value.put("error_rate", errorRate());
            value.put("passed", passed);
            ObjectNode surfaceJson = value.putObject("surfaces");
            surfaces.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> surfaceJson.set(entry.getKey(), entry.getValue().toJson()));
            return value;
        }
    }
}

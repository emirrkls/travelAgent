package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

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

    @Autowired
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
        surfaces.put("health", probeRepeated("health", configuration, configuration.healthBaseUrl(),
                "/health",
                configuration.sampleCount(), this::validHealth));
        surfaces.put("search", probeRepeated("search", configuration, searchPath(primary),
                configuration.sampleCount(), body -> validSearch(body, null)));
        surfaces.put("map_nearby", probeRepeated("map_nearby", configuration, nearbyPath(primary),
                configuration.sampleCount(), body -> validNearby(body, primary, null)));
        surfaces.put("map_bounds", probeRepeated("map_bounds", configuration, boundsPath(primary),
                configuration.sampleCount(), body -> validBounds(body, primary, null)));
        surfaces.put("place_detail", probeRepeated("place_detail", configuration,
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
        surfaces.put("health", probeRepeated("health", configuration, configuration.healthBaseUrl(),
                "/health",
                configuration.sampleCount(), this::validHealth));
        surfaces.put("search", probeRepeated("search", configuration, searchPath(primary),
                configuration.sampleCount(), body -> validSearch(body, primary)));
        surfaces.put("map_nearby", probeRepeated("map_nearby", configuration, nearbyPath(primary),
                configuration.sampleCount(), body -> validNearby(body, primary, primary)));
        surfaces.put("map_bounds", probeRepeated("map_bounds", configuration, boundsPath(primary),
                configuration.sampleCount(), body -> validBounds(body, primary, primary)));
        surfaces.put("place_detail", probeDetails(configuration, List.of(primary)));
        surfaces.put("search_coverage", probeCoverage(selectedTargets, (target, index) ->
                request("search_coverage", index, configuration, searchPath(target), body -> validSearch(body, target))));
        surfaces.put("map_nearby_coverage", probeCoverage(selectedTargets, (target, index) ->
                request("map_nearby_coverage", index, configuration, nearbyPath(target),
                        body -> validNearby(body, target, target))));
        surfaces.put("map_bounds_coverage", probeCoverage(selectedTargets, (target, index) ->
                request("map_bounds_coverage", index, configuration, boundsPath(target),
                        body -> validBounds(body, target, target))));
        surfaces.put("place_detail_coverage", probeCoverage(selectedTargets, (target, index) ->
                request("place_detail_coverage", index, configuration, "/api/v1/places/" + target.placeId(),
                        body -> validDetail(body, target))));
        return ProbeSuite.from(surfaces);
    }

    private SurfaceResult probeCoverage(
            List<ProbeTarget> targets,
            BiFunction<ProbeTarget, Integer, ProbeDiagnostic> probe
    ) {
        List<ProbeDiagnostic> observations = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            observations.add(probe.apply(targets.get(index), index + 1));
        }
        return SurfaceResult.from(observations);
    }

    private SurfaceResult probeDetails(
            ProbeConfiguration configuration,
            List<ProbeTarget> targets
    ) {
        List<ProbeDiagnostic> observations = new ArrayList<>();
        for (int index = 0; index < configuration.sampleCount(); index++) {
            ProbeTarget target = targets.get(index % targets.size());
            observations.add(request("place_detail", index + 1, configuration,
                    "/api/v1/places/" + target.placeId(),
                    body -> validDetail(body, target)));
        }
        return SurfaceResult.from(observations);
    }

    private SurfaceResult probeRepeated(
            String surface,
            ProbeConfiguration configuration,
            String path,
            int count,
            BodyValidator validator
    ) {
        return probeRepeated(surface, configuration, configuration.baseUrl(), path, count, validator);
    }

    private SurfaceResult probeRepeated(
            String surface,
            ProbeConfiguration configuration,
            URI baseUrl,
            String path,
            int count,
            BodyValidator validator
    ) {
        List<ProbeDiagnostic> observations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            observations.add(request(surface, index + 1, configuration, baseUrl, path, validator));
        }
        return SurfaceResult.from(observations);
    }

    private ProbeDiagnostic request(
            String surface,
            int sampleIndex,
            ProbeConfiguration configuration,
            String path,
            BodyValidator validator
    ) {
        return request(surface, sampleIndex, configuration, configuration.baseUrl(), path, validator);
    }

    private ProbeDiagnostic request(
            String surface,
            int sampleIndex,
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
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        Integer status = null;
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            status = response.statusCode();
            if (status < 200 || status >= 300) {
                return diagnostic(surface, sampleIndex, startedAt, elapsedMs, status,
                        false, "RESPONSE_RECEIVED", "NOT_EVALUATED", "HTTP_" + status);
            }
            JsonNode body;
            try {
                body = objectMapper.readTree(response.body());
            } catch (IOException invalidJson) {
                return diagnostic(surface, sampleIndex, startedAt, elapsedMs, status,
                        false, "RESPONSE_RECEIVED", "INVALID_JSON", "INVALID_JSON");
            }
            if (body == null) return diagnostic(surface, sampleIndex, startedAt, elapsedMs, status,
                    false, "RESPONSE_RECEIVED", "EMPTY_RESPONSE", "EMPTY_RESPONSE");
            String validationError = validator.validate(body);
            return diagnostic(surface, sampleIndex, startedAt, elapsedMs, status,
                    false, "RESPONSE_RECEIVED", validationError == null ? "VALID" : validationError,
                    validationError);
        } catch (HttpTimeoutException timeout) {
            return diagnostic(surface, sampleIndex, startedAt, elapsed(started), status, true,
                    "TIMEOUT", "NOT_EVALUATED", timeout instanceof HttpConnectTimeoutException
                            ? "CONNECT_TIMEOUT" : "HTTP_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return diagnostic(surface, sampleIndex, startedAt, elapsed(started), status, false,
                    "INTERRUPTED", "NOT_EVALUATED", "INTERRUPTED");
        } catch (IOException failure) {
            return diagnostic(surface, sampleIndex, startedAt, elapsed(started), status, false,
                    "IO_ERROR", "NOT_EVALUATED", "TRANSPORT_ERROR");
        } catch (RuntimeException failure) {
            return diagnostic(surface, sampleIndex, startedAt, elapsed(started), status, false,
                    status == null ? "CLIENT_ERROR" : "RESPONSE_RECEIVED",
                    "NOT_EVALUATED", status == null ? "CLIENT_ERROR" : "VALIDATION_ERROR");
        }
    }

    private double elapsed(long started) { return (System.nanoTime() - started) / 1_000_000.0; }

    private ProbeDiagnostic diagnostic(String surface, int index, Instant started, double duration,
            Integer status, boolean timeout, String transport, String validation, String failure) {
        // Closed templates: never serialize URI/query/headers/body/exception text.
        return new ProbeDiagnostic(surface, index, "GET", pathTemplate(surface), started, duration,
                status, timeout, transport, validation, failure);
    }

    static String pathTemplate(String surface) {
        return switch (surface) {
            case "health" -> "{management}/health";
            case "search", "search_coverage" -> "/api/v1/places?search={query}&page=0&size=100&sort=name,asc";
            case "map_nearby", "map_nearby_coverage" -> "/api/v1/places/nearby?lat={lat}&lon={lon}&radiusMeters=250&limit=200";
            case "map_bounds", "map_bounds_coverage" -> "/api/v1/places/bounds?west={west}&south={south}&east={east}&north={north}&limit=200";
            case "place_detail", "place_detail_coverage" -> "/api/v1/places/{canonical_uuid}";
            default -> throw new IllegalArgumentException("unknown probe surface");
        };
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

    @FunctionalInterface
    private interface BodyValidator {
        String validate(JsonNode body);
    }

    public record ProbeDiagnostic(String surface, int sampleIndex, String method, String pathTemplate,
            Instant startedAt, double durationMs, Integer httpStatus, boolean timeout,
            String transportResult, String responseValidation, String failureCategory) {
        public boolean passed() { return failureCategory == null; }
        ObjectNode toJson() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("surface", surface).put("sample_index", sampleIndex).put("method", method)
                    .put("path_template", pathTemplate).put("started_at", startedAt.toString())
                    .put("duration_ms", durationMs).put("timeout", timeout)
                    .put("transport_result", transportResult).put("response_validation", responseValidation);
            if (httpStatus == null) value.putNull("http_status"); else value.put("http_status", httpStatus);
            if (failureCategory == null) value.putNull("failure_category"); else value.put("failure_category", failureCategory);
            return value;
        }
    }

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
            List<String> failures,
            List<ProbeDiagnostic> observations
    ) {
        public SurfaceResult { failures = List.copyOf(failures); observations = List.copyOf(observations); }
        public SurfaceResult(int sampleCount, int errorCount, double averageMs, double p95Ms,
                boolean passed, List<String> failures) {
            this(sampleCount, errorCount, averageMs, p95Ms, passed, failures, List.of());
        }
        static SurfaceResult from(List<ProbeDiagnostic> observations) {
            List<Double> latencies = observations.stream()
                    .map(ProbeDiagnostic::durationMs).sorted(Comparator.naturalOrder()).toList();
            double average = latencies.stream().mapToDouble(Double::doubleValue)
                    .average().orElse(0.0);
            int p95Index = Math.max(0,
                    (int) Math.ceil(latencies.size() * 0.95) - 1);
            double p95 = latencies.isEmpty() ? 0.0 : latencies.get(p95Index);
            List<String> failures = observations.stream()
                    .filter(value -> !value.passed()).map(ProbeDiagnostic::failureCategory)
                    .filter(value -> value != null).distinct().limit(10).toList();
            int errors = (int) observations.stream().filter(value -> !value.passed()).count();
            return new SurfaceResult(observations.size(), errors, average, p95,
                    !observations.isEmpty() && errors == 0, failures, observations);
        }

        ObjectNode toJson() {
            ObjectNode value = JsonNodeFactory.instance.objectNode();
            value.put("sample_count", sampleCount);
            value.put("success_count", sampleCount - errorCount);
            value.put("failure_count", errorCount);
            value.put("timeout_count", observations.stream().filter(ProbeDiagnostic::timeout).count());
            ObjectNode distribution = value.putObject("status_distribution");
            observations.forEach(probe -> {
                String key = probe.httpStatus() == null ? "NO_RESPONSE" : probe.httpStatus().toString();
                distribution.put(key, distribution.path(key).asInt() + 1);
            });
            List<Double> latencies = observations.stream().map(ProbeDiagnostic::durationMs).sorted().toList();
            value.put("min_ms", latencies.isEmpty() ? averageMs : latencies.getFirst());
            int n = latencies.size();
            value.put("median_ms", n == 0 ? averageMs : n % 2 == 1 ? latencies.get(n / 2)
                    : (latencies.get(n / 2 - 1) + latencies.get(n / 2)) / 2.0);
            value.put("max_ms", latencies.isEmpty() ? p95Ms : latencies.getLast());
            value.put("surface_health", passed ? "PASS" : "FAIL");
            if (failures.isEmpty()) value.putNull("first_failure_category");
            else value.put("first_failure_category", failures.getFirst());
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
        public ProbeSuite { surfaces = Map.copyOf(surfaces); }
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

        public ObjectNode toDiagnosticJson() {
            ObjectNode value = toJson();
            value.put("overall", passed ? "PASS" : "FAIL");
            ArrayNode failing = value.putArray("failing_surfaces");
            surfaces.entrySet().stream().filter(entry -> !entry.getValue().passed())
                    .sorted(Map.Entry.comparingByKey()).forEach(entry -> failing.add(entry.getKey()));
            surfaces.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                ArrayNode samples = ((ObjectNode)value.path("surfaces").path(entry.getKey())).putArray("probes");
                // Every baseline sample is retained (maximum 20). Coverage summaries always count
                // all selected Places; cap only optional detailed coverage output, never gate totals.
                entry.getValue().observations().stream().limit(20).forEach(probe -> samples.add(probe.toJson()));
            });
            return value;
        }
    }
}

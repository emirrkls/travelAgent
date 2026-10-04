package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotV3Policy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import static com.emirrkls.phokarta.backend.operations.PlacePilotPrivateArtifacts.require;

/** Small external GET-only probe. No Spring, database, Docker, importer, scheduler or write credentials. */
public final class PlacePilotPersistentProbe {
    public static final String ROUTE = "PRIVATE_PERSISTENT_CONTAINER_NETNS";
    public static final String ORIGIN = "http://127.0.0.1:8080";
    public static final String HEALTH_ORIGIN = "http://127.0.0.1:8081";
    private static final int BODY_LIMIT = 4 * 1024 * 1024;
    private static final Set<String> SUMMARY = Set.of("id", "name", "category", "coverImage", "city", "region",
            "country", "latitude", "longitude", "priceLevel", "averageScore", "ratingCount");
    private static final Set<String> DETAIL = Set.of("id", "name", "description", "category", "subcategories",
            "latitude", "longitude", "city", "region", "country", "address", "coverImage", "photos",
            "priceLevel", "averageScore", "ratingCount", "dimensionScores", "recentPublicReviews");
    private static final Set<String> CATEGORIES = Set.of("BEACH", "RESTAURANT", "CAFE", "HOTEL", "BAR",
            "NIGHTLIFE", "ATTRACTION", "ACTIVITY", "NATURE");
    private final ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final URI origin, management;
    private final java.util.function.BooleanSupplier stopped;

    public PlacePilotPersistentProbe() { this(() -> false); }
    public PlacePilotPersistentProbe(java.util.function.BooleanSupplier stopped) {
        this(URI.create(ORIGIN), URI.create(HEALTH_ORIGIN), stopped);
    }
    // Package-private, loopback mock server only in isolated tests. CLI has no route override.
    PlacePilotPersistentProbe(URI origin, URI management) { this(origin, management, () -> false); }
    PlacePilotPersistentProbe(URI origin, URI management, java.util.function.BooleanSupplier stopped) {
        this.origin = origin; this.management = management; this.stopped = stopped;
    }

    public ObjectNode capture(UUID run, String hash, String role, JsonNode target) throws IOException {
        require(run != null && !run.equals(PlacePilotV3Policy.CONTAINED_RUN));
        require(hash != null && hash.matches("[0-9a-f]{64}") && Set.of("PRE", "POST").contains(role));
        require(ROUTE.equals(target.path("route_id").asText()) && ORIGIN.equals(target.path("origin").asText()));
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("version", "persistent-pilot-v3-v1").put("run_id", run.toString())
                .put("manifest_hash", hash).put("role", role).put("started_at", Instant.now().toString());
        snapshot.set("target", target.deepCopy());
        ArrayNode checks = snapshot.putArray("health_checks");
        ArrayNode records = snapshot.putArray("requests");
        try {
            double processStart = health(checks);
            snapshot.put("process_start_time_seconds", processStart);
            for (String surface : PlacePilotV3Policy.SURFACES) {
                for (int sample = 0; sample <= 20; sample++) {
                    require(!stopped.getAsBoolean());
                    ObjectNode record = request(origin, PlacePilotV3Policy.PATHS.get(surface), surface, sample == 0);
                    records.add(record);
                    require("VALID".equals(record.path("validation").asText()));
                }
            }
            require(Double.compare(processStart, health(checks)) == 0);
            snapshot.put("outcome", "COMPLETE");
        } catch (IOException | RuntimeException failed) {
            snapshot.put("outcome", "HARD_FAILURE");
            // Sanitized individual failing record stays present; no retries, no raw body/exception text.
        }
        snapshot.put("completed_at", Instant.now().toString());
        return snapshot;
    }

    private double health(ArrayNode checks) throws IOException {
        for (String path : List.of("/actuator/health/liveness", "/actuator/health/readiness")) {
            require(!stopped.getAsBoolean());
            ObjectNode record = request(management, path, "health", false); checks.add(record);
            require("VALID".equals(record.path("validation").asText()));
        }
        require(!stopped.getAsBoolean());
        ObjectNode record = request(management, "/actuator/prometheus", "process", false); checks.add(record);
        require("VALID".equals(record.path("validation").asText()));
        return record.path("process_start_time_seconds").doubleValue();
    }

    private ObjectNode request(URI base, String path, String surface, boolean prep) {
        ObjectNode record = mapper.createObjectNode();
        record.put("surface", surface).put("path", path).put("preconditioning", prep)
                .put("utc_start", Instant.now().toString()).put("validation", "NOT_EVALUATED")
                .putNull("status");
        record.putNull("headers_ms").putNull("body_ms");
        record.put("response_bytes", 0).put("result_count", 0).putArray("canonical_ids");
        record.put("id_digest", PlacePilotV3Policy.sha256(""));
        long started = System.nanoTime();
        var headersNanos = new java.util.concurrent.atomic.AtomicLong(-1);
        var responseStatus = new java.util.concurrent.atomic.AtomicInteger(-1);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(base.resolve(path)).GET().timeout(Duration.ofSeconds(5))
                    .header("Accept", surface.equals("process") ? "text/plain" : "application/json").build();
            pending = client.sendAsync(request, info -> {
                headersNanos.set(System.nanoTime()); responseStatus.set(info.statusCode());
                return new BoundedBody(BODY_LIMIT);
            });
            long remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            var response = pending.get(remaining, TimeUnit.NANOSECONDS); // One deadline from start, including request setup + full body.
            long completed = System.nanoTime();
            record.put("body_ms", (completed - started) / 1_000_000.0);
            record.put("response_bytes", response.body().length);
            require(response.statusCode() == 200 && response.body().length > 0);
            if (surface.equals("process")) {
                var matcher = Pattern.compile("(?m)^process_start_time_seconds(?:\\{[^\\r\\n]*})? ([0-9.eE+\\-]+)\\s*$")
                        .matcher(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
                require(matcher.find()); double start = Double.parseDouble(matcher.group(1));
                require(Double.isFinite(start) && start > 0 && !matcher.find());
                record.put("process_start_time_seconds", start);
            } else {
                JsonNode body = mapper.readTree(response.body());
                if (surface.equals("health")) require("UP".equals(body.path("status").asText()));
                else {
                    TreeSet<String> ids = validateBody(surface, body);
                    record.put("result_count", ids.size()).put("id_digest", PlacePilotV3Policy.sha256(String.join("\n", ids)));
                    var values = record.putArray("canonical_ids"); ids.forEach(values::add);
                }
            }
            require((System.nanoTime() - started) < TimeUnit.SECONDS.toNanos(5));
            record.put("validation", "VALID");
        } catch (TimeoutException | java.net.http.HttpTimeoutException failed) {
            record.put("validation", "TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); record.put("validation", "INTERRUPTED");
        } catch (Exception failed) {
            record.put("validation", rootCause(failed) instanceof java.net.http.HttpTimeoutException ? "TIMEOUT" : "INVALID");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (responseStatus.get() >= 0) record.put("status", responseStatus.get());
            if (headersNanos.get() >= 0) record.put("headers_ms", (headersNanos.get() - started) / 1_000_000.0);
            record.put("latency_ms", (System.nanoTime() - started) / 1_000_000.0);
            if (record.path("latency_ms").asDouble() >= 5000) record.put("validation", "TIMEOUT");
        }
        return record;
    }

    private static Throwable rootCause(Throwable value) {
        while (value.getCause() != null) value = value.getCause(); return value;
    }

    static TreeSet<String> validateBody(String surface, JsonNode body) throws IOException {
        JsonNode rows = body;
        if (surface.equals("search")) {
            PlacePilotPrivateArtifacts.fields(body, Set.of("content", "page", "size", "totalElements", "totalPages", "hasNext"));
            require(body.path("page").isIntegralNumber() && body.path("page").asInt() == 0
                    && body.path("size").isIntegralNumber() && body.path("size").asInt() == 100
                    && body.path("totalElements").isIntegralNumber() && body.path("totalPages").isIntegralNumber()
                    && body.path("hasNext").isBoolean());
            rows = body.path("content");
        } else if (surface.equals("detail")) {
            require(body.isObject()); rows = new ObjectMapper().createArrayNode().add(body);
        }
        require(rows.isArray() && !rows.isEmpty() && rows.size() <= (surface.equals("detail") ? 1 : surface.equals("search") ? 100 : 200));
        TreeSet<String> ids = new TreeSet<>(); double previousDistance = -1;
        for (JsonNode row : rows) {
            JsonNode place = row;
            if (surface.equals("nearby")) {
                PlacePilotPrivateArtifacts.fields(row, Set.of("place", "distanceMeters")); place = row.path("place");
            }
            PlacePilotPrivateArtifacts.fields(place, surface.equals("detail") ? DETAIL : SUMMARY);
            String id = place.path("id").asText();
            require(place.path("id").isTextual() && UUID.fromString(id).toString().equals(id) && ids.add(id));
            require(place.path("name").isTextual() && !place.path("name").asText().isBlank()
                    && CATEGORIES.contains(place.path("category").asText()));
            double lat = number(place, "latitude"), lon = number(place, "longitude");
            require(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180);
            require(place.path("priceLevel").isIntegralNumber() && place.path("ratingCount").isIntegralNumber()
                    && place.path("ratingCount").asLong() >= 0);
            require(place.path("averageScore").isNull() || (Double.isFinite(number(place, "averageScore"))
                    && place.path("averageScore").asDouble() >= 1 && place.path("averageScore").asDouble() <= 10));
            if (id.equals(PlacePilotV3Policy.SENTINEL)) {
                require(place.path("name").asText().equals("Staging Harbor Cafe") && place.path("category").asText().equals("CAFE")
                        && Math.abs(lat - 41.022) <= 1e-6 && Math.abs(lon - 28.9784) <= 1e-6);
            }
            if (surface.equals("bounds")) require(lat >= 41.012 && lat <= 41.032 && lon >= 28.9684 && lon <= 28.9884);
            if (surface.equals("search")) require(java.text.Normalizer.normalize(place.path("name").asText(), java.text.Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).contains("staging harbor cafe"));
            if (surface.equals("nearby")) {
                double distance = number(row, "distanceMeters"), actual = distance(lat, lon);
                require(distance >= 0 && distance <= 251 && actual <= 252 && Math.abs(actual - distance) <= 5
                        && distance + .01 >= previousDistance); previousDistance = distance;
            }
            if (surface.equals("detail")) {
                require(place.path("subcategories").isArray() && place.path("photos").isArray()
                        && place.path("dimensionScores").isArray() && place.path("recentPublicReviews").isArray());
                // Public nested DTOs may contain user review fields, but never provider IDs/source metadata.
                noProviderKeys(place);
            }
        }
        require(ids.contains(PlacePilotV3Policy.SENTINEL));
        if (surface.equals("search")) require(body.path("totalElements").asLong() >= ids.size()
                && body.path("totalPages").asInt() >= 1);
        return ids;
    }
    private static void noProviderKeys(JsonNode value) throws IOException {
        if (value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next(); String key = field.getKey().toLowerCase(Locale.ROOT).replace("_", "");
                require(!key.contains("provider") && !key.contains("externalid") && !key.contains("sourcerecord")
                        && !key.contains("provenance") && !key.contains("fsq") && !key.contains("overture"));
                noProviderKeys(field.getValue());
            }
        } else if (value.isArray()) for (var node : value) noProviderKeys(node);
    }
    private static double number(JsonNode node, String key) throws IOException {
        require(node.path(key).isNumber() && Double.isFinite(node.path(key).doubleValue())); return node.path(key).doubleValue();
    }
    private static double distance(double lat, double lon) {
        double a = Math.pow(Math.sin(Math.toRadians(lat - 41.022) / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(41.022))
                * Math.pow(Math.sin(Math.toRadians(lon - 28.9784) / 2), 2);
        return 6371008.8 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Bounded subscriber; rejecting oversized bodies cancels transport immediately. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final int limit; private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (bytes.size() + chunk.remaining() > limit) {
                    subscription.cancel(); body.completeExceptionally(new IOException("BODY_LIMIT")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}

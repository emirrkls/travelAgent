package com.emirrkls.phokarta.backend.operations;

import com.emirrkls.phokarta.backend.service.PlacePilotV3Policy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PlacePilotPersistentProbeTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final UUID RUN = UUID.fromString("10000000-0000-4000-8000-000000000099");
    static final String HASH = "a".repeat(64);
    static final double MOCK_PROCESS_START = Instant.now().minusSeconds(1800).getEpochSecond();

    static ObjectNode target() {
        return MAPPER.createObjectNode().put("kind", "LONG_LIVED_PERSISTENT")
                .put("origin", PlacePilotPersistentProbe.ORIGIN).put("route_id", PlacePilotPersistentProbe.ROUTE)
                .put("container_id", "1".repeat(64)).put("image_sha", "sha256:" + "2".repeat(64))
                .put("java_identity", "7:123456").put("container_started_at", Instant.now().minusSeconds(3600).toString())
                .put("restart_count", 0).put("oom", false).put("backend_healthy", true)
                .put("database_healthy", true).put("caddy_running", true)
                .put("detail_observability_enabled", true).put("detail_slow_threshold_ms", 350);
    }
    static ObjectNode place(boolean detail) {
        var value = MAPPER.createObjectNode().put("id", PlacePilotV3Policy.SENTINEL).put("name", "Staging Harbor Cafe")
                .put("category", "CAFE").putNull("coverImage").put("city", "Istanbul").putNull("region")
                .put("country", "TR").put("latitude", 41.022).put("longitude", 28.9784)
                .put("priceLevel", 2).putNull("averageScore").put("ratingCount", 0);
        if (detail) {
            value.putNull("description").putNull("address"); value.putArray("subcategories"); value.putArray("photos");
            value.putArray("dimensionScores"); value.putArray("recentPublicReviews");
        }
        return value;
    }
    static ObjectNode search() {
        var value = MAPPER.createObjectNode().put("page", 0).put("size", 100).put("totalElements", 1)
                .put("totalPages", 1).put("hasNext", false); value.putArray("content").add(place(false)); return value;
    }

    static final class Server implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger gets = new AtomicInteger(), mutation = new AtomicInteger();
        String failPath; int status = 200; boolean slowBody, wrongBody, wrongProcess;
        final double processStart = MOCK_PROCESS_START;
        Server() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                if (!exchange.getRequestMethod().equals("GET")) mutation.incrementAndGet(); else gets.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                String body;
                if (path.contains("/health/")) body = "{\"status\":\"UP\"}";
                else if (path.equals("/actuator/prometheus")) body = "process_start_time_seconds " +
                        (wrongProcess && gets.get() > 80 ? processStart + 10 : processStart) + "\n";
                else if (path.endsWith("/nearby")) body = MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                        .set("place", place(false))).toString().replace("}}]", "},\"distanceMeters\":0}]");
                else if (path.endsWith("/bounds")) body = MAPPER.createArrayNode().add(place(false)).toString();
                else if (path.endsWith(PlacePilotV3Policy.SENTINEL)) body = place(true).toString();
                else body = search().toString();
                boolean fail = path.equals(failPath);
                if (fail && wrongBody) body = "{\"private_response_not_for_artifacts\":true}";
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(fail ? status : 200, bytes.length);
                try {
                    if (fail && slowBody) Thread.sleep(5200);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException disconnected) { /* intentional hard deadline test */ }
                finally { exchange.close(); }
            }); server.start();
        }
        PlacePilotPersistentProbe probe() {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            return new PlacePilotPersistentProbe(origin, origin);
        }
        public void close() { server.stop(0); }
    }

    @Test void exactly84OrderedRequestsPlusSeparateHealthEvidenceEachSnapshotNoMutations() throws Exception {
        try (Server server = new Server()) {
            var target = target(); var pre = server.probe().capture(RUN, HASH, "PRE", target);
            var post = server.probe().capture(RUN, HASH, "POST", target);
            assertThat(pre.path("outcome").asText()).isEqualTo("COMPLETE");
            assertThat(pre.path("requests").size()).isEqualTo(84);
            assertThat(post.path("requests").size()).isEqualTo(84);
            assertThat(pre.path("health_checks").size()).isEqualTo(6);
            assertThat(server.gets.get()).isEqualTo(180); assertThat(server.mutation.get()).isZero();
            PlacePilotV3Policy.verify(RUN, HASH, pre, post);
            int index = 0;
            for (String surface : PlacePilotV3Policy.SURFACES) for (int sample = 0; sample <= 20; sample++) {
                var record = pre.path("requests").get(index++);
                assertThat(record.path("surface").asText()).isEqualTo(surface);
                assertThat(record.path("preconditioning").asBoolean()).isEqualTo(sample == 0);
                assertThat(record.path("headers_ms").asDouble()).isLessThanOrEqualTo(record.path("body_ms").asDouble());
                assertThat(record.path("validation").asText()).isEqualTo("VALID");
                assertThat(record.has("name") || record.has("raw_body")).isFalse();
            }
        }
    }
    @Test void fiveSecondDeadlineCoversSlowBodyAfterHeadersFirstFailureRetainedNoRetry() throws Exception {
        try (Server server = new Server()) {
            server.failPath = "/api/v1/places"; server.slowBody = true;
            long start = System.nanoTime(); var pre = server.probe().capture(RUN, HASH, "PRE", target());
            assertThat((System.nanoTime() - start) / 1e9).isLessThan(6.2);
            assertThat(pre.path("outcome").asText()).isEqualTo("HARD_FAILURE");
            assertThat(pre.path("requests").size()).isEqualTo(1);
            var record = pre.path("requests").get(0);
            assertThat(record.path("validation").asText()).isEqualTo("TIMEOUT");
            assertThat(record.path("status").asInt()).isEqualTo(200);
            assertThat(record.path("headers_ms").isNumber()).isTrue();
            assertThat(record.path("body_ms").isNull()).isTrue();
            assertThat(server.gets.get()).isEqualTo(4);
        }
    }
    @ParameterizedTest @ValueSource(ints = {301, 401, 404, 500, 503})
    void failedStatusStopsOnFirstRequestAndCannotFollowRedirectOrRetry(int status) throws Exception {
        try (Server server = new Server()) {
            server.failPath = "/api/v1/places"; server.status = status;
            var pre = server.probe().capture(RUN, HASH, "PRE", target());
            assertThat(pre.path("outcome").asText()).isEqualTo("HARD_FAILURE");
            assertThat(pre.path("requests").size()).isEqualTo(1);
            assertThat(pre.path("requests").get(0).path("status").asInt()).isEqualTo(status);
            assertThat(server.gets.get()).isEqualTo(4);
        }
    }
    @Test void invalidResponsePreservedOnlyAsSanitizedFailure() throws Exception {
        try (Server server = new Server()) {
            server.failPath = "/api/v1/places"; server.wrongBody = true;
            var pre = server.probe().capture(RUN, HASH, "PRE", target());
            assertThat(pre.path("outcome").asText()).isEqualTo("HARD_FAILURE");
            assertThat(pre.toString()).doesNotContain("private_response_not_for_artifacts");
        }
    }
    @Test void processRestartMeasuredAtActualManagementEndpointIsHardFailure() throws Exception {
        try (Server server = new Server()) {
            server.wrongProcess = true;
            var pre = server.probe().capture(RUN, HASH, "PRE", target());
            assertThat(pre.path("outcome").asText()).isEqualTo("HARD_FAILURE");
            assertThat(pre.path("requests").size()).isEqualTo(84);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"name", "category", "id", "latitude", "providerId", "external_id"})
    void wrongCanonicalShapeOrProviderMetadataFails(String field) throws Exception {
        var place = place(true); place.put(field, "not-approved");
        assertThatThrownBy(() -> PlacePilotPersistentProbe.validateBody("detail", place)).isInstanceOf(Exception.class);
    }
    @Test void mapBoundsRadiusOrderingAndLimitsAreActualSemanticChecks() throws Exception {
        var bounds = MAPPER.createArrayNode().add(place(false));
        ((ObjectNode) bounds.get(0)).put("latitude", 41.05);
        assertThatThrownBy(() -> PlacePilotPersistentProbe.validateBody("bounds", bounds)).isInstanceOf(Exception.class);
        var nearby = MAPPER.createArrayNode(); var row = MAPPER.createObjectNode().put("distanceMeters", 400); row.set("place", place(false)); nearby.add(row);
        assertThatThrownBy(() -> PlacePilotPersistentProbe.validateBody("nearby", nearby)).isInstanceOf(Exception.class);
        var tooMany = MAPPER.createArrayNode(); for (int i = 0; i < 201; i++) tooMany.add(place(false));
        assertThatThrownBy(() -> PlacePilotPersistentProbe.validateBody("bounds", tooMany)).isInstanceOf(Exception.class);
        var duplicate = MAPPER.createArrayNode().add(place(false)).add(place(false));
        assertThatThrownBy(() -> PlacePilotPersistentProbe.validateBody("bounds", duplicate)).isInstanceOf(Exception.class);
    }
    @Test void privateLauncherMissingOrUnsafeOptionsFailWithoutNetworkOrSpring() {
        assertThat(PlacePilotPersistentTelemetryApplication.execute(new String[0])).isEqualTo(1);
        assertThat(PlacePilotPersistentTelemetryApplication.execute(new String[]{"--base-url=http://wrong", "--datasource=invalid"})).isEqualTo(1);
    }
    @Test void safetyStopMarkerPreventsFurtherGetSchedulingWithoutTouchingBackend() throws Exception {
        try (Server server = new Server()) {
            URI origin = URI.create("http://127.0.0.1:" + server.server.getAddress().getPort());
            var snapshot = new PlacePilotPersistentProbe(origin, origin, () -> true).capture(RUN, HASH, "PRE", target());
            assertThat(snapshot.path("outcome").asText()).isEqualTo("HARD_FAILURE");
            assertThat(snapshot.path("requests").size()).isZero();
            assertThat(server.gets.get()).isZero(); assertThat(server.mutation.get()).isZero();
        }
    }
}

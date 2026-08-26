package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.service.VisitService;
import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Map companion batch friend metrics: public bounds stay community-only;
 * POST /me/places/friend-metrics returns viewer-relative aggregates only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("dev")
@Testcontainers
class MapFriendsDiscoveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VisitService visitService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.emirrkls.phokarta.backend.service.UgcPolicyService ugcPolicy;

    @Test
    void mutualPublicAndFriendsVisits_userWeightedScore() throws Exception {
        RegisteredUser a = register("mapA");
        RegisteredUser b = register("mapB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Map Batch X", PlaceCategory.RESTAURANT, 28.10, 37.10);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 1), 8.0,
                Visibility.PUBLIC, "b public", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 2), 10.0,
                Visibility.FRIENDS, "b friends", "mem"));

        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricsBody(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].placeId").value(place.toString()))
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(1))
                .andExpect(jsonPath("$[0].friendAverageScore").value(9.0));
    }

    @Test
    void privateVisitExcluded() throws Exception {
        RegisteredUser a = register("mapPrivA");
        RegisteredUser b = register("mapPrivB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Map Private", PlaceCategory.RESTAURANT, 28.11, 37.11);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 3), 10.0,
                Visibility.PRIVATE, "", "secret"));

        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricsBody(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(0))
                .andExpect(jsonPath("$[0].friendAverageScore").doesNotExist());
    }

    @Test
    void oneWayFollowExcluded() throws Exception {
        RegisteredUser a = register("mapOneA");
        RegisteredUser b = register("mapOneB");
        follow(a, b);

        UUID place = insertPlace("Map One Way", PlaceCategory.RESTAURANT, 28.12, 37.12);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 4), 9.0,
                Visibility.PUBLIC, "public", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 5), 10.0,
                Visibility.FRIENDS, "friends", "mem"));

        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricsBody(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(0))
                .andExpect(jsonPath("$[0].friendAverageScore").doesNotExist());
    }

    @Test
    void multipleFriends_userWeightedAverage() throws Exception {
        RegisteredUser a = register("mapMultiA");
        RegisteredUser b = register("mapMultiB");
        RegisteredUser c = register("mapMultiC");
        follow(a, b);
        follow(b, a);
        follow(a, c);
        follow(c, a);

        UUID place = insertPlace("Map Multi Friends", PlaceCategory.RESTAURANT, 28.13, 37.13);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 2, 1), 8.0,
                Visibility.PUBLIC, "b1", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 2, 2), 10.0,
                Visibility.FRIENDS, "b2", "mem"));
        visitService.create(c.id, visit(place, LocalDate.of(2026, 2, 3), 6.0,
                Visibility.PUBLIC, "c1", "mem"));

        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricsBody(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(2))
                .andExpect(jsonPath("$[0].friendAverageScore").value(7.5));
    }

    @Test
    void multiplePlaceIds_mapCorrectly_noCrossAggregation() throws Exception {
        RegisteredUser a = register("mapIdsA");
        RegisteredUser b = register("mapIdsB");
        RegisteredUser c = register("mapIdsC");
        RegisteredUser d = register("mapIdsD");
        follow(a, b);
        follow(b, a);
        follow(a, c);

        UUID x = insertPlace("Map X", PlaceCategory.RESTAURANT, 28.20, 37.20);
        UUID y = insertPlace("Map Y", PlaceCategory.RESTAURANT, 28.21, 37.21);
        UUID z = insertPlace("Map Z", PlaceCategory.RESTAURANT, 28.22, 37.22);
        UUID w = insertPlace("Map W", PlaceCategory.RESTAURANT, 28.23, 37.23);

        visitService.create(b.id, visit(x, LocalDate.of(2026, 3, 1), 8.0,
                Visibility.PUBLIC, "bx", "mem"));
        visitService.create(b.id, visit(x, LocalDate.of(2026, 3, 2), 10.0,
                Visibility.FRIENDS, "bx2", "mem"));
        visitService.create(c.id, visit(y, LocalDate.of(2026, 3, 3), 10.0,
                Visibility.PUBLIC, "cy", "mem"));
        visitService.create(c.id, visit(y, LocalDate.of(2026, 3, 4), 10.0,
                Visibility.FRIENDS, "cy2", "mem"));
        visitService.create(d.id, visit(z, LocalDate.of(2026, 3, 5), 9.0,
                Visibility.PUBLIC, "dz", "mem"));
        visitService.create(b.id, visit(w, LocalDate.of(2026, 3, 6), 10.0,
                Visibility.PRIVATE, "", "secret"));

        JsonNode body = metrics(a, x, y, z, w);
        assertThat(find(body, x).path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(find(body, x).path("friendAverageScore").asDouble()).isEqualTo(9.0);
        assertThat(find(body, y).path("friendsVisitedCount").asLong()).isEqualTo(0);
        assertThat(find(body, y).path("friendAverageScore").isMissingNode()
                || find(body, y).path("friendAverageScore").isNull()).isTrue();
        assertThat(find(body, z).path("friendsVisitedCount").asLong()).isEqualTo(0);
        assertThat(find(body, w).path("friendsVisitedCount").asLong()).isEqualTo(0);

        MvcResult bounds = mockMvc.perform(get("/api/v1/places/bounds")
                        .param("west", "28.0")
                        .param("south", "37.0")
                        .param("east", "28.5")
                        .param("north", "37.5")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode boundsRows = objectMapper.readTree(bounds.getResponse().getContentAsString());
        JsonNode zRow = StreamSupport.stream(boundsRows.spliterator(), false)
                .filter(n -> z.toString().equals(n.path("id").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(zRow.path("averageScore").asDouble()).isEqualTo(9.0);
        assertThat(zRow.has("friendAverageScore")).isFalse();
        assertThat(zRow.has("friendsVisitedCount")).isFalse();
    }

    @Test
    void duplicateInputIds_deduped() throws Exception {
        RegisteredUser a = register("mapDupA");
        RegisteredUser b = register("mapDupB");
        follow(a, b);
        follow(b, a);
        UUID place = insertPlace("Map Dup", PlaceCategory.RESTAURANT, 28.14, 37.14);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 1), 9.0,
                Visibility.PUBLIC, "dup", "mem"));

        String payload = """
                {"placeIds":["%s","%s","%s"]}
                """.formatted(place, place, place);
        MvcResult result = mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(array).hasSize(1);
        assertThat(array.get(0).path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(array.get(0).path("friendAverageScore").asDouble()).isEqualTo(9.0);
    }

    @Test
    void emptyInput_returnsEmptyArray() throws Exception {
        RegisteredUser a = register("mapEmpty");
        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void maxInputBoundary_rejectsOverLimit() throws Exception {
        RegisteredUser a = register("mapMax");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        String payload = "{\"placeIds\":[" + String.join(",", ids.stream()
                .map(id -> "\"" + id + "\"").toList()) + "]}";
        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void communityIndependence_friendsVisitDoesNotChangePublicBounds() throws Exception {
        RegisteredUser a = register("mapComA");
        RegisteredUser b = register("mapComB");
        follow(a, b);
        follow(b, a);
        UUID place = insertPlace("Map Community", PlaceCategory.RESTAURANT, 28.15, 37.15);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 1), 10.0,
                Visibility.FRIENDS, "friends only", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 2), 8.0,
                Visibility.PUBLIC, "public", "mem"));

        mockMvc.perform(get("/api/v1/places/bounds")
                        .param("west", "28.0")
                        .param("south", "37.0")
                        .param("east", "28.5")
                        .param("north", "37.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + place + "')].averageScore").value(8.0))
                .andExpect(jsonPath("$[?(@.id=='" + place + "')].ratingCount").value(1));

        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricsBody(place)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(1))
                .andExpect(jsonPath("$[0].friendAverageScore").value(9.0));
    }

    @Test
    void relationshipLifecycle_mutualThenUnfollow() throws Exception {
        RegisteredUser a = register("mapLifeA");
        RegisteredUser b = register("mapLifeB");
        UUID place = insertPlace("Map Lifecycle", PlaceCategory.RESTAURANT, 28.16, 37.16);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 6, 1), 9.0,
                Visibility.PUBLIC, "historical", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 6, 2), 10.0,
                Visibility.FRIENDS, "historical friends", "mem"));

        follow(a, b);
        JsonNode beforeMutual = metrics(a, place);
        assertThat(beforeMutual.get(0).path("friendsVisitedCount").asLong()).isEqualTo(0);

        follow(b, a);
        JsonNode afterMutual = metrics(a, place);
        assertThat(afterMutual.get(0).path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(afterMutual.get(0).path("friendAverageScore").asDouble()).isEqualTo(9.5);

        mockMvc.perform(delete("/api/v1/users/{id}/follow", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        JsonNode afterUnfollow = metrics(a, place);
        assertThat(afterUnfollow.get(0).path("friendsVisitedCount").asLong()).isEqualTo(0);
        assertThat(afterUnfollow.get(0).path("friendAverageScore").isMissingNode()
                || afterUnfollow.get(0).path("friendAverageScore").isNull()).isTrue();
    }

    @Test
    void oneWayBecomesMutual_refreshShowsSignal() throws Exception {
        RegisteredUser a = register("mapTransA");
        RegisteredUser c = register("mapTransC");
        follow(a, c);
        UUID y = insertPlace("Map Transition Y", PlaceCategory.RESTAURANT, 28.17, 37.17);
        visitService.create(c.id, visit(y, LocalDate.of(2026, 7, 1), 10.0,
                Visibility.PUBLIC, "c public", "mem"));
        visitService.create(c.id, visit(y, LocalDate.of(2026, 7, 2), 10.0,
                Visibility.FRIENDS, "c friends", "mem"));

        assertThat(metrics(a, y).get(0).path("friendsVisitedCount").asLong()).isEqualTo(0);
        follow(c, a);
        assertThat(metrics(a, y).get(0).path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(metrics(a, y).get(0).path("friendAverageScore").asDouble()).isEqualTo(10.0);
    }

    private JsonNode metrics(RegisteredUser user, UUID... placeIds) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(metricsBody(placeIds)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode find(JsonNode array, UUID placeId) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> placeId.toString().equals(n.path("placeId").asText()))
                .findFirst()
                .orElseThrow();
    }

    private String metricsBody(UUID... placeIds) {
        String ids = String.join(",", java.util.Arrays.stream(placeIds)
                .map(id -> "\"" + id + "\"")
                .toList());
        return "{\"placeIds\":[" + ids + "]}";
    }

    private void follow(RegisteredUser follower, RegisteredUser followed) throws Exception {
        mockMvc.perform(post("/api/v1/users/{id}/follow", followed.id)
                        .header("Authorization", "Bearer " + follower.access))
                .andExpect(status().isNoContent());
    }

    private RegisteredUser register(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = prefix + "_" + suffix;
        String email = username + "@example.com";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","displayName":"%s",
                                "password":"SecurePass1"}
                                """.formatted(email, username, prefix)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode session = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(session.get("user").get("id").asText());
        PolicyAcceptanceSupport.acceptCurrent(ugcPolicy, id);
        return new RegisteredUser(
                id,
                session.get("accessToken").asText());
    }

    private UUID insertPlace(String name, PlaceCategory category, double longitude,
                             double latitude) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, ?, 'map friends discovery place', ?, array[]::text[],
                    ST_SetSRID(ST_MakePoint(?, ?), 4326), 'Test City', 'Test Region',
                    'Test Country', 'Test Address', 'https://example.test/cover.jpg',
                    array[]::text[], 2, now(), now()
                )
                """, id, name, category.name(), longitude, latitude);
        return id;
    }

    private CreateVisitRequest visit(UUID placeId, LocalDate visitedAt, double rating,
                                     Visibility visibility, String publicReview,
                                     String privateMemory) {
        return new CreateVisitRequest(placeId, visitedAt, rating,
                List.of(
                        new CreateVisitRequest.DimensionScore("FOOD", 8.0),
                        new CreateVisitRequest.DimensionScore("SERVICE", 8.0),
                        new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0),
                        new CreateVisitRequest.DimensionScore("VALUE", 8.0),
                        new CreateVisitRequest.DimensionScore("PRESENTATION", 8.0)),
                publicReview, privateMemory, List.of(), visibility);
    }

    private record RegisteredUser(UUID id, String access) {
    }
}

package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.service.VisitService;
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
 * Saved places enriched with friend-overlap metrics (mutual + PUBLIC/FRIENDS Visits).
 * Batch aggregation on GET /me/saved-places — no per-place friends-summary N+1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("dev")
@Testcontainers
class SavedFriendsOverlapIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VisitService visitService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void mutualPublicVisitEnrichesSavedPlace() throws Exception {
        RegisteredUser a = register("savA");
        RegisteredUser b = register("savB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Overlap Public", PlaceCategory.RESTAURANT, 32.1, 42.1);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 1), 9.0,
                Visibility.PUBLIC, "b public", "mem"));
        savePlace(a, place);

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].place.id").value(place.toString()))
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.content[0].friendAverageScore").value(9.0));

        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(9.0));
    }

    @Test
    void friendsVisibilityVisitCountsTowardOverlap_communityUnchanged() throws Exception {
        RegisteredUser a = register("friA");
        RegisteredUser b = register("friB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Overlap Friends Vis", PlaceCategory.CAFE, 32.2, 42.2);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 2, 1), 10.0,
                Visibility.FRIENDS, "friends only", "mem"));
        savePlace(a, place);

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.content[0].friendAverageScore").value(10.0))
                .andExpect(jsonPath("$.content[0].place.averageScore").doesNotExist())
                .andExpect(jsonPath("$.content[0].place.ratingCount").value(0));
    }

    @Test
    void privateVisitDoesNotCreateFriendSignal() throws Exception {
        RegisteredUser a = register("privA");
        RegisteredUser b = register("privB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Overlap Private", PlaceCategory.RESTAURANT, 32.3, 42.3);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 3, 1), 10.0,
                Visibility.PRIVATE, "", "secret"));
        savePlace(a, place);

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(0))
                .andExpect(jsonPath("$.content[0].friendAverageScore").doesNotExist());
    }

    @Test
    void oneWayFollowDoesNotCreateFriendSignal() throws Exception {
        RegisteredUser a = register("oneA");
        RegisteredUser b = register("oneB");
        follow(a, b);

        UUID place = insertPlace("Overlap OneWay", PlaceCategory.CAFE, 32.4, 42.4);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 1), 9.0,
                Visibility.PUBLIC, "public", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 2), 10.0,
                Visibility.FRIENDS, "friends", "mem"));
        savePlace(a, place);

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(0))
                .andExpect(jsonPath("$.content[0].friendAverageScore").doesNotExist());
    }

    @Test
    void userWeightedScoreAndDistinctFriendCount() throws Exception {
        RegisteredUser a = register("scoreA");
        RegisteredUser b = register("scoreB");
        RegisteredUser c = register("scoreC");
        follow(a, b);
        follow(b, a);
        follow(a, c);
        follow(c, a);

        UUID place = insertPlace("Overlap Score", PlaceCategory.RESTAURANT, 32.5, 42.5);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 1), 8.0,
                Visibility.PUBLIC, "b8", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 2), 10.0,
                Visibility.FRIENDS, "b10", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 3), 1.0,
                Visibility.PRIVATE, "", "b1"));
        visitService.create(c.id, visit(place, LocalDate.of(2026, 5, 4), 6.0,
                Visibility.FRIENDS, "c6", "mem"));
        // Extra B PUBLIC visits must not inflate friendsVisitedCount
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 5), 9.0,
                Visibility.PUBLIC, "b9", "mem"));
        savePlace(a, place);

        // B avg=(8+10+9)/3=9; C avg=6; friends=(9+6)/2=7.5; count=2 (not 3 Visit rows)
        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(2))
                .andExpect(jsonPath("$.content[0].friendAverageScore").value(7.5));

        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(2))
                .andExpect(jsonPath("$.averageScore").value(7.5));
    }

    @Test
    void multipleSavedPlaces_filterOverlapIndependently() throws Exception {
        RegisteredUser a = register("multiA");
        RegisteredUser b = register("multiB");
        follow(a, b);
        follow(b, a);

        UUID x = insertPlace("Saved X", PlaceCategory.RESTAURANT, 32.6, 42.6);
        UUID y = insertPlace("Saved Y", PlaceCategory.CAFE, 32.61, 42.61);
        UUID z = insertPlace("Saved Z", PlaceCategory.RESTAURANT, 32.62, 42.62);
        visitService.create(b.id, visit(x, LocalDate.of(2026, 6, 1), 8.0,
                Visibility.PUBLIC, "x", "mem"));
        visitService.create(b.id, visit(y, LocalDate.of(2026, 6, 2), 7.0,
                Visibility.FRIENDS, "y", "mem"));
        savePlace(a, x);
        savePlace(a, y);
        savePlace(a, z);

        MvcResult result = mockMvc.perform(get("/api/v1/me/saved-places")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        JsonNode rowX = findByPlaceId(content, x);
        JsonNode rowY = findByPlaceId(content, y);
        JsonNode rowZ = findByPlaceId(content, z);
        assertThat(rowX.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(rowX.path("friendAverageScore").asDouble()).isEqualTo(8.0);
        assertThat(rowY.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(rowY.path("friendAverageScore").asDouble()).isEqualTo(7.0);
        assertThat(rowZ.path("friendsVisitedCount").asLong()).isEqualTo(0);
        assertThat(rowZ.path("friendAverageScore").isMissingNode()
                || rowZ.path("friendAverageScore").isNull()).isTrue();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("privateMemory")
                .doesNotContain("@example.com");
    }

    @Test
    void historicalFriendsVisitAppearsAfterMutual_andRevocationClears() throws Exception {
        RegisteredUser a = register("lifeA");
        RegisteredUser b = register("lifeB");

        UUID place = insertPlace("Lifecycle Place", PlaceCategory.CAFE, 32.7, 42.7);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 7, 1), 9.0,
                Visibility.FRIENDS, "historical", "mem"));
        savePlace(a, place);

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(0));

        follow(a, b);
        follow(b, a);

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.content[0].friendAverageScore").value(9.0));

        mockMvc.perform(delete("/api/v1/users/{id}/follow", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(0))
                .andExpect(jsonPath("$.content[0].friendAverageScore").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void abcdXyzRuntime_oneWayUnrelatedThenMutualThenRevokeThenPrivate() throws Exception {
        RegisteredUser a = register("rtA");
        RegisteredUser b = register("rtB");
        RegisteredUser c = register("rtC");
        RegisteredUser d = register("rtD");
        follow(a, b);
        follow(b, a);
        follow(a, c);

        UUID x = insertPlace("Runtime X", PlaceCategory.RESTAURANT, 33.1, 43.1);
        UUID y = insertPlace("Runtime Y", PlaceCategory.CAFE, 33.11, 43.11);
        UUID z = insertPlace("Runtime Z", PlaceCategory.RESTAURANT, 33.12, 43.12);

        visitService.create(b.id, visit(x, LocalDate.of(2026, 8, 1), 8.0,
                Visibility.PUBLIC, "bx8", "mem"));
        visitService.create(b.id, visit(x, LocalDate.of(2026, 8, 2), 10.0,
                Visibility.FRIENDS, "bx10", "mem"));
        visitService.create(b.id, visit(y, LocalDate.of(2026, 8, 3), 7.0,
                Visibility.FRIENDS, "by7", "mem"));
        visitService.create(c.id, visit(x, LocalDate.of(2026, 8, 4), 10.0,
                Visibility.PUBLIC, "cx10", "mem"));
        visitService.create(c.id, visit(z, LocalDate.of(2026, 8, 5), 10.0,
                Visibility.FRIENDS, "cz10", "mem"));
        visitService.create(d.id, visit(y, LocalDate.of(2026, 8, 6), 9.0,
                Visibility.PUBLIC, "dy9", "mem"));
        savePlace(a, x);
        savePlace(a, y);
        savePlace(a, z);

        JsonNode before = savedContent(a);
        JsonNode x0 = findByPlaceId(before, x);
        JsonNode y0 = findByPlaceId(before, y);
        JsonNode z0 = findByPlaceId(before, z);
        assertThat(x0.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(x0.path("friendAverageScore").asDouble()).isEqualTo(9.0);
        assertThat(y0.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(y0.path("friendAverageScore").asDouble()).isEqualTo(7.0);
        assertThat(z0.path("friendsVisitedCount").asLong()).isEqualTo(0);
        assertThat(z0.path("friendAverageScore").isMissingNode()
                || z0.path("friendAverageScore").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", x)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(9.0));

        follow(c, a);

        JsonNode afterMutual = savedContent(a);
        JsonNode x1 = findByPlaceId(afterMutual, x);
        JsonNode z1 = findByPlaceId(afterMutual, z);
        assertThat(x1.path("friendsVisitedCount").asLong()).isEqualTo(2);
        assertThat(x1.path("friendAverageScore").asDouble()).isEqualTo(9.5);
        assertThat(z1.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(z1.path("friendAverageScore").asDouble()).isEqualTo(10.0);

        mockMvc.perform(delete("/api/v1/users/{id}/follow", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        JsonNode afterUnfollow = savedContent(a);
        JsonNode x2 = findByPlaceId(afterUnfollow, x);
        JsonNode y2 = findByPlaceId(afterUnfollow, y);
        JsonNode z2 = findByPlaceId(afterUnfollow, z);
        assertThat(x2.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(x2.path("friendAverageScore").asDouble()).isEqualTo(10.0);
        assertThat(y2.path("friendsVisitedCount").asLong()).isEqualTo(0);
        assertThat(z2.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(afterUnfollow.size()).isEqualTo(3);

        visitService.create(b.id, visit(x, LocalDate.of(2026, 8, 7), 1.0,
                Visibility.PRIVATE, "", "secret"));
        JsonNode afterPrivate = savedContent(a);
        JsonNode x3 = findByPlaceId(afterPrivate, x);
        assertThat(x3.path("friendsVisitedCount").asLong()).isEqualTo(1);
        assertThat(x3.path("friendAverageScore").asDouble()).isEqualTo(10.0);
        assertThat(afterPrivate.toString()).doesNotContain("secret");
    }

    @Test
    void communityScoreIndependentOfFriendsVisit() throws Exception {
        RegisteredUser a = register("commA");
        RegisteredUser b = register("commB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Community Split", PlaceCategory.RESTAURANT, 32.8, 42.8);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 8, 1), 7.0,
                Visibility.PUBLIC, "pub7", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 8, 2), 10.0,
                Visibility.FRIENDS, "fri10", "mem"));
        savePlace(a, place);

        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].place.averageScore").value(7.0))
                .andExpect(jsonPath("$.content[0].place.ratingCount").value(1))
                .andExpect(jsonPath("$.content[0].friendAverageScore").value(8.5))
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(1));
    }

    private void savePlace(RegisteredUser user, UUID placeId) throws Exception {
        mockMvc.perform(post("/api/v1/me/saved-places/{id}", placeId)
                        .header("Authorization", "Bearer " + user.access))
                .andExpect(status().isOk());
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
        return new RegisteredUser(
                UUID.fromString(session.get("user").get("id").asText()),
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
                    ?, ?, 'saved friends overlap place', ?, array[]::text[],
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

    private JsonNode savedContent(RegisteredUser user) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me/saved-places")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + user.access))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
    }

    private JsonNode findByPlaceId(JsonNode content, UUID placeId) {
        return StreamSupport.stream(content.spliterator(), false)
                .filter(n -> placeId.toString().equals(n.path("place").path("id").asText()))
                .findFirst()
                .orElseThrow();
    }

    private record RegisteredUser(UUID id, String access) {
    }
}

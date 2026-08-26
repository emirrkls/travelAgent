package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.service.VisitService;
import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Community discovery aggregates/filters use PUBLIC Visits only.
 * Friends discovery uses mutual + friend-readable (PUBLIC or FRIENDS).
 * Owner history keeps all visibilities.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("dev")
@Testcontainers
class DiscoveryVisibilityIntegrationTest {

    private static final UUID DEMO_USER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

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

    @BeforeEach
    void acceptDemoUserPolicy() {
        PolicyAcceptanceSupport.acceptCurrent(jdbc, DEMO_USER);
    }

    @Test
    void communityAggregateExcludesFriendsAndPrivateVisits() throws Exception {
        UUID place = insertPlace("Mixed Visibility Aggregate", PlaceCategory.RESTAURANT,
                30.1, 40.1);
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 1, 1), 8.0,
                Visibility.PUBLIC, "pub a", ""));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 1, 2), 10.0,
                Visibility.PUBLIC, "pub b", ""));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 1, 3), 1.0,
                Visibility.PRIVATE, "", "private mem"));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 1, 4), 2.0,
                Visibility.FRIENDS, "friends only", ""));

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(9.0))
                .andExpect(jsonPath("$.ratingCount").value(2));

        mockMvc.perform(get("/api/v1/places")
                        .param("search", "Mixed Visibility Aggregate")
                        .param("sort", "averageScore,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(place.toString()))
                .andExpect(jsonPath("$.content[0].averageScore").value(9.0))
                .andExpect(jsonPath("$.content[0].ratingCount").value(2));
    }

    @Test
    void hiddenOnlyPlaceIsNotRatedInCommunityDiscovery() throws Exception {
        UUID place = insertPlace("Hidden Only Place", PlaceCategory.CAFE, 30.2, 40.2);
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 2, 1), 10.0,
                Visibility.PRIVATE, "", "secret"));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 2, 2), 9.0,
                Visibility.FRIENDS, "friends review", ""));

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(nullValue()))
                .andExpect(jsonPath("$.ratingCount").value(0));

        mockMvc.perform(get("/api/v1/places")
                        .param("search", "Hidden Only Place")
                        .param("minRating", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/places/nearby")
                        .param("lat", "40.2")
                        .param("lon", "30.2")
                        .param("radiusMeters", "5000")
                        .param("minRating", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/places/bounds")
                        .param("west", "30.0")
                        .param("south", "40.0")
                        .param("east", "30.5")
                        .param("north", "40.5")
                        .param("minRating", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void minRatingUsesPublicCommunityAverageOnly() throws Exception {
        UUID place = insertPlace("Min Rating Trap", PlaceCategory.RESTAURANT, 30.3, 40.3);
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 3, 1), 7.0,
                Visibility.PUBLIC, "public seven", ""));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 3, 2), 10.0,
                Visibility.PRIVATE, "", "private ten"));

        mockMvc.perform(get("/api/v1/places")
                        .param("search", "Min Rating Trap")
                        .param("minRating", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 3, 3), 10.0,
                Visibility.PUBLIC, "public ten", ""));

        // Public average = (7+10)/2 = 8.5 — still below 9
        mockMvc.perform(get("/api/v1/places")
                        .param("search", "Min Rating Trap")
                        .param("minRating", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.5))
                .andExpect(jsonPath("$.ratingCount").value(2));
    }

    @Test
    void ratingSortFollowsPublicAggregateOnly() throws Exception {
        UUID publicHigh = insertPlace("Sort Public High", PlaceCategory.RESTAURANT, 30.41, 40.41);
        UUID hiddenHigh = insertPlace("Sort Hidden High", PlaceCategory.RESTAURANT, 30.42, 40.42);
        visitService.create(DEMO_USER, visit(publicHigh, LocalDate.of(2026, 4, 1), 8.0,
                Visibility.PUBLIC, "eight", ""));
        visitService.create(DEMO_USER, visit(hiddenHigh, LocalDate.of(2026, 4, 2), 10.0,
                Visibility.PRIVATE, "", "ten"));
        visitService.create(DEMO_USER, visit(hiddenHigh, LocalDate.of(2026, 4, 3), 9.5,
                Visibility.FRIENDS, "friends", ""));

        MvcResult result = mockMvc.perform(get("/api/v1/places")
                        .param("category", "RESTAURANT")
                        .param("search", "Sort")
                        .param("sort", "averageScore,desc")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("content");
        List<JsonNode> ours = StreamSupport.stream(content.spliterator(), false)
                .filter(n -> n.path("name").asText().startsWith("Sort"))
                .toList();
        assertThat(ours).hasSize(2);
        assertThat(ours.get(0).path("id").asText()).isEqualTo(publicHigh.toString());
        assertThat(ours.get(0).path("averageScore").asDouble()).isEqualTo(8.0);
        assertThat(ours.get(1).path("id").asText()).isEqualTo(hiddenHigh.toString());
        assertThat(ours.get(1).path("averageScore").isNull()).isTrue();
        assertThat(ours.get(1).path("ratingCount").asLong()).isZero();
    }

    @Test
    void visitCreateUpdatesCommunityOnlyForPublicVisibility() throws Exception {
        UUID place = insertPlace("Create Aggregate Place", PlaceCategory.RESTAURANT,
                30.5, 40.5);

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(0))
                .andExpect(jsonPath("$.averageScore").value(nullValue()));

        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 5, 1), 8.0,
                Visibility.PUBLIC, "first public", ""));
        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.0))
                .andExpect(jsonPath("$.ratingCount").value(1));

        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 5, 2), 10.0,
                Visibility.PRIVATE, "", "private"));
        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.0))
                .andExpect(jsonPath("$.ratingCount").value(1));

        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 5, 3), 9.0,
                Visibility.FRIENDS, "friends", ""));
        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.0))
                .andExpect(jsonPath("$.ratingCount").value(1));

        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 5, 4), 10.0,
                Visibility.PUBLIC, "second public", ""));
        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(9.0))
                .andExpect(jsonPath("$.ratingCount").value(2));
    }

    @Test
    void communityReviewsAndActivityExcludeHiddenVisits() throws Exception {
        UUID place = insertPlace("Review Visibility Place", PlaceCategory.RESTAURANT,
                30.6, 40.6);
        String secret = "VISIBILITY_PRIVATE_MEMORY";
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 6, 1), 8.0,
                Visibility.PUBLIC, "visible review", secret));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 6, 2), 9.0,
                Visibility.PRIVATE, "should not list", secret));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 6, 3), 7.0,
                Visibility.FRIENDS, "friends should not list", secret));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("visible review"));

        MvcResult activity = mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "community")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        String body = activity.getResponse().getContentAsString();
        assertThat(body).doesNotContain(secret);
        assertThat(body).doesNotContain("privateMemory");
        JsonNode content = objectMapper.readTree(body).get("content");
        long forPlace = StreamSupport.stream(content.spliterator(), false)
                .filter(n -> place.toString().equals(n.path("place").path("id").asText()))
                .count();
        assertThat(forPlace).isEqualTo(1);
    }

    @Test
    void friendsDiscoveryIncludesFriendsVisitsExcludesPrivateAndStaysUserWeighted() throws Exception {
        RegisteredUser a = register("visA");
        RegisteredUser b = register("visB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Friends Readable Mix", PlaceCategory.RESTAURANT, 30.7, 40.7);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 7, 1), 8.0,
                Visibility.PUBLIC, "b public 8", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 7, 2), 10.0,
                Visibility.FRIENDS, "b friends 10", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 7, 3), 1.0,
                Visibility.PRIVATE, "", "hidden private"));

        // B contribution = avg(8, 10) = 9; PRIVATE excluded
        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(9.0))
                .andExpect(jsonPath("$.friends.length()").value(1))
                .andExpect(jsonPath("$.friends[0].latestScore").value(10.0));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "community"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.0))
                .andExpect(jsonPath("$.ratingCount").value(1));

        RegisteredUser c = register("visC");
        follow(a, c);
        follow(c, a);
        UUID friendsOnly = insertPlace("Friends Only Place", PlaceCategory.CAFE, 30.71, 40.71);
        visitService.create(c.id, visit(friendsOnly, LocalDate.of(2026, 7, 5), 10.0,
                Visibility.PRIVATE, "", "only private"));
        visitService.create(c.id, visit(friendsOnly, LocalDate.of(2026, 7, 6), 9.0,
                Visibility.FRIENDS, "only friends", ""));

        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", friendsOnly)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(9.0))
                .andExpect(jsonPath("$.friends.length()").value(1))
                .andExpect(jsonPath("$.friends[0].latestScore").value(9.0));
    }

    @Test
    void ownerHistoryStillReturnsAllVisibilities() throws Exception {
        RegisteredUser owner = register("ownerVis");
        UUID place = insertPlace("Owner History Place", PlaceCategory.CAFE, 30.8, 40.8);
        String secret = "OWNER_PRIVATE_MEMORY";
        visitService.create(owner.id, visit(place, LocalDate.of(2026, 8, 1), 8.0,
                Visibility.PUBLIC, "public", secret));
        visitService.create(owner.id, visit(place, LocalDate.of(2026, 8, 2), 9.0,
                Visibility.FRIENDS, "friends", secret));
        visitService.create(owner.id, visit(place, LocalDate.of(2026, 8, 3), 7.0,
                Visibility.PRIVATE, "", secret));

        MvcResult result = mockMvc.perform(get("/api/v1/me/visits")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + owner.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(secret);
        assertThat(body).contains("PRIVATE");
        assertThat(body).contains("FRIENDS");
        assertThat(body).contains("PUBLIC");

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.0))
                .andExpect(jsonPath("$.ratingCount").value(1));
    }

    @Test
    void nearbyAndBoundsReturnPublicCommunityScores() throws Exception {
        UUID place = insertPlace("Geo Public Score", PlaceCategory.RESTAURANT, 31.0, 41.0);
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 9, 1), 8.0,
                Visibility.PUBLIC, "a", ""));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 9, 2), 10.0,
                Visibility.PUBLIC, "b", ""));
        visitService.create(DEMO_USER, visit(place, LocalDate.of(2026, 9, 3), 1.0,
                Visibility.PRIVATE, "", "p"));

        MvcResult nearby = mockMvc.perform(get("/api/v1/places/nearby")
                        .param("lat", "41.0")
                        .param("lon", "31.0")
                        .param("radiusMeters", "2000"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode nearbyRow = StreamSupport.stream(
                        objectMapper.readTree(nearby.getResponse().getContentAsString())
                                .spliterator(), false)
                .filter(n -> place.toString().equals(n.path("place").path("id").asText()))
                .findFirst().orElseThrow();
        assertThat(nearbyRow.path("place").path("averageScore").asDouble()).isEqualTo(9.0);
        assertThat(nearbyRow.path("place").path("ratingCount").asLong()).isEqualTo(2);

        MvcResult bounds = mockMvc.perform(get("/api/v1/places/bounds")
                        .param("west", "30.9")
                        .param("south", "40.9")
                        .param("east", "31.1")
                        .param("north", "41.1"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode boundsRow = StreamSupport.stream(
                        objectMapper.readTree(bounds.getResponse().getContentAsString())
                                .spliterator(), false)
                .filter(n -> place.toString().equals(n.path("id").asText()))
                .findFirst().orElseThrow();
        assertThat(boundsRow.path("averageScore").asDouble()).isEqualTo(9.0);
        assertThat(boundsRow.path("ratingCount").asLong()).isEqualTo(2);
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
                    ?, ?, 'discovery visibility place', ?, array[]::text[],
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

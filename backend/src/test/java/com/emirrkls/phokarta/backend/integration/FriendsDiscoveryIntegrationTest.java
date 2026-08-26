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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("dev")
@Testcontainers
class FriendsDiscoveryIntegrationTest {

    private static final UUID DEMO_USER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FRIENDS_COLLECTION =
            UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID PRIVATE_COLLECTION =
            UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID PUBLIC_COLLECTION =
            UUID.fromString("40000000-0000-0000-0000-000000000001");

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
    void friendsActivityIncludesMutualExcludesOneWaySelfAndUnrelated() throws Exception {
        RegisteredUser a = register("viewer");
        RegisteredUser b = register("mutual");
        RegisteredUser c = register("oneway");
        RegisteredUser d = register("stranger");

        follow(a, b);
        follow(b, a);
        follow(a, c);

        UUID place = insertPlace("Friends Activity Place", PlaceCategory.RESTAURANT, 28.1, 38.1);
        String secret = "FRIENDS_ACTIVITY_PRIVATE";

        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 8, 20), 9.0,
                "Mutual review", secret));
        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 8, 21), 8.5,
                "", secret));
        visitService.create(c.id, visitRequest(place, LocalDate.of(2026, 8, 22), 9.9,
                "One-way should be hidden", secret));
        visitService.create(d.id, visitRequest(place, LocalDate.of(2026, 8, 23), 7.0,
                "Unrelated", secret));
        visitService.create(a.id, visitRequest(place, LocalDate.of(2026, 8, 24), 8.0,
                "Self should be hidden from friends", secret));

        MvcResult friendsResult = mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .param("page", "0")
                        .param("size", "50")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andReturn();

        String friendsBody = friendsResult.getResponse().getContentAsString();
        assertThat(friendsBody).doesNotContain(secret);
        assertThat(friendsBody).doesNotContain("privateMemory");
        assertThat(friendsBody.toLowerCase()).doesNotContain("\"email\"");

        JsonNode friendsContent = objectMapper.readTree(friendsBody).get("content");
        List<JsonNode> ours = filterByPlace(friendsContent, "Friends Activity Place");
        assertThat(ours).hasSize(2);
        Set<String> authorIds = ours.stream()
                .map(n -> n.path("author").path("id").asText())
                .collect(Collectors.toSet());
        assertThat(authorIds).containsExactly(b.id.toString());
        assertThat(ours.get(0).path("visitedAt").asText()).isEqualTo("2026-08-21");
        assertThat(ours.get(0).path("publicReview").asText()).isEmpty();
        assertThat(ours.get(1).path("visitedAt").asText()).isEqualTo("2026-08-20");
        assertThat(ours.get(0).has("privateMemory")).isFalse();
        assertThat(ours.get(0).path("author").has("email")).isFalse();

        MvcResult communityResult = mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "community")
                        .param("page", "0")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> communityOurs = filterByPlace(
                objectMapper.readTree(communityResult.getResponse().getContentAsString()).get("content"),
                "Friends Activity Place");
        Set<String> communityAuthors = communityOurs.stream()
                .map(n -> n.path("author").path("id").asText())
                .collect(Collectors.toSet());
        assertThat(communityAuthors).contains(a.id.toString(), b.id.toString(),
                c.id.toString(), d.id.toString());
        assertThat(communityOurs).hasSize(5);
    }

    @Test
    void friendsActivityPaginatesNewestFirstAndRequiresAuth() throws Exception {
        RegisteredUser a = register("pager");
        RegisteredUser b = register("friend");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Friends Page Place", PlaceCategory.CAFE, 28.2, 38.2);
        for (int day = 1; day <= 5; day++) {
            visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 6, day),
                    7.0 + day * 0.1, day % 2 == 0 ? "" : "R" + day, "secret"));
        }

        mockMvc.perform(get("/api/v1/activity").param("scope", "friends"))
                .andExpect(status().isUnauthorized());

        MvcResult page0 = mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();
        MvcResult page1 = mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .param("page", "1")
                        .param("size", "2")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andReturn();

        List<String> ids0 = visitIds(objectMapper.readTree(page0.getResponse().getContentAsString())
                .get("content"));
        List<String> ids1 = visitIds(objectMapper.readTree(page1.getResponse().getContentAsString())
                .get("content"));
        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
        assertThat(objectMapper.readTree(page0.getResponse().getContentAsString())
                .get("content").get(0).path("visitedAt").asText()).isEqualTo("2026-06-05");

        mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk());
    }

    @Test
    void friendScoreIsUserWeightedNotVisitWeighted() throws Exception {
        RegisteredUser a = register("scoreA");
        RegisteredUser b = register("scoreB");
        RegisteredUser c = register("scoreC");
        follow(a, b);
        follow(b, a);
        follow(a, c);
        follow(c, a);

        UUID place = insertPlace("Weighted Score Place", PlaceCategory.RESTAURANT, 28.3, 38.3);
        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 5, 1), 8.0, "b1", "x"));
        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 5, 2), 10.0, "b2", "x"));
        visitService.create(c.id, visitRequest(place, LocalDate.of(2026, 5, 3), 6.0, "c1", "x"));

        MvcResult result = mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(2))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("averageScore").asDouble()).isEqualTo(7.5);
        assertThat(body.toString()).doesNotContain("privateMemory");
        assertThat(body.toString().toLowerCase()).doesNotContain("\"email\"");
        assertThat(body.path("friends")).hasSize(2);
        assertThat(body.path("friends").get(0).path("latestVisitedAt").asText())
                .isEqualTo("2026-05-03");
        assertThat(body.path("friends").get(0).path("userId").asText()).isEqualTo(c.id.toString());
        assertThat(body.path("friends").get(0).path("latestScore").asDouble()).isEqualTo(6.0);
        assertThat(body.path("friends").get(1).path("userId").asText()).isEqualTo(b.id.toString());
        assertThat(body.path("friends").get(1).path("latestScore").asDouble()).isEqualTo(10.0);
    }

    @Test
    void repeatVisitsDedupInWhoVisitedButNotInActivityOrReviews() throws Exception {
        RegisteredUser a = register("repeatA");
        RegisteredUser b = register("repeatB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Repeat Friends Place", PlaceCategory.RESTAURANT, 28.4, 38.4);
        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 4, 1), 8.0, "first", "p"));
        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 4, 2), 9.0, "second", "p"));
        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 4, 3), 7.0, "third", "p"));

        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/v1/places/" + place + "/reviews")
                        .param("scope", "friends")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        MvcResult summary = mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.friends.length()").value(1))
                .andExpect(jsonPath("$.friends[0].userId").value(b.id.toString()))
                .andExpect(jsonPath("$.friends[0].latestScore").value(7.0))
                .andReturn();
        assertThat(objectMapper.readTree(summary.getResponse().getContentAsString())
                .path("averageScore").asDouble()).isEqualTo(8.0);
    }

    @Test
    void oneWayFollowExcludedFromAllFriendScopes() throws Exception {
        RegisteredUser a = register("exclA");
        RegisteredUser c = register("exclC");
        follow(a, c);

        UUID place = insertPlace("OneWay Exclude Place", PlaceCategory.RESTAURANT, 28.5, 38.5);
        visitService.create(c.id, visitRequest(place, LocalDate.of(2026, 3, 1), 9.8,
                "Should not appear", "priv"));

        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/places/" + place + "/reviews")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(0))
                .andExpect(jsonPath("$.averageScore").doesNotExist())
                .andExpect(jsonPath("$.friends").isEmpty());

        mockMvc.perform(get("/api/v1/places/" + place + "/reviews").param("scope", "community"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void friendshipRevocationRemovesFriendScopesWithoutDeletingVisits() throws Exception {
        RegisteredUser a = register("revA");
        RegisteredUser b = register("revB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Revoke Place", PlaceCategory.RESTAURANT, 28.6, 38.6);
        visitService.create(b.id, visitRequest(place, LocalDate.of(2026, 2, 1), 9.0,
                "Still community", "priv"));

        mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1));

        mockMvc.perform(delete("/api/v1/users/" + b.id + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/places/" + place + "/reviews")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(0))
                .andExpect(jsonPath("$.averageScore").doesNotExist());

        mockMvc.perform(get("/api/v1/places/" + place + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/users/" + b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship.isFollowing").value(false))
                .andExpect(jsonPath("$.relationship.followsYou").value(true))
                .andExpect(jsonPath("$.relationship.isFriend").value(false));
    }

    @Test
    void friendshipCreationIncludesPreviouslyOneWayUser() throws Exception {
        RegisteredUser a = register("createA");
        RegisteredUser c = register("createC");
        follow(a, c);

        UUID place = insertPlace("Create Mutual Place", PlaceCategory.RESTAURANT, 28.7, 38.7);
        visitService.create(c.id, visitRequest(place, LocalDate.of(2026, 1, 15), 8.2,
                "Waiting for mutual", "priv"));

        mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(0));

        follow(c, a);

        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].author.id").value(c.id.toString()));
        mockMvc.perform(get("/api/v1/places/" + place + "/reviews")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(8.2));
    }

    @Test
    void friendsEndpointsRequireAuthAndPreserveCommunityPublicAccess() throws Exception {
        UUID place = insertPlace("Auth Place", PlaceCategory.RESTAURANT, 28.8, 38.8);
        visitService.create(DEMO_USER, visitRequest(place, LocalDate.of(2026, 1, 1), 8.0,
                "public", "secret"));

        mockMvc.perform(get("/api/v1/activity").param("scope", "friends"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/places/" + place + "/reviews").param("scope", "friends"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/activity").param("scope", "community"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/places/" + place + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void friendsCollectionAuthStillRequiresMutualAfterFriendsDiscovery() throws Exception {
        RegisteredUser stranger = register("collFriend");
        String demoAccess = loginAccess("demo@phokarta.local", "DemoPass123!");

        mockMvc.perform(get("/api/v1/collections/" + PUBLIC_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/collections/" + PRIVATE_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());

        follow(stranger, new RegisteredUser(DEMO_USER, "demo", demoAccess));
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());

        follow(new RegisteredUser(DEMO_USER, "demo", demoAccess), stranger);
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/users/" + stranger.id + "/follow")
                        .header("Authorization", "Bearer " + demoAccess))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/collections/" + PRIVATE_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());
    }

    @Test
    void privateVisitsNeverAppearInFriendScopes() throws Exception {
        RegisteredUser a = register("privA");
        RegisteredUser b = register("privB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Private Friend Place", PlaceCategory.RESTAURANT, 28.9, 38.9);
        visitService.create(b.id, new CreateVisitRequest(
                place, LocalDate.of(2026, 7, 1), 9.5,
                List.of(
                        new CreateVisitRequest.DimensionScore("FOOD", 9.0),
                        new CreateVisitRequest.DimensionScore("SERVICE", 9.0),
                        new CreateVisitRequest.DimensionScore("ATMOSPHERE", 9.0),
                        new CreateVisitRequest.DimensionScore("VALUE", 9.0),
                        new CreateVisitRequest.DimensionScore("PRESENTATION", 9.0)),
                "hidden review", "private memory", List.of(), Visibility.PRIVATE));

        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/places/" + place + "/friends-summary")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(0));
    }

    private void follow(RegisteredUser follower, RegisteredUser followed) throws Exception {
        mockMvc.perform(post("/api/v1/users/" + followed.id + "/follow")
                        .header("Authorization", "Bearer " + follower.access))
                .andExpect(status().isNoContent());
    }

    private List<JsonNode> filterByPlace(JsonNode content, String placeName) {
        return StreamSupport.stream(content.spliterator(), false)
                .filter(node -> placeName.equals(node.path("place").path("name").asText()))
                .toList();
    }

    private List<String> visitIds(JsonNode content) {
        return StreamSupport.stream(content.spliterator(), false)
                .map(n -> n.path("visitId").asText())
                .toList();
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
                username,
                session.get("accessToken").asText());
    }

    private String loginAccess(String identifier, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","password":"%s"}
                                """.formatted(identifier, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private UUID insertPlace(String name, PlaceCategory category, double longitude,
                             double latitude) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, ?, 'friends discovery place', ?, array[]::text[],
                    ST_SetSRID(ST_MakePoint(?, ?), 4326), 'Test City', 'Test Region',
                    'Test Country', 'Test Address', 'https://example.test/cover.jpg',
                    array[]::text[], 2, now(), now()
                )
                """, id, name, category.name(), longitude, latitude);
        return id;
    }

    private CreateVisitRequest visitRequest(
            UUID placeId, LocalDate visitedAt, double rating,
            String publicReview, String privateMemory) {
        return new CreateVisitRequest(placeId, visitedAt, rating,
                List.of(
                        new CreateVisitRequest.DimensionScore("FOOD", 8.0),
                        new CreateVisitRequest.DimensionScore("SERVICE", 8.0),
                        new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0),
                        new CreateVisitRequest.DimensionScore("VALUE", 8.0),
                        new CreateVisitRequest.DimensionScore("PRESENTATION", 8.0)),
                publicReview, privateMemory, List.of(), Visibility.PUBLIC);
    }

    private record RegisteredUser(UUID id, String username, String access) {
    }
}

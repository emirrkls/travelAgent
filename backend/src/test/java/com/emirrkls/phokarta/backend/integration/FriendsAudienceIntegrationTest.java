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

/**
 * True FRIENDS Visit audience: mutual friends see PUBLIC+FRIENDS; PRIVATE owner-only;
 * Community stays PUBLIC-only. Audience is evaluated dynamically against the live graph.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("dev")
@Testcontainers
class FriendsAudienceIntegrationTest {

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
    void visibilityMatrix_mutualViewerSeesPublicAndFriendsNotPrivate() throws Exception {
        RegisteredUser a = register("mtxA");
        RegisteredUser b = register("mtxB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Matrix Place", PlaceCategory.RESTAURANT, 29.1, 39.1);
        String privateMem = "MATRIX_PRIVATE_MEMORY";
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 10), 8.0,
                Visibility.PUBLIC, "public review P", privateMem));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 2, 10), 9.0,
                Visibility.FRIENDS, "friends review F", privateMem));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 3, 10), 10.0,
                Visibility.PRIVATE, "private review X", privateMem));

        MvcResult friendsActivity = mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        String activityBody = friendsActivity.getResponse().getContentAsString();
        assertThat(activityBody).contains("public review P");
        assertThat(activityBody).contains("friends review F");
        assertThat(activityBody).doesNotContain("private review X");
        assertThat(activityBody).doesNotContain(privateMem);
        assertThat(activityBody).doesNotContain("privateMemory");
        assertThat(activityBody).doesNotContain("\"visibility\"");

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].publicReview").value("friends review F"))
                .andExpect(jsonPath("$.content[1].publicReview").value("public review P"));

        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(8.5))
                .andExpect(jsonPath("$.friends[0].latestScore").value(9.0));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "community"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("public review P"));
        mockMvc.perform(get("/api/v1/activity").param("scope", "community"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.0))
                .andExpect(jsonPath("$.ratingCount").value(1));

        MvcResult owner = mockMvc.perform(get("/api/v1/me/visits")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();
        String ownerBody = owner.getResponse().getContentAsString();
        assertThat(ownerBody).contains("PUBLIC");
        assertThat(ownerBody).contains("FRIENDS");
        assertThat(ownerBody).contains("PRIVATE");
        assertThat(ownerBody).contains(privateMem);
    }

    @Test
    void oneWayFollow_cannotSeeFriendsVisits_publicStillCommunityVisible() throws Exception {
        RegisteredUser a = register("oneA");
        RegisteredUser b = register("oneB");
        follow(a, b);

        UUID place = insertPlace("OneWay Place", PlaceCategory.CAFE, 29.2, 39.2);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 1), 8.0,
                Visibility.PUBLIC, "oneway public", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 2), 10.0,
                Visibility.FRIENDS, "oneway friends", "mem"));

        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(0));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("oneway public"));
    }

    @Test
    void unrelatedViewer_seesPublicInCommunity_notFriendsVisits() throws Exception {
        RegisteredUser a = register("unrelA");
        RegisteredUser b = register("unrelB");

        UUID place = insertPlace("Unrelated Place", PlaceCategory.CAFE, 29.3, 39.3);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 1), 7.0,
                Visibility.PUBLIC, "unrel public", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 2), 9.0,
                Visibility.FRIENDS, "unrel friends", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 5, 3), 1.0,
                Visibility.PRIVATE, "", "secret"));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void unfollowRevokesFriendsVisitAccess_publicRemainsCommunityVisible() throws Exception {
        RegisteredUser a = register("revA");
        RegisteredUser b = register("revB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Revoke Place", PlaceCategory.RESTAURANT, 29.4, 39.4);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 6, 1), 8.0,
                Visibility.PUBLIC, "revoke public", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 6, 2), 9.0,
                Visibility.FRIENDS, "revoke friends", "mem"));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(delete("/api/v1/users/{id}/follow", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/places/{id}/reviews", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("revoke public"));
    }

    @Test
    void historicalFriendsVisitBecomesVisibleWhenMutualFormed() throws Exception {
        RegisteredUser a = register("histA");
        RegisteredUser b = register("histB");

        UUID place = insertPlace("Historical Place", PlaceCategory.CAFE, 29.5, 39.5);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 7, 1), 9.0,
                Visibility.FRIENDS, "historical friends", "mem"));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        follow(a, b);
        follow(b, a);

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("historical friends"));
        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void friendScoreIsUserWeightedOverFriendReadableVisits() throws Exception {
        RegisteredUser a = register("scoreA");
        RegisteredUser b = register("scoreB");
        RegisteredUser c = register("scoreC");
        follow(a, b);
        follow(b, a);
        follow(a, c);
        follow(c, a);

        UUID place = insertPlace("Score Place", PlaceCategory.RESTAURANT, 29.6, 39.6);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 8, 1), 8.0,
                Visibility.PUBLIC, "b8", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 8, 2), 10.0,
                Visibility.FRIENDS, "b10", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 8, 3), 1.0,
                Visibility.PRIVATE, "", "b1"));
        visitService.create(c.id, visit(place, LocalDate.of(2026, 8, 4), 6.0,
                Visibility.FRIENDS, "c6", "mem"));

        // B avg=(8+10)/2=9; C avg=6; friends score=(9+6)/2=7.5
        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(2))
                .andExpect(jsonPath("$.averageScore").value(7.5));

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageScore").value(8.0))
                .andExpect(jsonPath("$.ratingCount").value(1));
    }

    @Test
    void whoVisitedLatestScoreIgnoresLaterPrivateVisit() throws Exception {
        RegisteredUser a = register("whoA");
        RegisteredUser b = register("whoB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Who Visited Place", PlaceCategory.RESTAURANT, 29.7, 39.7);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 15), 8.0,
                Visibility.PUBLIC, "jan", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 2, 15), 9.0,
                Visibility.FRIENDS, "feb", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 3, 15), 10.0,
                Visibility.PRIVATE, "", "mar"));

        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.friends[0].latestScore").value(9.0))
                .andExpect(jsonPath("$.friends[0].latestVisitedAt").value("2026-02-15"))
                .andExpect(jsonPath("$.averageScore").value(8.5));
    }

    @Test
    void friendsReviewPaginationReflectsFriendAudienceOnly() throws Exception {
        RegisteredUser a = register("pageA");
        RegisteredUser b = register("pageB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Page Place", PlaceCategory.CAFE, 29.8, 39.8);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 9, 1), 8.0,
                Visibility.PUBLIC, "P", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 9, 2), 9.0,
                Visibility.FRIENDS, "F", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 9, 3), 1.0,
                Visibility.PRIVATE, "X", "mem"));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .param("page", "0")
                        .param("size", "1")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("F"));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "community")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("P"));
    }

    @Test
    void sameFriendMultipleVisibilities_activityAndCommunitySeparated() throws Exception {
        RegisteredUser a = register("multiA");
        RegisteredUser b = register("multiB");
        follow(a, b);
        follow(b, a);

        UUID place = insertPlace("Multi Vis Place", PlaceCategory.RESTAURANT, 29.9, 39.9);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 1, 1), 8.0,
                Visibility.PUBLIC, "jan public", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 2, 1), 9.0,
                Visibility.FRIENDS, "feb friends", "mem"));
        visitService.create(b.id, visit(place, LocalDate.of(2026, 3, 1), 10.0,
                Visibility.PRIVATE, "", "mar private"));

        MvcResult friends = mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        Set<String> reviews = StreamSupport.stream(
                        objectMapper.readTree(friends.getResponse().getContentAsString())
                                .get("content").spliterator(), false)
                .map(n -> n.path("publicReview").asText())
                .collect(Collectors.toSet());
        assertThat(reviews).containsExactlyInAnyOrder("jan public", "feb friends");

        mockMvc.perform(get("/api/v1/activity").param("scope", "community"))
                .andExpect(status().isOk());
        MvcResult community = mockMvc.perform(get("/api/v1/places/{id}/reviews", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();
        assertThat(community.getResponse().getContentAsString()).contains("jan public");
        assertThat(community.getResponse().getContentAsString()).doesNotContain("feb friends");
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
                    ?, ?, 'friends audience place', ?, array[]::text[],
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

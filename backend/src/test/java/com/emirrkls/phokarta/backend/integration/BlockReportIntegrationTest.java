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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
@TestPropertySource(properties = "phokarta.safety.report-max-per-hour=10")
class BlockReportIntegrationTest {

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
    void blockIsDirectedIdempotentRemovesFollowsAndRejectsSelf() throws Exception {
        RegisteredUser a = register("blkA");
        RegisteredUser b = register("blkB");
        follow(a, b);
        follow(b, a);

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", a.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_SELF"));
        assertThat(blockCount(a.id, a.id)).isZero();

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        assertThat(blockCount(a.id, b.id)).isEqualTo(1);
        assertThat(blockCount(b.id, a.id)).isZero();
        assertThat(followCount(a.id, b.id)).isZero();
        assertThat(followCount(b.id, a.id)).isZero();

        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.friendCount").value(0));
        mockMvc.perform(get("/api/v1/me/blocks")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(b.id.toString()))
                .andExpect(jsonPath("$.content[0].email").doesNotExist());
        mockMvc.perform(get("/api/v1/me/blocks")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(delete("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        assertThat(blockCount(a.id, b.id)).isZero();
        assertThat(followCount(a.id, b.id)).isZero();
    }

    @Test
    void blockHidesProfileSearchAndFollowBothDirections() throws Exception {
        RegisteredUser a = register("hidA");
        RegisteredUser b = register("hidB");
        RegisteredUser c = register("hidC");

        mockMvc.perform(get("/api/v1/users/{id}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", b.username)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/{id}", a.id)
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/{id}", b.id)
                        .header("Authorization", "Bearer " + c.access))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{id}", b.id))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", b.username)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", a.username)
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(post("/api/v1/users/{id}/follow", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BLOCKED_RELATIONSHIP"))
                .andExpect(jsonPath("$.message").value("This action isn't available."));
        mockMvc.perform(post("/api/v1/users/{id}/follow", a.id)
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BLOCKED_RELATIONSHIP"));

        mockMvc.perform(delete("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/{id}/follow", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", b.username)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void authenticatedCommunityFiltersBlockedAuthorButAggregateStaysGlobal() throws Exception {
        RegisteredUser a = register("comA");
        RegisteredUser b = register("comB");
        UUID place = insertPlace("Block Community Place", PlaceCategory.RESTAURANT, 28.2, 38.2);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 1), 8.0,
                Visibility.PUBLIC, "blocked author review", "mem"));

        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(8.0));
        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("blocked author review"));
        mockMvc.perform(get("/api/v1/activity")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk());
        assertThat(activityContains(a.access, "blocked author review")).isTrue();

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(activityContains(a.access, "blocked author review")).isFalse();
        mockMvc.perform(get("/api/v1/places/{id}/reviews", place))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk());
        assertThat(anonymousActivityContains("blocked author review")).isTrue();
        mockMvc.perform(get("/api/v1/places/{id}", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.recentPublicReviews").isEmpty());
        mockMvc.perform(get("/api/v1/places/{id}", place))
                .andExpect(jsonPath("$.ratingCount").value(1))
                .andExpect(jsonPath("$.averageScore").value(8.0));
    }

    @Test
    void directPublicVisitAndFriendsVisitBecomeInvisibleAfterBlock() throws Exception {
        RegisteredUser a = register("visA");
        RegisteredUser b = register("visB");
        follow(a, b);
        follow(b, a);
        UUID place = insertPlace("Block Visit Place", PlaceCategory.RESTAURANT, 28.3, 38.3);
        var pub = visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 2), 7.0,
                Visibility.PUBLIC, "public blocked visit", "priv"));
        var friends = visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 3), 9.0,
                Visibility.FRIENDS, "friends blocked visit", "priv"));

        mockMvc.perform(get("/api/v1/visits/{id}", pub.id())
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicReview").value("public blocked visit"))
                .andExpect(jsonPath("$.privateMemory").doesNotExist());
        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/visits/{id}", pub.id())
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/visits/{id}", friends.id())
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/visits/{id}", pub.id()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/activity")
                        .param("scope", "friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(followCount(a.id, b.id)).isZero();
        assertThat(followCount(b.id, a.id)).isZero();
    }

    @Test
    void friendMetricsMapAndSavedSignalsDropBlockedUsers() throws Exception {
        RegisteredUser a = register("metA");
        RegisteredUser b = register("metB");
        follow(a, b);
        follow(b, a);
        UUID place = insertPlace("Block Metrics Place", PlaceCategory.RESTAURANT, 28.4, 38.4);
        visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 4), 10.0,
                Visibility.PUBLIC, "metrics review", "mem"));

        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.friendsVisitedCount").value(1))
                .andExpect(jsonPath("$.friends[0].userId").value(b.id.toString()));
        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[\"" + place + "\"]}"))
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(1));
        mockMvc.perform(post("/api/v1/me/saved-places/{placeId}", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(1));

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/places/{id}/friends-summary", place)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.friendsVisitedCount").value(0))
                .andExpect(jsonPath("$.friends").isEmpty());
        mockMvc.perform(post("/api/v1/me/places/friend-metrics")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeIds\":[\"" + place + "\"]}"))
                .andExpect(jsonPath("$[0].friendsVisitedCount").value(0));
        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(jsonPath("$.content[0].friendsVisitedCount").value(0));
    }

    @Test
    void reportUserAndVisitWithDuplicateAndSelfRejected() throws Exception {
        RegisteredUser a = register("repA");
        RegisteredUser b = register("repB");
        UUID place = insertPlace("Report Place", PlaceCategory.RESTAURANT, 28.5, 38.5);
        var pub = visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 5), 6.0,
                Visibility.PUBLIC, "reportable review", "mem"));
        var hidden = visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 6), 5.0,
                Visibility.PRIVATE, "hidden review", "secret"));

        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"USER","targetId":"%s","reason":"SPAM","details":"promo spam"}
                                """.formatted(b.id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reason").value("SPAM"))
                .andExpect(jsonPath("$.details").doesNotExist());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"USER","targetId":"%s","reason":"HARASSMENT"}
                                """.formatted(b.id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(openReportCount(a.id)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"VISIT","targetId":"%s","reason":"OTHER"}
                                """.formatted(pub.id())))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"VISIT","targetId":"%s","reason":"PRIVACY"}
                                """.formatted(hidden.id())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REPORT_TARGET_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"USER","targetId":"%s","reason":"SPAM"}
                                """.formatted(a.id)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_REPORT_SELF"));
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + b.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"VISIT","targetId":"%s","reason":"SPAM"}
                                """.formatted(pub.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_REPORT_SELF"));

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"USER","targetId":"%s","reason":"HATE_OR_ABUSE"}
                                """.formatted(b.id)))
                .andExpect(status().isOk());
    }

    @Test
    void reportRateLimitReturns429() throws Exception {
        RegisteredUser a = register("rateA");
        RegisteredUser b = register("rateB");
        String body = """
                {"targetType":"USER","targetId":"%s","reason":"SPAM"}
                """.formatted(b.id);
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        for (int i = 0; i < 9; i++) {
            mockMvc.perform(post("/api/v1/reports")
                            .header("Authorization", "Bearer " + a.access)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("REPORT_RATE_LIMITED"));
    }

    @Test
    void accountDeletionCascadesBlocksAndNullsReportFks() throws Exception {
        RegisteredUser a = register("delBlkA");
        RegisteredUser b = register("delBlkB");
        RegisteredUser c = register("delBlkC");
        UUID place = insertPlace("Delete Report Place", PlaceCategory.RESTAURANT, 28.6, 38.6);
        var visit = visitService.create(b.id, visit(place, LocalDate.of(2026, 4, 7), 8.0,
                Visibility.PUBLIC, "kept for report", "mem"));

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/me/blocks/{userId}", a.id)
                        .header("Authorization", "Bearer " + c.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"USER","targetId":"%s","reason":"SPAM","details":"safety note"}
                                """.formatted(b.id)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + c.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"VISIT","targetId":"%s","reason":"OTHER"}
                                """.formatted(visit.id())))
                .andExpect(status().isCreated());

        UUID userReportId = jdbc.queryForObject("""
                select id from reports where reporter_user_id = ? and target_type = 'USER'
                """, UUID.class, a.id);
        UUID visitReportId = jdbc.queryForObject("""
                select id from reports where reporter_user_id = ? and target_type = 'VISIT'
                """, UUID.class, c.id);

        deleteAccount(a);
        assertThat(blockCount(a.id, b.id)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from user_blocks where blocked_user_id = ?",
                Integer.class, a.id)).isZero();
        assertThat(jdbc.queryForObject(
                "select reporter_user_id from reports where id = ?",
                UUID.class, userReportId)).isNull();
        assertThat(jdbc.queryForObject(
                "select details from reports where id = ?",
                String.class, userReportId)).isEqualTo("safety note");
        assertThat(jdbc.queryForObject(
                "select reason from reports where id = ?",
                String.class, userReportId)).isEqualTo("SPAM");

        deleteAccount(b);
        assertThat(jdbc.queryForObject(
                "select target_user_id from reports where id = ?",
                UUID.class, userReportId)).isNull();
        assertThat(jdbc.queryForObject(
                "select target_visit_id from reports where id = ?",
                UUID.class, visitReportId)).isNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from reports where id in (?, ?)",
                Integer.class, userReportId, visitReportId)).isEqualTo(2);
    }

    @Test
    void unknownReportReasonIsRejected() throws Exception {
        RegisteredUser a = register("enumA");
        RegisteredUser b = register("enumB");
        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + a.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"USER","targetId":"%s","reason":"NOT_A_REASON"}
                                """.formatted(b.id)))
                .andExpect(status().isBadRequest());
    }

    private boolean activityContains(String access, String review) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/activity")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().contains(review);
    }

    private boolean anonymousActivityContains(String review) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().contains(review);
    }

    private long blockCount(UUID blocker, UUID blocked) {
        Integer count = jdbc.queryForObject("""
                select count(*) from user_blocks
                where blocker_user_id = ? and blocked_user_id = ?
                """, Integer.class, blocker, blocked);
        return count == null ? 0 : count;
    }

    private long followCount(UUID follower, UUID followed) {
        Integer count = jdbc.queryForObject("""
                select count(*) from user_follows
                where follower_user_id = ? and followed_user_id = ?
                """, Integer.class, follower, followed);
        return count == null ? 0 : count;
    }

    private long openReportCount(UUID reporterId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from reports where reporter_user_id = ? and status = 'OPEN'
                """, Integer.class, reporterId);
        return count == null ? 0 : count;
    }

    private void deleteAccount(RegisteredUser user) throws Exception {
        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\"}"))
                .andExpect(status().isNoContent());
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
        PolicyAcceptanceSupport.acceptCurrent(jdbc, id);
        return new RegisteredUser(
                id,
                username,
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
                    ?, ?, 'block report place', ?, array[]::text[],
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

    private record RegisteredUser(UUID id, String username, String access) {
    }
}

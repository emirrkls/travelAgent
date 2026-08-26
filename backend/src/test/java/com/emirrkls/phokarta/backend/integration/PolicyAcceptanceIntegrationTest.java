package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.MediaUploadIntentRequest;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.service.MediaService;
import com.emirrkls.phokarta.backend.service.UgcPolicyService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
class PolicyAcceptanceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VisitService visitService;
    @Autowired private MediaService mediaService;
    @Autowired private UgcPolicyService ugcPolicyService;

    @Test
    void policyStatusIsNotAcceptedByDefault() throws Exception {
        RegisteredUser user = register("polNone");

        mockMvc.perform(get("/api/v1/me/policy-status")
                        .header("Authorization", "Bearer " + user.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredVersion").value(PolicyAcceptanceSupport.CURRENT_VERSION))
                .andExpect(jsonPath("$.acceptedVersion").doesNotExist())
                .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void acceptCurrentVersionIsIdempotentAndWrongVersionIsRejected() throws Exception {
        RegisteredUser user = register("polAcc");
        String current = ugcPolicyService.requiredVersion();

        mockMvc.perform(post("/api/v1/me/policy-acceptance")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyVersion\":\"" + current + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.acceptedVersion").value(current))
                .andExpect(jsonPath("$.requiredVersion").value(current));

        mockMvc.perform(post("/api/v1/me/policy-acceptance")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyVersion\":\"" + current + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        assertThat(acceptanceCount(user.id, current)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/me/policy-acceptance")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyVersion\":\"2099-future\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/me/policy-status")
                        .header("Authorization", "Bearer " + user.access))
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void visitCreateAndMediaIntentAreBlockedUntilAcceptanceThenSucceed() throws Exception {
        RegisteredUser user = register("polUgc");
        UUID place = insertPlace("Policy Place");

        mockMvc.perform(post("/api/v1/visits")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visitJson(place, "blocked review")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POLICY_ACCEPTANCE_REQUIRED"))
                .andExpect(jsonPath("$.requiredVersion").value(PolicyAcceptanceSupport.CURRENT_VERSION));

        mockMvc.perform(post("/api/v1/me/collections")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Blocked list","description":"",
                                "visibility":"PUBLIC","coverImage":"https://example.test/c.jpg"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POLICY_ACCEPTANCE_REQUIRED"));

        acceptViaApi(user);

        mockMvc.perform(post("/api/v1/visits")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visitJson(place, "allowed review")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicReview").value("allowed review"));

        mockMvc.perform(post("/api/v1/me/collections")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Allowed list","description":"",
                                "visibility":"PUBLIC","coverImage":"https://example.test/c.jpg"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void mediaServiceRejectsUploadIntentBeforeAcceptance() {
        RegisteredUser user = register("polMedia");
        assertThatThrownBy(() -> mediaService.createUploadIntent(user.id,
                new MediaUploadIntentRequest(UUID.randomUUID(), "image/jpeg", 100L, 10, 10)))
                .isInstanceOfSatisfying(com.emirrkls.phokarta.backend.api.error.ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("POLICY_ACCEPTANCE_REQUIRED");
                    assertThat(ex.requiredVersion()).isEqualTo(PolicyAcceptanceSupport.CURRENT_VERSION);
                });
    }

    @Test
    void oldAcceptedVersionIsInsufficientWhenRequiredVersionChanges() throws Exception {
        RegisteredUser user = register("polStale");
        PolicyAcceptanceSupport.accept(jdbc, user.id, "2025-01-legacy");

        mockMvc.perform(get("/api/v1/me/policy-status")
                        .header("Authorization", "Bearer " + user.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.acceptedVersion").value("2025-01-legacy"))
                .andExpect(jsonPath("$.requiredVersion").value(PolicyAcceptanceSupport.CURRENT_VERSION));

        UUID place = insertPlace("Stale Policy Place");
        mockMvc.perform(post("/api/v1/visits")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visitJson(place, "stale")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POLICY_ACCEPTANCE_REQUIRED"));
    }

    @Test
    void blockReportAndAccountDeletionWorkWithoutAcceptance() throws Exception {
        RegisteredUser reporter = register("polSafeA");
        RegisteredUser target = register("polSafeB");
        PolicyAcceptanceSupport.acceptCurrent(jdbc, target.id);
        UUID place = insertPlace("Safety Place");
        visitService.create(target.id, visit(place, LocalDate.of(2026, 8, 1), 8.0,
                Visibility.PUBLIC, "visible review", "mem"));

        mockMvc.perform(put("/api/v1/me/blocks/{userId}", target.id)
                        .header("Authorization", "Bearer " + reporter.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/me/blocks/{userId}", target.id)
                        .header("Authorization", "Bearer " + reporter.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + reporter.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetType":"USER","targetId":"%s","reason":"SPAM"}
                                """.formatted(target.id)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + reporter.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\"}"))
                .andExpect(status().isNoContent());
        assertThat(userExists(reporter.id)).isFalse();
    }

    @Test
    void acceptanceRowsCascadeOnAccountDelete() throws Exception {
        RegisteredUser user = register("polCasc");
        acceptViaApi(user);
        assertThat(acceptanceCount(user.id, PolicyAcceptanceSupport.CURRENT_VERSION)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"SecurePass1\"}"))
                .andExpect(status().isNoContent());

        Integer leftover = jdbc.queryForObject(
                "select count(*) from user_policy_acceptances where user_id = ?",
                Integer.class, user.id);
        assertThat(leftover).isZero();
    }

    @Test
    void unacceptedUserCanStillReadExistingContent() throws Exception {
        RegisteredUser author = register("polReadA");
        RegisteredUser reader = register("polReadB");
        PolicyAcceptanceSupport.acceptCurrent(jdbc, author.id);
        UUID place = insertPlace("Readable Place");
        visitService.create(author.id, visit(place, LocalDate.of(2026, 8, 2), 8.0,
                Visibility.PUBLIC, "public review text", "mem"));

        mockMvc.perform(get("/api/v1/places/{id}/reviews", place)
                        .header("Authorization", "Bearer " + reader.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicReview").value("public review text"));
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + reader.access))
                .andExpect(status().isOk());
    }

    private void acceptViaApi(RegisteredUser user) throws Exception {
        mockMvc.perform(post("/api/v1/me/policy-acceptance")
                        .header("Authorization", "Bearer " + user.access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyVersion\":\"" + ugcPolicyService.requiredVersion() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    private RegisteredUser register(String prefix) {
        try {
            return registerQuiet(prefix);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private RegisteredUser registerQuiet(String prefix) throws Exception {
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

    private UUID insertPlace(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, ?, 'policy place', ?, array[]::text[],
                    ST_SetSRID(ST_MakePoint(28.3, 38.3), 4326), 'Test City', 'Test Region',
                    'Test Country', 'Test Address', 'https://example.test/cover.jpg',
                    array[]::text[], 2, now(), now()
                )
                """, id, name, PlaceCategory.RESTAURANT.name());
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

    private String visitJson(UUID placeId, String review) {
        return """
                {"placeId":"%s","visitedAt":"2026-08-01","overallRating":8.0,
                "dimensions":[{"key":"FOOD","score":8.0},{"key":"SERVICE","score":8.0},
                {"key":"ATMOSPHERE","score":8.0},{"key":"VALUE","score":8.0},
                {"key":"PRESENTATION","score":8.0}],
                "publicReview":"%s","privateMemory":"mem","photos":[],"visibility":"PUBLIC"}
                """.formatted(placeId, review);
    }

    private long acceptanceCount(UUID userId, String version) {
        Integer count = jdbc.queryForObject("""
                select count(*) from user_policy_acceptances
                where user_id = ? and policy_version = ?
                """, Integer.class, userId, version);
        return count == null ? 0 : count;
    }

    private boolean userExists(UUID userId) {
        Integer count = jdbc.queryForObject("select count(*) from users where id = ?",
                Integer.class, userId);
        return count != null && count > 0;
    }

    private record RegisteredUser(UUID id, String access) {
    }
}

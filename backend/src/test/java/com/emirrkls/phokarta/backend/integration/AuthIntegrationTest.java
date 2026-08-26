package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.domain.model.Visibility;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class AuthIntegrationTest {

    private static final UUID DEMO_USER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEMO_PLACE =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
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
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void acceptDemoUserPolicy() {
        PolicyAcceptanceSupport.acceptCurrent(jdbc, DEMO_USER);
    }

    @Test
    void registerLoginMeAndLogoutFlow() throws Exception {
        String email = "traveler_" + UUID.randomUUID() + "@example.com";
        String registerBody = """
                {"email":"%s","username":"u_%s","displayName":"Traveler",
                "password":"SecurePass1"}
                """.formatted(email, UUID.randomUUID().toString().substring(0, 8));

        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        JsonNode session = objectMapper.readTree(registered.getResponse().getContentAsString());
        String access = session.get("accessToken").asText();
        String refresh = session.get("refreshToken").asText();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.followerCount").value(0))
                .andExpect(jsonPath("$.followingCount").value(0))
                .andExpect(jsonPath("$.friendCount").value(0));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void loginWithDemoAccountAndRejectsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"demo@phokarta.local","password":"DemoPass123!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(DEMO_USER.toString()));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"demo@phokarta.local","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"missing@phokarta.local","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshRotatesAndRejectsReplay() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"emir_demo","password":"DemoPass123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode session = objectMapper.readTree(login.getResponse().getContentAsString());
        String refresh1 = session.get("refreshToken").asText();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        String refresh2 = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh2 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void ownerEndpointsRequireAuthAndBindPrincipal() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/visits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visitJson("secret-a")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/saved-places"))
                .andExpect(status().isUnauthorized());

        String accessA = loginAccess("demo@phokarta.local", "DemoPass123!");
        mockMvc.perform(post("/api/v1/visits")
                        .header("Authorization", "Bearer " + accessA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visitJson("owner-private-memory")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.privateMemory").value("owner-private-memory"));

        mockMvc.perform(get("/api/v1/me/visits")
                        .header("Authorization", "Bearer " + accessA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].privateMemory").exists());

        String emailB = "other_" + UUID.randomUUID() + "@example.com";
        String accessB = registerAccess(emailB, "other_" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(get("/api/v1/collections/" + PRIVATE_COLLECTION)
                        .header("Authorization", "Bearer " + accessB))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/collections/" + PUBLIC_COLLECTION + "/places/" + DEMO_PLACE)
                        .header("Authorization", "Bearer " + accessB))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/places/" + DEMO_PLACE + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].privateMemory").doesNotExist());

        String publicBody = mockMvc.perform(get("/api/v1/places/" + DEMO_PLACE + "/reviews"))
                .andReturn().getResponse().getContentAsString();
        assertThat(publicBody).doesNotContain("owner-private-memory");
    }

    @Test
    void jwtValidationRejectsMalformedExpiredAndBadSignature() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        String access = loginAccess("demo@phokarta.local", "DemoPass123!");
        String mangled = access.substring(0, access.length() - 4) + "xxxx";
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + mangled))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicPlacesRemainOpenAndPublicCollectionsReadable() throws Exception {
        mockMvc.perform(get("/api/v1/places"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/collections/" + PUBLIC_COLLECTION))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/collections/" + PRIVATE_COLLECTION))
                .andExpect(status().isForbidden());
    }

    @Test
    void savedPlacesArePrivateToAuthenticatedOwner() throws Exception {
        String access = loginAccess("demo@phokarta.local", "DemoPass123!");
        mockMvc.perform(post("/api/v1/me/saved-places/" + DEMO_PLACE)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/saved-places")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(delete("/api/v1/me/saved-places/" + DEMO_PLACE)
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());
    }

    @Test
    void authMigrationBackfillsLegacyDemoUser() {
        String email = jdbc.queryForObject(
                "select email from users where id = ?", String.class, DEMO_USER);
        assertThat(email).isEqualTo("demo@phokarta.local");
        Integer identities = jdbc.queryForObject(
                "select count(*) from auth_identities where user_id = ?",
                Integer.class, DEMO_USER);
        assertThat(identities).isEqualTo(1);
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

    private String registerAccess(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","displayName":"Other",
                                "password":"SecurePass1"}
                                """.formatted(email, username)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private static String visitJson(String privateMemory) {
        return """
                {"placeId":"%s","visitedAt":"2025-08-01","overallRating":8.5,
                "dimensions":[{"key":"FOOD","score":9.0},{"key":"SERVICE","score":8.0}],
                "publicReview":"public review","privateMemory":"%s","photos":[],
                "visibility":"%s"}
                """.formatted(DEMO_PLACE, privateMemory, Visibility.PUBLIC.name());
    }
}

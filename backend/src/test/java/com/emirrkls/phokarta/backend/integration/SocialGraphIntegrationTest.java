package com.emirrkls.phokarta.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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
class SocialGraphIntegrationTest {

    private static final UUID DEMO_USER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PUBLIC_COLLECTION =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID FRIENDS_COLLECTION =
            UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID PRIVATE_COLLECTION =
            UUID.fromString("40000000-0000-0000-0000-000000000003");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void followUnfollowMutualListsSearchAndPrivacy() throws Exception {
        RegisteredUser a = register("alice");
        RegisteredUser b = register("bob");
        RegisteredUser c = register("carol");

        mockMvc.perform(post("/api/v1/users/" + a.id + "/follow"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/users/" + a.id + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/users/" + UUID.randomUUID() + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/users/" + b.id + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/" + b.id + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/" + b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(b.id.toString()))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.relationship.isFollowing").value(true))
                .andExpect(jsonPath("$.relationship.followsYou").value(false))
                .andExpect(jsonPath("$.relationship.isFriend").value(false))
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(0));

        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(0))
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.friendCount").value(0));
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(0))
                .andExpect(jsonPath("$.friendCount").value(0));

        mockMvc.perform(get("/api/v1/me/following")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(b.id.toString()))
                .andExpect(jsonPath("$.content[0].email").doesNotExist());

        mockMvc.perform(get("/api/v1/me/followers")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(a.id.toString()));

        mockMvc.perform(get("/api/v1/me/friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(post("/api/v1/users/" + a.id + "/follow")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/" + b.id)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship.isFollowing").value(true))
                .andExpect(jsonPath("$.relationship.followsYou").value(true))
                .andExpect(jsonPath("$.relationship.isFriend").value(true));

        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.friendCount").value(1));
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.friendCount").value(1));

        mockMvc.perform(get("/api/v1/me/friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b.id.toString()))
                .andExpect(jsonPath("$.content[0].relationship.isFriend").value(true));

        mockMvc.perform(get("/api/v1/me/friends")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(a.id.toString()));

        mockMvc.perform(post("/api/v1/users/" + c.id + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me/following")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/me/friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followingCount").value(2))
                .andExpect(jsonPath("$.friendCount").value(1));

        mockMvc.perform(delete("/api/v1/users/" + b.id + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/users/" + b.id + "/follow")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/" + a.id)
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationship.isFollowing").value(true))
                .andExpect(jsonPath("$.relationship.followsYou").value(false))
                .andExpect(jsonPath("$.relationship.isFriend").value(false));

        mockMvc.perform(get("/api/v1/me/friends")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/me/friends")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(1))
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.friendCount").value(0));
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + b.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.followerCount").value(0))
                .andExpect(jsonPath("$.followingCount").value(1))
                .andExpect(jsonPath("$.friendCount").value(0));

        String searchBody = mockMvc.perform(get("/api/v1/users/search")
                        .param("q", "bob")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value(b.username))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(searchBody.toLowerCase()).doesNotContain("@example.com");

        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", "BOB")
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(b.id.toString()));

        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", a.username)
                        .header("Authorization", "Bearer " + a.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/users/search")
                        .param("q", "carol")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(c.id.toString()))
                .andExpect(jsonPath("$.content[0].relationship").doesNotExist());
    }

    @Test
    void friendsCollectionRequiresMutualFollow() throws Exception {
        RegisteredUser stranger = register("stranger");
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

        mockMvc.perform(post("/api/v1/users/" + DEMO_USER + "/follow")
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/users/" + DEMO_USER + "/follow")
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/" + stranger.id + "/follow")
                        .header("Authorization", "Bearer " + demoAccess))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/users/" + DEMO_USER + "/follow")
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FRIENDS_COLLECTION.toString()));
        mockMvc.perform(get("/api/v1/collections/" + PRIVATE_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/users/" + stranger.id + "/follow")
                        .header("Authorization", "Bearer " + demoAccess))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/collections/" + FRIENDS_COLLECTION)
                        .header("Authorization", "Bearer " + demoAccess))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/collections/" + PUBLIC_COLLECTION)
                        .header("Authorization", "Bearer " + stranger.access))
                .andExpect(status().isOk());
    }

    @Test
    void socialListEndpointsRequireAuthAndPaginateStably() throws Exception {
        mockMvc.perform(get("/api/v1/me/followers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/following")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/friends")).andExpect(status().isUnauthorized());

        RegisteredUser hub = register("hub");
        RegisteredUser u1 = register("zeta");
        RegisteredUser u2 = register("alpha");

        mockMvc.perform(post("/api/v1/users/" + hub.id + "/follow")
                        .header("Authorization", "Bearer " + u1.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/" + hub.id + "/follow")
                        .header("Authorization", "Bearer " + u2.access))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/followers")
                        .param("page", "0")
                        .param("size", "1")
                        .header("Authorization", "Bearer " + hub.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(post("/api/v1/users/" + u1.id + "/follow")
                        .header("Authorization", "Bearer " + hub.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/" + u2.id + "/follow")
                        .header("Authorization", "Bearer " + hub.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/" + hub.id + "/follow")
                        .header("Authorization", "Bearer " + u1.access))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/users/" + hub.id + "/follow")
                        .header("Authorization", "Bearer " + u2.access))
                .andExpect(status().isNoContent());

        MvcResult friends = mockMvc.perform(get("/api/v1/me/friends")
                        .header("Authorization", "Bearer " + hub.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();
        JsonNode content = objectMapper.readTree(friends.getResponse().getContentAsString())
                .get("content");
        assertThat(content.get(0).get("displayName").asText())
                .isLessThanOrEqualTo(content.get(1).get("displayName").asText());
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

    private record RegisteredUser(UUID id, String username, String access) {
    }
}

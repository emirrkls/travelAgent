package com.emirrkls.phokarta.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.service.VisitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("dev")
@TestPropertySource(properties = "phokarta.policy.required-version=2026-09-beta")
class PolicyRequiredVersionChangeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VisitService visitService;

    @Test
    void previousBetaAcceptanceDoesNotSatisfyNewRequiredVersion() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "polVer_" + suffix;
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s@example.com","username":"%s","displayName":"Ver",
                                "password":"SecurePass1"}
                                """.formatted(username, username)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode session = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(session.get("user").get("id").asText());
        PolicyAcceptanceSupport.accept(jdbc, userId, "2026-08-beta");

        UUID place = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, 'Version Place', 'policy place', ?, array[]::text[],
                    ST_SetSRID(ST_MakePoint(28.4, 38.4), 4326), 'Test City', 'Test Region',
                    'Test Country', 'Test Address', 'https://example.test/cover.jpg',
                    array[]::text[], 2, now(), now()
                )
                """, place, PlaceCategory.RESTAURANT.name());

        assertThatThrownBy(() -> visitService.create(userId, new CreateVisitRequest(
                place, LocalDate.of(2026, 8, 1), 8.0,
                List.of(
                        new CreateVisitRequest.DimensionScore("FOOD", 8.0),
                        new CreateVisitRequest.DimensionScore("SERVICE", 8.0),
                        new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0),
                        new CreateVisitRequest.DimensionScore("VALUE", 8.0),
                        new CreateVisitRequest.DimensionScore("PRESENTATION", 8.0)),
                "review", "mem", List.of(), Visibility.PUBLIC)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("POLICY_ACCEPTANCE_REQUIRED");
                    assertThat(ex.requiredVersion()).isEqualTo("2026-09-beta");
                });
    }
}

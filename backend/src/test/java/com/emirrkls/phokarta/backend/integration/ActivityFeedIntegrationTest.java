package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicActivityResponse;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("dev")
@Testcontainers
class ActivityFeedIntegrationTest {

    private static final UUID DEMO_USER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private VisitService visitService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void publicActivityReturnsNewestFirstWithPlaceSummaryAndNoPrivateFields() throws Exception {
        UUID placeA = insertPlace("Activity Place A", PlaceCategory.RESTAURANT, 27.1, 37.1);
        UUID placeB = insertPlace("Activity Place B", PlaceCategory.BEACH, 27.2, 37.2);

        String secret = "ACTIVITY_PRIVATE_MEMORY_SECRET";
        visitService.create(DEMO_USER, visitRequest(placeA, LocalDate.of(2026, 8, 10), 8.0,
                "Older review", secret));
        visitService.create(DEMO_USER, visitRequest(placeB, LocalDate.of(2026, 8, 20), 9.1,
                "", secret));
        visitService.create(DEMO_USER, visitRequest(placeA, LocalDate.of(2026, 8, 22), 9.4,
                "Newest review", secret));

        MvcResult result = mockMvc.perform(get("/api/v1/activity").param("page", "0").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(secret);
        assertThat(body).doesNotContain("privateMemory");
        assertThat(body.toLowerCase()).doesNotContain("\"email\"");

        JsonNode content = objectMapper.readTree(body).get("content");
        List<JsonNode> ours = java.util.stream.StreamSupport.stream(content.spliterator(), false)
                .filter(node -> {
                    String name = node.path("place").path("name").asText();
                    return name.equals("Activity Place A") || name.equals("Activity Place B");
                })
                .toList();
        assertThat(ours).hasSize(3);
        assertThat(ours.get(0).path("visitedAt").asText()).isEqualTo("2026-08-22");
        assertThat(ours.get(0).path("overallScore").asDouble()).isEqualTo(9.4);
        assertThat(ours.get(0).path("publicReview").asText()).isEqualTo("Newest review");
        assertThat(ours.get(0).path("place").path("city").asText()).isEqualTo("Test City");
        assertThat(ours.get(0).path("place").path("category").asText()).isEqualTo("RESTAURANT");
        assertThat(ours.get(0).path("place").path("coverImage").asText()).isNotBlank();
        assertThat(ours.get(0).path("author").path("displayName").asText()).isNotBlank();
        assertThat(ours.get(0).has("privateMemory")).isFalse();
        assertThat(ours.get(0).path("author").has("email")).isFalse();

        assertThat(ours.get(1).path("visitedAt").asText()).isEqualTo("2026-08-20");
        assertThat(ours.get(1).path("publicReview").asText()).isEmpty();
        assertThat(ours.get(2).path("visitedAt").asText()).isEqualTo("2026-08-10");

        long distinctVisitIds = ours.stream()
                .map(node -> node.get("visitId").asText())
                .distinct()
                .count();
        assertThat(distinctVisitIds).isEqualTo(3);
    }

    @Test
    void publicActivityPreservesRepeatVisitsAndPaginates() {
        UUID place = insertPlace("Repeat Visit Place", PlaceCategory.CAFE, 27.3, 37.3);
        for (int day = 1; day <= 5; day++) {
            visitService.create(DEMO_USER, visitRequest(
                    place, LocalDate.of(2026, 7, day), 7.0 + day * 0.1,
                    day % 2 == 0 ? "" : "Review " + day, "secret-" + day));
        }

        PageResponse<PublicActivityResponse> page0 = visitService.publicActivity(0, 2);
        PageResponse<PublicActivityResponse> page1 = visitService.publicActivity(1, 2);

        assertThat(page0.content()).hasSize(2);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page1.content()).hasSize(2);
        assertThat(page0.content().get(0).visitId())
                .isNotEqualTo(page0.content().get(1).visitId());

        List<UUID> page0Ids = page0.content().stream().map(PublicActivityResponse::visitId).toList();
        List<UUID> page1Ids = page1.content().stream().map(PublicActivityResponse::visitId).toList();
        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);

        assertThat(Arrays.stream(PublicActivityResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("privateMemory", "personalNote", "email");
    }

    @Test
    void activityEndpointIsPubliclyReadable() throws Exception {
        mockMvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    private UUID insertPlace(String name, PlaceCategory category, double longitude,
                             double latitude) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, ?, 'activity feed place', ?, array[]::text[],
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
        List<CreateVisitRequest.DimensionScore> dimensions = List.of(
                new CreateVisitRequest.DimensionScore("FOOD", 8.0),
                new CreateVisitRequest.DimensionScore("SERVICE", 8.0),
                new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0),
                new CreateVisitRequest.DimensionScore("VALUE", 8.0),
                new CreateVisitRequest.DimensionScore("PRESENTATION", 8.0));
        PlaceCategory category = PlaceCategory.valueOf(
                jdbc.queryForObject("select category from places where id = ?", String.class, placeId));
        if (category == PlaceCategory.BEACH) {
            dimensions = List.of(
                    new CreateVisitRequest.DimensionScore("SEA", 8.0),
                    new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0),
                    new CreateVisitRequest.DimensionScore("SERVICE", 8.0),
                    new CreateVisitRequest.DimensionScore("CLEANLINESS", 8.0),
                    new CreateVisitRequest.DimensionScore("VALUE", 8.0),
                    new CreateVisitRequest.DimensionScore("CROWD", 8.0));
        } else if (category == PlaceCategory.CAFE) {
            dimensions = List.of(
                    new CreateVisitRequest.DimensionScore("FOOD", 8.0),
                    new CreateVisitRequest.DimensionScore("SERVICE", 8.0),
                    new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0),
                    new CreateVisitRequest.DimensionScore("VALUE", 8.0),
                    new CreateVisitRequest.DimensionScore("PRESENTATION", 8.0));
        }
        return new CreateVisitRequest(placeId, visitedAt, rating, dimensions,
                publicReview, privateMemory, List.of(), Visibility.PUBLIC);
    }
}

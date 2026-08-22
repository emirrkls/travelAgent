package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.domain.entity.Place;
import com.emirrkls.phokarta.backend.domain.model.PlaceCategory;
import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.PlaceRepository;
import com.emirrkls.phokarta.backend.repository.SavedPlaceRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.service.CollectionService;
import com.emirrkls.phokarta.backend.service.PlaceService;
import com.emirrkls.phokarta.backend.service.VisitService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
class PostgisIntegrationTest {

    private static final UUID DEMO_USER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEMO_COLLECTION =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID DEMO_COLLECTION_PLACE =
            UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private PlaceRepository places;
    @Autowired private SavedPlaceRepository savedPlaces;
    @Autowired private VisitRepository visits;
    @Autowired private CollectionService collectionService;
    @Autowired private PlaceService placeService;
    @Autowired private VisitService visitService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;

    @Test
    void persistsLongitudeAndLatitudeInPostgisCoordinateOrder() {
        UUID placeId = insertPlace("Coordinate Point", PlaceCategory.NATURE,
                29.123456, 40.654321);

        entityManager.clear();
        Place place = places.findById(placeId).orElseThrow();
        Point location = place.getLocation();

        assertThat(location.getSRID()).isEqualTo(4326);
        assertThat(location.getX()).isEqualTo(29.123456);
        assertThat(location.getY()).isEqualTo(40.654321);
    }

    @Test
    void nearbyIncludesOnlyRadiusMatchesAndOrdersByGeodesicDistance() {
        UUID nearest = insertPlace("Nearest", PlaceCategory.CAFE, 0.001, 0.0);
        UUID farther = insertPlace("Farther", PlaceCategory.CAFE, 0.010, 0.0);
        insertPlace("Outside", PlaceCategory.CAFE, 0.100, 0.0);

        var rows = places.findNearby(0.0, 0.0, 2_000, PlaceCategory.CAFE.name(), null, 10);

        assertThat(rows).extracting(PlaceRepository.DistanceRow::getId)
                .containsExactly(nearest, farther);
        assertThat(rows.get(0).getDistanceMeters())
                .isLessThan(rows.get(1).getDistanceMeters());
    }

    @Test
    void schemaProvidesGeometryAndGeographyGistIndexes() {
        List<String> indexDefinitions = jdbc.queryForList("""
                select indexdef from pg_indexes
                where schemaname = current_schema() and tablename = 'places'
                  and indexname in ('idx_places_location_gist',
                                    'idx_places_location_geography_gist')
                order by indexname
                """, String.class);

        assertThat(indexDefinitions).hasSize(2);
        assertThat(indexDefinitions).anyMatch(value -> value.contains("USING gist (location)"));
        assertThat(indexDefinitions).anyMatch(value ->
                value.contains("USING gist (((location)::geography))"));
    }

    @Test
    void boundsReturnsOnlyIntersectingPlacesInCommunityScoreOrder() {
        UUID high = insertPlace("High Score", PlaceCategory.BEACH, 10.20, 10.20);
        UUID low = insertPlace("Low Score", PlaceCategory.BEACH, 10.10, 10.10);
        insertPlace("Outside Bounds", PlaceCategory.BEACH, 11.0, 11.0);
        insertVisit(UUID.randomUUID(), high, 9.1, Visibility.PRIVATE, "", "");
        insertVisit(UUID.randomUUID(), low, 7.2, Visibility.PUBLIC, "", "");

        List<UUID> ids = places.findInBounds(10.0, 10.0, 10.5, 10.5,
                PlaceCategory.BEACH.name(), null, 10).stream()
                .map(PlaceRepository.SummaryRow::getId).toList();

        assertThat(ids).containsExactly(high, low);
    }

    @Test
    void allVisitsAreAggregatedAndUnratedAverageIsNull() {
        UUID rated = insertPlace("Rated", PlaceCategory.ATTRACTION, 15.0, 15.0);
        UUID unrated = insertPlace("Unrated", PlaceCategory.ATTRACTION, 16.0, 16.0);
        insertVisit(UUID.randomUUID(), rated, 6.0, Visibility.PUBLIC, "public one", "");
        insertVisit(UUID.randomUUID(), rated, 10.0, Visibility.PUBLIC, "public two", "");
        insertVisit(UUID.randomUUID(), rated, 1.0, Visibility.PRIVATE, "", "private");

        var detail = placeService.detail(rated);
        var unratedDetail = placeService.detail(unrated);
        VisitRepository.ScoreAggregate unratedAggregate = visits.aggregate(unrated);

        assertThat(detail.averageScore()).isEqualTo(17.0 / 3.0);
        assertThat(detail.ratingCount()).isEqualTo(3);
        assertThat(unratedAggregate.getCount()).isZero();
        assertThat(unratedAggregate.getAverage()).isNull();
        assertThat(unratedDetail.averageScore()).isNull();
        assertThat(unratedDetail.ratingCount()).isZero();
    }

    @Test
    void retainsTwoVisitsForTheSameUserAndPlace() {
        UUID placeId = insertPlace("Repeat Beach", PlaceCategory.BEACH, 20.0, 20.0);
        CreateVisitRequest request = beachVisitRequest(placeId, "first private memory");

        VisitOwnerResponse first = visitService.create(request);
        VisitOwnerResponse second = visitService.create(request);

        Long count = jdbc.queryForObject(
                "select count(*) from visits where user_id = ? and place_id = ?",
                Long.class, DEMO_USER, placeId);
        assertThat(count).isEqualTo(2);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(first.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(second.verificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void savedPlaceInsertIsAtomicAndPreservesOriginalTimestamp() {
        UUID placeId = insertPlace("Atomic Saved Place", PlaceCategory.CAFE, 22.0, 22.0);
        OffsetDateTime firstSavedAt = OffsetDateTime.parse("2026-01-01T10:00:00Z");
        OffsetDateTime laterSavedAt = firstSavedAt.plusDays(1);

        int firstInsert = savedPlaces.insertIfAbsent(DEMO_USER, placeId, firstSavedAt);
        int duplicateInsert = savedPlaces.insertIfAbsent(DEMO_USER, placeId, laterSavedAt);
        OffsetDateTime storedSavedAt = jdbc.queryForObject("""
                select saved_at from saved_places where user_id = ? and place_id = ?
                """, OffsetDateTime.class, DEMO_USER, placeId);

        assertThat(firstInsert).isEqualTo(1);
        assertThat(duplicateInsert).isZero();
        assertThat(storedSavedAt).isEqualTo(firstSavedAt);
    }

    @Test
    void removingCollectionPlaceThroughLockedRepositoryTouchesCollection() {
        OffsetDateTime before = jdbc.queryForObject(
                "select updated_at from collections where id = ?",
                OffsetDateTime.class, DEMO_COLLECTION);

        collectionService.remove(DEMO_COLLECTION, DEMO_USER, DEMO_COLLECTION_PLACE);
        entityManager.flush();

        OffsetDateTime after = jdbc.queryForObject(
                "select updated_at from collections where id = ?",
                OffsetDateTime.class, DEMO_COLLECTION);
        assertThat(after).isAfter(before);
    }

    @Test
    void publicDtoAndEndpointNeverExposePrivateMemory() throws Exception {
        UUID placeId = insertPlace("Public Review Place", PlaceCategory.BEACH,
                21.0, 21.0);
        String secret = "never serialize this private memory";
        visitService.create(beachVisitRequest(placeId, secret));

        assertThat(Arrays.stream(
                com.emirrkls.phokarta.backend.api.dto.PublicVisitResponse.class
                        .getRecordComponents())
                .map(component -> component.getName()))
                .doesNotContain("privateMemory");

        String response = mockMvc.perform(get("/api/v1/places/{placeId}/reviews", placeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].publicReview").value("public review"))
                .andExpect(jsonPath("$.content[0].privateMemory").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(secret);
    }

    @Test
    void openApiKeepsVerificationOutOfCreateRequestsButInResponses() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.components.schemas.CreateVisitRequest.properties.verificationStatus")
                        .doesNotExist())
                .andExpect(jsonPath(
                        "$.components.schemas.VisitOwnerResponse.properties.verificationStatus")
                        .exists())
                .andExpect(jsonPath(
                        "$.components.schemas.PublicVisitResponse.properties.verificationStatus")
                        .exists());
    }

    private UUID insertPlace(String name, PlaceCategory category, double longitude,
                             double latitude) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (
                    ?, ?, 'integration test place', ?, array[]::text[],
                    ST_SetSRID(ST_MakePoint(?, ?), 4326), 'Test City', 'Test Region',
                    'Test Country', 'Test Address', 'https://example.test/cover.jpg',
                    array[]::text[], 2, now(), now()
                )
                """, id, name, category.name(), longitude, latitude);
        return id;
    }

    private void insertVisit(UUID id, UUID placeId, double rating, Visibility visibility,
                             String publicReview, String privateMemory) {
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, ?, ?, ?, array[]::text[], ?,
                    'LOCATION_CONFIRMED', now(), now())
                """, id, DEMO_USER, placeId, rating, publicReview, privateMemory,
                visibility.name());
    }

    private CreateVisitRequest beachVisitRequest(UUID placeId, String privateMemory) {
        List<CreateVisitRequest.DimensionScore> dimensions = List.of(
                new CreateVisitRequest.DimensionScore("SEA", 9.0),
                new CreateVisitRequest.DimensionScore("ATMOSPHERE", 8.0));
        return new CreateVisitRequest(DEMO_USER, placeId, LocalDate.of(2025, 8, 1), 8.5,
                dimensions, "public review", privateMemory, List.of(), Visibility.PUBLIC);
    }
}

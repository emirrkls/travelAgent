package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateCollectionRequest;
import com.emirrkls.phokarta.backend.api.dto.CreateExperienceV2Request;
import com.emirrkls.phokarta.backend.api.dto.ExperienceAcknowledgementV2Response;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.CompanionCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TimeOfDayCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TitleSource;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.ExperienceAcknowledgementRepository;
import com.emirrkls.phokarta.backend.service.CollectionService;
import com.emirrkls.phokarta.backend.service.ExperienceAcknowledgementService;
import com.emirrkls.phokarta.backend.service.ExperiencePlanService;
import com.emirrkls.phokarta.backend.service.ExperienceWriteService;
import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class Milestone4LifecycleIntegrationTest {
    private static final UUID PLANNER = UUID.fromString("11111111-1111-1111-1111-111111111451");
    private static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111452");
    private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111453");
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000451");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Autowired JdbcTemplate jdbc;
    @Autowired ExperiencePlanService plans;
    @Autowired CollectionService collections;
    @Autowired ExperienceAcknowledgementService acknowledgements;
    @Autowired ExperienceWriteService writes;
    @Autowired ExperienceAcknowledgementRepository acknowledgementRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        jdbc.update("delete from places");
        user(PLANNER, "m4_planner", "PUBLIC");
        user(AUTHOR, "m4_author", "PUBLIC");
        user(VIEWER, "m4_viewer", "PUBLIC");
        place();
        PolicyAcceptanceSupport.acceptCurrent(jdbc, PLANNER);
        PolicyAcceptanceSupport.acceptCurrent(jdbc, AUTHOR);
        PolicyAcceptanceSupport.acceptCurrent(jdbc, VIEWER);
    }

    @Test
    void plannedExperienceAndMixedCollectionAreIdempotentOrderedPrivateAndDeletionSafe() {
        UUID source = experience(AUTHOR, "PUBLIC");

        plans.save(PLANNER, source);
        plans.save(PLANNER, source);
        assertThat(count("select count(*) from planned_experiences where user_id = ? and experience_id = ?",
                PLANNER, source)).isEqualTo(1);
        assertThat(plans.list(PLANNER, 0, 20).content()).hasSize(1);

        UUID collection = collections.create(PLANNER,
                new CreateCollectionRequest("Mixed", "", Visibility.PUBLIC, "")).id();
        collections.add(collection, PLANNER, PLACE);
        collections.addExperience(collection, PLANNER, source);
        collections.addExperience(collection, PLANNER, source);

        var ownerDetail = collections.detailV2(collection, PLANNER);
        assertThat(ownerDetail.items()).extracting(item -> item.type().name())
                .containsExactly("PLACE", "EXPERIENCE");
        assertThat(ownerDetail.items()).extracting(item -> item.displayOrder())
                .containsExactly(0, 1);
        assertThat(count("select count(*) from collection_experiences where collection_id = ?", collection))
                .isEqualTo(1);
        assertThat(collections.detail(collection, PLANNER).places()).hasSize(1);

        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, now())",
                AUTHOR, VIEWER);
        assertThat(collections.detailV2(collection, VIEWER).items())
                .extracting(item -> item.type().name()).containsExactly("PLACE");
        assertThatThrownBy(() -> plans.save(VIEWER, source)).isInstanceOf(ApiException.class);

        jdbc.update("update visits set visibility = 'PRIVATE' where id = ?", source);
        assertThat(plans.list(PLANNER, 0, 20).content()).isEmpty();

        jdbc.update("delete from visits where id = ?", source);
        assertThat(count("select count(*) from planned_experiences")).isZero();
        assertThat(count("select count(*) from collection_experiences")).isZero();
        assertThat(count("select count(*) from collection_places where collection_id = ?", collection)).isEqualTo(1);
    }

    @Test
    void acknowledgementIsIdempotentCreatesNoContentAndConversionRetryKeepsTheCount() {
        UUID source = experience(AUTHOR, "PUBLIC");
        int visitsBefore = count("select count(*) from visits");

        var first = acknowledgements.acknowledge(PLANNER, source);
        var duplicate = acknowledgements.acknowledge(PLANNER, source);
        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(count("select count(*) from experience_acknowledgements where source_experience_id = ?", source))
                .isEqualTo(1);
        assertThat(count("select count(*) from visits")).isEqualTo(visitsBefore);
        assertThatThrownBy(() -> acknowledgements.acknowledge(AUTHOR, source))
                .isInstanceOf(ApiException.class);

        UUID mutation = UUID.randomUUID();
        var request = publication(mutation, first.id());
        var published = writes.create(PLANNER, request);
        var retry = writes.create(PLANNER, request);
        assertThat(retry.id()).isEqualTo(published.id());
        assertThat(acknowledgements.listUnconverted(PLANNER, PLANNER, 0, 20).content()).isEmpty();
        assertThat(count("select count(*) from experience_acknowledgements where source_experience_id = ?", source))
                .isEqualTo(1);

        jdbc.update("delete from visits where id = ?", published.id());
        var historical = acknowledgementRepository.findById(first.id()).orElseThrow();
        assertThat(historical.isConverted()).isTrue();
        assertThat(historical.getConvertedExperience()).isNull();
        assertThat(acknowledgements.listUnconverted(PLANNER, PLANNER, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> writes.create(PLANNER, publication(UUID.randomUUID(), first.id())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void deletedSourceAndSourceAuthorStillLeaveAnAnchorThatCanBeConverted() {
        UUID source = experience(AUTHOR, "PUBLIC");
        jdbc.update("delete from users where id = ?", AUTHOR);
        var acknowledgement = acknowledgements.acknowledge(
                PLANNER, source, UUID.randomUUID(), PLACE, PrimaryExperienceCode.GUN_BATIMI, null);
        var durable = acknowledgementRepository.findById(acknowledgement.id()).orElseThrow();
        assertThat(durable.getSourceExperience()).isNull();
        assertThat(durable.getPlace().getId()).isEqualTo(PLACE);
        assertThat(durable.getPrimaryExperienceCode()).isEqualTo(PrimaryExperienceCode.GUN_BATIMI);

        var ownerHistory = acknowledgements.listUnconverted(PLANNER, PLANNER, 0, 20);
        assertThat(ownerHistory.content()).singleElement().satisfies(value -> {
            assertThat(value.sourceAvailable()).isFalse();
            assertThat(value.sourceExperience()).isNull();
            assertThat(value.place().id()).isEqualTo(PLACE);
        });

        var published = writes.create(PLANNER, publication(UUID.randomUUID(), acknowledgement.id()));
        assertThat(published.place().id()).isEqualTo(PLACE);
        assertThat(published.primaryExperience().code()).isEqualTo(PrimaryExperienceCode.GUN_BATIMI);
        assertThat(acknowledgements.listUnconverted(PLANNER, PLANNER, 0, 20).content()).isEmpty();

        jdbc.update("delete from users where id = ?", PLANNER);
        assertThat(count("select count(*) from experience_acknowledgements")).isZero();
    }

    @Test
    void profilePrivacyAndBlockPreventAcknowledgementAndDoNotLeakHiddenRowsOrTotals() {
        UUID source = experience(AUTHOR, "PUBLIC");
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, now())",
                AUTHOR, PLANNER);
        assertThatThrownBy(() -> acknowledgements.acknowledge(PLANNER, source))
                .isInstanceOf(ApiException.class);
        jdbc.update("delete from user_blocks");

        acknowledgements.acknowledge(PLANNER, source);
        jdbc.update("update users set profile_visibility = 'PRIVATE' where id = ?", PLANNER);
        assertThatThrownBy(() -> acknowledgements.listUnconverted(PLANNER, VIEWER, 0, 20))
                .isInstanceOf(ApiException.class);

        jdbc.update("update users set profile_visibility = 'PUBLIC' where id = ?", PLANNER);
        jdbc.update("update visits set visibility = 'PRIVATE' where id = ?", source);
        var filtered = acknowledgements.listUnconverted(PLANNER, VIEWER, 0, 20);
        assertThat(filtered.content()).isEmpty();
        assertThat(filtered.totalElements()).isZero();
        assertThat(filtered.hasNext()).isFalse();
    }

    private CreateExperienceV2Request publication(UUID mutation, UUID acknowledgementId) {
        return new CreateExperienceV2Request(
                mutation, PLACE, LocalDate.of(2026, 9, 17), PrimaryExperienceCode.GUN_BATIMI,
                null, OverallFeelingCode.GUZELDI, CompanionCode.ALONE, TimeOfDayCode.EVENING,
                List.of(), List.of(), List.of(), null, TitleSource.GENERATED,
                "My independent story", null, "private memory", Visibility.PUBLIC,
                List.of(), acknowledgementId);
    }

    private void user(UUID id, String username, String profileVisibility) {
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, profile_visibility,
                    created_at, updated_at
                ) values (?, ?, ?, ?, true, 0, 0, 0, 0, '{}', ?, now(), now())
                """, id, username + "@example.test", username, username, profileVisibility);
    }

    private void place() {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (?, 'Milestone Place', '', 'BEACH', '{}',
                    ST_SetSRID(ST_MakePoint(29.0, 41.0), 4326), 'Istanbul', 'Marmara',
                    'Turkiye', '', '', '{}', 1, now(), now())
                """, PLACE);
    }

    private UUID experience(UUID authorId, String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, 8, 'Source story', 'source private memory', '{}', ?,
                    'UNVERIFIED', now(), now())
                """, id, authorId, PLACE, visibility);
        jdbc.update("""
                insert into visit_experience_details (
                    visit_id, primary_experience_code, overall_feeling_code, feeling_source,
                    companion_code, time_of_day_code, title, title_source, story, tip,
                    taxonomy_version, created_at, updated_at
                ) values (?, 'GUN_BATIMI', 'GUZELDI', 'EXPLICIT', 'PARTNER', 'EVENING',
                    'Sunset', 'CUSTOM', 'Source story', 'Source tip', 1, now(), now())
                """, id);
        return id;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}

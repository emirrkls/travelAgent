package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CursorPageResponse;
import com.emirrkls.phokarta.backend.api.dto.ExperienceSummaryV2Response;
import com.emirrkls.phokarta.backend.domain.model.ExperienceFeedLens;
import com.emirrkls.phokarta.backend.service.ExperienceFeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ExperienceFeedV2IntegrationTest {
    private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111251");
    private static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111252");
    private static final UUID OTHER = UUID.fromString("11111111-1111-1111-1111-111111111253");
    private static final UUID PLACE_A = UUID.fromString("20000000-0000-0000-0000-000000000251");
    private static final UUID PLACE_B = UUID.fromString("20000000-0000-0000-0000-000000000252");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ExperienceFeedService feeds;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        jdbc.update("delete from places");
        user(VIEWER, "feed_viewer", "PUBLIC");
        user(AUTHOR, "feed_author", "PUBLIC");
        user(OTHER, "feed_other", "PUBLIC");
        place(PLACE_A, "Near Beach", 29.000, 41.000);
        place(PLACE_B, "Far Cafe", 30.000, 42.000);
    }

    @Test
    void forYouAnonymousAndPrivateProfileBoundariesAreAppliedInSql() {
        UUID publicCard = visit(AUTHOR, PLACE_A, "PUBLIC", 1, "public-secret");
        visit(AUTHOR, PLACE_A, "FRIENDS", 2, "friends-secret");
        jdbc.update("update users set profile_visibility = 'PRIVATE' where id = ?", OTHER);
        UUID hiddenProfileCard = visit(OTHER, PLACE_B, "PUBLIC", 3, "profile-secret");

        var anonymous = explore(ExperienceFeedLens.FOR_YOU, null, null, 20, null, null, null);
        assertThat(ids(anonymous)).contains(publicCard).doesNotContain(hiddenProfileCard);
        follow(VIEWER, OTHER);
        var follower = explore(ExperienceFeedLens.FOR_YOU, VIEWER, null, 20, null, null, null);
        assertThat(ids(follower)).contains(publicCard, hiddenProfileCard);
    }

    @Test
    void followingUsesApprovedOneWayFollowButFriendsVisibilityStillRequiresMutuality() {
        UUID publicCard = visit(AUTHOR, PLACE_A, "PUBLIC", 1, "secret-a");
        UUID friendsCard = visit(AUTHOR, PLACE_A, "FRIENDS", 2, "secret-b");
        follow(VIEWER, AUTHOR);

        var oneWay = explore(ExperienceFeedLens.FOLLOWING, VIEWER, null, 20, null, null, null);
        assertThat(ids(oneWay)).contains(publicCard).doesNotContain(friendsCard);
        follow(AUTHOR, VIEWER);
        var mutual = explore(ExperienceFeedLens.FOLLOWING, VIEWER, null, 20, null, null, null);
        assertThat(ids(mutual)).contains(publicCard, friendsCard);
    }

    @Test
    void symmetricBlockAppliesToEveryExploreLens() {
        visit(AUTHOR, PLACE_A, "PUBLIC", 1, "blocked-secret");
        follow(VIEWER, AUTHOR);
        jdbc.update("insert into user_blocks values (?, ?, now())", AUTHOR, VIEWER);

        for (ExperienceFeedLens lens : ExperienceFeedLens.values()) {
            CursorPageResponse<ExperienceSummaryV2Response> page = lens == ExperienceFeedLens.NEARBY
                    ? feeds.explore(lens, VIEWER, null, 20, null, null, null,
                            41.0, 29.0, 10_000.0)
                    : explore(lens, VIEWER, null, 20, null, null, null);
            assertThat(page.items()).isEmpty();
        }
    }

    @Test
    void privateProfileBoundaryAppliesToEveryExploreLens() {
        UUID card = visit(OTHER, PLACE_A, "PUBLIC", 1, "private-profile-secret");
        jdbc.update("update users set profile_visibility = 'PRIVATE' where id = ?", OTHER);

        for (ExperienceFeedLens lens : ExperienceFeedLens.values()) {
            CursorPageResponse<ExperienceSummaryV2Response> hidden = lens == ExperienceFeedLens.NEARBY
                    ? feeds.explore(lens, VIEWER, null, 20, null, null, null,
                            41.0, 29.0, 10_000.0)
                    : explore(lens, VIEWER, null, 20, null, null, null);
            assertThat(ids(hidden)).doesNotContain(card);
        }

        follow(VIEWER, OTHER);
        for (ExperienceFeedLens lens : ExperienceFeedLens.values()) {
            CursorPageResponse<ExperienceSummaryV2Response> visible = lens == ExperienceFeedLens.NEARBY
                    ? feeds.explore(lens, VIEWER, null, 20, null, null, null,
                            41.0, 29.0, 10_000.0)
                    : explore(lens, VIEWER, null, 20, null, null, null);
            assertThat(ids(visible)).contains(card);
        }
    }

    @Test
    void cursorIsStableDeterministicAndHasNoDuplicatesAcrossPages() {
        for (int index = 0; index < 7; index++) {
            visit(index % 2 == 0 ? AUTHOR : OTHER,
                    index % 3 == 0 ? PLACE_B : PLACE_A, "PUBLIC", index, "secret-" + index);
        }
        var first = explore(ExperienceFeedLens.FOR_YOU, VIEWER, null, 3, null, null, null);
        var repeat = explore(ExperienceFeedLens.FOR_YOU, VIEWER, null, 3, null, null, null);
        var second = explore(ExperienceFeedLens.FOR_YOU, VIEWER, first.nextCursor(), 3, null, null, null);
        assertThat(ids(repeat)).containsExactlyElementsOf(ids(first));
        assertThat(first.hasMore()).isTrue();
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
    }

    @Test
    void nearbyUsesCanonicalPlaceGeographyAndKeepsPrivacyFilter() {
        UUID near = visit(AUTHOR, PLACE_A, "PUBLIC", 1, "near-secret");
        visit(OTHER, PLACE_B, "PUBLIC", 2, "far-secret");
        jdbc.update("update users set profile_visibility = 'PRIVATE' where id = ?", AUTHOR);
        var anonymous = feeds.explore(ExperienceFeedLens.NEARBY, null, null, 20,
                null, null, null, 41.0, 29.0, 20_000.0);
        assertThat(ids(anonymous)).doesNotContain(near);
        follow(VIEWER, AUTHOR);
        var viewer = feeds.explore(ExperienceFeedLens.NEARBY, VIEWER, null, 20,
                null, null, null, 41.0, 29.0, 20_000.0);
        assertThat(ids(viewer)).containsExactly(near);
        assertThat(viewer.items().getFirst().place().distanceMeters()).isLessThan(100.0);
    }

    @Test
    void popularUsesRealPlaceCommunityContributionCountThenRecency() {
        UUID lessPopular = visit(AUTHOR, PLACE_A, "PUBLIC", 1, "a");
        UUID popular = visit(OTHER, PLACE_B, "PUBLIC", 2, "b");
        visit(AUTHOR, PLACE_B, "FRIENDS", 3, "c");
        visit(OTHER, PLACE_B, "PRIVATE", 4, "excluded");

        var page = explore(ExperienceFeedLens.POPULAR, null, null, 20, null, null, null);
        assertThat(ids(page).getFirst()).isEqualTo(popular);
        assertThat(ids(page)).contains(lessPopular);
    }

    @Test
    void placeAndProfileFeedsApplyFiltersAndLegacyCompatibilitySafely() throws Exception {
        UUID legacy = visit(AUTHOR, PLACE_A, "PUBLIC", 1, "LEGACY_PRIVATE_MEMORY");
        UUID nativeCard = visit(AUTHOR, PLACE_A, "PUBLIC", 2, "NATIVE_PRIVATE_MEMORY");
        nativeDetail(nativeCard, "GUN_BATIMI", "CALM");
        visit(OTHER, PLACE_B, "PUBLIC", 3, "other-secret");

        var place = feeds.forPlace(PLACE_A, VIEWER, null, 20, "GUN_BATIMI");
        assertThat(ids(place)).containsExactly(nativeCard);
        var profile = feeds.forProfile(AUTHOR, VIEWER, null, 20);
        assertThat(ids(profile)).containsExactlyInAnyOrder(legacy, nativeCard);
        String json = objectMapper.writeValueAsString(profile);
        assertThat(json).doesNotContain("privateMemory", "LEGACY_PRIVATE_MEMORY", "NATIVE_PRIVATE_MEMORY");
        assertThat(profile.items().stream().filter(value -> value.id().equals(legacy)).findFirst()
                .orElseThrow().primaryExperience().code()).isEqualTo("UNKNOWN_LEGACY");
        assertThat(profile.items().stream().filter(value -> value.id().equals(nativeCard)).findFirst()
                .orElseThrow().vibes()).containsExactly(
                        com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode.CALM);
    }

    @Test
    void deletedExperienceDisappearsAndPlaceDistributionUsesVisiblePopulation() {
        UUID legacy = visit(AUTHOR, PLACE_A, "PUBLIC", 1, "secret");
        UUID nativeCard = visit(OTHER, PLACE_A, "PUBLIC", 2, "secret");
        nativeDetail(nativeCard, "PLAJ", null);
        assertThat(feeds.primaryDistribution(PLACE_A, null))
                .extracting(value -> value.code())
                .containsExactlyInAnyOrder("UNKNOWN_LEGACY", "PLAJ");
        jdbc.update("delete from visits where id = ?", legacy);
        assertThat(ids(feeds.forPlace(PLACE_A, null, null, 20, null)))
                .containsExactly(nativeCard);
    }

    @Test
    void searchMatchesPlaceAndCanonicalAliasWithoutClaimingSemanticUnderstanding() {
        UUID byPlace = visit(AUTHOR, PLACE_A, "PUBLIC", 1, "secret");
        UUID byAlias = visit(OTHER, PLACE_B, "PUBLIC", 2, "secret");
        nativeDetail(byAlias, "GUN_BATIMI", null);
        assertThat(ids(explore(ExperienceFeedLens.FOR_YOU, null, null, 20,
                "Near Beach", null, null))).contains(byPlace);
        assertThat(ids(explore(ExperienceFeedLens.FOR_YOU, null, null, 20,
                "sunset", null, null))).contains(byAlias);
    }

    private CursorPageResponse<ExperienceSummaryV2Response> explore(
            ExperienceFeedLens lens, UUID viewer, String cursor, int size,
            String search, String primary, String vibe) {
        return feeds.explore(lens, viewer, cursor, size, search, primary, vibe,
                null, null, 10_000.0);
    }

    private List<UUID> ids(CursorPageResponse<ExperienceSummaryV2Response> page) {
        return page.items().stream().map(ExperienceSummaryV2Response::id).toList();
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

    private void place(UUID id, String name, double longitude, double latitude) {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (?, ?, '', 'BEACH', '{}',
                    ST_SetSRID(ST_MakePoint(?, ?), 4326), 'İzmir', 'Aegean',
                    'Türkiye', '', '', '{}', 1, now(), now())
                """, id, name, longitude, latitude);
    }

    private UUID visit(UUID userId, UUID placeId, String visibility, int minutes, String memory) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, 8, 'A real public story', ?, '{}', ?,
                    'UNVERIFIED', now() - (? * interval '1 minute'), now())
                """, id, userId, placeId, memory, visibility, minutes);
        return id;
    }

    private void nativeDetail(UUID visitId, String primary, String vibe) {
        jdbc.update("""
                insert into visit_experience_details (
                    visit_id, primary_experience_code, overall_feeling_code, feeling_source,
                    companion_code, time_of_day_code, title, title_source, story, tip,
                    taxonomy_version, created_at, updated_at
                ) values (?, ?, 'GUZELDI', 'EXPLICIT', 'FRIENDS', 'EVENING',
                    'A persisted title', 'GENERATED', 'A native story', 'Arrive early',
                    1, now(), now())
                """, visitId, primary);
        if (vibe != null) {
            jdbc.update("insert into visit_experience_vibes values (?, ?, 0)", visitId, vibe);
        }
    }

    private void follow(UUID follower, UUID followed) {
        jdbc.update("""
                insert into user_follows values (?, ?, now()) on conflict do nothing
                """, follower, followed);
    }
}

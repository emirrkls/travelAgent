package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.PlaceAggregateV2Response;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import com.emirrkls.phokarta.backend.service.BlockService;
import com.emirrkls.phokarta.backend.service.FollowRequestService;
import com.emirrkls.phokarta.backend.service.PlaceAggregateV2Service;
import com.emirrkls.phokarta.backend.service.ProfileV2Service;
import com.emirrkls.phokarta.backend.service.ViewerAccessPolicy;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PrivacyAggregateIntegrationTest {
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111191");
    private static final UUID B = UUID.fromString("11111111-1111-1111-1111-111111111192");
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000191");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FollowRequestService followRequests;
    @Autowired private ProfileV2Service profiles;
    @Autowired private PlaceAggregateV2Service aggregates;
    @Autowired private ViewerAccessPolicy access;
    @Autowired private VisitRepository visits;
    @Autowired private BlockService blocks;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        jdbc.update("delete from places");
        user(A, "privacy_a", "PUBLIC");
        user(B, "privacy_b", "PUBLIC");
        place();
    }

    @Test
    void privateFollowRequestLifecycleKeepsPendingSeparateFromApprovedEdge() {
        profiles.updateVisibility(B, ProfileVisibility.PRIVATE);
        followRequests.follow(A, B);
        followRequests.follow(A, B);
        assertThat(count("select count(*) from follow_requests where status = 'PENDING'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from user_follows where follower_user_id = ? and followed_user_id = ?", A, B))
                .isZero();

        UUID requestId = jdbc.queryForObject(
                "select id from follow_requests where requester_user_id = ? and target_user_id = ? and status = 'PENDING'",
                UUID.class, A, B);
        followRequests.approve(B, requestId);
        assertThat(count("select count(*) from user_follows where follower_user_id = ? and followed_user_id = ?", A, B))
                .isEqualTo(1);
        assertThat(count("select count(*) from follow_requests where status = 'APPROVED'"))
                .isEqualTo(1);
    }

    @Test
    void rejectCancelAndUnfollowNeverCreatePendingOrApprovedStateUnexpectedly() {
        profiles.updateVisibility(B, ProfileVisibility.PRIVATE);
        followRequests.follow(A, B);
        UUID rejected = pendingId(A, B);
        followRequests.reject(B, rejected);
        assertThat(count("select count(*) from user_follows")).isZero();

        followRequests.follow(A, B);
        followRequests.cancel(A, B);
        assertThat(count("select count(*) from follow_requests where status = 'PENDING'")).isZero();
        jdbc.update("update users set profile_visibility = 'PUBLIC' where id = ?", B);
        followRequests.follow(A, B);
        followRequests.unfollow(A, B);
        assertThat(count("select count(*) from user_follows")).isZero();
        assertThat(count("select count(*) from follow_requests where status = 'PENDING'")).isZero();
    }

    @Test
    void friendRequiresMutualApprovedFollows() {
        followRequests.follow(A, B);
        assertThat(followRequests.relationship(A, B).state().name()).isEqualTo("FOLLOWING");
        followRequests.follow(B, A);
        assertThat(followRequests.relationship(A, B).state().name()).isEqualTo("FRIENDS");
    }

    @Test
    void blockCancelsRequestsRemovesBothEdgesAndUnblockRestoresNothing() {
        profiles.updateVisibility(B, ProfileVisibility.PRIVATE);
        followRequests.follow(A, B);
        blocks.block(B, A);
        assertThat(count("select count(*) from follow_requests where status = 'PENDING'")).isZero();

        blocks.unblock(B, A);
        jdbc.update("update users set profile_visibility = 'PUBLIC' where id = ?", B);
        followRequests.follow(A, B);
        followRequests.follow(B, A);
        blocks.block(B, A);
        assertThat(count("select count(*) from user_follows")).isZero();
        blocks.unblock(B, A);
        assertThat(count("select count(*) from user_follows")).isZero();
        assertThat(count("select count(*) from follow_requests where status = 'PENDING'")).isZero();
    }

    @Test
    void accountDeletionCascadesRequestForEitherParticipant() {
        profiles.updateVisibility(B, ProfileVisibility.PRIVATE);
        followRequests.follow(A, B);
        jdbc.update("delete from users where id = ?", A);
        assertThat(count("select count(*) from follow_requests")).isZero();
    }

    @Test
    void profilePrivacyIsUpperBoundWhileOwnerFollowerAndFriendCountsDiffer() {
        jdbc.update("update users set profile_visibility = 'PRIVATE' where id = ?", B);
        UUID publicVisit = visit(B, "PUBLIC", 9.0);
        visit(B, "FRIENDS", 7.0);
        visit(B, "PRIVATE", 5.0);
        Visit loaded = visits.findDetailedById(publicVisit).orElseThrow();

        assertThat(access.canViewVisit(loaded, null)).isFalse();
        assertThat(profiles.profile(B, null).fullProfile()).isFalse();
        assertThat(profiles.profile(B, A).fullProfile()).isFalse();
        followRequests.follow(A, B);
        UUID request = pendingId(A, B);
        followRequests.approve(B, request);
        assertThat(access.canViewVisit(loaded, A)).isTrue();
        assertThat(profiles.profile(B, A).visibleExperienceCount()).isEqualTo(1L);
        followRequests.follow(B, A);
        assertThat(profiles.profile(B, A).visibleExperienceCount()).isEqualTo(2L);
        assertThat(profiles.profile(B, B).visibleExperienceCount()).isEqualTo(3L);
    }

    @Test
    void globalAggregateIncludesPublicAndFriendsAcrossPrivateProfileButExcludesPrivate() {
        jdbc.update("update users set profile_visibility = 'PRIVATE' where id = ?", B);
        UUID publicVisit = visit(B, "PUBLIC", 9.5);
        UUID friendsVisit = visit(B, "FRIENDS", 4.0);
        visit(B, "PRIVATE", 8.0);
        nativeDetail(friendsVisit, "EH_ISTE");
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, now())", A, B);

        PlaceAggregateV2Response anonymous = aggregates.get(PLACE, null);
        PlaceAggregateV2Response blockedViewer = aggregates.get(PLACE, A);
        assertThat(anonymous.visibleExperienceCount()).isZero();
        assertThat(blockedViewer.visibleExperienceCount()).isZero();
        assertThat(anonymous.communityContributionCount()).isEqualTo(2L);
        assertThat(blockedViewer.communityContributionCount()).isEqualTo(2L);
        assertThat(feeling(anonymous, OverallFeelingCode.BAYILDIM)).isEqualTo(1L);
        assertThat(feeling(anonymous, OverallFeelingCode.EH_ISTE)).isEqualTo(1L);
        assertThat(visits.aggregate(PLACE).getCount()).isEqualTo(1L);
        assertThat(access.canViewVisit(visits.findDetailedById(publicVisit).orElseThrow(), A)).isFalse();
    }

    @Test
    void practicalAndDimensionAggregatesPreserveLegacyNumericSemantics() {
        UUID legacy = visit(A, "PUBLIC", 8.0);
        UUID nativeVisit = visit(B, "FRIENDS", 10.0);
        jdbc.update("insert into visit_dimension_scores (visit_id, dimension_key, score) values (?, 'SEA', 6)", legacy);
        nativeDetail(nativeVisit, "BAYILDIM");
        jdbc.update("""
                insert into visit_dimension_scores (
                    visit_id, dimension_key, score, semantic_state_code, template_version
                ) values (?, 'SEA', 10, 'VERY_GOOD', 1)
                """, nativeVisit);
        jdbc.update("insert into visit_experience_practical_signals values (?, 'ARRIVE_EARLY', 0)", nativeVisit);

        PlaceAggregateV2Response response = aggregates.get(PLACE, null);
        var dimension = response.dimensions().getFirst();
        assertThat(dimension.key()).isEqualTo("SEA");
        assertThat(dimension.contributionCount()).isEqualTo(2L);
        assertThat(dimension.numericAverage()).isEqualTo(8.0);
        assertThat(dimension.legacyNumericContributionCount()).isEqualTo(1L);
        assertThat(dimension.semanticDistribution()).singleElement().satisfies(state ->
                assertThat(state.contributionCount()).isEqualTo(1L));
        assertThat(response.practicalSignals()).singleElement().satisfies(signal -> {
            assertThat(signal.contributionCount()).isEqualTo(1L);
            assertThat(signal.eligibleContributionDenominator()).isEqualTo(2L);
        });
    }

    private void user(UUID id, String username, String visibility) {
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, profile_visibility,
                    created_at, updated_at
                ) values (?, ?, ?, ?, true, 0, 0, 0, 0, '{}', ?, now(), now())
                """, id, username + "@example.test", username, username, visibility);
    }

    private void place() {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (?, 'Privacy Place', '', 'BEACH', '{}',
                    ST_SetSRID(ST_MakePoint(26.75, 38.67), 4326), 'İzmir', 'Aegean',
                    'Türkiye', '', '', '{}', 1, now(), now())
                """, PLACE);
    }

    private UUID visit(UUID userId, String visibility, double rating) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, ?, '', 'owner-only', '{}', ?,
                    'UNVERIFIED', now(), now())
                """, id, userId, PLACE, rating, visibility);
        return id;
    }

    private void nativeDetail(UUID visitId, String feeling) {
        jdbc.update("""
                insert into visit_experience_details (
                    visit_id, primary_experience_code, overall_feeling_code, feeling_source,
                    title, title_source, story, taxonomy_version, created_at, updated_at
                ) values (?, 'PLAJ', ?, 'EXPLICIT', 'Title', 'GENERATED', '', 1, now(), now())
                """, visitId, feeling);
    }

    private UUID pendingId(UUID requester, UUID target) {
        return jdbc.queryForObject("""
                select id from follow_requests
                where requester_user_id = ? and target_user_id = ? and status = 'PENDING'
                """, UUID.class, requester, target);
    }

    private long feeling(PlaceAggregateV2Response response, OverallFeelingCode code) {
        return response.feelings().stream().filter(row -> row.code() == code)
                .findFirst().orElseThrow().contributionCount();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}

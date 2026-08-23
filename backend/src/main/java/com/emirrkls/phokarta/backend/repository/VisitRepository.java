package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface VisitRepository extends JpaRepository<Visit, UUID> {
    @EntityGraph(attributePaths = {"place", "user"})
    Page<Visit> findByUserIdOrderByVisitedAtDescCreatedAtDescIdDesc(
            UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "place"})
    Page<Visit> findByPlaceIdAndVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
            UUID placeId, Visibility visibility, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "place"})
    Page<Visit> findByVisibilityOrderByVisitedAtDescCreatedAtDescIdDesc(
            Visibility visibility, Pageable pageable);

    /**
     * Friends Activity: PUBLIC visits by mutual friends of viewer, excluding self.
     * Mutual = viewer→author AND author→viewer. Ordering matches community feed.
     */
    @EntityGraph(attributePaths = {"user", "place"})
    @Query("""
            select v from Visit v
            where v.visibility = :visibility
              and v.user.id <> :viewerId
              and exists (
                  select 1 from UserFollow outbound
                  where outbound.id.followerUserId = :viewerId
                    and outbound.id.followedUserId = v.user.id
              )
              and exists (
                  select 1 from UserFollow inbound
                  where inbound.id.followerUserId = v.user.id
                    and inbound.id.followedUserId = :viewerId
              )
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    Page<Visit> findFriendsActivity(
            @Param("viewerId") UUID viewerId,
            @Param("visibility") Visibility visibility,
            Pageable pageable);

    /**
     * Friends reviews for a place: PUBLIC visits by mutual friends, excluding self.
     */
    @EntityGraph(attributePaths = {"user", "place"})
    @Query("""
            select v from Visit v
            where v.place.id = :placeId
              and v.visibility = :visibility
              and v.user.id <> :viewerId
              and exists (
                  select 1 from UserFollow outbound
                  where outbound.id.followerUserId = :viewerId
                    and outbound.id.followedUserId = v.user.id
              )
              and exists (
                  select 1 from UserFollow inbound
                  where inbound.id.followerUserId = v.user.id
                    and inbound.id.followedUserId = :viewerId
              )
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    Page<Visit> findFriendsReviews(
            @Param("placeId") UUID placeId,
            @Param("viewerId") UUID viewerId,
            @Param("visibility") Visibility visibility,
            Pageable pageable);

    @Query("""
            select v from Visit v join fetch v.user join fetch v.place
            where v.place.id = :placeId and v.visibility = :visibility
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    List<Visit> findRecent(@Param("placeId") UUID placeId,
                           @Param("visibility") Visibility visibility, Pageable pageable);

    interface ScoreAggregate {
        Double getAverage();
        long getCount();
    }

    @Query("""
            select avg(v.overallRating) as average, count(v) as count
            from Visit v where v.place.id = :placeId
            """)
    ScoreAggregate aggregate(@Param("placeId") UUID placeId);

    interface FriendScoreAggregate {
        Double getAverageScore();
        long getFriendsVisitedCount();
    }

    /**
     * User-weighted friends score: AVG(per-friend AVG(overall_rating)).
     * friendsVisitedCount = distinct mutual friends with ≥1 PUBLIC visit.
     */
    @Query(value = """
            select avg(friend_avg) as "averageScore",
                   count(*) as "friendsVisitedCount"
            from (
                select avg(v.overall_rating) as friend_avg
                from visits v
                where v.place_id = :placeId
                  and v.visibility = 'PUBLIC'
                  and v.user_id <> :viewerId
                  and exists (
                      select 1 from user_follows outbound
                      where outbound.follower_user_id = :viewerId
                        and outbound.followed_user_id = v.user_id
                  )
                  and exists (
                      select 1 from user_follows inbound
                      where inbound.follower_user_id = v.user_id
                        and inbound.followed_user_id = :viewerId
                  )
                group by v.user_id
            ) per_friend
            """, nativeQuery = true)
    FriendScoreAggregate aggregateFriendsScore(
            @Param("placeId") UUID placeId,
            @Param("viewerId") UUID viewerId);

    interface FriendPreviewRow {
        UUID getUserId();
        String getDisplayName();
        String getAvatarUrl();
        double getLatestScore();
        LocalDate getLatestVisitedAt();
    }

    /**
     * Unique mutual friends who visited, ordered by each friend's latest Visit date DESC.
     * latestScore is the score from that latest Visit (not the friend's place average).
     */
    @Query(value = """
            select u.id as "userId",
                   u.display_name as "displayName",
                   u.avatar_url as "avatarUrl",
                   latest.overall_rating as "latestScore",
                   latest.visited_at as "latestVisitedAt"
            from (
                select v.user_id,
                       max(v.visited_at) as max_visited_at
                from visits v
                where v.place_id = :placeId
                  and v.visibility = 'PUBLIC'
                  and v.user_id <> :viewerId
                  and exists (
                      select 1 from user_follows outbound
                      where outbound.follower_user_id = :viewerId
                        and outbound.followed_user_id = v.user_id
                  )
                  and exists (
                      select 1 from user_follows inbound
                      where inbound.follower_user_id = v.user_id
                        and inbound.followed_user_id = :viewerId
                  )
                group by v.user_id
            ) friends
            join users u on u.id = friends.user_id
            join lateral (
                select v2.overall_rating, v2.visited_at
                from visits v2
                where v2.place_id = :placeId
                  and v2.visibility = 'PUBLIC'
                  and v2.user_id = friends.user_id
                order by v2.visited_at desc, v2.created_at desc, v2.id desc
                limit 1
            ) latest on true
            order by friends.max_visited_at desc, u.id asc
            limit :limit
            """, nativeQuery = true)
    List<FriendPreviewRow> findFriendPreviews(
            @Param("placeId") UUID placeId,
            @Param("viewerId") UUID viewerId,
            @Param("limit") int limit);
}

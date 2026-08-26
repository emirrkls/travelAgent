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
    @EntityGraph(attributePaths = {"user", "place"})
    @Query("select v from Visit v where v.id = :id")
    java.util.Optional<Visit> findDetailedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"place", "user"})
    java.util.Optional<Visit> findByUserIdAndClientMutationId(UUID userId, UUID clientMutationId);

    /** Serializes concurrent first delivery for one ownership-scoped mutation key. */
    @Query(value = "select pg_advisory_xact_lock(hashtextextended(cast(:userId as text) || ':' || cast(:mutationId as text), 0))", nativeQuery = true)
    void lockClientMutation(@Param("userId") UUID userId, @Param("mutationId") UUID mutationId);

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
     * Authenticated Community reviews: PUBLIC Visits excluding block-separated authors.
     * The viewer's own PUBLIC Visits remain visible. Filter is in-query so pagination is dense.
     */
    @EntityGraph(attributePaths = {"user", "place"})
    @Query("""
            select v from Visit v
            where v.place.id = :placeId
              and v.visibility = com.emirrkls.phokarta.backend.domain.model.Visibility.PUBLIC
              and (v.user.id = :viewerId or not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = v.user.id)
                     or (block.id.blockerUserId = v.user.id and block.id.blockedUserId = :viewerId)
              ))
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    Page<Visit> findPublicReviewsVisibleTo(
            @Param("placeId") UUID placeId,
            @Param("viewerId") UUID viewerId,
            Pageable pageable);

    /**
     * Authenticated Community Activity: PUBLIC Visits excluding block-separated authors.
     */
    @EntityGraph(attributePaths = {"user", "place"})
    @Query("""
            select v from Visit v
            where v.visibility = com.emirrkls.phokarta.backend.domain.model.Visibility.PUBLIC
              and (v.user.id = :viewerId or not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = v.user.id)
                     or (block.id.blockerUserId = v.user.id and block.id.blockedUserId = :viewerId)
              ))
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    Page<Visit> findPublicActivityVisibleTo(
            @Param("viewerId") UUID viewerId,
            Pageable pageable);

    /**
     * Friends Activity: friend-readable visits (PUBLIC or FRIENDS) by mutual friends,
     * excluding self and PRIVATE. Mutual = viewer→author AND author→viewer.
     */
    @EntityGraph(attributePaths = {"user", "place"})
    @Query("""
            select v from Visit v
            where v.visibility in (
                    com.emirrkls.phokarta.backend.domain.model.Visibility.PUBLIC,
                    com.emirrkls.phokarta.backend.domain.model.Visibility.FRIENDS)
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
              and not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = v.user.id)
                     or (block.id.blockerUserId = v.user.id and block.id.blockedUserId = :viewerId)
              )
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    Page<Visit> findFriendsActivity(
            @Param("viewerId") UUID viewerId,
            Pageable pageable);

    /**
     * Friends reviews for a place: friend-readable visits by mutual friends, excluding self.
     */
    @EntityGraph(attributePaths = {"user", "place"})
    @Query("""
            select v from Visit v
            where v.place.id = :placeId
              and v.visibility in (
                    com.emirrkls.phokarta.backend.domain.model.Visibility.PUBLIC,
                    com.emirrkls.phokarta.backend.domain.model.Visibility.FRIENDS)
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
              and not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = v.user.id)
                     or (block.id.blockerUserId = v.user.id and block.id.blockedUserId = :viewerId)
              )
            order by v.visitedAt desc, v.createdAt desc, v.id desc
            """)
    Page<Visit> findFriendsReviews(
            @Param("placeId") UUID placeId,
            @Param("viewerId") UUID viewerId,
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

    /**
     * Community aggregate: PUBLIC Visit overall ratings only.
     * FRIENDS/PRIVATE visits never contribute. Null average when count is 0.
     */
    @Query("""
            select avg(v.overallRating) as average, count(v) as count
            from Visit v
            where v.place.id = :placeId
              and v.visibility = com.emirrkls.phokarta.backend.domain.model.Visibility.PUBLIC
            """)
    ScoreAggregate aggregate(@Param("placeId") UUID placeId);

    interface FriendScoreAggregate {
        Double getAverageScore();
        long getFriendsVisitedCount();
    }

    /**
     * User-weighted friends score: AVG(per-friend AVG(overall_rating)).
     * Per-friend average uses friend-readable Visits only (PUBLIC or FRIENDS).
     * friendsVisitedCount = distinct mutual friends with ≥1 friend-readable Visit.
     * PRIVATE never contributes.
     */
    @Query(value = """
            select avg(friend_avg) as "averageScore",
                   count(*) as "friendsVisitedCount"
            from (
                select avg(v.overall_rating) as friend_avg
                from visits v
                where v.place_id = :placeId
                  and v.visibility in ('PUBLIC', 'FRIENDS')
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
                  and not exists (
                      select 1 from user_blocks ub
                      where (ub.blocker_user_id = :viewerId and ub.blocked_user_id = v.user_id)
                         or (ub.blocker_user_id = v.user_id and ub.blocked_user_id = :viewerId)
                  )
                group by v.user_id
            ) per_friend
            """, nativeQuery = true)
    FriendScoreAggregate aggregateFriendsScore(
            @Param("placeId") UUID placeId,
            @Param("viewerId") UUID viewerId);

    interface FriendScoreByPlace {
        UUID getPlaceId();
        Double getAverageScore();
        long getFriendsVisitedCount();
    }

    /**
     * Batch user-weighted friends score for many places in one query.
     * Same semantics as {@link #aggregateFriendsScore}: PUBLIC+FRIENDS, mutual friends,
     * PRIVATE excluded, AVG(per-friend AVG). Places with no qualifying friends are omitted.
     */
    @Query(value = """
            select place_id as "placeId",
                   avg(friend_avg) as "averageScore",
                   count(*) as "friendsVisitedCount"
            from (
                select v.place_id,
                       avg(v.overall_rating) as friend_avg
                from visits v
                where v.place_id in (:placeIds)
                  and v.visibility in ('PUBLIC', 'FRIENDS')
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
                  and not exists (
                      select 1 from user_blocks ub
                      where (ub.blocker_user_id = :viewerId and ub.blocked_user_id = v.user_id)
                         or (ub.blocker_user_id = v.user_id and ub.blocked_user_id = :viewerId)
                  )
                group by v.place_id, v.user_id
            ) per_friend
            group by place_id
            """, nativeQuery = true)
    List<FriendScoreByPlace> aggregateFriendsScoreByPlaceIds(
            @Param("placeIds") List<UUID> placeIds,
            @Param("viewerId") UUID viewerId);

    interface FriendPreviewRow {
        UUID getUserId();
        String getDisplayName();
        String getAvatarUrl();
        double getLatestScore();
        LocalDate getLatestVisitedAt();
    }

    /**
     * Unique mutual friends who visited with a friend-readable Visit,
     * ordered by each friend's latest friend-readable Visit date DESC.
     * latestScore is from that latest friend-readable Visit (PRIVATE ignored).
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
                  and v.visibility in ('PUBLIC', 'FRIENDS')
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
                  and not exists (
                      select 1 from user_blocks ub
                      where (ub.blocker_user_id = :viewerId and ub.blocked_user_id = v.user_id)
                         or (ub.blocker_user_id = v.user_id and ub.blocked_user_id = :viewerId)
                  )
                group by v.user_id
            ) friends
            join users u on u.id = friends.user_id
            join lateral (
                select v2.overall_rating, v2.visited_at
                from visits v2
                where v2.place_id = :placeId
                  and v2.visibility in ('PUBLIC', 'FRIENDS')
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

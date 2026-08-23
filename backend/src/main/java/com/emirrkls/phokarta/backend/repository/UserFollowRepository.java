package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.UserFollow;
import com.emirrkls.phokarta.backend.domain.entity.UserFollowId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserFollowRepository extends JpaRepository<UserFollow, UserFollowId> {

    boolean existsById(UserFollowId id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into user_follows (follower_user_id, followed_user_id, created_at)
            values (:followerId, :followedId, :createdAt)
            on conflict (follower_user_id, followed_user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("followerId") UUID followerId,
                        @Param("followedId") UUID followedId,
                        @Param("createdAt") OffsetDateTime createdAt);

    @Query("""
            select count(f) from UserFollow f
            where f.id.followedUserId = :userId
            """)
    long countFollowers(@Param("userId") UUID userId);

    @Query("""
            select count(f) from UserFollow f
            where f.id.followerUserId = :userId
            """)
    long countFollowing(@Param("userId") UUID userId);

    @Query("""
            select count(f) from UserFollow f
            where f.id.followerUserId = :userId
              and exists (
                  select 1 from UserFollow reverse
                  where reverse.id.followerUserId = f.id.followedUserId
                    and reverse.id.followedUserId = :userId
              )
            """)
    long countFriends(@Param("userId") UUID userId);

    @Query("""
            select case when count(f) > 0 then true else false end from UserFollow f
            where f.id.followerUserId = :followerId
              and f.id.followedUserId = :followedId
            """)
    boolean existsFollow(@Param("followerId") UUID followerId,
                         @Param("followedId") UUID followedId);

    @Query("""
            select case when count(f) > 0 then true else false end from UserFollow f
            where f.id.followerUserId = :a
              and f.id.followedUserId = :b
              and exists (
                  select 1 from UserFollow reverse
                  where reverse.id.followerUserId = :b
                    and reverse.id.followedUserId = :a
              )
            """)
    boolean areFriends(@Param("a") UUID a, @Param("b") UUID b);

    @Query("""
            select f.id.followedUserId from UserFollow f
            where f.id.followerUserId = :viewerId
              and f.id.followedUserId in :targetIds
            """)
    List<UUID> findFollowedIdsAmong(@Param("viewerId") UUID viewerId,
                                    @Param("targetIds") Collection<UUID> targetIds);

    @Query("""
            select f.id.followerUserId from UserFollow f
            where f.id.followedUserId = :viewerId
              and f.id.followerUserId in :targetIds
            """)
    List<UUID> findFollowerIdsAmong(@Param("viewerId") UUID viewerId,
                                    @Param("targetIds") Collection<UUID> targetIds);

    @Query("""
            select f.follower from UserFollow f
            where f.id.followedUserId = :userId
            order by f.createdAt desc, f.follower.id asc
            """)
    Page<User> findFollowers(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            select f.followed from UserFollow f
            where f.id.followerUserId = :userId
            order by f.createdAt desc, f.followed.id asc
            """)
    Page<User> findFollowing(@Param("userId") UUID userId, Pageable pageable);

    @Query(value = """
            select u from User u
            where exists (
                select 1 from UserFollow outbound
                where outbound.id.followerUserId = :userId
                  and outbound.id.followedUserId = u.id
            )
            and exists (
                select 1 from UserFollow inbound
                where inbound.id.followerUserId = u.id
                  and inbound.id.followedUserId = :userId
            )
            order by lower(u.displayName) asc, lower(u.username) asc, u.id asc
            """)
    Page<User> findFriends(@Param("userId") UUID userId, Pageable pageable);
}

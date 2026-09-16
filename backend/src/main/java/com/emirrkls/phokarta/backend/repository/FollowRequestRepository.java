package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.FollowRequest;
import com.emirrkls.phokarta.backend.domain.model.FollowRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FollowRequestRepository extends JpaRepository<FollowRequest, UUID> {
    @Query(value = """
            select pg_advisory_xact_lock(hashtextextended(
                cast(:firstUserId as text) || ':' || cast(:secondUserId as text), 3))
            """, nativeQuery = true)
    void lockRelationship(@Param("firstUserId") UUID firstUserId,
                          @Param("secondUserId") UUID secondUserId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into follow_requests (
                id, requester_user_id, target_user_id, status, created_at, resolved_at
            ) values (:id, :requesterId, :targetId, 'PENDING', :createdAt, null)
            on conflict (requester_user_id, target_user_id) where status = 'PENDING'
            do nothing
            """, nativeQuery = true)
    int insertPendingIfAbsent(@Param("id") UUID id,
                              @Param("requesterId") UUID requesterId,
                              @Param("targetId") UUID targetId,
                              @Param("createdAt") OffsetDateTime createdAt);

    @EntityGraph(attributePaths = {"requester", "target"})
    Optional<FollowRequest> findByRequesterIdAndTargetIdAndStatus(
            UUID requesterId, UUID targetId, FollowRequestStatus status);

    @Query("""
            select request.target.id from FollowRequest request
            where request.requester.id = :requesterId
              and request.target.id in :targetIds
              and request.status = com.emirrkls.phokarta.backend.domain.model.FollowRequestStatus.PENDING
            """)
    List<UUID> findPendingTargetIds(
            @Param("requesterId") UUID requesterId,
            @Param("targetIds") Collection<UUID> targetIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"requester", "target"})
    @Query("select request from FollowRequest request where request.id = :id")
    Optional<FollowRequest> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"requester", "target"})
    Page<FollowRequest> findByTargetIdAndStatusOrderByCreatedAtDescIdAsc(
            UUID targetId, FollowRequestStatus status, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update FollowRequest request
               set request.status = com.emirrkls.phokarta.backend.domain.model.FollowRequestStatus.CANCELLED,
                   request.resolvedAt = :resolvedAt
             where request.status = com.emirrkls.phokarta.backend.domain.model.FollowRequestStatus.PENDING
               and ((request.requester.id = :a and request.target.id = :b)
                 or (request.requester.id = :b and request.target.id = :a))
            """)
    int cancelPendingBetween(@Param("a") UUID a, @Param("b") UUID b,
                             @Param("resolvedAt") OffsetDateTime resolvedAt);
}

package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.MediaAsset;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {
    @Query(value = "select pg_advisory_xact_lock(hashtextextended(cast(:ownerId as text) || ':' || cast(:clientId as text), 1))", nativeQuery = true)
    void lockClientMedia(@Param("ownerId") UUID ownerId, @Param("clientId") UUID clientId);

    @EntityGraph(attributePaths = "owner")
    Optional<MediaAsset> findByOwnerIdAndClientMediaId(UUID ownerId, UUID clientMediaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "owner")
    @Query("select m from MediaAsset m where m.id = :id")
    Optional<MediaAsset> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "owner")
    @Query("select m from MediaAsset m where m.id in :ids order by m.id")
    List<MediaAsset> findAllByIdForUpdate(@Param("ids") List<UUID> ids);

    @Query(value = """
            select m.* from media_assets m
            where (
                (m.status in ('PENDING_UPLOAD', 'READY') and m.expires_at <= :now)
                or (m.status = 'DELETING' and m.updated_at <= :retryBefore)
            )
            and not exists (select 1 from visit_media vm where vm.media_id = m.id)
            order by m.expires_at, m.id
            for update skip locked
            limit :limit
            """, nativeQuery = true)
    List<MediaAsset> findCleanupCandidates(@Param("now") OffsetDateTime now,
                                           @Param("retryBefore") OffsetDateTime retryBefore,
                                           @Param("limit") int limit);

    @Modifying
    @Query(value = "delete from media_assets where id = :id and status = 'DELETING'",
            nativeQuery = true)
    int deleteDeletingById(@Param("id") UUID id);
}

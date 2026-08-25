package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.AccountDeletionMediaJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AccountDeletionMediaJobRepository extends JpaRepository<AccountDeletionMediaJob, UUID> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into account_deletion_media_jobs
                (id, deletion_id, storage_key, created_at, attempt_count, next_attempt_at)
            select gen_random_uuid(), :deletionId, storage_key, :now, 0, :now
            from media_assets
            where owner_user_id = :userId
            """, nativeQuery = true)
    int enqueueOwnedMedia(@Param("userId") UUID userId,
                          @Param("deletionId") UUID deletionId,
                          @Param("now") OffsetDateTime now);

    @Query(value = """
            select j.* from account_deletion_media_jobs j
            where j.next_attempt_at <= :now
            order by j.next_attempt_at, j.id
            for update skip locked
            limit :limit
            """, nativeQuery = true)
    List<AccountDeletionMediaJob> claimDue(@Param("now") OffsetDateTime now,
                                           @Param("limit") int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "delete from account_deletion_media_jobs where id = :id", nativeQuery = true)
    int deleteJobById(@Param("id") UUID id);

    long countByNextAttemptAtLessThanEqual(OffsetDateTime now);
}

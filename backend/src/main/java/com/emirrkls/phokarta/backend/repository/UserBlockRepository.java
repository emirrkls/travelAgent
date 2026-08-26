package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.UserBlock;
import com.emirrkls.phokarta.backend.domain.entity.UserBlockId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlockId> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into user_blocks (blocker_user_id, blocked_user_id, created_at)
            values (:blockerId, :blockedId, :createdAt)
            on conflict (blocker_user_id, blocked_user_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("blockerId") UUID blockerId,
                       @Param("blockedId") UUID blockedId,
                       @Param("createdAt") OffsetDateTime createdAt);

    @Query("""
            select case when count(b) > 0 then true else false end from UserBlock b
            where (b.id.blockerUserId = :a and b.id.blockedUserId = :b)
               or (b.id.blockerUserId = :b and b.id.blockedUserId = :a)
            """)
    boolean existsEitherDirection(@Param("a") UUID a, @Param("b") UUID b);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from UserBlock b
            where b.id.blockerUserId = :blockerId
              and b.id.blockedUserId = :blockedId
            """)
    int deleteBlock(@Param("blockerId") UUID blockerId, @Param("blockedId") UUID blockedId);

    @EntityGraph(attributePaths = {"blocked"})
    @Query("""
            select b from UserBlock b
            where b.id.blockerUserId = :blockerId
            order by b.createdAt desc, b.id.blockedUserId asc
            """)
    Page<UserBlock> findBlockedBy(@Param("blockerId") UUID blockerId, Pageable pageable);
}

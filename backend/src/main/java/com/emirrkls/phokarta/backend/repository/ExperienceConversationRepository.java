package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.ExperienceConversationEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperienceConversationRepository
        extends JpaRepository<ExperienceConversationEntry, UUID> {

    @Query(value = "select pg_advisory_xact_lock(hashtextextended(cast(:authorId as text) || ':' || cast(:mutationId as text), 0))", nativeQuery = true)
    void lockClientMutation(@Param("authorId") UUID authorId,
                            @Param("mutationId") UUID mutationId);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user", "parent", "parent.author"})
    Optional<ExperienceConversationEntry> findByAuthorIdAndClientMutationId(
            UUID authorId, UUID clientMutationId);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user", "parent", "parent.author"})
    @Query("select entry from ExperienceConversationEntry entry where entry.id = :id")
    Optional<ExperienceConversationEntry> findDetailedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user"})
    @Query("""
            select entry from ExperienceConversationEntry entry
            where entry.experience.id = :experienceId
              and entry.parent is null
              and entry.deletedAt is null
            order by entry.createdAt desc, entry.id desc
            """)
    List<ExperienceConversationEntry> findFirstAnonymousRoots(
            @Param("experienceId") UUID experienceId,
            Pageable pageable);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user"})
    @Query("""
            select entry from ExperienceConversationEntry entry
            where entry.experience.id = :experienceId
              and entry.parent is null
              and entry.deletedAt is null
              and (entry.createdAt < :cursorCreatedAt
                   or (entry.createdAt = :cursorCreatedAt and entry.id < :cursorId))
            order by entry.createdAt desc, entry.id desc
            """)
    List<ExperienceConversationEntry> findAnonymousRootsAfter(
            @Param("experienceId") UUID experienceId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user"})
    @Query("""
            select entry from ExperienceConversationEntry entry
            where entry.experience.id = :experienceId
              and entry.parent is null
              and entry.deletedAt is null
              and (entry.author.id = :viewerId or not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = entry.author.id)
                     or (block.id.blockerUserId = entry.author.id and block.id.blockedUserId = :viewerId)
              ))
            order by entry.createdAt desc, entry.id desc
            """)
    List<ExperienceConversationEntry> findFirstVisibleRoots(
            @Param("experienceId") UUID experienceId,
            @Param("viewerId") UUID viewerId,
            Pageable pageable);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user"})
    @Query("""
            select entry from ExperienceConversationEntry entry
            where entry.experience.id = :experienceId
              and entry.parent is null
              and entry.deletedAt is null
              and (entry.author.id = :viewerId or not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = entry.author.id)
                     or (block.id.blockerUserId = entry.author.id and block.id.blockedUserId = :viewerId)
              ))
              and (entry.createdAt < :cursorCreatedAt
                   or (entry.createdAt = :cursorCreatedAt and entry.id < :cursorId))
            order by entry.createdAt desc, entry.id desc
            """)
    List<ExperienceConversationEntry> findVisibleRootsAfter(
            @Param("experienceId") UUID experienceId,
            @Param("viewerId") UUID viewerId,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user", "parent"})
    @Query("""
            select entry from ExperienceConversationEntry entry
            where entry.parent.id in :rootIds
              and entry.deletedAt is null
            order by entry.createdAt asc, entry.id asc
            """)
    List<ExperienceConversationEntry> findAnonymousReplies(
            @Param("rootIds") Collection<UUID> rootIds, Pageable pageable);

    @EntityGraph(attributePaths = {"author", "experience", "experience.user", "parent"})
    @Query("""
            select entry from ExperienceConversationEntry entry
            where entry.parent.id in :rootIds
              and entry.deletedAt is null
              and (entry.author.id = :viewerId or not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = entry.author.id)
                     or (block.id.blockerUserId = entry.author.id and block.id.blockedUserId = :viewerId)
              ))
            order by entry.createdAt asc, entry.id asc
            """)
    List<ExperienceConversationEntry> findVisibleReplies(
            @Param("rootIds") Collection<UUID> rootIds,
            @Param("viewerId") UUID viewerId,
            Pageable pageable);

    @Query("""
            select count(entry) from ExperienceConversationEntry entry
            where entry.experience.id = :experienceId
              and entry.parent is null
              and entry.deletedAt is null
            """)
    long countAnonymousRoots(@Param("experienceId") UUID experienceId);

    @Query("""
            select count(entry) from ExperienceConversationEntry entry
            where entry.experience.id = :experienceId
              and entry.parent is null
              and entry.deletedAt is null
              and (entry.author.id = :viewerId or not exists (
                  select 1 from UserBlock block
                  where (block.id.blockerUserId = :viewerId and block.id.blockedUserId = entry.author.id)
                     or (block.id.blockerUserId = entry.author.id and block.id.blockedUserId = :viewerId)
              ))
            """)
    long countVisibleRoots(@Param("experienceId") UUID experienceId,
                           @Param("viewerId") UUID viewerId);
}

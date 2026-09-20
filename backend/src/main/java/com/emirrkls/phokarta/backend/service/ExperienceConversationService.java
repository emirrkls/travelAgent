package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.ConversationEntryResponse;
import com.emirrkls.phokarta.backend.api.dto.CreateConversationEntryRequest;
import com.emirrkls.phokarta.backend.api.dto.CreateConversationReplyRequest;
import com.emirrkls.phokarta.backend.api.dto.CursorPageResponse;
import com.emirrkls.phokarta.backend.api.dto.UpdateConversationEntryRequest;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.ExperienceConversationEntry;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.ConversationEntryType;
import com.emirrkls.phokarta.backend.repository.ExperienceConversationRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ExperienceConversationService {
    public static final int MAX_BODY_LENGTH = 1000;
    private static final int MAX_ROOT_PAGE_SIZE = 30;
    private static final int MAX_INCLUDED_REPLIES = 300;

    private final ExperienceConversationRepository entries;
    private final VisitRepository visits;
    private final UserRepository users;
    private final ViewerAccessPolicy access;
    private final UgcPolicyService ugcPolicy;
    private final Clock clock;

    public ExperienceConversationService(
            ExperienceConversationRepository entries,
            VisitRepository visits,
            UserRepository users,
            ViewerAccessPolicy access,
            UgcPolicyService ugcPolicy,
            Clock clock) {
        this.entries = entries;
        this.visits = visits;
        this.users = users;
        this.access = access;
        this.ugcPolicy = ugcPolicy;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<ConversationEntryResponse> read(
            UUID experienceId, UUID viewerId, String cursor, int size) {
        if (size < 1 || size > MAX_ROOT_PAGE_SIZE) {
            throw ApiException.validation("size must be between 1 and " + MAX_ROOT_PAGE_SIZE);
        }
        Visit experience = requireVisibleExperience(experienceId, viewerId);
        Cursor decoded = decode(cursor);
        List<ExperienceConversationEntry> roots = viewerId == null
                ? entries.findAnonymousRoots(experienceId, decoded == null ? null : decoded.createdAt(),
                        decoded == null ? null : decoded.id(), PageRequest.of(0, size + 1))
                : entries.findVisibleRoots(experienceId, viewerId,
                        decoded == null ? null : decoded.createdAt(),
                        decoded == null ? null : decoded.id(), PageRequest.of(0, size + 1));
        boolean hasMore = roots.size() > size;
        List<ExperienceConversationEntry> pageRoots = hasMore
                ? roots.subList(0, size) : roots;
        List<UUID> rootIds = pageRoots.stream().map(ExperienceConversationEntry::getId).toList();
        List<ExperienceConversationEntry> replies;
        if (rootIds.isEmpty()) {
            replies = List.of();
        } else if (viewerId == null) {
            replies = entries.findAnonymousReplies(rootIds, PageRequest.of(0, MAX_INCLUDED_REPLIES));
        } else {
            replies = entries.findVisibleReplies(rootIds, viewerId,
                    PageRequest.of(0, MAX_INCLUDED_REPLIES));
        }
        Map<UUID, List<ExperienceConversationEntry>> repliesByRoot = new HashMap<>();
        replies.forEach(reply -> repliesByRoot
                .computeIfAbsent(reply.getParent().getId(), ignored -> new ArrayList<>())
                .add(reply));
        List<ConversationEntryResponse> response = pageRoots.stream()
                .map(root -> toResponse(root, viewerId, experience.getUser().getId(),
                        repliesByRoot.getOrDefault(root.getId(), List.of())))
                .toList();
        String nextCursor = hasMore && !pageRoots.isEmpty()
                ? encode(pageRoots.get(pageRoots.size() - 1)) : null;
        return new CursorPageResponse<>(response, nextCursor, hasMore);
    }

    @Transactional
    public ConversationEntryResponse createRoot(
            UUID experienceId, UUID authorId, CreateConversationEntryRequest request) {
        if (request.type() == ConversationEntryType.REPLY) {
            throw ApiException.badRequest("INVALID_CONVERSATION_TYPE",
                    "A root entry must be QUESTION or COMMENT");
        }
        String body = normalizeBody(request.body());
        Visit experience = requireWritableExperience(experienceId, authorId);
        User author = users.findById(authorId)
                .orElseThrow(() -> ApiException.notFound("User", authorId));
        entries.lockClientMutation(authorId, request.clientMutationId());
        ExperienceConversationEntry existing = entries
                .findByAuthorIdAndClientMutationId(authorId, request.clientMutationId())
                .orElse(null);
        if (existing != null) {
            requireSameCreate(existing, experienceId, null, request.type(), body);
            return toResponse(existing, authorId, experience.getUser().getId(), List.of());
        }
        ExperienceConversationEntry saved = entries.save(new ExperienceConversationEntry(
                UUID.randomUUID(), experience, author, request.type(), null, body,
                request.clientMutationId(), OffsetDateTime.now(clock)));
        return toResponse(saved, authorId, experience.getUser().getId(), List.of());
    }

    @Transactional
    public ConversationEntryResponse createReply(
            UUID rootId, UUID authorId, CreateConversationReplyRequest request) {
        ExperienceConversationEntry root = requireActiveEntry(rootId);
        Visit experience = requireWritableExperience(root.getExperience().getId(), authorId);
        if (access.isBlockSeparated(authorId, root.getAuthor().getId())) {
            throw ApiException.notFound("Conversation entry", rootId);
        }
        if (root.getEntryType() == ConversationEntryType.REPLY || root.getParent() != null) {
            throw ApiException.badRequest("NESTED_REPLY_NOT_ALLOWED",
                    "Replies may only target a Question or Comment root");
        }
        String body = normalizeBody(request.body());
        User author = users.findById(authorId)
                .orElseThrow(() -> ApiException.notFound("User", authorId));
        entries.lockClientMutation(authorId, request.clientMutationId());
        ExperienceConversationEntry existing = entries
                .findByAuthorIdAndClientMutationId(authorId, request.clientMutationId())
                .orElse(null);
        if (existing != null) {
            requireSameCreate(existing, experience.getId(), rootId, ConversationEntryType.REPLY, body);
            return toResponse(existing, authorId, experience.getUser().getId(), List.of());
        }
        ExperienceConversationEntry saved = entries.save(new ExperienceConversationEntry(
                UUID.randomUUID(), experience, author, ConversationEntryType.REPLY, root, body,
                request.clientMutationId(), OffsetDateTime.now(clock)));
        return toResponse(saved, authorId, experience.getUser().getId(), List.of());
    }

    @Transactional
    public ConversationEntryResponse edit(
            UUID entryId, UUID authorId, UpdateConversationEntryRequest request) {
        ExperienceConversationEntry entry = requireActiveEntry(entryId);
        Visit experience = requireVisibleExperience(entry.getExperience().getId(), authorId);
        requireVisibleEntry(entry, authorId);
        requireOwner(entry, authorId);
        entry.edit(normalizeBody(request.body()), OffsetDateTime.now(clock));
        return toResponse(entry, authorId, experience.getUser().getId(), List.of());
    }

    @Transactional
    public void delete(UUID entryId, UUID authorId) {
        ExperienceConversationEntry entry = entries.findDetailedById(entryId)
                .orElseThrow(() -> ApiException.notFound("Conversation entry", entryId));
        requireVisibleExperience(entry.getExperience().getId(), authorId);
        requireVisibleEntry(entry, authorId);
        requireOwner(entry, authorId);
        entry.delete(OffsetDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public long visibleRootCount(UUID experienceId, UUID viewerId) {
        return viewerId == null
                ? entries.countAnonymousRoots(experienceId)
                : entries.countVisibleRoots(experienceId, viewerId);
    }

    @Transactional(readOnly = true)
    public ExperienceConversationEntry requireReportable(UUID entryId, UUID reporterId) {
        ExperienceConversationEntry entry = requireActiveEntry(entryId);
        requireVisibleExperience(entry.getExperience().getId(), reporterId);
        requireVisibleEntry(entry, reporterId);
        if (entry.getAuthor().getId().equals(reporterId)) {
            throw ApiException.badRequest("CANNOT_REPORT_SELF",
                    "You cannot report your own conversation entry");
        }
        return entry;
    }

    private Visit requireWritableExperience(UUID experienceId, UUID viewerId) {
        ugcPolicy.requireAccepted(viewerId);
        return requireVisibleExperience(experienceId, viewerId);
    }

    private Visit requireVisibleExperience(UUID experienceId, UUID viewerId) {
        Visit experience = visits.findDetailedById(experienceId)
                .orElseThrow(() -> ApiException.notFound("Experience", experienceId));
        if (!access.canViewVisit(experience, viewerId)) {
            throw ApiException.notFound("Experience", experienceId);
        }
        return experience;
    }

    private ExperienceConversationEntry requireActiveEntry(UUID entryId) {
        ExperienceConversationEntry entry = entries.findDetailedById(entryId)
                .orElseThrow(() -> ApiException.notFound("Conversation entry", entryId));
        if (entry.getDeletedAt() != null) {
            throw ApiException.notFound("Conversation entry", entryId);
        }
        return entry;
    }

    private void requireVisibleEntry(ExperienceConversationEntry entry, UUID viewerId) {
        if (access.isBlockSeparated(viewerId, entry.getAuthor().getId())) {
            throw ApiException.notFound("Conversation entry", entry.getId());
        }
        if (entry.getParent() != null
                && (entry.getParent().getDeletedAt() != null
                || access.isBlockSeparated(viewerId, entry.getParent().getAuthor().getId()))) {
            throw ApiException.notFound("Conversation entry", entry.getId());
        }
    }

    private static void requireOwner(ExperienceConversationEntry entry, UUID authorId) {
        if (!entry.getAuthor().getId().equals(authorId)) {
            throw ApiException.forbidden("Only the conversation entry author may change it");
        }
    }

    private static void requireSameCreate(
            ExperienceConversationEntry existing, UUID experienceId, UUID parentId,
            ConversationEntryType type, String body) {
        UUID existingParent = existing.getParent() == null ? null : existing.getParent().getId();
        if (!existing.getExperience().getId().equals(experienceId)
                || !java.util.Objects.equals(existingParent, parentId)
                || existing.getEntryType() != type
                || !existing.getBody().equals(body)) {
            throw ApiException.conflict("clientMutationId was already used with a different conversation payload");
        }
    }

    private static String normalizeBody(String body) {
        String normalized = body == null ? "" : body.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_BODY_LENGTH) {
            throw ApiException.validation("Conversation body must be between 1 and 1000 characters");
        }
        return normalized;
    }

    private static ConversationEntryResponse toResponse(
            ExperienceConversationEntry entry, UUID viewerId, UUID experienceAuthorId,
            List<ExperienceConversationEntry> replies) {
        boolean owned = viewerId != null && entry.getAuthor().getId().equals(viewerId);
        List<ConversationEntryResponse> mappedReplies = replies.stream()
                .map(reply -> toResponse(reply, viewerId, experienceAuthorId, List.of()))
                .toList();
        return new ConversationEntryResponse(
                entry.getId(), entry.getExperience().getId(), entry.getEntryType(), entry.getBody(),
                new ConversationEntryResponse.Author(entry.getAuthor().getId(),
                        entry.getAuthor().getUsername(), entry.getAuthor().getDisplayName(),
                        entry.getAuthor().getAvatarUrl()),
                entry.getCreatedAt(), entry.getUpdatedAt(), entry.getEditedAt() != null,
                entry.getAuthor().getId().equals(experienceAuthorId), owned,
                viewerId != null && !owned, mappedReplies);
    }

    private static String encode(ExperienceConversationEntry entry) {
        String value = "1|" + entry.getCreatedAt() + "|" + entry.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 3 || !"1".equals(fields[0])) throw new IllegalArgumentException();
            return new Cursor(OffsetDateTime.parse(fields[1]), UUID.fromString(fields[2]));
        } catch (RuntimeException invalid) {
            throw ApiException.validation("Invalid conversation cursor");
        }
    }

    private record Cursor(OffsetDateTime createdAt, UUID id) {
    }
}

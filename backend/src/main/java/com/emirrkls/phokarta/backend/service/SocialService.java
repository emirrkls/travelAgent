package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.PublicUserProfileResponse;
import com.emirrkls.phokarta.backend.api.dto.RelationshipStateResponse;
import com.emirrkls.phokarta.backend.api.dto.UserSummaryResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.UserFollowId;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SocialService {
    private final UserRepository users;
    private final UserFollowRepository follows;

    public SocialService(UserRepository users, UserFollowRepository follows) {
        this.users = users;
        this.follows = follows;
    }

    @Transactional
    public void follow(UUID followerId, UUID targetId) {
        if (followerId.equals(targetId)) {
            throw ApiException.validation("Cannot follow yourself");
        }
        requireUser(targetId);
        follows.insertIfAbsent(followerId, targetId, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional
    public void unfollow(UUID followerId, UUID targetId) {
        if (followerId.equals(targetId)) {
            throw ApiException.validation("Cannot unfollow yourself");
        }
        requireUser(targetId);
        follows.deleteById(new UserFollowId(followerId, targetId));
    }

    @Transactional(readOnly = true)
    public PublicUserProfileResponse publicProfile(UUID targetId, UUID viewerId) {
        User user = users.findById(targetId)
                .orElseThrow(() -> ApiException.notFound("User", targetId));
        RelationshipStateResponse relationship = null;
        if (viewerId != null && !viewerId.equals(targetId)) {
            relationship = relationship(viewerId, targetId);
        }
        return new PublicUserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getCityCount(),
                user.getCountryCount(),
                follows.countFollowers(targetId),
                follows.countFollowing(targetId),
                follows.countFriends(targetId),
                relationship);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummaryResponse> search(String rawQuery, UUID viewerId, int page, int size) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return emptyPage(page, size);
        }
        Page<User> result = users.searchByUsernameOrDisplayName(
                query, viewerId, PageRequest.of(page, size));
        return toSummaryPage(result, viewerId);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummaryResponse> followers(UUID userId, int page, int size) {
        requireUser(userId);
        Page<User> result = follows.findFollowers(userId, PageRequest.of(page, size));
        return toSummaryPage(result, userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummaryResponse> following(UUID userId, int page, int size) {
        requireUser(userId);
        Page<User> result = follows.findFollowing(userId, PageRequest.of(page, size));
        return toSummaryPage(result, userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserSummaryResponse> friends(UUID userId, int page, int size) {
        requireUser(userId);
        Page<User> result = follows.findFriends(userId, PageRequest.of(page, size));
        return toSummaryPage(result, userId);
    }

    @Transactional(readOnly = true)
    public boolean areFriends(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        return follows.areFriends(a, b);
    }

    private PageResponse<UserSummaryResponse> toSummaryPage(Page<User> result, UUID viewerId) {
        List<User> content = result.getContent();
        Map<UUID, RelationshipStateResponse> relationships = relationshipsFor(viewerId, content);
        List<UserSummaryResponse> summaries = content.stream()
                .map(user -> new UserSummaryResponse(
                        user.getId(),
                        user.getUsername(),
                        user.getDisplayName(),
                        user.getAvatarUrl(),
                        relationships.get(user.getId())))
                .toList();
        return new PageResponse<>(summaries, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    private Map<UUID, RelationshipStateResponse> relationshipsFor(UUID viewerId, List<User> users) {
        Map<UUID, RelationshipStateResponse> out = new HashMap<>();
        if (viewerId == null || users.isEmpty()) {
            for (User user : users) {
                out.put(user.getId(), null);
            }
            return out;
        }
        List<UUID> ids = users.stream()
                .map(User::getId)
                .filter(id -> !id.equals(viewerId))
                .toList();
        Set<UUID> following = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(follows.findFollowedIdsAmong(viewerId, ids));
        Set<UUID> followers = ids.isEmpty()
                ? Set.of()
                : new HashSet<>(follows.findFollowerIdsAmong(viewerId, ids));
        for (User user : users) {
            if (user.getId().equals(viewerId)) {
                out.put(user.getId(), null);
            } else {
                out.put(user.getId(), RelationshipStateResponse.of(
                        following.contains(user.getId()),
                        followers.contains(user.getId())));
            }
        }
        return out;
    }

    private RelationshipStateResponse relationship(UUID viewerId, UUID targetId) {
        boolean isFollowing = follows.existsFollow(viewerId, targetId);
        boolean followsYou = follows.existsFollow(targetId, viewerId);
        return RelationshipStateResponse.of(isFollowing, followsYou);
    }

    private void requireUser(UUID id) {
        if (!users.existsById(id)) {
            throw ApiException.notFound("User", id);
        }
    }

    private static PageResponse<UserSummaryResponse> emptyPage(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, 0, false);
    }
}

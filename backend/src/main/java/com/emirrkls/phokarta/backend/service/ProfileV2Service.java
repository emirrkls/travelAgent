package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.dto.ProfileSummaryV2Response;
import com.emirrkls.phokarta.backend.api.dto.ProfileV2Response;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.config.ProfilePrivacyProperties;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class ProfileV2Service {
    private final UserRepository users;
    private final UserFollowRepository follows;
    private final VisitRepository visits;
    private final ViewerAccessPolicy access;
    private final FollowRequestService followRequests;
    private final ProfilePrivacyProperties properties;

    public ProfileV2Service(UserRepository users, UserFollowRepository follows,
                            VisitRepository visits, ViewerAccessPolicy access,
                            FollowRequestService followRequests,
                            ProfilePrivacyProperties properties) {
        this.users = users;
        this.follows = follows;
        this.visits = visits;
        this.access = access;
        this.followRequests = followRequests;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ProfileV2Response profile(UUID targetId, UUID viewerId) {
        User target = users.findById(targetId)
                .orElseThrow(() -> ApiException.notFound("User", targetId));
        if (!access.canViewProfile(targetId, viewerId)) {
            throw ApiException.notFound("User", targetId);
        }
        boolean full = access.canViewFullProfile(target, viewerId);
        var relationship = followRequests.relationship(viewerId, targetId);
        if (!full) {
            return new ProfileV2Response(targetId, target.getUsername(), target.getDisplayName(),
                    target.getAvatarUrl(), target.getBio(), target.getProfileVisibility(), false,
                    relationship, null, null, null, null, null, null);
        }
        return new ProfileV2Response(targetId, target.getUsername(), target.getDisplayName(),
                target.getAvatarUrl(), target.getBio(), target.getProfileVisibility(), true,
                relationship, target.getCityCount(), target.getCountryCount(),
                follows.countFollowers(targetId), follows.countFollowing(targetId),
                follows.countFriends(targetId), visibleExperienceCount(target, viewerId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProfileSummaryV2Response> search(
            String rawQuery, UUID viewerId, int page, int size) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0, false);
        }
        Page<User> result = users.searchByUsernameOrDisplayName(
                query, viewerId, PageRequest.of(page, size));
        return PageResponse.from(result, user -> new ProfileSummaryV2Response(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl(),
                user.getBio(), user.getProfileVisibility(),
                followRequests.relationship(viewerId, user.getId())));
    }

    @Transactional
    public ProfileV2Response updateVisibility(UUID ownerId, ProfileVisibility visibility) {
        if (visibility == ProfileVisibility.PRIVATE && !properties.enabled()) {
            throw ApiException.conflict("PROFILE_PRIVACY_V2_DISABLED",
                    "Private profiles are not enabled for this deployment.");
        }
        User owner = users.findById(ownerId)
                .orElseThrow(() -> ApiException.notFound("User", ownerId));
        owner.changeProfileVisibility(visibility, OffsetDateTime.now(ZoneOffset.UTC));
        return profile(ownerId, ownerId);
    }

    private long visibleExperienceCount(User target, UUID viewerId) {
        if (viewerId != null && viewerId.equals(target.getId())) {
            return visits.countByUserId(target.getId());
        }
        if (viewerId != null && follows.areFriends(viewerId, target.getId())) {
            return visits.countByUserIdAndVisibilityIn(
                    target.getId(), List.of(Visibility.PUBLIC, Visibility.FRIENDS));
        }
        return visits.countByUserIdAndVisibility(target.getId(), Visibility.PUBLIC);
    }
}

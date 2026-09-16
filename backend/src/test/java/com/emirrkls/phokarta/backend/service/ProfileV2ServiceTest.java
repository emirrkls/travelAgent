package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.config.ProfilePrivacyProperties;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileV2ServiceTest {
    @Mock private UserRepository users;
    @Mock private UserFollowRepository follows;
    @Mock private VisitRepository visits;
    @Mock private ViewerAccessPolicy access;
    @Mock private FollowRequestService followRequests;
    private UUID targetId;
    private UUID viewerId;
    private User target;

    @BeforeEach
    void setUp() {
        targetId = UUID.randomUUID();
        viewerId = UUID.randomUUID();
        target = new User(targetId, "target@example.test", "target", "Target", null,
                OffsetDateTime.now(ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(users.findById(targetId)).thenReturn(Optional.of(target));
        org.mockito.Mockito.lenient().when(access.canViewProfile(targetId, viewerId)).thenReturn(true);
        org.mockito.Mockito.lenient().when(access.canViewProfile(targetId, targetId)).thenReturn(true);
    }

    @Test
    void unapprovedPrivateProfileIsIdentityOnly() {
        makePrivate();
        when(access.canViewFullProfile(target, viewerId)).thenReturn(false);
        var response = enabledService().profile(targetId, viewerId);
        assertThat(response.fullProfile()).isFalse();
        assertThat(response.bio()).isEqualTo(target.getBio());
        assertThat(response.visibleExperienceCount()).isNull();
        assertThat(response.followerCount()).isNull();
    }

    @Test
    void ownerSeesFullPrivateProfileAndEveryOwnExperience() {
        makePrivate();
        when(access.canViewFullProfile(target, targetId)).thenReturn(true);
        when(visits.countByUserId(targetId)).thenReturn(7L);
        var response = enabledService().profile(targetId, targetId);
        assertThat(response.fullProfile()).isTrue();
        assertThat(response.visibleExperienceCount()).isEqualTo(7L);
    }

    @Test
    void approvedFollowerFullProfileCountsOnlyPublicExperiences() {
        makePrivate();
        when(access.canViewFullProfile(target, viewerId)).thenReturn(true);
        when(visits.countByUserIdAndVisibility(targetId, Visibility.PUBLIC)).thenReturn(2L);
        var response = enabledService().profile(targetId, viewerId);
        assertThat(response.visibleExperienceCount()).isEqualTo(2L);
    }

    @Test
    void mutualFriendCountIncludesPublicAndFriendsExperiences() {
        when(access.canViewFullProfile(target, viewerId)).thenReturn(true);
        when(follows.areFriends(viewerId, targetId)).thenReturn(true);
        when(visits.countByUserIdAndVisibilityIn(eq(targetId), any())).thenReturn(4L);
        var response = enabledService().profile(targetId, viewerId);
        assertThat(response.visibleExperienceCount()).isEqualTo(4L);
    }

    @Test
    void blockedProfileUsesNotFoundWithoutDirectionDisclosure() {
        when(access.canViewProfile(targetId, viewerId)).thenReturn(false);
        assertThatThrownBy(() -> enabledService().profile(targetId, viewerId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
    }

    @Test
    void privateActivationIsDisabledByDefaultCapability() {
        ProfileV2Service disabled = service(false);
        assertThatThrownBy(() -> disabled.updateVisibility(targetId, ProfileVisibility.PRIVATE))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PROFILE_PRIVACY_V2_DISABLED"));
    }

    @Test
    void enabledCapabilityAllowsPrivateActivation() {
        when(access.canViewProfile(targetId, targetId)).thenReturn(true);
        when(access.canViewFullProfile(target, targetId)).thenReturn(true);
        enabledService().updateVisibility(targetId, ProfileVisibility.PRIVATE);
        assertThat(target.getProfileVisibility()).isEqualTo(ProfileVisibility.PRIVATE);
    }

    @Test
    void searchResponseCannotCarryProtectedCounts() {
        when(users.searchByUsernameOrDisplayName(eq("target"), eq(viewerId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(target)));
        var result = enabledService().search("target", viewerId, 0, 20);
        assertThat(result.content()).singleElement().satisfies(summary -> {
            assertThat(summary.username()).isEqualTo("target");
            assertThat(List.of(summary.getClass().getRecordComponents()).stream()
                    .map(component -> component.getName()))
                    .doesNotContain("followerCount", "visibleExperienceCount", "cityCount");
        });
    }

    private ProfileV2Service enabledService() { return service(true); }

    private ProfileV2Service service(boolean enabled) {
        return new ProfileV2Service(users, follows, visits, access, followRequests,
                new ProfilePrivacyProperties(enabled));
    }

    private void makePrivate() {
        target.changeProfileVisibility(ProfileVisibility.PRIVATE, OffsetDateTime.now(ZoneOffset.UTC));
    }
}

package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialServicePrivacyTest {
    @Mock private UserRepository users;
    @Mock private UserFollowRepository follows;
    @Mock private ViewerAccessPolicy access;
    private SocialService service;
    private UUID viewerId;
    private UUID targetId;
    private User target;

    @BeforeEach
    void setUp() {
        service = new SocialService(users, follows, access);
        viewerId = UUID.randomUUID();
        targetId = UUID.randomUUID();
        target = new User(targetId, "target@example.test", "target", "Target", null,
                OffsetDateTime.now(ZoneOffset.UTC));
        when(users.findById(targetId)).thenReturn(Optional.of(target));
    }

    @Test
    void v1PublicFollowKeepsImmediateApprovedBehavior() {
        when(access.canFollow(viewerId, targetId)).thenReturn(true);
        service.follow(viewerId, targetId);
        verify(follows).insertIfAbsent(eq(viewerId), eq(targetId), any());
    }

    @Test
    void v1PrivateFollowFailsApprovalRequiredWithoutEdgeOrRequestGuess() {
        target.changeProfileVisibility(ProfileVisibility.PRIVATE, OffsetDateTime.now(ZoneOffset.UTC));
        when(access.canFollow(viewerId, targetId)).thenReturn(true);
        assertThatThrownBy(() -> service.follow(viewerId, targetId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FOLLOW_APPROVAL_REQUIRED"));
        verify(follows, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void v1FullProfileFailsClosedWhenPrivateViewerIsNotApproved() {
        target.changeProfileVisibility(ProfileVisibility.PRIVATE, OffsetDateTime.now(ZoneOffset.UTC));
        when(access.canViewProfile(targetId, viewerId)).thenReturn(true);
        when(access.canViewFullProfile(target, viewerId)).thenReturn(false);
        assertThatThrownBy(() -> service.publicProfile(targetId, viewerId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
    }
}

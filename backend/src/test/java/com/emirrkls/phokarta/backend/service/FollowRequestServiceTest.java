package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.RelationshipV2Response;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.FollowRequest;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.UserFollowId;
import com.emirrkls.phokarta.backend.domain.model.FollowRequestStatus;
import com.emirrkls.phokarta.backend.domain.model.ProfileVisibility;
import com.emirrkls.phokarta.backend.repository.FollowRequestRepository;
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
class FollowRequestServiceTest {
    @Mock private UserRepository users;
    @Mock private UserFollowRepository follows;
    @Mock private FollowRequestRepository requests;
    @Mock private ViewerAccessPolicy access;
    private FollowRequestService service;
    private UUID requesterId;
    private UUID targetId;
    private User requester;
    private User target;

    @BeforeEach
    void setUp() {
        service = new FollowRequestService(users, follows, requests, access);
        requesterId = UUID.randomUUID();
        targetId = UUID.randomUUID();
        requester = user(requesterId, "requester");
        target = user(targetId, "target");
        org.mockito.Mockito.lenient().when(users.findById(requesterId)).thenReturn(Optional.of(requester));
        org.mockito.Mockito.lenient().when(users.findById(targetId)).thenReturn(Optional.of(target));
    }

    @Test
    void publicTargetCreatesApprovedFollowEdge() {
        when(access.canFollow(requesterId, targetId)).thenReturn(true);
        service.follow(requesterId, targetId);
        verify(follows).insertIfAbsent(eq(requesterId), eq(targetId), any());
        verify(requests, never()).insertPendingIfAbsent(any(), any(), any(), any());
    }

    @Test
    void privateTargetCreatesPendingWithoutFollowEdge() {
        makePrivate(target);
        when(access.canFollow(requesterId, targetId)).thenReturn(true);
        service.follow(requesterId, targetId);
        verify(requests).insertPendingIfAbsent(any(), eq(requesterId), eq(targetId), any());
        verify(follows, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void duplicatePendingUsesDatabaseIdempotentInsert() {
        makePrivate(target);
        when(access.canFollow(requesterId, targetId)).thenReturn(true);
        service.follow(requesterId, targetId);
        service.follow(requesterId, targetId);
        verify(requests, org.mockito.Mockito.times(2))
                .insertPendingIfAbsent(any(), eq(requesterId), eq(targetId), any());
    }

    @Test
    void approvalCreatesOneDirectedFollowAndResolvesRequest() {
        UUID requestId = UUID.randomUUID();
        FollowRequest request = pending(requestId);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(access.canFollow(requesterId, targetId)).thenReturn(true);
        service.approve(targetId, requestId);
        verify(follows).insertIfAbsent(eq(requesterId), eq(targetId), any());
        assertThat(request.getStatus()).isEqualTo(FollowRequestStatus.APPROVED);
    }

    @Test
    void rejectionResolvesWithoutFollow() {
        UUID requestId = UUID.randomUUID();
        FollowRequest request = pending(requestId);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        service.reject(targetId, requestId);
        assertThat(request.getStatus()).isEqualTo(FollowRequestStatus.REJECTED);
        verify(follows, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void cancellationResolvesWithoutFollow() {
        FollowRequest request = pending(UUID.randomUUID());
        when(requests.findByRequesterIdAndTargetIdAndStatus(
                requesterId, targetId, FollowRequestStatus.PENDING)).thenReturn(Optional.of(request));
        service.cancel(requesterId, targetId);
        assertThat(request.getStatus()).isEqualTo(FollowRequestStatus.CANCELLED);
        verify(follows, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void unfollowOnlyRemovesApprovedEdge() {
        service.unfollow(requesterId, targetId);
        verify(follows).deleteById(new UserFollowId(requesterId, targetId));
        verify(requests, never()).insertPendingIfAbsent(any(), any(), any(), any());
    }

    @Test
    void oneApprovedDirectionIsFollowingNotFriend() {
        when(follows.existsFollow(requesterId, targetId)).thenReturn(true);
        RelationshipV2Response relationship = service.relationship(requesterId, targetId);
        assertThat(relationship.state()).isEqualTo(RelationshipV2Response.State.FOLLOWING);
    }

    @Test
    void mutualApprovedDirectionsAreFriends() {
        when(follows.existsFollow(requesterId, targetId)).thenReturn(true);
        when(follows.existsFollow(targetId, requesterId)).thenReturn(true);
        RelationshipV2Response relationship = service.relationship(requesterId, targetId);
        assertThat(relationship.state()).isEqualTo(RelationshipV2Response.State.FRIENDS);
    }

    @Test
    void blockedRelationshipIsGenericUnavailable() {
        when(access.isBlockSeparated(requesterId, targetId)).thenReturn(true);
        RelationshipV2Response relationship = service.relationship(requesterId, targetId);
        assertThat(relationship.state()).isEqualTo(RelationshipV2Response.State.UNAVAILABLE);
        assertThat(relationship.followsYou()).isFalse();
    }

    @Test
    void blockedRequesterCannotCreateRequest() {
        makePrivate(target);
        assertThatThrownBy(() -> service.follow(requesterId, targetId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RELATIONSHIP_UNAVAILABLE"));
        verify(requests, never()).insertPendingIfAbsent(any(), any(), any(), any());
    }

    @Test
    void blockedApprovalCancelsStaleRequest() {
        UUID requestId = UUID.randomUUID();
        FollowRequest request = pending(requestId);
        when(requests.findById(requestId)).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        assertThatThrownBy(() -> service.approve(targetId, requestId))
                .isInstanceOf(ApiException.class);
        assertThat(request.getStatus()).isEqualTo(FollowRequestStatus.CANCELLED);
        verify(follows, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void selfFollowRemainsInvalid() {
        assertThatThrownBy(() -> service.follow(requesterId, requesterId))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("VALIDATION_ERROR"));
    }

    private FollowRequest pending(UUID id) {
        return new FollowRequest(id, requester, target, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private User user(UUID id, String username) {
        return new User(id, username + "@example.test", username, username, null,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void makePrivate(User user) {
        user.changeProfileVisibility(ProfileVisibility.PRIVATE, OffsetDateTime.now(ZoneOffset.UTC));
    }
}

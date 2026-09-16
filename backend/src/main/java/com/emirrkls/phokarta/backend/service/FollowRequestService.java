package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.FollowRequestV2Response;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class FollowRequestService {
    private final UserRepository users;
    private final UserFollowRepository follows;
    private final FollowRequestRepository requests;
    private final ViewerAccessPolicy access;

    public FollowRequestService(UserRepository users, UserFollowRepository follows,
                                FollowRequestRepository requests, ViewerAccessPolicy access) {
        this.users = users;
        this.follows = follows;
        this.requests = requests;
        this.access = access;
    }

    @Transactional
    public RelationshipV2Response follow(UUID requesterId, UUID targetId) {
        validateDistinct(requesterId, targetId);
        lockRelationship(requesterId, targetId);
        User requester = requireUser(requesterId);
        User target = requireUser(targetId);
        if (!access.canFollow(requesterId, targetId)) {
            throw ApiException.conflict("RELATIONSHIP_UNAVAILABLE", "This action isn't available.");
        }
        OffsetDateTime now = now();
        if (target.getProfileVisibility() == ProfileVisibility.PUBLIC) {
            follows.insertIfAbsent(requester.getId(), target.getId(), now);
            requests.findByRequesterIdAndTargetIdAndStatus(
                    requesterId, targetId, FollowRequestStatus.PENDING)
                    .ifPresent(request -> request.resolve(FollowRequestStatus.APPROVED, now));
        } else if (!follows.existsFollow(requesterId, targetId)) {
            requests.insertPendingIfAbsent(UUID.randomUUID(), requesterId, targetId, now);
        }
        return relationship(requesterId, targetId);
    }

    @Transactional
    public RelationshipV2Response unfollow(UUID requesterId, UUID targetId) {
        validateDistinct(requesterId, targetId);
        lockRelationship(requesterId, targetId);
        requireUser(targetId);
        follows.deleteById(new UserFollowId(requesterId, targetId));
        return relationship(requesterId, targetId);
    }

    @Transactional
    public RelationshipV2Response cancel(UUID requesterId, UUID targetId) {
        validateDistinct(requesterId, targetId);
        lockRelationship(requesterId, targetId);
        requireUser(targetId);
        requests.findByRequesterIdAndTargetIdAndStatus(
                requesterId, targetId, FollowRequestStatus.PENDING)
                .ifPresent(request -> request.resolve(FollowRequestStatus.CANCELLED, now()));
        return relationship(requesterId, targetId);
    }

    @Transactional
    public RelationshipV2Response approve(UUID targetId, UUID requestId) {
        FollowRequest snapshot = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Follow request", requestId));
        if (!snapshot.getTarget().getId().equals(targetId)) {
            throw ApiException.notFound("Follow request", requestId);
        }
        lockRelationship(snapshot.getRequester().getId(), targetId);
        FollowRequest request = requireOwnedPending(targetId, requestId);
        UUID requesterId = request.getRequester().getId();
        if (!access.canFollow(requesterId, targetId)) {
            request.resolve(FollowRequestStatus.CANCELLED, now());
            throw ApiException.conflict("RELATIONSHIP_UNAVAILABLE", "This action isn't available.");
        }
        OffsetDateTime now = now();
        request.resolve(FollowRequestStatus.APPROVED, now);
        // Resolve before the native follow insert: that repository clears the persistence
        // context, and flushAutomatically persists this state in the same transaction.
        follows.insertIfAbsent(requesterId, targetId, now);
        return relationship(targetId, requesterId);
    }

    @Transactional
    public RelationshipV2Response reject(UUID targetId, UUID requestId) {
        FollowRequest snapshot = requests.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("Follow request", requestId));
        if (!snapshot.getTarget().getId().equals(targetId)) {
            throw ApiException.notFound("Follow request", requestId);
        }
        lockRelationship(snapshot.getRequester().getId(), targetId);
        FollowRequest request = requireOwnedPending(targetId, requestId);
        UUID requesterId = request.getRequester().getId();
        request.resolve(FollowRequestStatus.REJECTED, now());
        return relationship(targetId, requesterId);
    }

    @Transactional(readOnly = true)
    public PageResponse<FollowRequestV2Response> incoming(UUID targetId, int page, int size) {
        Page<FollowRequest> result = requests.findByTargetIdAndStatusOrderByCreatedAtDescIdAsc(
                targetId, FollowRequestStatus.PENDING, PageRequest.of(page, size));
        return PageResponse.from(result, this::response);
    }

    @Transactional(readOnly = true)
    public RelationshipV2Response relationship(UUID viewerId, UUID targetId) {
        if (viewerId == null || viewerId.equals(targetId)) {
            return null;
        }
        if (access.isBlockSeparated(viewerId, targetId)) {
            return new RelationshipV2Response(RelationshipV2Response.State.UNAVAILABLE,
                    false, false, false);
        }
        boolean outbound = follows.existsFollow(viewerId, targetId);
        boolean inbound = follows.existsFollow(targetId, viewerId);
        if (outbound && inbound) {
            return new RelationshipV2Response(RelationshipV2Response.State.FRIENDS,
                    true, false, false);
        }
        if (outbound) {
            return new RelationshipV2Response(RelationshipV2Response.State.FOLLOWING,
                    inbound, false, false);
        }
        boolean pending = requests.findByRequesterIdAndTargetIdAndStatus(
                viewerId, targetId, FollowRequestStatus.PENDING).isPresent();
        if (pending) {
            return new RelationshipV2Response(RelationshipV2Response.State.REQUEST_PENDING,
                    inbound, false, true);
        }
        return new RelationshipV2Response(RelationshipV2Response.State.NONE,
                inbound, true, false);
    }

    private FollowRequest requireOwnedPending(UUID targetId, UUID requestId) {
        FollowRequest request = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("Follow request", requestId));
        if (!request.getTarget().getId().equals(targetId)
                || request.getStatus() != FollowRequestStatus.PENDING) {
            throw ApiException.notFound("Follow request", requestId);
        }
        return request;
    }

    private FollowRequestV2Response response(FollowRequest request) {
        User requester = request.getRequester();
        return new FollowRequestV2Response(request.getId(),
                new FollowRequestV2Response.UserIdentity(requester.getId(), requester.getUsername(),
                        requester.getDisplayName(), requester.getAvatarUrl()),
                request.getStatus(), request.getCreatedAt(), request.getResolvedAt());
    }

    private void validateDistinct(UUID requesterId, UUID targetId) {
        if (requesterId.equals(targetId)) {
            throw ApiException.validation("Cannot follow yourself");
        }
    }

    private User requireUser(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
    }

    private void lockRelationship(UUID a, UUID b) {
        if (a.toString().compareTo(b.toString()) < 0) {
            requests.lockRelationship(a, b);
        } else {
            requests.lockRelationship(b, a);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}

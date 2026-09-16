package com.emirrkls.phokarta.backend.domain.entity;

import com.emirrkls.phokarta.backend.domain.model.FollowRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "follow_requests")
public class FollowRequest {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_user_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_user_id", nullable = false)
    private User target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FollowRequestStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    protected FollowRequest() {}

    public FollowRequest(UUID id, User requester, User target, OffsetDateTime createdAt) {
        this.id = id;
        this.requester = requester;
        this.target = target;
        this.status = FollowRequestStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void resolve(FollowRequestStatus resolution, OffsetDateTime now) {
        if (resolution == FollowRequestStatus.PENDING) {
            throw new IllegalArgumentException("A follow request resolution cannot be PENDING");
        }
        if (status == FollowRequestStatus.PENDING) {
            status = resolution;
            resolvedAt = now;
        }
    }

    public UUID getId() { return id; }
    public User getRequester() { return requester; }
    public User getTarget() { return target; }
    public FollowRequestStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
}

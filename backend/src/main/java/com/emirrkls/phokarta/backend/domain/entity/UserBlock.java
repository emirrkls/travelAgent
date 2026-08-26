package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "user_blocks")
public class UserBlock {

    @EmbeddedId
    private UserBlockId id;

    @MapsId("blockerUserId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocker_user_id", nullable = false)
    private User blocker;

    @MapsId("blockedUserId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_user_id", nullable = false)
    private User blocked;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UserBlock() {
    }

    public UserBlock(User blocker, User blocked, OffsetDateTime createdAt) {
        this.blocker = blocker;
        this.blocked = blocked;
        this.id = new UserBlockId(blocker.getId(), blocked.getId());
        this.createdAt = createdAt;
    }

    public UserBlockId getId() { return id; }
    public User getBlocker() { return blocker; }
    public User getBlocked() { return blocked; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserBlockId implements Serializable {

    @Column(name = "blocker_user_id", nullable = false)
    private UUID blockerUserId;

    @Column(name = "blocked_user_id", nullable = false)
    private UUID blockedUserId;

    protected UserBlockId() {
    }

    public UserBlockId(UUID blockerUserId, UUID blockedUserId) {
        this.blockerUserId = Objects.requireNonNull(blockerUserId);
        this.blockedUserId = Objects.requireNonNull(blockedUserId);
    }

    public UUID getBlockerUserId() { return blockerUserId; }
    public UUID getBlockedUserId() { return blockedUserId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserBlockId that)) return false;
        return blockerUserId.equals(that.blockerUserId)
                && blockedUserId.equals(that.blockedUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockerUserId, blockedUserId);
    }
}

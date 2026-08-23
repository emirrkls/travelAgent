package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UserFollowId implements Serializable {

    @Column(name = "follower_user_id", nullable = false)
    private UUID followerUserId;

    @Column(name = "followed_user_id", nullable = false)
    private UUID followedUserId;

    protected UserFollowId() {
    }

    public UserFollowId(UUID followerUserId, UUID followedUserId) {
        this.followerUserId = Objects.requireNonNull(followerUserId);
        this.followedUserId = Objects.requireNonNull(followedUserId);
    }

    public UUID getFollowerUserId() { return followerUserId; }
    public UUID getFollowedUserId() { return followedUserId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserFollowId that)) return false;
        return followerUserId.equals(that.followerUserId)
                && followedUserId.equals(that.followedUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(followerUserId, followedUserId);
    }
}

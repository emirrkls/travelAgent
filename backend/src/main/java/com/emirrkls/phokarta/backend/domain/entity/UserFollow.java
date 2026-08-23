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
@Table(name = "user_follows")
public class UserFollow {

    @EmbeddedId
    private UserFollowId id;

    @MapsId("followerUserId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follower_user_id", nullable = false)
    private User follower;

    @MapsId("followedUserId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "followed_user_id", nullable = false)
    private User followed;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected UserFollow() {
    }

    public UserFollow(User follower, User followed, OffsetDateTime createdAt) {
        this.follower = follower;
        this.followed = followed;
        this.id = new UserFollowId(follower.getId(), followed.getId());
        this.createdAt = createdAt;
    }

    public UserFollowId getId() { return id; }
    public User getFollower() { return follower; }
    public User getFollowed() { return followed; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}

package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SavedPlaceId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "place_id", nullable = false)
    private UUID placeId;

    protected SavedPlaceId() {
    }

    public SavedPlaceId(UUID userId, UUID placeId) {
        this.userId = Objects.requireNonNull(userId);
        this.placeId = Objects.requireNonNull(placeId);
    }

    public UUID getUserId() { return userId; }
    public UUID getPlaceId() { return placeId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SavedPlaceId that)) return false;
        return userId.equals(that.userId) && placeId.equals(that.placeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, placeId);
    }
}

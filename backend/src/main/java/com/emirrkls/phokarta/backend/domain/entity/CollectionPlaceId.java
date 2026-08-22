package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class CollectionPlaceId implements Serializable {

    @Column(name = "collection_id", nullable = false)
    private UUID collectionId;

    @Column(name = "place_id", nullable = false)
    private UUID placeId;

    protected CollectionPlaceId() {
    }

    public CollectionPlaceId(UUID collectionId, UUID placeId) {
        this.collectionId = Objects.requireNonNull(collectionId);
        this.placeId = Objects.requireNonNull(placeId);
    }

    public UUID getCollectionId() { return collectionId; }
    public UUID getPlaceId() { return placeId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CollectionPlaceId that)) return false;
        return collectionId.equals(that.collectionId) && placeId.equals(that.placeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collectionId, placeId);
    }
}

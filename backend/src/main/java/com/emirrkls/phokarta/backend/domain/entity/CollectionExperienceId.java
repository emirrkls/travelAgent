package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class CollectionExperienceId implements Serializable {
    private UUID collectionId;
    private UUID experienceId;
    protected CollectionExperienceId() {}
    public CollectionExperienceId(UUID collectionId, UUID experienceId) {
        this.collectionId = collectionId;
        this.experienceId = experienceId;
    }
    public UUID getCollectionId() { return collectionId; }
    public UUID getExperienceId() { return experienceId; }
    @Override public boolean equals(Object value) {
        return value instanceof CollectionExperienceId other
                && Objects.equals(collectionId, other.collectionId)
                && Objects.equals(experienceId, other.experienceId);
    }
    @Override public int hashCode() { return Objects.hash(collectionId, experienceId); }
}

package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class PlannedExperienceId implements Serializable {
    private UUID userId;
    private UUID experienceId;

    protected PlannedExperienceId() {}
    public PlannedExperienceId(UUID userId, UUID experienceId) {
        this.userId = userId;
        this.experienceId = experienceId;
    }
    public UUID getUserId() { return userId; }
    public UUID getExperienceId() { return experienceId; }
    @Override public boolean equals(Object value) {
        return value instanceof PlannedExperienceId other
                && Objects.equals(userId, other.userId)
                && Objects.equals(experienceId, other.experienceId);
    }
    @Override public int hashCode() { return Objects.hash(userId, experienceId); }
}

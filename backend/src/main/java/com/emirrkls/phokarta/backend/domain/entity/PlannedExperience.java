package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "planned_experiences")
public class PlannedExperience {
    @EmbeddedId private PlannedExperienceId id;
    @MapsId("userId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private User user;
    @MapsId("experienceId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experience_id", nullable = false) private Visit experience;
    @Column(name = "planned_at", nullable = false) private OffsetDateTime plannedAt;

    protected PlannedExperience() {}
    public PlannedExperience(User user, Visit experience, OffsetDateTime plannedAt) {
        this.user = user;
        this.experience = experience;
        this.id = new PlannedExperienceId(user.getId(), experience.getId());
        this.plannedAt = plannedAt;
    }
    public PlannedExperienceId getId() { return id; }
    public Visit getExperience() { return experience; }
    public OffsetDateTime getPlannedAt() { return plannedAt; }
}

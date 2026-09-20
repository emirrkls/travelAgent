package com.emirrkls.phokarta.backend.domain.entity;

import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "experience_acknowledgements")
public class ExperienceAcknowledgement {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private User user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_experience_id") private Visit sourceExperience;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false) private Place place;
    @Enumerated(EnumType.STRING)
    @Column(name = "primary_experience_code", nullable = false, length = 60)
    private PrimaryExperienceCode primaryExperienceCode;
    @Column(name = "raw_experience_label", length = 120) private String rawExperienceLabel;
    @Column(name = "acknowledged_at", nullable = false) private OffsetDateTime acknowledgedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "converted_experience_id") private Visit convertedExperience;
    @Column(name = "converted_at") private OffsetDateTime convertedAt;

    protected ExperienceAcknowledgement() {}
    public ExperienceAcknowledgement(UUID id, User user, Visit sourceExperience, Place place,
            PrimaryExperienceCode primaryExperienceCode, String rawExperienceLabel,
            OffsetDateTime acknowledgedAt) {
        this.id = id; this.user = user; this.sourceExperience = sourceExperience; this.place = place;
        this.primaryExperienceCode = primaryExperienceCode; this.rawExperienceLabel = rawExperienceLabel;
        this.acknowledgedAt = acknowledgedAt;
    }
    public UUID getId() { return id; }
    public User getUser() { return user; }
    public Visit getSourceExperience() { return sourceExperience; }
    public Place getPlace() { return place; }
    public PrimaryExperienceCode getPrimaryExperienceCode() { return primaryExperienceCode; }
    public String getRawExperienceLabel() { return rawExperienceLabel; }
    public OffsetDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public Visit getConvertedExperience() { return convertedExperience; }
    public OffsetDateTime getConvertedAt() { return convertedAt; }
    public boolean isConverted() { return convertedAt != null; }
    public void convertTo(Visit experience, OffsetDateTime now) {
        if (convertedAt != null
                && (convertedExperience == null || !convertedExperience.getId().equals(experience.getId()))) {
            throw new IllegalStateException("Acknowledgement is already converted");
        }
        convertedExperience = experience;
        convertedAt = convertedAt == null ? now : convertedAt;
    }
}

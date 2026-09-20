package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "collection_experiences")
public class CollectionExperience {
    @EmbeddedId private CollectionExperienceId id;
    @MapsId("collectionId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id", nullable = false) private Collection collection;
    @MapsId("experienceId") @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experience_id", nullable = false) private Visit experience;
    @Column(name = "display_order", nullable = false) private int displayOrder;
    @Column(name = "added_at", nullable = false) private OffsetDateTime addedAt;

    protected CollectionExperience() {}
    public CollectionExperience(Collection collection, Visit experience, int displayOrder, OffsetDateTime addedAt) {
        this.collection = collection;
        this.experience = experience;
        this.id = new CollectionExperienceId(collection.getId(), experience.getId());
        this.displayOrder = displayOrder;
        this.addedAt = addedAt;
    }
    public CollectionExperienceId getId() { return id; }
    public Visit getExperience() { return experience; }
    public int getDisplayOrder() { return displayOrder; }
    public OffsetDateTime getAddedAt() { return addedAt; }
}

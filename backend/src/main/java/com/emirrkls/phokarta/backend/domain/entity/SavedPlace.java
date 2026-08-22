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
@Table(name = "saved_places")
public class SavedPlace {

    @EmbeddedId
    private SavedPlaceId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("placeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(name = "saved_at", nullable = false)
    private OffsetDateTime savedAt;

    protected SavedPlace() {
    }

    public SavedPlace(User user, Place place, OffsetDateTime savedAt) {
        this.user = user;
        this.place = place;
        this.id = new SavedPlaceId(user.getId(), place.getId());
        this.savedAt = savedAt;
    }

    public SavedPlaceId getId() { return id; }
    public User getUser() { return user; }
    public Place getPlace() { return place; }
    public OffsetDateTime getSavedAt() { return savedAt; }
}

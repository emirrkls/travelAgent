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
@Table(name = "collection_places")
public class CollectionPlace {

    @EmbeddedId
    private CollectionPlaceId id;

    @MapsId("collectionId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "collection_id", nullable = false)
    private Collection collection;

    @MapsId("placeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "added_at", nullable = false)
    private OffsetDateTime addedAt;

    protected CollectionPlace() {
    }

    public CollectionPlace(Collection collection, Place place, int displayOrder, OffsetDateTime addedAt) {
        this.collection = collection;
        this.place = place;
        this.id = new CollectionPlaceId(collection.getId(), place.getId());
        this.displayOrder = displayOrder;
        this.addedAt = addedAt;
    }

    public CollectionPlaceId getId() { return id; }
    public Collection getCollection() { return collection; }
    public Place getPlace() { return place; }
    public int getDisplayOrder() { return displayOrder; }
    public OffsetDateTime getAddedAt() { return addedAt; }
}

package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "visit_media")
public class VisitMedia {
    @EmbeddedId
    private VisitMediaId id;

    @MapsId("visitId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @MapsId("mediaId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    private MediaAsset media;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected VisitMedia() {
    }

    public VisitMedia(Visit visit, MediaAsset media, int sortOrder) {
        this.id = new VisitMediaId(visit.getId(), media.getId());
        this.visit = visit;
        this.media = media;
        this.sortOrder = sortOrder;
    }

    public Visit getVisit() { return visit; }
    public MediaAsset getMedia() { return media; }
    public int getSortOrder() { return sortOrder; }
}

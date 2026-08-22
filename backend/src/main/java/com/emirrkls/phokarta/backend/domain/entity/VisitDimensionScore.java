package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "visit_dimension_scores")
public class VisitDimensionScore {

    @EmbeddedId
    private VisitDimensionScoreId id;

    @MapsId("visitId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Column(nullable = false)
    private double score;

    protected VisitDimensionScore() {
    }

    public VisitDimensionScore(Visit visit, String dimensionKey, double score) {
        this.visit = visit;
        this.id = new VisitDimensionScoreId(visit.getId(), dimensionKey);
        this.score = score;
    }

    public VisitDimensionScoreId getId() { return id; }
    public Visit getVisit() { return visit; }
    public double getScore() { return score; }
}

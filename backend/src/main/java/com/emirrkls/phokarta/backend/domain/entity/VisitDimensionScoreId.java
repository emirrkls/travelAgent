package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class VisitDimensionScoreId implements Serializable {

    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "dimension_key", nullable = false, length = 40)
    private String dimensionKey;

    protected VisitDimensionScoreId() {
    }

    public VisitDimensionScoreId(UUID visitId, String dimensionKey) {
        this.visitId = Objects.requireNonNull(visitId);
        this.dimensionKey = Objects.requireNonNull(dimensionKey);
    }

    public UUID getVisitId() { return visitId; }
    public String getDimensionKey() { return dimensionKey; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof VisitDimensionScoreId that)) return false;
        return visitId.equals(that.visitId) && dimensionKey.equals(that.dimensionKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(visitId, dimensionKey);
    }
}

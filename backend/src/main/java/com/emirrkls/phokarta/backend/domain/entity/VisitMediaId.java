package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class VisitMediaId implements Serializable {
    @Column(name = "visit_id")
    private UUID visitId;
    @Column(name = "media_id")
    private UUID mediaId;

    protected VisitMediaId() {
    }

    public VisitMediaId(UUID visitId, UUID mediaId) {
        this.visitId = visitId;
        this.mediaId = mediaId;
    }

    public UUID getVisitId() { return visitId; }
    public UUID getMediaId() { return mediaId; }

    @Override public boolean equals(Object value) {
        return this == value || value instanceof VisitMediaId other
                && Objects.equals(visitId, other.visitId) && Objects.equals(mediaId, other.mediaId);
    }
    @Override public int hashCode() { return Objects.hash(visitId, mediaId); }
}

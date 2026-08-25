package com.emirrkls.phokarta.backend.domain.entity;

import com.emirrkls.phokarta.backend.domain.model.MediaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "media_assets")
public class MediaAsset {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @Column(name = "client_media_id", nullable = false)
    private UUID clientMediaId;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    private Integer width;
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "uploaded_at")
    private OffsetDateTime uploadedAt;
    @Column(name = "attached_at")
    private OffsetDateTime attachedAt;
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;
    @Column(length = 200)
    private String etag;

    protected MediaAsset() {
    }

    public MediaAsset(UUID id, User owner, UUID clientMediaId, String storageKey,
                      String contentType, long byteSize, Integer width, Integer height,
                      OffsetDateTime now, OffsetDateTime expiresAt) {
        this.id = id;
        this.owner = owner;
        this.clientMediaId = clientMediaId;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.width = width;
        this.height = height;
        this.status = MediaStatus.PENDING_UPLOAD;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public void markReady(OffsetDateTime now, OffsetDateTime expiresAt, String etag) {
        this.status = MediaStatus.READY;
        this.uploadedAt = now;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
        this.etag = etag;
    }

    public void markAttached(OffsetDateTime now) {
        this.status = MediaStatus.ATTACHED;
        this.attachedAt = now;
        this.updatedAt = now;
        this.expiresAt = null;
    }

    public void markDeleting(OffsetDateTime now) {
        this.status = MediaStatus.DELETING;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public User getOwner() { return owner; }
    public UUID getClientMediaId() { return clientMediaId; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public long getByteSize() { return byteSize; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public MediaStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUploadedAt() { return uploadedAt; }
    public OffsetDateTime getAttachedAt() { return attachedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public String getEtag() { return etag; }
}

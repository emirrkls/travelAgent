package com.emirrkls.phokarta.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_deletion_media_jobs")
public class AccountDeletionMediaJob {

    @Id
    private UUID id;

    @Column(name = "deletion_id", nullable = false)
    private UUID deletionId;

    @Column(name = "storage_key", nullable = false, unique = true, length = 500)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error_category", length = 40)
    private String lastErrorCategory;

    protected AccountDeletionMediaJob() {
    }

    public AccountDeletionMediaJob(UUID id, UUID deletionId, String storageKey,
                                   OffsetDateTime now) {
        this.id = id;
        this.deletionId = deletionId;
        this.storageKey = storageKey;
        this.createdAt = now;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
    }

    public UUID getId() { return id; }
    public UUID getDeletionId() { return deletionId; }
    public String getStorageKey() { return storageKey; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public int getAttemptCount() { return attemptCount; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public String getLastErrorCategory() { return lastErrorCategory; }

    public void lease(OffsetDateTime nextAttemptAt) {
        this.attemptCount += 1;
        this.nextAttemptAt = nextAttemptAt;
    }

    public void markError(String errorCategory) {
        this.lastErrorCategory = errorCategory;
    }
}

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
    public static final String AWAITING_FINAL = "awaiting_final";
    public static final String STORAGE_ERROR = "storage_error";

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

    /**
     * Retryable error ({@code storage_error}) or the phase sentinel
     * {@link #AWAITING_FINAL} after a successful initial object delete.
     * V10 has no dedicated phase column; {@code awaiting_final} is never used
     * as an error category, so the encoding is unambiguous. Attempt count is
     * only a lease/retry counter.
     */
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

    public boolean isAwaitingFinal() {
        return AWAITING_FINAL.equals(lastErrorCategory);
    }

    public void markAwaitingFinal(OffsetDateTime nextAttemptAt) {
        this.lastErrorCategory = AWAITING_FINAL;
        this.nextAttemptAt = nextAttemptAt;
    }
}

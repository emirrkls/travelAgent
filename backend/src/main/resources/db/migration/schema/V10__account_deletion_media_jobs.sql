-- Durable object-storage cleanup after hard account deletion.
-- Product identity is removed in the same transaction that inserts these rows.
-- Jobs retain only storage keys and a random deletion batch id — never email,
-- username, review text, or privateMemory.

CREATE TABLE account_deletion_media_jobs (
    id UUID PRIMARY KEY,
    deletion_id UUID NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error_category VARCHAR(40),
    CONSTRAINT uq_account_deletion_media_jobs_storage_key UNIQUE (storage_key)
);

CREATE INDEX idx_account_deletion_media_jobs_due
    ON account_deletion_media_jobs (next_attempt_at, id);

CREATE INDEX idx_account_deletion_media_jobs_deletion
    ON account_deletion_media_jobs (deletion_id);

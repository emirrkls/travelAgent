CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_media_id UUID NOT NULL,
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    content_type VARCHAR(40) NOT NULL
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 15728640),
    width INTEGER CHECK (width IS NULL OR width > 0),
    height INTEGER CHECK (height IS NULL OR height > 0),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING_UPLOAD', 'READY', 'DELETING', 'ATTACHED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    uploaded_at TIMESTAMPTZ,
    attached_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    etag VARCHAR(200),
    CONSTRAINT uq_media_assets_owner_client UNIQUE (owner_user_id, client_media_id),
    CONSTRAINT media_assets_status_timestamps CHECK (
        (status = 'PENDING_UPLOAD' AND uploaded_at IS NULL AND attached_at IS NULL AND expires_at IS NOT NULL)
        OR (status = 'READY' AND uploaded_at IS NOT NULL AND attached_at IS NULL AND expires_at IS NOT NULL)
        OR (status = 'DELETING' AND attached_at IS NULL AND expires_at IS NOT NULL)
        OR (status = 'ATTACHED' AND uploaded_at IS NOT NULL AND attached_at IS NOT NULL AND expires_at IS NULL)
    )
);

CREATE TABLE visit_media (
    visit_id UUID NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media_assets(id) ON DELETE RESTRICT,
    sort_order INTEGER NOT NULL CHECK (sort_order >= 0 AND sort_order < 20),
    PRIMARY KEY (visit_id, media_id),
    CONSTRAINT uq_visit_media_media UNIQUE (media_id),
    CONSTRAINT uq_visit_media_order UNIQUE (visit_id, sort_order)
);

CREATE INDEX idx_media_assets_owner_status ON media_assets(owner_user_id, status);
CREATE INDEX idx_media_assets_expiry ON media_assets(expires_at, id)
    WHERE expires_at IS NOT NULL AND status IN ('PENDING_UPLOAD', 'READY', 'DELETING');
CREATE INDEX idx_visit_media_visit_order ON visit_media(visit_id, sort_order);

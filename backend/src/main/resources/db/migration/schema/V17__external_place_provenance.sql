ALTER TABLE places
    ADD COLUMN origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_COMMUNITY',
    ADD COLUMN catalog_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT places_origin_valid
        CHECK (origin IN ('MANUAL_COMMUNITY', 'EXTERNAL_IMPORT')),
    ADD CONSTRAINT places_catalog_status_valid
        CHECK (catalog_status IN ('ACTIVE', 'RETIRED'));

ALTER TABLE places DROP CONSTRAINT places_price_level_check;
ALTER TABLE places ADD CONSTRAINT places_price_level_check
    CHECK (price_level BETWEEN 0 AND 4);

COMMENT ON COLUMN places.price_level IS
    '0 means unknown; 1-4 retain the legacy price-band meaning.';
COMMENT ON COLUMN places.origin IS
    'Canonical identity origin only. Provider identity remains in place_external_refs.';

CREATE INDEX idx_places_catalog_location_gist
    ON places USING GIST(location)
    WHERE catalog_status = 'ACTIVE';
CREATE INDEX idx_places_catalog_search
    ON places(catalog_status, city, category, lower(name));

CREATE TABLE place_provider_sync_runs (
    id UUID PRIMARY KEY,
    provider VARCHAR(24) NOT NULL
        CHECK (provider IN ('OVERTURE', 'FSQ', 'MULTI_SOURCE')),
    requested_release VARCHAR(160),
    resolved_release VARCHAR(160) NOT NULL,
    snapshot_id VARCHAR(160),
    method_version VARCHAR(40) NOT NULL,
    scope_name VARCHAR(80) NOT NULL,
    scope_center_latitude DOUBLE PRECISION NOT NULL
        CHECK (scope_center_latitude BETWEEN -90 AND 90),
    scope_center_longitude DOUBLE PRECISION NOT NULL
        CHECK (scope_center_longitude BETWEEN -180 AND 180),
    scope_radius_meters DOUBLE PRECISION NOT NULL
        CHECK (scope_radius_meters > 0),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    source_count BIGINT NOT NULL DEFAULT 0 CHECK (source_count >= 0),
    usable_count BIGINT NOT NULL DEFAULT 0 CHECK (usable_count >= 0),
    created_count BIGINT NOT NULL DEFAULT 0 CHECK (created_count >= 0),
    linked_count BIGINT NOT NULL DEFAULT 0 CHECK (linked_count >= 0),
    review_count BIGINT NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    rejected_count BIGINT NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    checkpoint_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    retry_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    failure_reason VARCHAR(500),
    manifest_hash CHAR(64),
    CONSTRAINT place_sync_run_completion_valid CHECK (
        (status = 'STARTED' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT place_sync_run_manifest_hash_valid CHECK (
        manifest_hash IS NULL OR manifest_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT place_sync_run_json_bounded CHECK (
        octet_length(checkpoint_state::text) <= 16384
        AND octet_length(retry_metadata::text) <= 16384
    )
);

CREATE UNIQUE INDEX uq_place_sync_run_manifest
    ON place_provider_sync_runs(manifest_hash)
    WHERE manifest_hash IS NOT NULL;
CREATE INDEX idx_place_sync_run_provider_started
    ON place_provider_sync_runs(provider, started_at DESC, id);

CREATE TABLE place_source_records (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL
        REFERENCES place_provider_sync_runs(id) ON DELETE RESTRICT,
    provider VARCHAR(24) NOT NULL CHECK (provider IN ('OVERTURE', 'FSQ')),
    external_id VARCHAR(255) NOT NULL,
    source_release VARCHAR(160) NOT NULL,
    snapshot_id VARCHAR(160),
    method_version VARCHAR(40) NOT NULL,
    normalized_name VARCHAR(300),
    location geometry(Point, 4326),
    address VARCHAR(500),
    locality VARCHAR(160),
    region VARCHAR(160),
    country_code VARCHAR(8),
    provider_categories JSONB NOT NULL DEFAULT '[]'::jsonb,
    proposed_place_category VARCHAR(30),
    phone VARCHAR(80),
    website VARCHAR(500),
    operating_status VARCHAR(40),
    source_hash CHAR(64) NOT NULL,
    license_identifier VARCHAR(160) NOT NULL,
    provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    observed_at TIMESTAMPTZ NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT place_source_record_category_valid CHECK (
        proposed_place_category IS NULL OR proposed_place_category IN (
            'BEACH', 'RESTAURANT', 'CAFE', 'HOTEL', 'BAR',
            'NIGHTLIFE', 'ATTRACTION', 'ACTIVITY', 'NATURE'
        )
    ),
    CONSTRAINT place_source_record_hash_valid
        CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT place_source_record_location_valid CHECK (
        location IS NULL OR (ST_IsValid(location) AND ST_SRID(location) = 4326)
    ),
    CONSTRAINT place_source_record_json_bounded CHECK (
        jsonb_typeof(provider_categories) = 'array'
        AND octet_length(provider_categories::text) <= 8192
        AND jsonb_typeof(provenance) = 'object'
        AND octet_length(provenance::text) <= 16384
    ),
    UNIQUE (provider, external_id, source_release, method_version, source_hash)
);

CREATE INDEX idx_place_source_record_external
    ON place_source_records(provider, external_id, retrieved_at DESC);
CREATE INDEX idx_place_source_record_location_gist
    ON place_source_records USING GIST(location);

CREATE FUNCTION reject_place_source_record_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_source_records are append-only'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_source_records_append_only
BEFORE UPDATE OR DELETE ON place_source_records
FOR EACH ROW EXECUTE FUNCTION reject_place_source_record_mutation();

CREATE TABLE place_external_refs (
    provider VARCHAR(24) NOT NULL CHECK (provider IN ('OVERTURE', 'FSQ')),
    external_id VARCHAR(255) NOT NULL,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    current_source_record_id UUID NOT NULL
        REFERENCES place_source_records(id) ON DELETE RESTRICT,
    source_release VARCHAR(160) NOT NULL,
    snapshot_id VARCHAR(160),
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'MERGED')),
    redirected_provider VARCHAR(24),
    redirected_external_id VARCHAR(255),
    source_hash CHAR(64) NOT NULL,
    last_sync_run_id UUID NOT NULL
        REFERENCES place_provider_sync_runs(id) ON DELETE RESTRICT,
    PRIMARY KEY (provider, external_id),
    CONSTRAINT place_external_ref_hash_valid
        CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT place_external_ref_seen_order
        CHECK (last_seen_at >= first_seen_at),
    CONSTRAINT place_external_ref_redirect_shape CHECK (
        (status = 'MERGED'
            AND redirected_provider IN ('OVERTURE', 'FSQ')
            AND redirected_external_id IS NOT NULL
            AND (redirected_provider, redirected_external_id) <> (provider, external_id))
        OR (status <> 'MERGED'
            AND redirected_provider IS NULL
            AND redirected_external_id IS NULL)
    )
);

CREATE INDEX idx_place_external_ref_place
    ON place_external_refs(place_id, provider, status);
CREATE INDEX idx_place_external_ref_redirect
    ON place_external_refs(redirected_provider, redirected_external_id)
    WHERE status = 'MERGED';

CREATE TABLE place_external_ref_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(24) NOT NULL CHECK (provider IN ('OVERTURE', 'FSQ')),
    external_id VARCHAR(255) NOT NULL,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    event_type VARCHAR(20) NOT NULL
        CHECK (event_type IN ('LINKED', 'SEEN', 'REMOVED', 'MERGED', 'REACTIVATED')),
    source_record_id UUID REFERENCES place_source_records(id) ON DELETE RESTRICT,
    sync_run_id UUID NOT NULL
        REFERENCES place_provider_sync_runs(id) ON DELETE RESTRICT,
    redirected_provider VARCHAR(24),
    redirected_external_id VARCHAR(255),
    occurred_at TIMESTAMPTZ NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT place_external_ref_event_redirect_shape CHECK (
        (event_type = 'MERGED'
            AND redirected_provider IN ('OVERTURE', 'FSQ')
            AND redirected_external_id IS NOT NULL)
        OR (event_type <> 'MERGED'
            AND redirected_provider IS NULL
            AND redirected_external_id IS NULL)
    ),
    CONSTRAINT place_external_ref_event_details_bounded CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 8192
    )
);

CREATE INDEX idx_place_external_ref_event_history
    ON place_external_ref_events(provider, external_id, occurred_at DESC, id);

CREATE FUNCTION reject_place_external_ref_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_external_ref_events are append-only'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_external_ref_events_append_only
BEFORE UPDATE OR DELETE ON place_external_ref_events
FOR EACH ROW EXECUTE FUNCTION reject_place_external_ref_event_mutation();

CREATE TABLE place_canonical_overrides (
    id UUID PRIMARY KEY,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    field_name VARCHAR(40) NOT NULL CHECK (field_name IN (
        'name', 'category', 'location', 'address', 'city', 'region', 'country'
    )),
    override_value JSONB NOT NULL,
    prior_value JSONB,
    actor_type VARCHAR(24) NOT NULL CHECK (actor_type IN (
        'ADMIN', 'HUMAN_REVIEW', 'COMMUNITY_TRUSTED', 'SYSTEM_MIGRATION'
    )),
    actor_reference VARCHAR(160),
    reason VARCHAR(500) NOT NULL,
    source_record_id UUID REFERENCES place_source_records(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL,
    superseded_at TIMESTAMPTZ,
    CONSTRAINT place_canonical_override_value_bounded CHECK (
        octet_length(override_value::text) <= 8192
        AND (prior_value IS NULL OR octet_length(prior_value::text) <= 8192)
    ),
    CONSTRAINT place_canonical_override_time_valid CHECK (
        superseded_at IS NULL OR superseded_at >= created_at
    )
);

CREATE UNIQUE INDEX uq_place_canonical_override_active
    ON place_canonical_overrides(place_id, field_name)
    WHERE superseded_at IS NULL;
CREATE INDEX idx_place_canonical_override_history
    ON place_canonical_overrides(place_id, field_name, created_at DESC, id);

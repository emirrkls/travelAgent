ALTER TABLE places
    ADD COLUMN origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL_COMMUNITY',
    ADD COLUMN catalog_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

-- Existing catalog rows are checked without holding the stronger ADD-CONSTRAINT table lock
-- for the duration of the scan. Flyway still runs V17 transactionally; the ordinary indexes
-- below therefore cannot use CREATE INDEX CONCURRENTLY and need a rehearsed maintenance window.
ALTER TABLE places ADD CONSTRAINT places_origin_valid
    CHECK (origin IN ('MANUAL_COMMUNITY', 'EXTERNAL_IMPORT')) NOT VALID;
ALTER TABLE places VALIDATE CONSTRAINT places_origin_valid;
ALTER TABLE places ADD CONSTRAINT places_catalog_status_valid
    CHECK (catalog_status IN ('ACTIVE', 'PROVISIONAL', 'RETIRED')) NOT VALID;
ALTER TABLE places VALIDATE CONSTRAINT places_catalog_status_valid;

ALTER TABLE places DROP CONSTRAINT places_price_level_check;
ALTER TABLE places ADD CONSTRAINT places_price_level_check
    CHECK (price_level BETWEEN 0 AND 4) NOT VALID;
ALTER TABLE places VALIDATE CONSTRAINT places_price_level_check;

COMMENT ON COLUMN places.price_level IS
    '0 means unknown; 1-4 retain the legacy price-band meaning.';
COMMENT ON COLUMN places.origin IS
    'Canonical identity origin only. Provider identity remains in place_external_refs.';

CREATE INDEX idx_places_catalog_location_gist
    ON places USING GIST(location)
    WHERE catalog_status = 'ACTIVE';
CREATE INDEX idx_places_catalog_search
    ON places(catalog_status, city, category, lower(name));

-- One immutable authorization lineage identifies a logical pilot. Normal stages reuse the
-- current frozen plan; only a fully contained failed leaf attempt can add one successor binding.
CREATE TABLE place_pilot_authorization_bindings (
    pilot_run_key VARCHAR(160) NOT NULL,
    authorization_reference VARCHAR(200) NOT NULL UNIQUE,
    plan_digest CHAR(64) NOT NULL CHECK (plan_digest ~ '^[0-9a-f]{64}$'),
    supersedes_authorization_reference VARCHAR(200) UNIQUE,
    PRIMARY KEY (pilot_run_key, authorization_reference, plan_digest),
    UNIQUE (pilot_run_key, authorization_reference),
    CONSTRAINT place_pilot_authorization_predecessor_fk
        FOREIGN KEY (pilot_run_key, supersedes_authorization_reference)
        REFERENCES place_pilot_authorization_bindings(
            pilot_run_key, authorization_reference
        )
        ON DELETE RESTRICT,
    CONSTRAINT place_pilot_authorization_not_self CHECK (
        supersedes_authorization_reference IS NULL
        OR supersedes_authorization_reference <> authorization_reference
    )
);

CREATE UNIQUE INDEX uq_place_pilot_authorization_root
    ON place_pilot_authorization_bindings(pilot_run_key)
    WHERE supersedes_authorization_reference IS NULL;

CREATE FUNCTION reject_place_pilot_authorization_binding_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place pilot authorization bindings are immutable'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_pilot_authorization_bindings_immutable
BEFORE UPDATE OR DELETE ON place_pilot_authorization_bindings
FOR EACH ROW EXECUTE FUNCTION reject_place_pilot_authorization_binding_mutation();

CREATE TABLE place_provider_sync_runs (
    id UUID PRIMARY KEY,
    pilot_run_key VARCHAR(160) NOT NULL,
    canary_stage VARCHAR(16) NOT NULL
        CHECK (canary_stage IN ('DRY_RUN', 'STAGE_1', 'STAGE_2', 'STAGE_3')),
    authorization_reference VARCHAR(200),
    reauthorizes_run_id UUID UNIQUE,
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
    gate_deadline TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
    claim_token UUID,
    claim_expires_at TIMESTAMPTZ,
    source_count BIGINT NOT NULL DEFAULT 0 CHECK (source_count >= 0),
    usable_count BIGINT NOT NULL DEFAULT 0 CHECK (usable_count >= 0),
    source_rejected_count BIGINT NOT NULL DEFAULT 0 CHECK (source_rejected_count >= 0),
    created_count BIGINT NOT NULL DEFAULT 0 CHECK (created_count >= 0),
    linked_count BIGINT NOT NULL DEFAULT 0 CHECK (linked_count >= 0),
    enriched_count BIGINT NOT NULL DEFAULT 0 CHECK (enriched_count >= 0),
    auto_rejected_count BIGINT NOT NULL DEFAULT 0 CHECK (auto_rejected_count >= 0),
    quarantined_count BIGINT NOT NULL DEFAULT 0 CHECK (quarantined_count >= 0),
    canary_eligible_count BIGINT NOT NULL DEFAULT 0 CHECK (canary_eligible_count >= 0),
    checkpoint_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    retry_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    failure_reason VARCHAR(500),
    manifest_hash CHAR(64),
    plan_digest CHAR(64),
    CONSTRAINT uq_place_sync_run_id_pilot UNIQUE (id, pilot_run_key),
    CONSTRAINT uq_place_sync_run_id_manifest UNIQUE (id, manifest_hash),
    CONSTRAINT uq_place_sync_run_id_stage UNIQUE (id, canary_stage),
    CONSTRAINT uq_place_sync_run_id_pilot_stage UNIQUE (id, pilot_run_key, canary_stage),
    CONSTRAINT place_sync_run_reauthorization_fk
        FOREIGN KEY (reauthorizes_run_id, pilot_run_key, canary_stage)
        REFERENCES place_provider_sync_runs(id, pilot_run_key, canary_stage)
        ON DELETE RESTRICT,
    CONSTRAINT place_sync_run_authorization_binding_fk
        FOREIGN KEY (pilot_run_key, authorization_reference, plan_digest)
        REFERENCES place_pilot_authorization_bindings(
            pilot_run_key, authorization_reference, plan_digest
        ) ON DELETE RESTRICT,
    CONSTRAINT place_sync_run_completion_valid CHECK (
        (status = 'STARTED' AND completed_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL)
    ),
    CONSTRAINT place_sync_run_completion_order_valid CHECK (
        completed_at IS NULL OR completed_at >= started_at
    ),
    CONSTRAINT place_sync_run_gate_deadline_valid CHECK (
        (canary_stage = 'DRY_RUN' AND gate_deadline IS NULL)
        OR (canary_stage <> 'DRY_RUN' AND (
            (status = 'SUCCEEDED' AND gate_deadline IS NOT NULL
                AND gate_deadline > completed_at)
            OR (status IN ('STARTED', 'FAILED') AND gate_deadline IS NULL)
        ))
    ),
    CONSTRAINT place_sync_run_claim_state_valid CHECK (
        (status = 'STARTED' AND claim_token IS NOT NULL AND claim_expires_at IS NOT NULL)
        OR (status IN ('SUCCEEDED', 'FAILED')
            AND claim_token IS NULL AND claim_expires_at IS NULL)
    ),
    CONSTRAINT place_sync_run_manifest_hash_valid CHECK (
        manifest_hash IS NULL OR manifest_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT place_sync_run_plan_digest_valid CHECK (
        plan_digest IS NULL OR plan_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT place_sync_run_authorization_valid CHECK (
        (canary_stage = 'DRY_RUN'
            AND authorization_reference IS NULL AND plan_digest IS NULL
            AND reauthorizes_run_id IS NULL)
        OR (canary_stage <> 'DRY_RUN'
            AND authorization_reference IS NOT NULL AND plan_digest IS NOT NULL)
    ),
    CONSTRAINT place_sync_run_json_bounded CHECK (
        octet_length(checkpoint_state::text) <= 16384
        AND octet_length(retry_metadata::text) <= 16384
    )
);

CREATE UNIQUE INDEX uq_place_sync_run_manifest
    ON place_provider_sync_runs(manifest_hash)
    WHERE manifest_hash IS NOT NULL;
CREATE UNIQUE INDEX uq_place_sync_run_active_pilot
    ON place_provider_sync_runs(pilot_run_key)
    WHERE status = 'STARTED';
CREATE INDEX idx_place_sync_run_provider_started
    ON place_provider_sync_runs(provider, started_at DESC, id);
CREATE INDEX idx_place_sync_run_pilot
    ON place_provider_sync_runs(pilot_run_key, canary_stage, started_at DESC, id);
CREATE INDEX idx_place_sync_run_gate_deadline
    ON place_provider_sync_runs(gate_deadline, id)
    WHERE status = 'SUCCEEDED' AND canary_stage <> 'DRY_RUN';

CREATE FUNCTION validate_place_provider_sync_run_identity()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.gate_deadline IS NOT NULL
        AND NEW.gate_deadline > clock_timestamp() + interval '24 hours' THEN
        RAISE EXCEPTION 'place provider sync run gate deadline exceeds 24 hours'
            USING ERRCODE = '23514';
    END IF;

    IF TG_OP = 'UPDATE' AND (
        OLD.id IS DISTINCT FROM NEW.id
        OR OLD.pilot_run_key IS DISTINCT FROM NEW.pilot_run_key
        OR OLD.canary_stage IS DISTINCT FROM NEW.canary_stage
        OR OLD.authorization_reference IS DISTINCT FROM NEW.authorization_reference
        OR OLD.reauthorizes_run_id IS DISTINCT FROM NEW.reauthorizes_run_id
        OR OLD.provider IS DISTINCT FROM NEW.provider
        OR OLD.requested_release IS DISTINCT FROM NEW.requested_release
        OR OLD.resolved_release IS DISTINCT FROM NEW.resolved_release
        OR OLD.snapshot_id IS DISTINCT FROM NEW.snapshot_id
        OR OLD.method_version IS DISTINCT FROM NEW.method_version
        OR OLD.scope_name IS DISTINCT FROM NEW.scope_name
        OR OLD.scope_center_latitude IS DISTINCT FROM NEW.scope_center_latitude
        OR OLD.scope_center_longitude IS DISTINCT FROM NEW.scope_center_longitude
        OR OLD.scope_radius_meters IS DISTINCT FROM NEW.scope_radius_meters
        OR OLD.started_at IS DISTINCT FROM NEW.started_at
        OR OLD.manifest_hash IS DISTINCT FROM NEW.manifest_hash
        OR OLD.plan_digest IS DISTINCT FROM NEW.plan_digest
    ) THEN
        RAISE EXCEPTION 'place provider sync run identity is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'UPDATE'
        AND OLD.status IN ('SUCCEEDED', 'FAILED')
        AND NEW IS DISTINCT FROM OLD THEN
        IF NOT (
            OLD.status = 'SUCCEEDED'
            AND NEW.status = 'SUCCEEDED'
            AND OLD.canary_stage <> 'DRY_RUN'
            AND OLD.gate_deadline IS NOT NULL
            AND NEW.gate_deadline > OLD.gate_deadline
            AND OLD.gate_deadline > clock_timestamp()
            AND NEW.gate_deadline <= clock_timestamp() + interval '24 hours'
            AND (to_jsonb(NEW) - 'gate_deadline') =
                (to_jsonb(OLD) - 'gate_deadline')
            AND NOT EXISTS (
                SELECT 1 FROM place_pilot_canary_gates gate
                 WHERE gate.sync_run_id = OLD.id
            )
        ) THEN
            RAISE EXCEPTION 'completed place provider sync runs are terminal'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    IF TG_OP = 'INSERT' AND NEW.authorization_reference IS NOT NULL THEN
        IF EXISTS (
            SELECT 1
              FROM place_pilot_authorization_bindings binding
             WHERE binding.pilot_run_key = NEW.pilot_run_key
               AND binding.authorization_reference = NEW.authorization_reference
               AND binding.plan_digest = NEW.plan_digest
        ) THEN
            IF NEW.reauthorizes_run_id IS NOT NULL THEN
                RAISE EXCEPTION 'reauthorization requires a new authorization reference'
                    USING ERRCODE = '23514';
            END IF;
            IF EXISTS (
                SELECT 1 FROM place_pilot_authorization_bindings successor_binding
                 WHERE successor_binding.supersedes_authorization_reference =
                       NEW.authorization_reference
            ) THEN
                RAISE EXCEPTION 'superseded pilot authorization cannot start another run'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF NOT EXISTS (
            SELECT 1 FROM place_pilot_authorization_bindings binding
             WHERE binding.pilot_run_key = NEW.pilot_run_key
        ) THEN
            IF NEW.reauthorizes_run_id IS NOT NULL THEN
                RAISE EXCEPTION 'initial pilot authorization cannot reauthorize a prior run'
                    USING ERRCODE = '23514';
            END IF;
            INSERT INTO place_pilot_authorization_bindings (
                pilot_run_key, authorization_reference, plan_digest,
                supersedes_authorization_reference
            ) VALUES (NEW.pilot_run_key, NEW.authorization_reference, NEW.plan_digest, NULL);
        ELSE
            IF NEW.reauthorizes_run_id IS NULL THEN
                RAISE EXCEPTION 'pilot identity is already bound to another authorization reference'
                    USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
                SELECT 1
                  FROM place_provider_sync_runs prior
                 WHERE prior.id = NEW.reauthorizes_run_id
                   AND prior.pilot_run_key = NEW.pilot_run_key
                   AND prior.canary_stage = NEW.canary_stage
                   AND (
                       prior.status = 'FAILED'
                       OR EXISTS (
                           SELECT 1 FROM place_pilot_canary_gates gate
                            WHERE gate.sync_run_id = prior.id
                              AND gate.gate_status = 'FAILED'
                       )
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_catalog_writes write
                        WHERE write.sync_run_id = prior.id
                          AND write.rollback_state NOT IN
                              ('RETIRED', 'RETIRED_GRAPH_PROTECTED')
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM place_provider_sync_runs successor
                        WHERE successor.reauthorizes_run_id = prior.id
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM place_pilot_authorization_bindings successor_binding
                        WHERE successor_binding.supersedes_authorization_reference =
                              prior.authorization_reference
                   )
            ) THEN
                RAISE EXCEPTION 'reauthorization requires the contained leaf failed attempt for the same pilot stage'
                    USING ERRCODE = '23514';
            END IF;
            INSERT INTO place_pilot_authorization_bindings (
                pilot_run_key, authorization_reference, plan_digest,
                supersedes_authorization_reference
            )
            SELECT NEW.pilot_run_key, NEW.authorization_reference, NEW.plan_digest,
                   prior.authorization_reference
              FROM place_provider_sync_runs prior
             WHERE prior.id = NEW.reauthorizes_run_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_provider_sync_run_identity
BEFORE INSERT OR UPDATE ON place_provider_sync_runs
FOR EACH ROW EXECUTE FUNCTION validate_place_provider_sync_run_identity();

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
    provider_categories JSONB NOT NULL,
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
    CONSTRAINT uq_place_source_record_id_external
        UNIQUE (id, provider, external_id),
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
    CONSTRAINT place_external_ref_current_source_identity_fk
        FOREIGN KEY (current_source_record_id, provider, external_id)
        REFERENCES place_source_records(id, provider, external_id)
        ON DELETE RESTRICT,
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
    ),
    CONSTRAINT place_external_ref_redirect_target_fk
        FOREIGN KEY (redirected_provider, redirected_external_id)
        REFERENCES place_external_refs(provider, external_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

CREATE FUNCTION validate_place_external_ref_source_snapshot()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM place_source_records source
         WHERE source.id = NEW.current_source_record_id
           AND source.provider = NEW.provider
           AND source.external_id = NEW.external_id
           AND source.source_release = NEW.source_release
           AND source.snapshot_id IS NOT DISTINCT FROM NEW.snapshot_id
           AND source.source_hash = NEW.source_hash
    ) THEN
        RAISE EXCEPTION 'external reference source snapshot does not match its current source record'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_external_ref_source_snapshot
BEFORE INSERT OR UPDATE ON place_external_refs
FOR EACH ROW EXECUTE FUNCTION validate_place_external_ref_source_snapshot();

CREATE FUNCTION validate_place_external_ref_redirect()
RETURNS TRIGGER AS $$
DECLARE
    target_place_id UUID;
    target_status VARCHAR(16);
BEGIN
    IF NEW.status = 'MERGED' THEN
        SELECT place_id, status INTO target_place_id, target_status
          FROM place_external_refs
         WHERE provider = NEW.redirected_provider
           AND external_id = NEW.redirected_external_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'merged external reference target does not exist'
                USING ERRCODE = '23503';
        END IF;
        IF target_place_id <> NEW.place_id THEN
            RAISE EXCEPTION 'merged external reference target crosses canonical Place UUIDs'
                USING ERRCODE = '23514';
        END IF;
        IF target_status = 'INACTIVE' THEN
            RAISE EXCEPTION 'merged external reference target is inactive'
                USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            WITH RECURSIVE redirect_chain(provider, external_id) AS (
                SELECT NEW.redirected_provider, NEW.redirected_external_id
                UNION
                SELECT next_ref.redirected_provider, next_ref.redirected_external_id
                  FROM place_external_refs next_ref
                  JOIN redirect_chain current_ref
                    ON next_ref.provider = current_ref.provider
                   AND next_ref.external_id = current_ref.external_id
                 WHERE next_ref.status = 'MERGED'
            )
            SELECT 1
              FROM redirect_chain
             WHERE provider = NEW.provider
               AND external_id = NEW.external_id
        ) THEN
            RAISE EXCEPTION 'external reference redirect cycle is not allowed'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.status = 'INACTIVE' AND EXISTS (
        SELECT 1 FROM place_external_refs source_ref
         WHERE source_ref.status = 'MERGED'
           AND source_ref.redirected_provider = NEW.provider
           AND source_ref.redirected_external_id = NEW.external_id
           AND (source_ref.provider, source_ref.external_id) <>
               (NEW.provider, NEW.external_id)
    ) THEN
        RAISE EXCEPTION 'an external reference with live merge redirects cannot be inactivated'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM place_external_refs source_ref
         WHERE source_ref.status = 'MERGED'
           AND source_ref.redirected_provider = NEW.provider
           AND source_ref.redirected_external_id = NEW.external_id
           AND source_ref.place_id <> NEW.place_id
    ) THEN
        RAISE EXCEPTION 'external reference update invalidates merge redirect identity'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Redirect integrity spans multiple rows. Acquire this before the mutating statement finishes,
-- so a deferred validator in a waiting transaction receives a fresh post-commit snapshot.
CREATE FUNCTION lock_place_external_ref_redirect_mutation()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(5517, 917);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_external_ref_redirect_serial
BEFORE INSERT OR UPDATE ON place_external_refs
FOR EACH STATEMENT EXECUTE FUNCTION lock_place_external_ref_redirect_mutation();

CREATE CONSTRAINT TRIGGER trg_place_external_ref_redirect_integrity
AFTER INSERT OR UPDATE ON place_external_refs
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_place_external_ref_redirect();

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
        CHECK (event_type IN (
            'LINKED', 'SEEN', 'REMOVED', 'MERGED', 'REACTIVATED', 'PILOT_ROLLBACK'
        )),
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
CREATE UNIQUE INDEX uq_place_external_ref_event_command
    ON place_external_ref_events(sync_run_id, provider, external_id, event_type);

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

CREATE FUNCTION restrict_place_canonical_override_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'place_canonical_overrides cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.place_id IS DISTINCT FROM NEW.place_id
        OR OLD.field_name IS DISTINCT FROM NEW.field_name
        OR OLD.override_value IS DISTINCT FROM NEW.override_value
        OR OLD.prior_value IS DISTINCT FROM NEW.prior_value
        OR OLD.actor_type IS DISTINCT FROM NEW.actor_type
        OR OLD.actor_reference IS DISTINCT FROM NEW.actor_reference
        OR OLD.reason IS DISTINCT FROM NEW.reason
        OR OLD.source_record_id IS DISTINCT FROM NEW.source_record_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at
        OR OLD.superseded_at IS NOT NULL
        OR NEW.superseded_at IS NULL THEN
        RAISE EXCEPTION 'place_canonical_overrides allow only one-way supersession'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_canonical_overrides_one_way
BEFORE UPDATE OR DELETE ON place_canonical_overrides
FOR EACH ROW EXECUTE FUNCTION restrict_place_canonical_override_mutation();

-- Immutable autonomous decisions keep rule-versioned evidence replayable. The current decision
-- for a candidate is the leaf of its supersedes_decision_id chain; QUARANTINE therefore remains
-- candidate/evidence state and never requires a user-visible places row.
CREATE TABLE place_validation_decisions (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL
        REFERENCES place_provider_sync_runs(id) ON DELETE RESTRICT,
    pilot_run_key VARCHAR(160) NOT NULL,
    candidate_key VARCHAR(200) NOT NULL,
    validation_method_version VARCHAR(80) NOT NULL,
    decision_state VARCHAR(20) NOT NULL CHECK (decision_state IN (
        'AUTO_LINK', 'AUTO_CREATE', 'AUTO_ENRICH', 'AUTO_REJECT', 'QUARANTINE'
    )),
    decision_reason VARCHAR(120) NOT NULL,
    existence_assessment VARCHAR(16) NOT NULL CHECK (existence_assessment IN (
        'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'
    )),
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    hard_blockers JSONB NOT NULL DEFAULT '[]'::jsonb,
    field_proposals JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_record_ids UUID[] NOT NULL,
    canonical_place_id UUID,
    candidate_hash CHAR(64) NOT NULL,
    canary_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    selected_for_stage BOOLEAN NOT NULL DEFAULT FALSE,
    selection_rank INTEGER,
    supersedes_decision_id UUID UNIQUE,
    decided_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_place_validation_decision_id_run UNIQUE (id, sync_run_id),
    CONSTRAINT uq_place_validation_decision_id_run_place
        UNIQUE (id, sync_run_id, canonical_place_id),
    CONSTRAINT uq_place_validation_decision_id_candidate
        UNIQUE (id, pilot_run_key, candidate_key),
    CONSTRAINT place_validation_decision_run_pilot_fk
        FOREIGN KEY (sync_run_id, pilot_run_key)
        REFERENCES place_provider_sync_runs(id, pilot_run_key) ON DELETE RESTRICT,
    CONSTRAINT place_validation_decision_supersedes_fk
        FOREIGN KEY (supersedes_decision_id, pilot_run_key, candidate_key)
        REFERENCES place_validation_decisions(id, pilot_run_key, candidate_key)
        ON DELETE RESTRICT,
    CONSTRAINT place_validation_decision_reason_valid CHECK (
        decision_reason ~ '^[A-Z][A-Z0-9_]{1,119}$'
    ),
    CONSTRAINT place_validation_decision_hash_valid CHECK (
        candidate_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT place_validation_decision_sources_valid CHECK (
        cardinality(source_record_ids) > 0
    ),
    CONSTRAINT place_validation_decision_json_valid CHECK (
        jsonb_typeof(evidence) = 'object'
        AND jsonb_typeof(hard_blockers) = 'array'
        AND jsonb_typeof(field_proposals) = 'object'
        AND octet_length(evidence::text) <= 65536
        AND octet_length(hard_blockers::text) <= 16384
        AND octet_length(field_proposals::text) <= 32768
    ),
    CONSTRAINT place_validation_canary_eligible_valid CHECK (
        NOT canary_eligible
        OR (
            decision_state = 'AUTO_CREATE'
            AND existence_assessment = 'HIGH'
            AND hard_blockers = '[]'::jsonb
            AND canonical_place_id IS NOT NULL
        )
    ),
    CONSTRAINT place_validation_stage_selection_valid CHECK (
        NOT selected_for_stage OR canary_eligible
    ),
    CONSTRAINT place_validation_selection_rank_valid CHECK (
        (canary_eligible AND selection_rank IS NOT NULL AND selection_rank > 0)
        OR (NOT canary_eligible AND selection_rank IS NULL)
    ),
    UNIQUE (sync_run_id, candidate_key, validation_method_version)
);

CREATE INDEX idx_place_validation_decision_candidate
    ON place_validation_decisions(pilot_run_key, candidate_key, decided_at DESC, id);
CREATE INDEX idx_place_validation_decision_quarantine
    ON place_validation_decisions(pilot_run_key, decided_at, id)
    WHERE decision_state = 'QUARANTINE';
CREATE INDEX idx_place_validation_decision_canary
    ON place_validation_decisions(sync_run_id, decided_at, id)
    WHERE canary_eligible;
CREATE UNIQUE INDEX uq_place_validation_decision_selection_rank
    ON place_validation_decisions(sync_run_id, selection_rank)
    WHERE selection_rank IS NOT NULL;

CREATE FUNCTION validate_place_validation_decision_supersession()
RETURNS TRIGGER AS $$
DECLARE
    prior_decided_at TIMESTAMPTZ;
BEGIN
    IF NEW.supersedes_decision_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT prior.decided_at INTO prior_decided_at
      FROM place_validation_decisions prior
      JOIN place_provider_sync_runs current_run
        ON current_run.id = NEW.sync_run_id
       AND current_run.reauthorizes_run_id = prior.sync_run_id
     WHERE prior.id = NEW.supersedes_decision_id
       AND prior.pilot_run_key = NEW.pilot_run_key
       AND prior.candidate_key = NEW.candidate_key
       AND prior.canonical_place_id IS NOT DISTINCT FROM NEW.canonical_place_id;
    IF NOT FOUND OR prior_decided_at > NEW.decided_at THEN
        RAISE EXCEPTION 'superseded validation decision must belong to the reauthorized predecessor candidate and Place'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_validation_decision_supersession
BEFORE INSERT ON place_validation_decisions
FOR EACH ROW EXECUTE FUNCTION validate_place_validation_decision_supersession();

CREATE FUNCTION reject_place_validation_decision_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_validation_decisions are append-only'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_validation_decisions_append_only
BEFORE UPDATE OR DELETE ON place_validation_decisions
FOR EACH ROW EXECUTE FUNCTION reject_place_validation_decision_mutation();

-- The queue is mutable operational state. Re-evaluation writes a new immutable decision and
-- points this row at it; it never edits historical evidence.
CREATE TABLE place_validation_recheck_queue (
    pilot_run_key VARCHAR(160) NOT NULL,
    candidate_key VARCHAR(200) NOT NULL,
    current_decision_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'LEASED', 'RESOLVED')),
    trigger_reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (pilot_run_key, candidate_key),
    CONSTRAINT place_validation_recheck_current_decision_fk
        FOREIGN KEY (current_decision_id, pilot_run_key, candidate_key)
        REFERENCES place_validation_decisions(id, pilot_run_key, candidate_key)
        ON DELETE RESTRICT,
    CONSTRAINT place_validation_recheck_json_valid CHECK (
        jsonb_typeof(trigger_reasons) = 'array'
        AND octet_length(trigger_reasons::text) <= 16384
    ),
    CONSTRAINT place_validation_recheck_lease_valid CHECK (
        (status = 'LEASED' AND lease_expires_at IS NOT NULL)
        OR (status <> 'LEASED' AND lease_expires_at IS NULL)
    )
);

CREATE INDEX idx_place_validation_recheck_due
    ON place_validation_recheck_queue(next_attempt_at, pilot_run_key, candidate_key)
    WHERE status = 'PENDING';

-- A durable write journal gives a canary rollback an exact scope. Rollback retires catalog
-- exposure; it never deletes a canonical Place or rewrites Phokarta-owned graph data.
CREATE TABLE place_pilot_catalog_writes (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL
        REFERENCES place_provider_sync_runs(id) ON DELETE RESTRICT,
    validation_decision_id UUID NOT NULL UNIQUE
        REFERENCES place_validation_decisions(id) ON DELETE RESTRICT,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    canary_stage VARCHAR(16) NOT NULL
        CHECK (canary_stage IN ('STAGE_1', 'STAGE_2', 'STAGE_3')),
    write_action VARCHAR(20) NOT NULL CHECK (write_action IN ('AUTO_CREATE')),
    supersedes_write_id UUID UNIQUE,
    imported_at TIMESTAMPTZ NOT NULL,
    rollback_state VARCHAR(32) NOT NULL DEFAULT 'NONE' CHECK (rollback_state IN (
        'NONE', 'RETIRED', 'RETIRED_GRAPH_PROTECTED', 'CONTAINED_NEWER_REFERENCES'
    )),
    rolled_back_at TIMESTAMPTZ,
    CONSTRAINT place_pilot_catalog_write_run_decision_fk
        FOREIGN KEY (validation_decision_id, sync_run_id, place_id)
        REFERENCES place_validation_decisions(id, sync_run_id, canonical_place_id)
        ON DELETE RESTRICT,
    CONSTRAINT place_pilot_catalog_write_run_stage_fk
        FOREIGN KEY (sync_run_id, canary_stage)
        REFERENCES place_provider_sync_runs(id, canary_stage) ON DELETE RESTRICT,
    CONSTRAINT uq_place_pilot_catalog_write_id_place UNIQUE (id, place_id),
    CONSTRAINT uq_place_pilot_catalog_write_run_place UNIQUE (sync_run_id, place_id),
    CONSTRAINT place_pilot_catalog_write_predecessor_fk
        FOREIGN KEY (supersedes_write_id, place_id)
        REFERENCES place_pilot_catalog_writes(id, place_id) ON DELETE RESTRICT,
    CONSTRAINT place_pilot_catalog_write_rollback_valid CHECK (
        (rollback_state = 'NONE' AND rolled_back_at IS NULL)
        OR (rollback_state <> 'NONE' AND rolled_back_at IS NOT NULL
            AND rolled_back_at >= imported_at)
    )
);

CREATE INDEX idx_place_pilot_catalog_write_run
    ON place_pilot_catalog_writes(sync_run_id, canary_stage, imported_at, id);
CREATE UNIQUE INDEX uq_place_pilot_catalog_write_active_place
    ON place_pilot_catalog_writes(place_id)
    WHERE rollback_state = 'NONE';

CREATE FUNCTION validate_place_pilot_catalog_write()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM place_validation_decisions decision
         WHERE decision.id = NEW.validation_decision_id
           AND decision.sync_run_id = NEW.sync_run_id
           AND decision.canonical_place_id = NEW.place_id
           AND decision.decision_state = 'AUTO_CREATE'
           AND decision.canary_eligible
           AND decision.selected_for_stage
    ) THEN
        RAISE EXCEPTION 'catalog write requires its selected eligible AUTO_CREATE decision bound to the canonical Place UUID'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.supersedes_write_id IS NULL AND EXISTS (
        SELECT 1 FROM place_pilot_catalog_writes prior
         WHERE prior.place_id = NEW.place_id AND prior.id <> NEW.id
    ) THEN
        RAISE EXCEPTION 'a replacement catalog write must name its contained predecessor'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.supersedes_write_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
          FROM place_pilot_catalog_writes prior
          JOIN place_validation_decisions current_decision
            ON current_decision.id = NEW.validation_decision_id
         WHERE prior.id = NEW.supersedes_write_id
           AND prior.place_id = NEW.place_id
           AND prior.rollback_state IN ('RETIRED', 'RETIRED_GRAPH_PROTECTED')
           AND current_decision.supersedes_decision_id = prior.validation_decision_id
    ) THEN
        RAISE EXCEPTION 'replacement catalog write requires a contained predecessor decision chain'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_pilot_catalog_write_integrity
BEFORE INSERT ON place_pilot_catalog_writes
FOR EACH ROW EXECUTE FUNCTION validate_place_pilot_catalog_write();

CREATE FUNCTION restrict_place_pilot_catalog_write_mutation()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'place_pilot_catalog_writes cannot be deleted'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.sync_run_id IS DISTINCT FROM NEW.sync_run_id
        OR OLD.validation_decision_id IS DISTINCT FROM NEW.validation_decision_id
        OR OLD.place_id IS DISTINCT FROM NEW.place_id
        OR OLD.canary_stage IS DISTINCT FROM NEW.canary_stage
        OR OLD.write_action IS DISTINCT FROM NEW.write_action
        OR OLD.supersedes_write_id IS DISTINCT FROM NEW.supersedes_write_id
        OR OLD.imported_at IS DISTINCT FROM NEW.imported_at
        OR OLD.rollback_state <> 'NONE'
        OR NEW.rollback_state NOT IN (
            'RETIRED', 'RETIRED_GRAPH_PROTECTED', 'CONTAINED_NEWER_REFERENCES'
        )
        OR NEW.rolled_back_at IS NULL THEN
        RAISE EXCEPTION 'place_pilot_catalog_writes allow only NONE to terminal rollback'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_pilot_catalog_writes_one_way
BEFORE UPDATE OR DELETE ON place_pilot_catalog_writes
FOR EACH ROW EXECUTE FUNCTION restrict_place_pilot_catalog_write_mutation();

-- Expansion is fail-closed: a later stage is accepted only after the immediately preceding
-- run has a durable PASSED gate result for the same pilot identity.
CREATE TABLE place_pilot_canary_gates (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL UNIQUE
        REFERENCES place_provider_sync_runs(id) ON DELETE RESTRICT,
    pilot_run_key VARCHAR(160) NOT NULL,
    canary_stage VARCHAR(16) NOT NULL
        CHECK (canary_stage IN ('STAGE_1', 'STAGE_2', 'STAGE_3')),
    gate_status VARCHAR(16) NOT NULL CHECK (gate_status IN ('PASSED', 'FAILED')),
    diagnostics JSONB NOT NULL DEFAULT '{}'::jsonb,
    checked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT place_pilot_canary_gate_run_identity_fk
        FOREIGN KEY (sync_run_id, pilot_run_key, canary_stage)
        REFERENCES place_provider_sync_runs(id, pilot_run_key, canary_stage)
        ON DELETE RESTRICT,
    CONSTRAINT place_pilot_canary_gate_json_valid CHECK (
        jsonb_typeof(diagnostics) = 'object'
        AND octet_length(diagnostics::text) <= 65536
    )
);

CREATE FUNCTION validate_place_pilot_canary_gate_chronology()
RETURNS TRIGGER AS $$
DECLARE
    run_completed_at TIMESTAMPTZ;
    run_gate_deadline TIMESTAMPTZ;
BEGIN
    SELECT completed_at, gate_deadline
      INTO run_completed_at, run_gate_deadline
      FROM place_provider_sync_runs
     WHERE id = NEW.sync_run_id;
    IF run_completed_at IS NULL OR NEW.checked_at < run_completed_at THEN
        RAISE EXCEPTION 'canary gate checked_at cannot precede run completion'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.checked_at > clock_timestamp() + interval '5 minutes' THEN
        RAISE EXCEPTION 'canary gate checked_at is materially in the future'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.gate_status = 'PASSED' AND (
        run_gate_deadline IS NULL
        OR NEW.checked_at >= run_gate_deadline
        OR clock_timestamp() >= run_gate_deadline
    ) THEN
        RAISE EXCEPTION 'passing canary gate missed its durable deadline'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_pilot_canary_gate_chronology
BEFORE INSERT ON place_pilot_canary_gates
FOR EACH ROW EXECUTE FUNCTION validate_place_pilot_canary_gate_chronology();

CREATE INDEX idx_place_pilot_canary_gate_progression
    ON place_pilot_canary_gates(pilot_run_key, canary_stage, gate_status, checked_at DESC);

CREATE FUNCTION reject_place_pilot_canary_gate_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_pilot_canary_gates are immutable'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_pilot_canary_gates_immutable
BEFORE UPDATE OR DELETE ON place_pilot_canary_gates
FOR EACH ROW EXECUTE FUNCTION reject_place_pilot_canary_gate_mutation();

-- A later product/operational acceptance failure is separate from an immutable automated
-- gate outcome. These events audit the bounded rollback-only command without rewriting PASS.
CREATE TABLE place_pilot_operational_events (
    id UUID PRIMARY KEY,
    sync_run_id UUID NOT NULL,
    manifest_hash CHAR(64) NOT NULL CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN (
        'PILOT_CONTAINMENT_REQUESTED', 'PILOT_ROLLBACK_STARTED', 'PILOT_ROLLBACK_COMPLETED'
    )),
    occurred_at TIMESTAMPTZ NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT place_pilot_operational_event_run_manifest_fk
        FOREIGN KEY (sync_run_id, manifest_hash)
        REFERENCES place_provider_sync_runs(id, manifest_hash) ON DELETE RESTRICT,
    CONSTRAINT place_pilot_operational_event_unique_type UNIQUE (sync_run_id, event_type),
    CONSTRAINT place_pilot_operational_event_details_valid CHECK (
        jsonb_typeof(details) = 'object' AND octet_length(details::text) <= 16384
    )
);

CREATE FUNCTION validate_place_pilot_operational_event()
RETURNS TRIGGER AS $$
DECLARE
    run_completed_at TIMESTAMPTZ;
    predecessor_at TIMESTAMPTZ;
    predecessor_type VARCHAR(32);
BEGIN
    SELECT completed_at INTO run_completed_at
      FROM place_provider_sync_runs
     WHERE id = NEW.sync_run_id AND manifest_hash = NEW.manifest_hash
       AND canary_stage <> 'DRY_RUN' AND status IN ('SUCCEEDED', 'FAILED');
    IF run_completed_at IS NULL OR NEW.occurred_at < run_completed_at THEN
        RAISE EXCEPTION 'operational rollback event requires its completed manifest-bound pilot run'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.occurred_at > clock_timestamp() + interval '5 minutes' THEN
        RAISE EXCEPTION 'operational rollback event is materially in the future'
            USING ERRCODE = '23514';
    END IF;
    predecessor_type := CASE NEW.event_type
        WHEN 'PILOT_ROLLBACK_STARTED' THEN 'PILOT_CONTAINMENT_REQUESTED'
        WHEN 'PILOT_ROLLBACK_COMPLETED' THEN 'PILOT_ROLLBACK_STARTED'
        ELSE NULL
    END;
    IF predecessor_type IS NOT NULL THEN
        SELECT occurred_at INTO predecessor_at FROM place_pilot_operational_events
         WHERE sync_run_id = NEW.sync_run_id AND event_type = predecessor_type;
        IF predecessor_at IS NULL OR NEW.occurred_at < predecessor_at THEN
            RAISE EXCEPTION 'operational rollback events require ordered request/start/completion'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_pilot_operational_event_valid
BEFORE INSERT ON place_pilot_operational_events
FOR EACH ROW EXECUTE FUNCTION validate_place_pilot_operational_event();

CREATE FUNCTION reject_place_pilot_operational_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'place_pilot_operational_events are append-only'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_pilot_operational_events_append_only
BEFORE UPDATE OR DELETE ON place_pilot_operational_events
FOR EACH ROW EXECUTE FUNCTION reject_place_pilot_operational_event_mutation();

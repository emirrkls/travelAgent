ALTER TABLE users
    ADD COLUMN profile_visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    ADD CONSTRAINT users_profile_visibility_check
        CHECK (profile_visibility IN ('PUBLIC', 'PRIVATE'));

CREATE TABLE follow_requests (
    id UUID PRIMARY KEY,
    requester_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT follow_requests_no_self CHECK (requester_user_id <> target_user_id),
    CONSTRAINT follow_requests_resolution_check CHECK (
        (status = 'PENDING' AND resolved_at IS NULL)
        OR (status <> 'PENDING' AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_follow_requests_pending_pair
    ON follow_requests(requester_user_id, target_user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_follow_requests_target_pending
    ON follow_requests(target_user_id, created_at DESC, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_follow_requests_requester_pending
    ON follow_requests(requester_user_id, created_at DESC, id)
    WHERE status = 'PENDING';

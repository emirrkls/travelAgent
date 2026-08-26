-- Directed blocks (one row = blocker blocked blocked). Product barrier is symmetric.
CREATE TABLE user_blocks (
    blocker_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CONSTRAINT user_blocks_no_self CHECK (blocker_user_id <> blocked_user_id)
);

CREATE INDEX idx_user_blocks_blocker ON user_blocks (blocker_user_id, created_at DESC);
CREATE INDEX idx_user_blocks_blocked ON user_blocks (blocked_user_id, created_at DESC);
CREATE INDEX idx_user_blocks_pair_reverse ON user_blocks (blocked_user_id, blocker_user_id);

-- Moderation-ready abuse reports. Survive account/content deletion with FKs nulled.
CREATE TABLE reports (
    id UUID PRIMARY KEY,
    reporter_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    target_type VARCHAR(16) NOT NULL,
    target_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    target_visit_id UUID REFERENCES visits (id) ON DELETE SET NULL,
    reason VARCHAR(32) NOT NULL,
    details VARCHAR(2000),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reports_target_type_valid CHECK (target_type IN ('USER', 'VISIT')),
    CONSTRAINT reports_reason_valid CHECK (reason IN (
        'SPAM', 'HARASSMENT', 'HATE_OR_ABUSE', 'SEXUAL_CONTENT',
        'VIOLENCE_OR_THREAT', 'IMPERSONATION', 'PRIVACY', 'OTHER'
    )),
    CONSTRAINT reports_status_valid CHECK (status IN ('OPEN', 'REVIEWED', 'ACTIONED', 'DISMISSED')),
    CONSTRAINT reports_target_shape CHECK (
        (target_type = 'USER' AND target_visit_id IS NULL)
        OR (target_type = 'VISIT')
    )
);

CREATE INDEX idx_reports_status_created ON reports (status, created_at DESC);
CREATE INDEX idx_reports_target_user ON reports (target_user_id, created_at DESC)
    WHERE target_user_id IS NOT NULL;
CREATE INDEX idx_reports_target_visit ON reports (target_visit_id, created_at DESC)
    WHERE target_visit_id IS NOT NULL;
CREATE INDEX idx_reports_reason_created ON reports (reason, created_at DESC);

CREATE UNIQUE INDEX idx_reports_open_user ON reports (reporter_user_id, target_user_id)
    WHERE status = 'OPEN'
      AND target_type = 'USER'
      AND reporter_user_id IS NOT NULL
      AND target_user_id IS NOT NULL;

CREATE UNIQUE INDEX idx_reports_open_visit ON reports (reporter_user_id, target_visit_id)
    WHERE status = 'OPEN'
      AND target_type = 'VISIT'
      AND reporter_user_id IS NOT NULL
      AND target_visit_id IS NOT NULL;

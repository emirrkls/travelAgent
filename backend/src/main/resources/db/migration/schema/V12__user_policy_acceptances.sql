-- Versioned UGC / User Policy acceptance. Terms can change, so this is not a permanent boolean.
-- Rows are owned product data: they cascade away with the account. Not mixed with report retention.
CREATE TABLE user_policy_acceptances (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    policy_version VARCHAR(64) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_policy_acceptances_version_not_blank CHECK (length(trim(policy_version)) > 0)
);

CREATE UNIQUE INDEX idx_user_policy_acceptances_user_version
    ON user_policy_acceptances (user_id, policy_version);

CREATE INDEX idx_user_policy_acceptances_user_accepted
    ON user_policy_acceptances (user_id, accepted_at DESC);

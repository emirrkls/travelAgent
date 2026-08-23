-- Authentication foundation: credentials on users, external identity hooks, refresh sessions.

ALTER TABLE users
    ADD COLUMN email VARCHAR(320),
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill legacy rows (e.g. demo seed applied before auth) with unique placeholder emails.
UPDATE users
SET email = lower(username) || '@legacy.phokarta.invalid'
WHERE email IS NULL;

ALTER TABLE users
    ALTER COLUMN email SET NOT NULL;

CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE UNIQUE INDEX uq_users_username_lower ON users (lower(username));

CREATE TABLE auth_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL
        CHECK (provider IN ('LOCAL', 'GOOGLE', 'APPLE')),
    provider_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_auth_identities_provider_subject UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_auth_identities_user ON auth_identities(user_id);

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by_session_id UUID REFERENCES refresh_sessions(id),
    CONSTRAINT uq_refresh_sessions_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_sessions_user ON refresh_sessions(user_id);
CREATE INDEX idx_refresh_sessions_family ON refresh_sessions(family_id);
CREATE INDEX idx_refresh_sessions_active
    ON refresh_sessions(token_hash)
    WHERE revoked_at IS NULL;

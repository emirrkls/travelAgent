-- Hibernate maps String to VARCHAR; align refresh token hashes accordingly.
ALTER TABLE refresh_sessions
    ALTER COLUMN token_hash TYPE VARCHAR(64);

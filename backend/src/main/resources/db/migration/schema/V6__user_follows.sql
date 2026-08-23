CREATE TABLE user_follows (
    follower_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followed_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (follower_user_id, followed_user_id),
    CONSTRAINT user_follows_no_self CHECK (follower_user_id <> followed_user_id)
);

CREATE INDEX idx_user_follows_follower ON user_follows (follower_user_id, created_at DESC);
CREATE INDEX idx_user_follows_followed ON user_follows (followed_user_id, created_at DESC);

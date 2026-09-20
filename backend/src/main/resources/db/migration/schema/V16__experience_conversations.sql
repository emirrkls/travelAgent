CREATE TABLE experience_conversation_entries (
    id UUID PRIMARY KEY,
    experience_id UUID NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    entry_type VARCHAR(16) NOT NULL
        CHECK (entry_type IN ('QUESTION', 'COMMENT', 'REPLY')),
    parent_entry_id UUID REFERENCES experience_conversation_entries(id) ON DELETE CASCADE,
    body VARCHAR(1000) NOT NULL,
    client_mutation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT experience_conversation_entry_shape CHECK (
        (entry_type IN ('QUESTION', 'COMMENT') AND parent_entry_id IS NULL)
        OR (entry_type = 'REPLY' AND parent_entry_id IS NOT NULL)
    ),
    CONSTRAINT experience_conversation_body_valid CHECK (
        length(trim(body)) BETWEEN 1 AND 1000
    ),
    CONSTRAINT experience_conversation_edit_time_valid CHECK (
        edited_at IS NULL OR edited_at >= created_at
    ),
    CONSTRAINT experience_conversation_delete_time_valid CHECK (
        deleted_at IS NULL OR deleted_at >= created_at
    ),
    UNIQUE (author_user_id, client_mutation_id)
);

CREATE INDEX idx_experience_conversation_roots
    ON experience_conversation_entries(experience_id, created_at DESC, id DESC)
    WHERE parent_entry_id IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_experience_conversation_replies
    ON experience_conversation_entries(parent_entry_id, created_at ASC, id ASC)
    WHERE parent_entry_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_experience_conversation_author
    ON experience_conversation_entries(author_user_id, created_at DESC, id DESC);

CREATE FUNCTION enforce_experience_conversation_reply_parent()
RETURNS TRIGGER AS $$
DECLARE
    parent_experience UUID;
    parent_type VARCHAR(16);
    parent_parent UUID;
    parent_deleted TIMESTAMPTZ;
BEGIN
    IF NEW.entry_type <> 'REPLY' THEN
        RETURN NEW;
    END IF;

    SELECT experience_id, entry_type, parent_entry_id, deleted_at
      INTO parent_experience, parent_type, parent_parent, parent_deleted
      FROM experience_conversation_entries
     WHERE id = NEW.parent_entry_id;

    IF NOT FOUND
       OR parent_experience <> NEW.experience_id
       OR parent_type NOT IN ('QUESTION', 'COMMENT')
       OR parent_parent IS NOT NULL
       OR parent_deleted IS NOT NULL THEN
        RAISE EXCEPTION 'conversation reply parent must be an active root in the same Experience'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_experience_conversation_reply_parent
BEFORE INSERT OR UPDATE OF experience_id, entry_type, parent_entry_id
ON experience_conversation_entries
FOR EACH ROW EXECUTE FUNCTION enforce_experience_conversation_reply_parent();

ALTER TABLE reports ALTER COLUMN target_type TYPE VARCHAR(24);
ALTER TABLE reports ADD COLUMN target_conversation_entry_id UUID
    REFERENCES experience_conversation_entries(id) ON DELETE SET NULL;
ALTER TABLE reports DROP CONSTRAINT reports_target_type_valid;
ALTER TABLE reports ADD CONSTRAINT reports_target_type_valid
    CHECK (target_type IN ('USER', 'VISIT', 'CONVERSATION_ENTRY'));
ALTER TABLE reports DROP CONSTRAINT reports_target_shape;
ALTER TABLE reports ADD CONSTRAINT reports_target_shape CHECK (
    (target_type = 'USER'
        AND target_visit_id IS NULL
        AND target_conversation_entry_id IS NULL)
    OR (target_type = 'VISIT' AND target_conversation_entry_id IS NULL)
    OR target_type = 'CONVERSATION_ENTRY'
);

CREATE INDEX idx_reports_target_conversation
    ON reports(target_conversation_entry_id, created_at DESC)
    WHERE target_conversation_entry_id IS NOT NULL;
CREATE UNIQUE INDEX idx_reports_open_conversation
    ON reports(reporter_user_id, target_conversation_entry_id)
    WHERE status = 'OPEN'
      AND target_type = 'CONVERSATION_ENTRY'
      AND reporter_user_id IS NOT NULL
      AND target_conversation_entry_id IS NOT NULL;

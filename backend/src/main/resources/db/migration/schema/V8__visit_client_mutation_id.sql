ALTER TABLE visits
    ADD COLUMN client_mutation_id UUID,
    ADD COLUMN client_payload_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX uq_visits_user_client_mutation
    ON visits(user_id, client_mutation_id)
    WHERE client_mutation_id IS NOT NULL;

ALTER TABLE visits ADD CONSTRAINT visits_client_mutation_pair
    CHECK ((client_mutation_id IS NULL AND client_payload_fingerprint IS NULL)
        OR (client_mutation_id IS NOT NULL AND client_payload_fingerprint IS NOT NULL));

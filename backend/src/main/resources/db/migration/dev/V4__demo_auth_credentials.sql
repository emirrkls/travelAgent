-- Development-only demo credentials. Password: DemoPass123!
-- BCrypt hash precomputed; never use these credentials outside local/dev.

UPDATE users
SET
    email = 'demo@phokarta.local',
    password_hash = '$2b$10$tS/arR/DyAZ91TWsKo6xTOAykt09Q/0AlssHHcBsTb7ayVOEYoJWC',
    enabled = TRUE,
    updated_at = now()
WHERE id = '11111111-1111-1111-1111-111111111111';

INSERT INTO auth_identities (id, user_id, provider, provider_subject, created_at, updated_at)
VALUES (
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    '11111111-1111-1111-1111-111111111111',
    'LOCAL',
    'demo@phokarta.local',
    now(),
    now()
)
ON CONFLICT (provider, provider_subject) DO NOTHING;

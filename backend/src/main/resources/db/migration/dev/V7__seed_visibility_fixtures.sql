-- Visibility fixtures for Community discovery semantics.
-- Place …001 already has PUBLIC 9.2; add PRIVATE 1.0 (must not affect Community average).
-- Place …015 previously unvisited; PRIVATE-only → Community Not rated.
-- Places …003 and …009 remain FRIENDS-only visits (Community Not rated).

INSERT INTO visits (
    id, user_id, place_id, visited_at, overall_rating, public_review, private_memory,
    photos, visibility, verification_status, created_at, updated_at
) VALUES
('30000000-0000-0000-0000-000000000009', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000001',
 '2025-05-19', 1.0, '', 'Gizli not — Community ortalamasına girmez.', ARRAY[]::TEXT[],
 'PRIVATE', 'UNVERIFIED', '2025-05-19T21:00:00Z', '2025-05-19T21:00:00Z'),
('30000000-0000-0000-0000-000000000010', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000015',
 '2025-06-01', 10.0, '', 'Sadece bende kalsın.', ARRAY[]::TEXT[],
 'PRIVATE', 'UNVERIFIED', '2025-06-01T10:00:00Z', '2025-06-01T10:00:00Z');

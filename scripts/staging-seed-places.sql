-- Explicit staging Place fixture. Apply manually after Flyway V1–V9 on an empty staging
-- database. Do not add this file to Flyway. Do not run it against production.
-- Cover URLs are synthetic placeholders, not managed Visit media.

INSERT INTO places (
    id, name, description, category, subcategories, location, city, region, country,
    address, cover_image, photos, price_level, created_at, updated_at
) VALUES
(
    'aa000000-0000-4000-8000-000000000001',
    'Staging Harbor Cafe',
    'Synthetic closed-beta cafe used for staging nearby and bounds checks.',
    'CAFE',
    ARRAY['Staging','Synthetic'],
    ST_SetSRID(ST_MakePoint(28.9784, 41.0220), 4326),
    'İstanbul', 'İstanbul', 'Türkiye',
    'Karaköy, staging fixture',
    'https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?w=1200',
    ARRAY[]::text[],
    2,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00'
),
(
    'aa000000-0000-4000-8000-000000000002',
    'Staging Courtyard Kitchen',
    'Synthetic closed-beta restaurant near the staging cafe for PostGIS radius tests.',
    'RESTAURANT',
    ARRAY['Staging','Synthetic'],
    ST_SetSRID(ST_MakePoint(29.0254, 41.0445), 4326),
    'İstanbul', 'İstanbul', 'Türkiye',
    'Kadıköy, staging fixture',
    'https://images.unsplash.com/photo-1414235077428-338989a2e8c0?w=1200',
    ARRAY[]::text[],
    3,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00'
)
ON CONFLICT (id) DO NOTHING;

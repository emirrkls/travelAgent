CREATE TABLE planned_experiences (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    experience_id UUID NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    planned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, experience_id)
);

CREATE INDEX idx_planned_experiences_user_time
    ON planned_experiences(user_id, planned_at DESC, experience_id);

CREATE TABLE collection_experiences (
    collection_id UUID NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    experience_id UUID NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (collection_id, experience_id)
);

CREATE INDEX idx_collection_experiences_collection_order
    ON collection_experiences(collection_id, display_order, experience_id);
CREATE INDEX idx_collection_experiences_experience
    ON collection_experiences(experience_id);

CREATE TABLE experience_acknowledgements (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_experience_id UUID REFERENCES visits(id) ON DELETE SET NULL,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    primary_experience_code VARCHAR(60) NOT NULL CHECK (primary_experience_code IN (
        'KAHVALTI', 'OGUN_YEMEK', 'KAHVE', 'TATLI', 'SOKAK_LEZZETI', 'YEREL_LEZZET',
        'GUN_BATIMI', 'GUN_DOGUMU', 'MANZARA', 'GECE_MANZARASI', 'FOTOGRAF_NOKTASI',
        'DENIZ_YUZME', 'PLAJ', 'TEKNE', 'DALIS_SNORKEL', 'SU_AKTIVITESI',
        'DOGA_YURUYUSU', 'PIKNIK', 'KAMP', 'ORMAN', 'GOL_SELALE', 'SEYIR_NOKTASI',
        'SOKAK_KESFI', 'MAHALLE_SEHIR_GEZISI', 'SAHIL_YURUYUSU', 'GIZLI_KOSE', 'ROTA_GEZI',
        'MUZE', 'TARIHI_YER', 'MIMARI', 'YEREL_PAZAR', 'YEREL_YASAM', 'SERGI_SANAT',
        'CANLI_MUZIK', 'BAR_PUB', 'GECE_HAYATI', 'KONSER_GOSTERI', 'SOSYAL_ETKINLIK',
        'BISIKLET', 'TIRMANIS', 'KAYAK', 'SU_SPORU', 'WORKSHOP', 'ACIK_HAVA_AKTIVITESI',
        'SAKIN_ZAMAN', 'SPA_HAMAM', 'TERMAL', 'YOGA_MEDITASYON', 'DINLENME',
        'OTEL', 'BUTIK_OTEL', 'HOSTEL', 'KAMP_KONAKLAMASI', 'KIRALIK_EV_BUNGALOV', 'OTHER'
    )),
    raw_experience_label VARCHAR(120),
    acknowledged_at TIMESTAMPTZ NOT NULL,
    converted_experience_id UUID REFERENCES visits(id) ON DELETE SET NULL,
    converted_at TIMESTAMPTZ,
    CONSTRAINT acknowledgement_other_label CHECK (
        (primary_experience_code = 'OTHER' AND length(trim(raw_experience_label)) > 0)
        OR (primary_experience_code <> 'OTHER' AND raw_experience_label IS NULL)
    ),
    CONSTRAINT acknowledgement_conversion_pair CHECK (
        (converted_experience_id IS NULL AND converted_at IS NULL)
        OR converted_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX uq_acknowledgement_user_source
    ON experience_acknowledgements(user_id, source_experience_id)
    WHERE source_experience_id IS NOT NULL;
CREATE UNIQUE INDEX uq_acknowledgement_converted_experience
    ON experience_acknowledgements(converted_experience_id)
    WHERE converted_experience_id IS NOT NULL;
CREATE INDEX idx_acknowledgement_user_unconverted
    ON experience_acknowledgements(user_id, acknowledged_at DESC, id)
    WHERE converted_at IS NULL;
CREATE INDEX idx_acknowledgement_source_count
    ON experience_acknowledgements(source_experience_id)
    WHERE source_experience_id IS NOT NULL;

CREATE TABLE visit_experience_details (
    visit_id UUID PRIMARY KEY REFERENCES visits(id) ON DELETE CASCADE,
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
    overall_feeling_code VARCHAR(40) NOT NULL CHECK (overall_feeling_code IN (
        'BAYILDIM', 'GUZELDI', 'EH_ISTE', 'BEKLENTIMI_KARSILAMADI', 'BIR_DAHA_TERCIH_ETMEM'
    )),
    feeling_source VARCHAR(30) NOT NULL CHECK (feeling_source IN ('EXPLICIT', 'DERIVED_LEGACY')),
    companion_code VARCHAR(20) CHECK (companion_code IN ('ALONE', 'PARTNER', 'FRIENDS', 'FAMILY', 'CHILDREN')),
    time_of_day_code VARCHAR(20) CHECK (time_of_day_code IN ('MORNING', 'DAYTIME', 'EVENING', 'NIGHT')),
    title VARCHAR(240) NOT NULL CHECK (length(trim(title)) > 0),
    title_source VARCHAR(20) NOT NULL CHECK (title_source IN ('GENERATED', 'CUSTOM')),
    story VARCHAR(4000) NOT NULL DEFAULT '',
    tip VARCHAR(1000),
    taxonomy_version INTEGER NOT NULL CHECK (taxonomy_version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT experience_other_label CHECK (
        (primary_experience_code = 'OTHER' AND length(trim(raw_experience_label)) > 0)
        OR (primary_experience_code <> 'OTHER' AND raw_experience_label IS NULL)
    )
);

CREATE TABLE visit_experience_vibes (
    visit_id UUID NOT NULL REFERENCES visit_experience_details(visit_id) ON DELETE CASCADE,
    vibe_code VARCHAR(30) NOT NULL CHECK (vibe_code IN (
        'CALM', 'LIVELY', 'ROMANTIC', 'SOCIAL', 'INTIMATE',
        'LOCAL_AUTHENTIC', 'SCENIC', 'ADVENTUROUS'
    )),
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (visit_id, vibe_code),
    UNIQUE (visit_id, position)
);

CREATE TABLE visit_experience_practical_signals (
    visit_id UUID NOT NULL REFERENCES visit_experience_details(visit_id) ON DELETE CASCADE,
    practical_signal_code VARCHAR(40) NOT NULL CHECK (practical_signal_code IN (
        'ACCESSIBLE_WITHOUT_CAR', 'CAR_RECOMMENDED', 'PARKING_DIFFICULT',
        'RESERVATION_RECOMMENDED', 'NO_RESERVATION_NEEDED', 'WEEKENDS_CROWDED',
        'MAY_BE_CROWDED', 'CALMER_IN_MORNING', 'IDEAL_FOR_SUNSET',
        'SUITABLE_WITH_CHILDREN', 'PET_FRIENDLY', 'WALKING_REQUIRED',
        'ARRIVE_EARLY', 'CASH_MAY_BE_NEEDED', 'FREE', 'QUIET_AREA_AVAILABLE'
    )),
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (visit_id, practical_signal_code),
    UNIQUE (visit_id, position)
);

ALTER TABLE visit_dimension_scores
    ADD COLUMN semantic_state_code VARCHAR(20),
    ADD COLUMN template_version INTEGER,
    ADD CONSTRAINT visit_dimension_semantic_pair CHECK (
        (semantic_state_code IS NULL AND template_version IS NULL)
        OR (
            semantic_state_code IN ('VERY_GOOD', 'GOOD', 'MEDIUM', 'WEAK', 'VERY_WEAK')
            AND template_version > 0
            AND score = CASE semantic_state_code
                WHEN 'VERY_GOOD' THEN 10
                WHEN 'GOOD' THEN 8
                WHEN 'MEDIUM' THEN 6
                WHEN 'WEAK' THEN 4
                WHEN 'VERY_WEAK' THEN 2
            END
        )
    );

CREATE INDEX idx_visit_experience_primary
    ON visit_experience_details(primary_experience_code);
CREATE INDEX idx_visit_experience_feeling
    ON visit_experience_details(overall_feeling_code);
CREATE INDEX idx_visit_experience_vibes_code
    ON visit_experience_vibes(vibe_code);
CREATE INDEX idx_visit_experience_practical_code
    ON visit_experience_practical_signals(practical_signal_code);
CREATE INDEX idx_visit_dimension_semantic_state
    ON visit_dimension_scores(semantic_state_code)
    WHERE semantic_state_code IS NOT NULL;

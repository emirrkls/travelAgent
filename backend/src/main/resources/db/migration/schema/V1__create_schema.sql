CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(500),
    bio VARCHAR(500),
    city_count INTEGER NOT NULL DEFAULT 0 CHECK (city_count >= 0),
    country_count INTEGER NOT NULL DEFAULT 0 CHECK (country_count >= 0),
    followers_count INTEGER NOT NULL DEFAULT 0 CHECK (followers_count >= 0),
    following_count INTEGER NOT NULL DEFAULT 0 CHECK (following_count >= 0),
    travel_taste TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE places (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    category VARCHAR(30) NOT NULL
        CHECK (category IN ('BEACH', 'RESTAURANT', 'CAFE', 'HOTEL', 'BAR', 'NIGHTLIFE', 'ATTRACTION', 'ACTIVITY', 'NATURE')),
    subcategories TEXT[] NOT NULL DEFAULT '{}',
    location geometry(Point, 4326) NOT NULL,
    city VARCHAR(100) NOT NULL,
    region VARCHAR(100) NOT NULL,
    country VARCHAR(100) NOT NULL,
    address VARCHAR(500) NOT NULL,
    cover_image VARCHAR(500) NOT NULL,
    photos TEXT[] NOT NULL DEFAULT '{}',
    price_level INTEGER NOT NULL CHECK (price_level BETWEEN 1 AND 4),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT places_location_valid CHECK (ST_IsValid(location)),
    CONSTRAINT places_location_srid CHECK (ST_SRID(location) = 4326)
);

CREATE TABLE visits (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    visited_at DATE NOT NULL,
    overall_rating DOUBLE PRECISION NOT NULL CHECK (overall_rating BETWEEN 0 AND 10),
    public_review VARCHAR(4000) NOT NULL DEFAULT '',
    private_memory VARCHAR(4000) NOT NULL DEFAULT '',
    photos TEXT[] NOT NULL DEFAULT '{}',
    visibility VARCHAR(20) NOT NULL CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'PUBLIC')),
    verification_status VARCHAR(30) NOT NULL
        CHECK (verification_status IN ('UNVERIFIED', 'LOCATION_CONFIRMED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE visit_dimension_scores (
    visit_id UUID NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    dimension_key VARCHAR(40) NOT NULL,
    score DOUBLE PRECISION NOT NULL CHECK (score BETWEEN 0 AND 10),
    PRIMARY KEY (visit_id, dimension_key)
);

CREATE TABLE saved_places (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    saved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, place_id)
);

CREATE TABLE collections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    visibility VARCHAR(20) NOT NULL CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'PUBLIC')),
    cover_image VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE collection_places (
    collection_id UUID NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    added_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (collection_id, place_id),
    UNIQUE (collection_id, display_order)
);

CREATE INDEX idx_places_category ON places(category);
CREATE INDEX idx_places_city_category ON places(city, category);
CREATE INDEX idx_places_location_gist ON places USING GIST(location);
CREATE INDEX idx_places_location_geography_gist ON places USING GIST((location::geography));
CREATE INDEX idx_visits_user_visited_at ON visits(user_id, visited_at DESC);
CREATE INDEX idx_visits_place_visited_at ON visits(place_id, visited_at DESC);
CREATE INDEX idx_visit_dimension_scores_key ON visit_dimension_scores(dimension_key);
CREATE INDEX idx_saved_places_place ON saved_places(place_id);
CREATE INDEX idx_saved_places_user_saved_at ON saved_places(user_id, saved_at DESC);
CREATE INDEX idx_collections_user_updated_at ON collections(user_id, updated_at DESC);
CREATE INDEX idx_collection_places_place ON collection_places(place_id);

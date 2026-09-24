# Phokarta Place Data Foundation

Status: Milestone 5.5A foundation and partial provider benchmark
Date: 2026-09-25
Benchmark output: `C:\Users\Emir\Documents\Phokarta_Place_Benchmark\M5_5A\20260925_013702`

## 1. Decision boundary

Milestone 5.5A builds provider-neutral ingestion and measures external Place data. It does not import a provider into the production database, alter a canonical Place UUID, change a public API or mobile contract, deploy data, or begin Milestone 5.5B.

The current result is **B. PARTIAL — FSQ DATA ACCESS REQUIRED**. Overture was measured. The FSQ adapter and offline tests are complete, but no Places Portal token was available, so no FSQ metric, overlap figure, comparative score, or winner is claimed.

## 2. Locked canonical invariant

`places.id` is Phokarta's canonical UUID. Overture GERS IDs and FSQ Place IDs are external aliases and provenance only.

```text
External provider record
        ↓
Normalized source record
        ↓
Conservative candidate matching
        ↓
AUTO_LINK | REVIEW_REQUIRED | CREATE_NEW
        ↓
Phokarta places.id UUID
```

Experience, Planım, Collection, acknowledgement, conversation, Map and Search relationships continue to resolve through the Phokarta UUID. A provider may be replaced or removed without changing those identities.

Place category and Experience taxonomy remain separate. The ten benchmark categories in this milestone are analysis buckets, not a new Phokarta taxonomy.

## 3. Current Place architecture audit

### 3.1 Database schema

Flyway `V1__create_schema.sql` creates `places` with:

- `id UUID PRIMARY KEY`;
- required `name VARCHAR(160)` and `description VARCHAR(2000)`;
- required enum-like `category VARCHAR(30)` constrained to `BEACH`, `RESTAURANT`, `CAFE`, `HOTEL`, `BAR`, `NIGHTLIFE`, `ATTRACTION`, `ACTIVITY`, or `NATURE`;
- required `subcategories TEXT[]`;
- required PostGIS `geometry(Point, 4326)` in `location`, with valid-geometry and SRID checks;
- required `city`, `region`, `country`, `address`, `cover_image`, `photos`, `price_level`, `created_at`, and `updated_at`.

Indexes are:

- B-tree `places(category)`;
- B-tree `places(city, category)`;
- GiST `places(location)`;
- GiST `places((location::geography))`.

Current free-text search uses case-insensitive `LIKE` over name and description. There is no text-search/trigram index. Nearby uses `ST_DWithin(location::geography, point::geography, radius)` and geodesic distance ordering. Map bounds use bbox overlap plus `ST_Intersects`.

### 3.2 Runtime model and APIs

`Place` is a JPA entity with the same UUID, category and Point fields. The production runtime has no Place-create/update endpoint and no `PlaceRepository.save` path. Runtime Place APIs are read/discovery surfaces:

- V1 list/search, nearby, bounds and detail;
- V2 Place aggregate and Place Experience feed;
- save/unsave Place;
- add/remove Place in a Collection;
- Experience publication that resolves an existing Place UUID.

Users currently create Experiences, not canonical Places. Android's mock catalog is presentation/development data; it is not a server Place creation path.

### 3.3 Demo data

The development-only Flyway location includes `V2__seed_demo_data.sql`, which inserts 15 synthetic Places and related Visits, saves and Collections. Production Flyway loads only `db/migration/schema`, so the demo seed is not a production source.

### 3.4 Identity dependency graph

Direct database references include:

- `visits.place_id → places.id ON DELETE CASCADE`;
- `saved_places.place_id → places.id ON DELETE CASCADE`;
- `collection_places.place_id → places.id ON DELETE CASCADE`;
- `experience_acknowledgements.place_id → places.id ON DELETE RESTRICT`.

Indirect Place dependencies include:

- native V2 Experiences, because the existing Visit UUID remains Experience identity;
- `planned_experiences → visits → places`;
- `collection_experiences → visits → places`;
- acknowledgement source/converted Experiences;
- Experience conversations attached to Visits;
- media attached to Visits;
- Place aggregate, feed, Map, nearby, profile and search reads;
- Android Room and iOS SQLite drafts, pending mutations, saved Place state, Collection membership and Place caches.

Replacing a Place UUID would orphan or require rewriting user-owned graph edges, offline payloads and caches. Deleting it is worse: the current Visit foreign key cascades through Experiences and their dependent graph, while the acknowledgement anchor explicitly restricts deletion. This is why an external ID must never become `places.id`, and why a provider removal must never directly delete a canonical Place.

## 4. Isolated provider foundation

The independent tool lives at `tools/place-ingestion/` and does not require Spring Boot or the production database.

`ExternalPlaceProvider` defines:

- `describe_release()`;
- `fetch_scope()`;
- `normalize()`;
- `license_metadata()`;
- a deliberately unimplemented `fetch_delta()` extension point for M5.5B.

The normalized record contains provider, external ID, source release, original name, WGS84 coordinate, address components, provider categories, neutral benchmark category, phone, website, operating state, provider quality, creation/refresh date, and raw source metadata needed for diagnostics.

Optional data remains null. The tool does not manufacture values. Provider-specific source semantics remain under `source_metadata`.

## 5. Provider access and release handling

### 5.1 Overture Maps Places

- Actual benchmark release: `2026-09-23.0`.
- Schema: `v2.0.0`.
- Access: official AWS S3 GeoParquet through DuckDB 1.4.0.
- Release discovery: Overture STAC `latest`; the benchmark pins the resolved release for reproducibility.
- Current fields: `id`, `version`, `names`, `basic_category`, `taxonomy`, `confidence`, `operating_status`, `websites`, `phones`, `addresses`, `sources` and point bbox coordinates.
- Legacy `categories` input is rejected with a schema error.
- Monthly data changelog, GERS registry and bridge files are future delta/reconciliation inputs.

The query projects only benchmark columns and pushes a bbox predicate into GeoParquet before applying the exact circle. It does not download the global dataset.

### 5.2 Foursquare Open Source Places

- September 2026 published dataset size: 109,440,828 global Places.
- Current access: Places Portal token and Iceberg REST catalog.
- Adapter target: current OS fields only, including `fsq_place_id`, name, WGS84 coordinates, address components, dates, `tel`, `website`, category IDs/labels and unresolved flags.
- Pro/Premium ratings, popularity and other paid attributes are not part of the model or benchmark.
- The adapter does not use the retired anonymous public S3 delivery.
- Monthly delta semantics are add, update, merge with redirect, and remove.

No `FSQ_PLACES_TOKEN` was present. Minimal action: create a token in the Foursquare Places Portal and set it as the process environment variable `FSQ_PLACES_TOKEN`, then rerun the tool. Current Portal endpoint, warehouse and table defaults are built in; non-secret overrides exist if the Portal snippet changes.

The token is used only in an in-memory DuckDB secret. It is never printed, logged, written to SQL artifacts, included in reports, or committed.

## 6. Pilot geometry

Both providers use the same public WGS84 center/radius circles. Provider-defined city boundaries are not used.

| Area | Latitude | Longitude | Radius |
|---|---:|---:|---:|
| Didim | 37.3751 | 27.2678 | 12,000 m |
| Foça | 38.6703 | 26.7566 | 10,000 m |
| Bodrum | 37.0344 | 27.4305 | 15,000 m |
| Çeşme | 38.3240 | 26.3030 | 14,000 m |
| Kadıköy | 40.9917 | 29.0277 | 8,000 m |
| Antalya Kaleiçi | 36.8854 | 30.7048 | 4,000 m |

The committed configuration records the reasoning for each radius. A bbox is derived only as a pushdown prefilter; final inclusion uses Haversine distance to the same center.

## 7. Neutral category benchmark

The analysis buckets are:

`RESTAURANT`, `CAFE`, `BAR_NIGHTLIFE`, `HOTEL_LODGING`, `BEACH`, `MUSEUM`, `HISTORIC_PLACE`, `PARK`, `VIEWPOINT`, `MARKET`, plus `UNMAPPED`.

Mappings are explicit, versioned and provider-specific. Exact IDs/categories are preferred; documented token mappings cover provider hierarchy labels. No uncertain value is forced. `UNMAPPED` is an expected result because the fetched Place population includes health care, retail, services, transport and many other concepts outside the ten requested buckets.

These mappings do not change the existing nine-value Phokarta Place category or the V2 Experience taxonomy.

## 8. Quality method

Two views are retained:

- **RAW:** every provider row inside the exact circle.
- **USABLE:** valid coordinate, non-empty name, no explicit reliable closed state, and no severe provider-specific exclusion.

Overture confidence below 0.30 is a severe exclusion. FSQ severe exclusions are unresolved `closed`, `duplicate`, `delete`, `privatevenue`, `inappropriate`, or `doesnt_exist` flags. Rules do not vary by area.

Unsupported attributes are `NOT AVAILABLE`, not silently zero. Overture freshness is derived only from upstream-source timestamps; the release-time Overture confidence-calculation timestamp is excluded.

## 9. Overture benchmark result

| Area | Raw | Usable | Neutral category mapped | Address | Locality | Phone | Website | Operating status | Refreshed ≤365d | Duplicate pairs | Obvious junk |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Didim | 4,085 | 3,919 | 37.013% | 73.121% | 98.091% | 61.542% | 32.827% | 0.000% | 97.821% | 7 | 4.064% |
| Foça | 888 | 865 | 44.032% | 70.495% | 97.297% | 55.518% | 32.320% | 0.000% | 96.959% | 3 | 2.590% |
| Bodrum | 12,652 | 12,081 | 40.215% | 78.383% | 98.459% | 68.543% | 45.068% | 0.008% | 95.463% | 37 | 4.513% |
| Çeşme | 5,008 | 4,795 | 55.871% | 81.430% | 98.762% | 71.865% | 46.266% | 0.000% | 95.787% | 14 | 4.253% |
| Kadıköy | 107,667 | 100,100 | 25.116% | 88.953% | 99.022% | 79.590% | 59.287% | 0.009% | 95.044% | 343 | 7.028% |
| Antalya Kaleiçi | 22,423 | 21,114 | 23.083% | 85.381% | 99.318% | 74.071% | 43.710% | 0.009% | 96.642% | 47 | 5.838% |

Total: 152,723 raw and 142,874 usable (93.551%). Names and coordinates are 100% complete in the scoped release. Upstream timestamp coverage is 100%, but it is only a source refresh indicator, not proof that the real-world Place is current. Operating-status coverage is effectively absent.

The six queries took 165.421 seconds in total. DuckDB did not expose reliable bytes-scanned/downloaded metrics, so they remain unavailable.

## 10. Known-place recall

The compact truth fixture contains 60 factual records: ten per area, across beaches, museums, historic sites, parks, viewpoints and markets. It stores only name, approximate coordinate, neutral category and public/official reference.

Matching is intentionally conservative: within 250 m, with at least 0.95 normalized-name similarity regardless of category, or at least 0.88 with a compatible mapped category. A close second within 0.03 similarity becomes ambiguous.

| Area | Matched | Missing | Ambiguous | Recall |
|---|---:|---:|---:|---:|
| Didim | 1 | 9 | 0 | 10% |
| Foça | 3 | 7 | 0 | 30% |
| Bodrum | 3 | 7 | 0 | 30% |
| Çeşme | 0 | 10 | 0 | 0% |
| Kadıköy | 1 | 9 | 0 | 10% |
| Antalya Kaleiçi | 5 | 5 | 0 | 50% |
| **Total** | **13** | **47** | **0** | **21.667%** |

Provider-only records are not classified as wrong. Missing truth matches require human inspection: causes may include provider absence, incorrect coordinate, alternate/localized naming, taxonomy incompatibility, or truth-fixture error.

## 11. Duplicate candidates and human sample

Name normalization applies Unicode NFKD, Unicode casefolding, combining-mark removal, punctuation-to-space normalization and whitespace collapse. It preserves the Turkish dotless `ı` distinction instead of applying an English-only lowercase rule. Original names are retained.

Internal duplicate candidate thresholds:

- distance at most 30 m;
- normalized-name similarity at least 0.82;
- high confidence when the normalized name is exact, or distance is at most 15 m and similarity is at least 0.94;
- every other candidate is ambiguous.

Overture produced 451 candidate pairs: 123 high-confidence and 328 ambiguous. This is an estimate, not an automatic merge list.

`review_sample.csv` uses fixed seed 5501 and samples usable rows across every available area/category stratum. No recommendation should depend only on automated metrics.

## 12. Cross-provider matching

When both providers are present, candidates use at most 50 m plus normalized-name similarity of at least 0.80. At most 25 m, similarity at least 0.93 and compatible categories yields `HIGH_CONFIDENCE_MATCH`; other candidate pairs are `POSSIBLE_MATCH`. Unpaired rows are `OVERTURE_ONLY` or `FSQ_ONLY`, never automatically “missing” or “incorrect.”

Cross-provider overlap is **not run** because FSQ access is unavailable.

## 13. License and provenance

This section is architecture metadata, not legal advice.

Overture Places is multi-source. Official documentation lists CDLA-Permissive-2.0 for several providers, Apache-2.0 for Foursquare-derived data and CC0-1.0 for AllThePlaces. Each normalized record retains its Overture `sources` structures, including per-source license values when present. Production persistence must not collapse this into a false single-license statement.

Foursquare Open Source Places is documented under Apache-2.0. Pro/Premium datasets are outside scope.

Human legal review is required before production persistence, export, attribution presentation or provider combination.

## 14. Proposed M5.5B persistence model

No migration was applied in M5.5A. The following is proposed for review only:

```sql
CREATE TABLE place_external_refs (
    provider              VARCHAR(30) NOT NULL,
    external_id           TEXT NOT NULL,
    place_id              UUID NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    source_release        TEXT NOT NULL,
    first_seen_at         TIMESTAMPTZ NOT NULL,
    last_seen_at          TIMESTAMPTZ NOT NULL,
    source_hash           CHAR(64) NOT NULL,
    status                VARCHAR(20) NOT NULL
                          CHECK (status IN ('ACTIVE','REMOVED','MERGED')),
    redirected_external_id TEXT,
    PRIMARY KEY (provider, external_id)
);

CREATE INDEX idx_place_external_refs_place
    ON place_external_refs(place_id, provider);

CREATE TABLE place_source_records (
    provider              VARCHAR(30) NOT NULL,
    external_id           TEXT NOT NULL,
    source_release        TEXT NOT NULL,
    observed_at           TIMESTAMPTZ NOT NULL,
    source_hash           CHAR(64) NOT NULL,
    normalized_record     JSONB NOT NULL,
    raw_provenance        JSONB NOT NULL,
    status                VARCHAR(20) NOT NULL,
    PRIMARY KEY (provider, external_id, source_release)
);

CREATE TABLE place_provider_sync_runs (
    id                    UUID PRIMARY KEY,
    provider              VARCHAR(30) NOT NULL,
    requested_release     TEXT,
    resolved_release      TEXT NOT NULL,
    snapshot_id           TEXT,
    started_at            TIMESTAMPTZ NOT NULL,
    completed_at          TIMESTAMPTZ,
    status                VARCHAR(20) NOT NULL,
    fetched_count         BIGINT NOT NULL DEFAULT 0,
    normalized_count      BIGINT NOT NULL DEFAULT 0,
    created_count         BIGINT NOT NULL DEFAULT 0,
    linked_count          BIGINT NOT NULL DEFAULT 0,
    review_count          BIGINT NOT NULL DEFAULT 0,
    failure_summary       TEXT
);
```

Before migration, names, indexes, retention and JSON payload bounds need PostgreSQL/operational review. Raw snapshots may belong in object storage with hashes and compact database indexes instead of unbounded JSONB history.

## 15. Future canonicalization and dedupe policy

Evaluation order:

1. exact `(provider, external_id)` reference;
2. verified provider crosswalk/merge redirect;
3. candidate generation by distance;
4. independently inspect normalized name, phone, website domain, address and category compatibility;
5. classify `AUTO_LINK`, `REVIEW_REQUIRED`, or `CREATE_NEW`;
6. attach/create only a Phokarta UUID.

The transparent proposal gives strongest weight to exact external ref/crosswalk, then exact phone/domain, very close distance and exact name. A score of at least 10 with at least two strong independent corroborators is required for `AUTO_LINK`; 5–9 is `REVIEW_REQUIRED`; lower is `CREATE_NEW`. Exact external ref or an approved crosswalk can auto-link. One opaque similarity number must never decide a merge.

False merges are more dangerous than duplicates. Borderline cases remain separate until reviewed.

## 16. User/community override safety

Source snapshots and canonical values are distinct:

```text
provider observation (immutable/versioned)
        +
Phokarta canonical Place value
        +
optional trusted override marker
```

M5.5B should initially support a compact override mask or per-field override table only for fields actually imported. A provider refresh may update its snapshot and proposed value, but may not overwrite a trusted Phokarta/community correction. Both values and their timestamps remain inspectable. This avoids prematurely building a giant provenance engine while preserving corrections.

Existing seed/community Places with user graph data are matched more conservatively than newly imported records. A provider row does not gain authority merely because it is newer.

## 17. Provider removal and merge

- Provider `REMOVED`: mark the external ref/source record inactive. Never hard-delete a canonical Place carrying an Experience, saved Place, Planım item, Collection item, acknowledgement, conversation or any other Phokarta-owned edge.
- Provider `MERGED A → B`: retain the Phokarta UUID. Record the redirect, link the surviving external ID only after safe matching, and preserve history.
- A purely imported Place with no Phokarta-owned graph may later be hidden/retired under approved M5.5B rules. It is not automatically hard-deleted.
- Removing a provider removes/archives its snapshots and aliases according to retention rules; it does not rewrite user-owned identities.

## 18. Future sync strategy

Each run records provider, requested/resolved release, schema/snapshot, timestamps, record counts, status and failure summary. Apply deltas idempotently after a successful checkpoint; never advance the checkpoint on partial failure.

Overture uses release changelogs plus GERS registry/bridge artifacts. FSQ deltas must be processed in the documented add → update → merge → remove order. A full periodic reconciliation remains advisable because providers can change schemas and historical correction behavior.

Scheduled production sync is not implemented.

## 19. Security and repository hygiene

- No provider token was available, printed, logged or committed.
- Provider IDs are absent from public mobile/backend contracts.
- Environment-derived table identifiers are validated before SQL interpolation.
- Query values use parameters; token SQL is in-memory and error propagation is redacted.
- CSV output protects spreadsheet formula prefixes.
- The CLI rejects benchmark output inside the Git repository and refuses a non-empty output directory.
- Large provider extracts and generated CSV/Parquet files are ignored and live outside Git.
- No beta/production database, VPS, Flyway history or mobile app was touched.

## 20. Human decision gate

Primary provider: **UNDECIDED**.
Secondary/enrichment provider: **UNDECIDED**.

Overture has measured Turkey strengths and weaknesses, but choosing it because FSQ credentials are absent would violate the benchmark design. After an FSQ token is configured, rerun the identical six circles, review the cross-provider and human samples, confirm license persistence with counsel, then ask the product owner to approve:

- primary provider;
- secondary/enrichment provider;
- final persistence DDL;
- Turkey pilot import scope.

Do not start M5.5B automatically.

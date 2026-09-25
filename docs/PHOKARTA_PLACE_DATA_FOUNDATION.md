# Phokarta Place Data Foundation

Status: Milestone 5.5B Phase A complete — waiting for physical / human review
Date: 2026-09-25
Benchmark output: `C:\Users\Emir\Documents\Phokarta_Place_Benchmark\M5_5A_Final\20260925_1514`

## 1. Decision boundary

Milestone 5.5A builds provider-neutral ingestion and measures external Place data. It does not import a provider into the production database, alter a canonical Place UUID, change a public API or mobile contract, deploy data, or begin Milestone 5.5B.

The M5.5A result is **C. COMPLETE**. Overture and Foursquare Open Source Places were queried live with benchmark lock `1.0.3`, over the same six circles and the same frozen gold set, mappings, normalization, thresholds and sample seed. M5.5B Phase A has since implemented and CI-targeted the additive schema, private importer, and Didim Core dry run, but no provider data has been imported and no beta migration has been deployed.

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
- Actual snapshot: `2325979374271449319`, timestamp `2026-09-15 20:07:45.157000`, schema marker `FSQ_OS_CURRENT`.
- Adapter target: current OS fields only, including `fsq_place_id`, name, WGS84 coordinates, address components, dates, `tel`, `website`, category IDs/labels and unresolved flags.
- Pro/Premium ratings, popularity and other paid attributes are not part of the model or benchmark.
- The adapter does not use the retired anonymous public S3 delivery.
- Monthly delta semantics are add, update, merge with redirect, and remove.

Live token authentication, catalog attachment, snapshot discovery and Places-table access succeeded. Current Portal endpoint, warehouse and table defaults are built in; non-secret overrides exist if the Portal contract changes.

The token is used only in an in-memory DuckDB secret. It is never printed, logged, written to SQL artifacts, included in reports, or committed.

## 6. Pilot geometry

Both providers use the same public WGS84 center/radius circles. Provider-defined city boundaries are not used.

| Area | Latitude | Longitude | Radius |
|---|---:|---:|---:|
| Didim | 37.3751 | 27.2678 | 12,000 m |
| Foça | 38.6703 | 26.7566 | 12,000 m |
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

## 9. Final comparative benchmark

| Area | Overture raw | Overture usable | FSQ raw | FSQ usable |
|---|---:|---:|---:|---:|
| Didim | 4,085 | 3,919 | 17,093 | 15,850 |
| Foça | 1,238 | 1,202 | 5,542 | 5,141 |
| Bodrum | 12,652 | 12,081 | 45,958 | 42,211 |
| Çeşme | 5,008 | 4,795 | 18,733 | 16,542 |
| Kadıköy | 107,667 | 100,100 | 375,865 | 334,377 |
| Antalya Kaleiçi | 22,423 | 21,114 | 98,275 | 89,941 |
| **Total** | **153,073** | **143,211** | **561,466** | **504,062** |

| Provider | Usable rate | Address | Locality | Phone | Website | Refreshed ≤365d | Query time |
|---|---:|---:|---:|---:|---:|---:|---:|
| Overture | 93.557% | 86.755% | 98.974% | 76.943% | 54.472% | 95.428% | 148.328 s |
| FSQ | 89.776% | 43.873% | 42.650% | 18.370% | 8.550% | 12.847% | 371.462 s |

Both providers had 100% name and coordinate completeness in the scoped rows. Overture v2 exposes `operating_status`, but scoped population is only 0%–0.009%; FSQ's closed-date-derived status coverage ranges from 3.926% to 8.739% by area. Freshness coverage is 100% for both, but recency differs sharply as shown above. DuckDB did not expose reliable bytes-scanned/downloaded metrics.

The area-balanced neutral-category mapping rate is 37.553% for Overture and 29.036% for FSQ; row-weighted rates are 27.542% and 23.093%. Low rates are expected because the source populations include many concepts outside the ten benchmark categories. No post-result mapping changes were made.

## 10. Known-place recall and Overture miss audit

The compact truth fixture contains 60 factual records: ten per area, across beaches, museums, historic sites, parks, viewpoints and markets. It stores only name, approximate coordinate, neutral category and public/official reference.

Matching is intentionally conservative: within 250 m, with at least 0.95 normalized-name similarity regardless of category, or at least 0.88 with a compatible mapped category. A close second within 0.03 similarity becomes ambiguous.

| Area | Overture M/Miss/Amb | Overture recall | FSQ M/Miss/Amb | FSQ recall |
|---|---:|---:|---:|---:|
| Didim | 5 / 4 / 1 | 50% | 4 / 6 / 0 | 40% |
| Foça | 5 / 5 / 0 | 50% | 4 / 6 / 0 | 40% |
| Bodrum | 3 / 7 / 0 | 30% | 5 / 5 / 0 | 50% |
| Çeşme | 3 / 7 / 0 | 30% | 3 / 7 / 0 | 30% |
| Kadıköy | 6 / 4 / 0 | 60% | 5 / 5 / 0 | 50% |
| Antalya Kaleiçi | 8 / 1 / 1 | 80% | 7 / 3 / 0 | 70% |
| **Total** | **30 / 28 / 2** | **50.000%** | **28 / 32 / 0** | **46.667%** |

The pre-FSQ audit of the original 47 Overture misses classified them as: `TRUE_PROVIDER_MISSING=1`, `MATCHER_FALSE_NEGATIVE=3`, `GOLD_FIXTURE_ISSUE=11`, `AMBIGUOUS_ENTITY=1`, `ENTITY_GRANULARITY_DIFFERENCE=11`, `CATEGORY_MAPPING_EFFECT=1`, `COORDINATE_THRESHOLD_EFFECT=6`, `NAME_VARIANT_EFFECT=12`, and `OTHER_VERIFIED_REASON=1`. Objective fixture corrections and factual aliases were frozen before FSQ was queried. No adjusted or post-hoc provider score is claimed.

## 11. Duplicate candidates and human sample

Name normalization applies Unicode NFKD, Unicode casefolding, combining-mark removal, punctuation-to-space normalization and whitespace collapse. It preserves the Turkish dotless `ı` distinction instead of applying an English-only lowercase rule. Original names are retained.

Internal duplicate candidate thresholds:

- distance at most 30 m;
- normalized-name similarity at least 0.82;
- high confidence when the normalized name is exact, or distance is at most 15 m and similarity is at least 0.94;
- every other candidate is ambiguous.

Overture produced 456 candidate pairs: 128 high-confidence and 328 ambiguous, or 0.318 pairs per 100 usable rows. FSQ produced 16,796: 7,489 high-confidence and 9,307 ambiguous, or 3.332 per 100 usable rows. These are estimates, not automatic merge lists; FSQ's substantially higher rate strengthens the case for source-record isolation and conservative canonical linking.

`review_sample.csv` uses fixed seed 5501 and samples usable rows across every available area/category stratum: 120 Overture and 132 FSQ rows. It includes name, neutral category, coordinate, address, phone, website, provider quality/confidence, and cross-provider classification. No recommendation should depend only on automated metrics.

## 12. Cross-provider matching

When both providers are present, candidates use at most 50 m plus normalized-name similarity of at least 0.80. At most 25 m, similarity at least 0.93 and compatible categories yields `HIGH_CONFIDENCE_MATCH`; other candidate pairs are `POSSIBLE_MATCH`. Unpaired rows are `OVERTURE_ONLY` or `FSQ_ONLY`, never automatically “missing” or “incorrect.”

| Area | High confidence | Possible | Overture-only | FSQ-only |
|---|---:|---:|---:|---:|
| Didim | 1,255 | 488 | 2,342 | 15,350 |
| Foça | 410 | 170 | 658 | 4,962 |
| Bodrum | 4,032 | 1,246 | 7,374 | 40,680 |
| Çeşme | 1,514 | 658 | 2,836 | 16,561 |
| Kadıköy | 30,345 | 10,645 | 66,677 | 334,875 |
| Antalya Kaleiçi | 6,861 | 2,503 | 13,059 | 88,911 |
| **Total** | **44,417** | **15,710** | **92,946** | **501,339** |

Provider-only means unmatched under the frozen conservative rule, not wrong or necessarily unique. The large provider-only populations and 60,127 matched/possible pairs demonstrate both meaningful complementarity and material canonicalization risk.

## 13. License and provenance

This section is architecture metadata, not legal advice.

Overture Places is multi-source. Official documentation lists CDLA-Permissive-2.0 for several providers, Apache-2.0 for Foursquare-derived data and CC0-1.0 for AllThePlaces. Each normalized record retains its Overture `sources` structures, including per-source license values when present. Production persistence must not collapse this into a false single-license statement.

Foursquare Open Source Places is documented under Apache-2.0. Pro/Premium datasets are outside scope.

Human legal review is required before production persistence, export, attribution presentation or provider combination.

## 14. Proposed M5.5B persistence model

No migration was applied in M5.5A. The following is proposed for review only:

Persistence review verdict: **REVISE** before implementation. The table separation is approved in principle, but M5.5B must add explicit snapshot/method identity to source rows, redirect lineage and tombstone history to external refs, source-license/provenance fields or durable object pointers with hashes, full sync-run counters/checkpoints, and field-level canonical override history with actor, reason and timestamp. Provider observations must remain independently replayable and may never overwrite a trusted canonical/community override merely because they are newer.

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

- The local provider token was detected and used only through the ignored `.env.local`/in-memory DuckDB secret path; it was not printed, logged, copied into artifacts or committed.
- Provider IDs are absent from public mobile/backend contracts.
- Environment-derived table identifiers are validated before SQL interpolation.
- Query values use parameters; token SQL is in-memory and error propagation is redacted.
- CSV output protects spreadsheet formula prefixes.
- The CLI rejects benchmark output inside the Git repository and refuses a non-empty output directory.
- Large provider extracts and generated CSV/Parquet files are ignored and live outside Git.
- No beta/production database, VPS, Flyway history or mobile app was touched.

## 20. Recommendation and human decision gate

Recommended strategy: **MULTI-SOURCE CANONICALIZATION**.

The generated optional composite ranks FSQ `0.6249` and Overture `0.4840`, primarily because FSQ's record volume dominates that diagnostic. It is not the provider-strategy decision; the generated report itself keeps raw measurements authoritative. The recommendation below weighs recall, recency, attribute completeness, duplicate risk, operations and provenance alongside coverage.

- Use Overture as the preferred canonical-attribute proposal source: it has higher gold recall (50.000% vs 46.667%), far better address/locality/phone/website completeness, much stronger one-year recency, lower duplicate risk, and simpler unauthenticated access.
- Use FSQ as a secondary coverage and enrichment source: it contributes 504,062 usable scoped rows and 501,339 conservative provider-only rows, but those rows must pass quality filtering, dedupe and false-merge-safe linking before they can influence a canonical Place.
- Never let either external ID replace a Phokarta UUID. Conflicting or weak records remain source observations or enter review.

Recommended first M5.5B pilot: **Foça, the frozen 12 km circle centered at 38.6703, 26.7566**. It is the smallest representative scope: 1,202 usable Overture rows plus 5,141 usable FSQ rows, with 580 raw high-confidence/possible overlaps. Expect approximately **5,800 canonical Place candidates** before manual exclusions and usable-only overlap reconciliation. The exact import count must be a dry-run output, not a quota.

Human approval is still required for the multi-source strategy, revised persistence DDL, legal/attribution handling, and pilot execution. Do not start M5.5B automatically.

## 21. M5.5B Phase A — Didim Core human checkpoint

The product owner selected a smaller first pilot after M5.5A. **Didim Core** is
frozen at `37.3751, 27.2678`, radius `6,000 m`; the previous Didim `12,000 m`
benchmark circle is explicitly excluded and remains future expansion scope.

### Persistence and operational boundary

Additive migration `V17__external_place_provenance.sql` introduces:

- explicit canonical Place `origin` (`MANUAL_COMMUNITY` or `EXTERNAL_IMPORT`) and
  active/retired catalog status;
- versioned provider sync runs and append-only source observations with release,
  snapshot, method, source hash, bounded provenance, and observed/retrieved time;
- external aliases with provider-ID uniqueness, active/tombstoned state, merge
  redirect lineage, plus append-only alias events;
- field-level canonical overrides preserving prior/new values, actor, reason, and
  originating source linkage.

Active-catalog filters are applied to existing Place list, search, nearby, bounds,
and aggregate queries. Provider removal retires only an imported-only Place with no
active source reference and no Phokarta-owned graph. Visits, saved Places,
Collection membership, acknowledgements, and all visit-dependent Planım,
Experience Collection, and conversation relationships protect the canonical UUID.
A merge may connect aliases only when both resolve to the same Phokarta UUID.

The importer is a disabled-by-default operational job, not a public endpoint. It
accepts only a bounded, hash-verified, `APPROVED` non-secret manifest for the exact
6 km scope and pinned provider versions. Candidate transactions are isolated and
idempotent; unresolved review and rejected rows cannot create canonical Places.
Trusted canonical overrides win over provider refreshes. Phase A does not produce
the approved Phase B manifest and has not run the importer against beta.

### Live Didim dry run

The authoritative package is
`C:\Users\Emir\Documents\Phokarta_Place_Pilot\M5_5B_Didim_DryRun\20260925_1742`.
It pins Overture release `2026-09-23.0` / schema `v2.0.0` and FSQ snapshot
`2325979374271449319` resolved at `2026-09-15 20:07:45.157000`.

| Measure | Overture | FSQ | Total |
|---|---:|---:|---:|
| Source observations | 3,799 | 15,125 | 18,924 |
| Usable observations | 3,638 | 13,992 | 17,630 |
| Rejected observations | 161 | 1,133 | 1,294 |

The 17,630 usable observations produce 16,135 canonical candidate groups:
`AUTO_LINK 0`, `REVIEW_REQUIRED 12,527`, and `CREATE_NEW 3,608`. The current beta
Place feed contains zero canonical Places in Didim Core, so existing matches and
genuine AUTO_LINK candidates are both zero. Cross-provider agreement can group
source observations but cannot manufacture a pre-existing canonical UUID.

All candidate geometries are within the frozen radius (maximum `5,999.996 m`). The
30-row physical-validation sample is geographically distributed and contains 15
review cases, 10 create-new cases, and 5 explicit conflict cases. Its
`review_decision` and `review_notes` fields are blank for human completion. Exact
source observations were preserved from the matching pinned M5.5A normalized
files after the live query; reconciliation found 18,924 expected and actual unique
hashes, with zero missing or unexpected hashes.

### Canonicalization and deferred acceptance

Matching is deterministic and staged: trusted existing external reference,
provider redirect/crosswalk, high-confidence cross-provider grouping, then
multi-signal comparison with an existing canonical Place. Distance or name alone
never authorizes a merge. Overture proposes canonical fields first and FSQ may fill
gaps; geometry is selected by provenance rather than averaged. Category conflict,
tourism nesting, same-provider duplicates, branch ambiguity, and weak evidence stay
`REVIEW_REQUIRED`. Clearly unusable observations alone are `REJECT`.

Search, Map, Place detail, Composer, and both mobile clients were audited. Their
public identity remains the Phokarta UUID, and existing empty-Place behavior needs
no Phase A source change. Real-beta search/map acceptance and import counts are
deferred until after the human checkpoint because beta has neither V17 nor imported
Didim candidates.

Rollback is separated into source-link rollback and canonical Place retirement.
Aliases and observations retain audit history; a Place that acquires user-owned
graph data is never deleted or retired automatically. No ad-hoc destructive delete
is part of the plan.

Provider/license provenance is retained, but this is not legal approval. **Human
legal review is still recommended before broader public or national rollout.**

Phase A stops at `WAITING FOR PHYSICAL / HUMAN REVIEW`. V17 is committed for test
and review only, the beta migration is not deployed, canonical beta writes are not
performed, and the 6 km → 12 km expansion is not authorized.

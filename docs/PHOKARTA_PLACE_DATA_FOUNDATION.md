# Phokarta Place Data Foundation

Status: Milestone 5.5B autonomous Didim pilot contract — pre-canary, no canonical writes
Date: 2026-09-26
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
Autonomous evidence and validation
        ↓
AUTO_LINK | AUTO_CREATE | AUTO_ENRICH | AUTO_REJECT | QUARANTINE
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

## 11. Duplicate candidates and calibration sample

Name normalization applies Unicode NFKD, Unicode casefolding, combining-mark removal, punctuation-to-space normalization and whitespace collapse. It preserves the Turkish dotless `ı` distinction instead of applying an English-only lowercase rule. Original names are retained.

Internal duplicate candidate thresholds:

- distance at most 30 m;
- normalized-name similarity at least 0.82;
- high confidence when the normalized name is exact, or distance is at most 15 m and similarity is at least 0.94;
- every other candidate is ambiguous.

Overture produced 456 candidate pairs: 128 high-confidence and 328 ambiguous, or 0.318 pairs per 100 usable rows. FSQ produced 16,796: 7,489 high-confidence and 9,307 ambiguous, or 3.332 per 100 usable rows. These are estimates, not automatic merge lists; FSQ's substantially higher rate strengthens the case for source-record isolation and conservative canonical linking.

`review_sample.csv` uses fixed seed 5501 and samples usable rows across every available area/category stratum: 120 Overture and 132 FSQ rows. It includes name, neutral category, coordinate, address, phone, website, provider quality/confidence, and cross-provider classification. Samples are calibration and audit evidence; they are not approval manifests and production processing never waits for a product owner to classify each row.

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

## 14. M5.5B persistence model lineage

No migration was applied in M5.5A. The following is the historical design sketch that
led to V17; it is retained for design lineage and is not executable migration text:

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

V17 is the authoritative migration source. Its autonomous review additionally requires
the smallest production-grade representation of pilot run identity, validation method
version, final decision state and reason, bounded evidence/blockers, canary eligibility
and quarantine/re-evaluation lifecycle. Old decisions remain append-only/auditable.
Raw snapshots may belong in object storage with hashes and compact database indexes
instead of unbounded JSONB history. V17 must pass PostgreSQL/PostGIS Testcontainers
and Flyway validation but must not be deployed before the pre-canary gate completes.

## 15. Autonomous canonicalization and dedupe policy

The operating objective is precision-first catalog growth. Missing Place coverage is
acceptable; a false canonical merge is not. Full autonomy means that the system can
make a safe decision, refuse an unsafe decision, or quarantine uncertainty without
creating a permanent row-by-row moderation job.

Evaluation order:

1. resolve an exact `(provider, external_id)` reference;
2. resolve a verified provider crosswalk or merge redirect;
3. generate nearby candidates without treating distance as identity;
4. assemble independent evidence for identity, existence, location, name, category,
   phone, website/domain, address, freshness, operating status, provider agreement,
   source quality, duplicate risk and entity-hierarchy risk;
5. detect hard blockers before considering an automatic action;
6. independently validate each proposed canonical field;
7. produce exactly one action: `AUTO_LINK`, `AUTO_CREATE`, `AUTO_ENRICH`,
   `AUTO_REJECT`, or `QUARANTINE`;
8. attach or create only a Phokarta UUID when the selected action permits it.

Every evidence item has a dimension, source, normalized value, strength and reason.
`VERY_STRONG` evidence includes an exact trusted external reference, approved
crosswalk, exact valid normalized-phone match, or exact verified-domain match.
`STRONG` evidence includes independent-provider agreement, very close coordinates
combined with a near-identical normalized name, compatible categories, or a matching
detailed address. Same category, same neighborhood, partial-name similarity, and
proximity without identity evidence are `WEAK`. Weak evidence alone can never link
or merge Places. No single unexplained numeric confidence is authoritative.

Action semantics are:

- `AUTO_LINK`: connect external evidence to an existing Place only after an exact
  trusted reference/crosswalk or multiple independent strong corroborators.
- `AUTO_CREATE`: create a new canonical identity only when existence is high,
  canonical fields are safe, no credible existing match exists, and no blocker is
  unresolved. Optional phone or website data is not required.
- `AUTO_ENRICH`: fill an absent or explicitly externally managed field on an existing
  Place. A trusted manual/community value is never overwritten merely because an
  external observation is newer.
- `AUTO_REJECT`: reject a grouped canonical candidate when deterministic evidence makes
  the candidate itself unusable. It is a candidate decision, not a source-row count;
  incomplete evidence by itself is not rejection.
- `QUARANTINE`: persist the source, evidence and explanation but create no visible
  Place. Quarantine is a successful safe outcome, not a processing failure.

`SOURCE_REJECTED` is a separate pre-grouping source-record state for deterministic
junk, invalid coordinates, unusable identity, impossible representation, or a source
duplicate already represented elsewhere. Such observations retain provenance but do
not become synthetic candidate decisions.

Hard blockers include `POSSIBLE_SUBVENUE`, `SAME_NAME_MULTIPLE_NEARBY`,
`CATEGORY_CONFLICT`, `PROVIDER_GEOMETRY_CONFLICT`,
`SAME_PROVIDER_DUPLICATE_CONFLICT`, `POSSIBLE_BRANCH_CONFUSION`,
`TOURISM_NESTING`, `UNVERIFIED_IDENTITY`, `SUSPICIOUS_WEBSITE`, and
`LARGE_COORDINATE_DISAGREEMENT`. A blocker produces `QUARANTINE` unless a stronger
deterministic rule explicitly resolves and records it. A weighted score cannot
override a blocker.

Tourism entity hierarchy is evaluated separately from duplicate identity. Hotels,
marinas, shopping centers and holiday resorts may contain restaurants, spas, beaches,
beach clubs, pools, bars, clubs or tenant businesses at the same coordinate and with
shared names, phones or domains. These relationships normally produce
`POSSIBLE_SUBVENUE` or `TOURISM_NESTING`; parent and child are not merged merely
because their evidence overlaps.

False merges are more dangerous than duplicate or missing catalog rows. Borderline
cases remain quarantined until stronger evidence arrives.

## 16. Canonical attributes and override safety

Source snapshots, field proposals and canonical values are distinct:

```text
provider observation (immutable/versioned)
        +
validated field proposal with evidence
        +
Phokarta canonical Place value and ownership
        +
optional trusted manual/community override marker
```

The reusable canonical-attribute policy validates name, website, phone, address,
coordinates and category independently. Each accepted proposal records the chosen
value, contributing source or sources, evidence strength, decision reason and raw
source linkage. Rejected raw values remain in provenance. A compact field decision
model is preferred over a speculative generic EAV system.

Website proposals require HTTP(S), a syntactically valid public hostname and sane
domain identity. The hostname's ASCII top-level label must occur in the frozen IANA
root-zone snapshot `2026092600`; undelegated/private suffixes such as `.internal`,
`.zz`, and `.kunefe`, plus the non-web infrastructure suffix `.arpa`, fail closed.
Social-handle-shaped values masquerading as URLs,
placeholder or local domains, malformed URLs, and values such as `http://@handle` are
excluded from canonical fields without being removed from source evidence. Network
reachability is not required. An exact domain is identity evidence only after the
domain itself has passed validation. Place/domain identity uses bounded hostname
labels or a complete normalized brand, never arbitrary substring containment:
`Opera Cafe` does not match `operation.com`. Canonical names exclude embedded
phone/contact text, prices or currencies, reservation/menu advertising and
excessively promotional copy.

Turkish phone normalization handles `+90`, a domestic leading `0`, whitespace and
punctuation, and compares only valid normalized numbers. Malformed or implausible
numbers are preserved as raw data but cannot corroborate identity. Turkish name
normalization remains Unicode-aware and preserves distinctions needed for deterministic
matching.

A provider refresh may update its source snapshot and field proposal, but may not
overwrite a trusted Phokarta/community correction. Both source and canonical values,
their ownership, timestamps and reasons remain inspectable. An externally managed
field may be refreshed only under its declared field policy.

Existing seed/community Places with user graph data are matched more conservatively than newly imported records. A provider row does not gain authority merely because it is newer.

## 17. Catalog lifecycle, operating state, provider removal and merge

Canonical catalog lifecycle and candidate state are separate. A canonical Place is
`ACTIVE`, `PROVISIONAL`, or `RETIRED`; a quarantined candidate is not a Place and is
never discoverable. `ACTIVE` means high-confidence canonical identity.
`PROVISIONAL` may be exposed only when existence is strong and identity is safe but
corroboration is not yet complete; it must never mean “probably junk.” `RETIRED` is
normally absent from discovery while identity and history remain stable.

Operating status is evidence independent of identity. An `OPEN`/`ACTIVE`, `CLOSED`,
`MOVED`, or `UNKNOWN` assessment records its evidence and confidence. One provider's
disappearance does not prove closure and cannot delete a Place.

- Provider `REMOVED`: mark the source/reference state inactive and trigger
  re-evaluation. Never hard-delete a canonical Place carrying an Experience, saved
  Place, Planım item, Collection item, acknowledgement, conversation or any other
  Phokarta-owned edge.
- Provider `MERGED A → B`: retain the Phokarta UUID, record redirect lineage, regroup
  sources and re-evaluate. The surviving provider ID is attached only after safe
  identity validation.
- A purely imported Place with no Phokarta-owned graph may be retired by a bounded,
  audited pilot rollback policy. It is not automatically hard-deleted.
- Removing a provider archives or removes its snapshots and aliases according to
  retention rules; it does not rewrite user-owned identities.

## 18. Sync, deterministic replay and external evidence

Each run records provider, requested/resolved release, schema/snapshot, pilot run ID,
validation method version, timestamps, record counts, action counts, status and
failure summary. Apply deltas idempotently only after a successful checkpoint; never
advance the checkpoint on partial failure.

Every autonomous decision retains its method version, input snapshot identity,
independent evidence, blockers, field proposals, final action and reason. Replaying
the same inputs under the same method is deterministic. A rule change creates a new
decision; it never silently rewrites historical evidence or every existing canonical
Place.

The bounded `re-evaluate-quarantine` operation replays persisted observations against
the current method without requiring a new provider fetch. It is triggered by a new
Overture release, new FSQ snapshot/delta, provider redirect/merge, new phone or domain,
category change, future community evidence, approved official evidence, or a change
in neighboring duplicate state. A rule update primarily handles new candidates and
quarantine; a newly discovered conflict on an imported Place is flagged for the
canonical-change policy instead of being silently applied.

Replay inputs are verified structurally and semantically. The exact hash-frozen
`didim-canonicalization-v2` package is the only historical compatibility exception;
new packages must use the current canonicalization method and their candidate groups
are rebuilt from the source observations before supplied candidate rows are accepted.
Each current source row also binds its complete replay envelope—including
`observed_at`, `retrieved_at`, `source_sequence`, provenance and license metadata—in
a deterministic integrity hash; observation time, sequence and provenance are also
recomputed where their normalized inputs permit it.
Autonomy-package artifact hashes, decision digests and quarantine subsets are checked
before re-evaluation. Candidate-ID changes caused by safe regrouping are reconciled
through unique provider-reference lineage; missing or ambiguous lineage remains
quarantined instead of being guessed or dropped. Re-evaluation persists those
synthetic missing/ambiguous decisions with complete evidence and explicit rejected
field proposals in both the quarantine ledger and canonical JSON lineage envelope,
with transition/quarantine digests and per-artifact SHA-256 hashes. It also emits a
hash-verified `candidate_evidence.csv` and compatible `autonomous_summary.json`, so
the result can be used as the prior package for a second-generation re-evaluation.

Overture uses release changelogs plus GERS registry/bridge artifacts. FSQ deltas are
processed in documented add → update → merge → remove order. Periodic full
reconciliation remains advisable because providers can change schemas and historical
correction behavior.

An `ExternalPlaceEvidenceProvider` extension may later add evidence from approved
official business, municipality, tourism-authority, public-institutional or licensed
search sources. M5.5B uses a `NOOP` implementation when no approved integration is
configured; bulk web scraping and Google Maps as a bulk canonical dataset are outside
scope. An LLM, if introduced later, may contribute evidence but may never be the sole
authority for an action. Secrets or private community data are not sent to external
models.

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

## 20. Provider strategy and operational authorization gate

Recommended strategy: **MULTI-SOURCE CANONICALIZATION**.

The generated optional composite ranks FSQ `0.6249` and Overture `0.4840`, primarily because FSQ's record volume dominates that diagnostic. It is not the provider-strategy decision; the generated report itself keeps raw measurements authoritative. The recommendation below weighs recall, recency, attribute completeness, duplicate risk, operations and provenance alongside coverage.

- Use Overture as the preferred canonical-attribute proposal source: it has higher gold recall (50.000% vs 46.667%), far better address/locality/phone/website completeness, much stronger one-year recency, lower duplicate risk, and simpler unauthenticated access.
- Use FSQ as a secondary coverage and enrichment source: it contributes 504,062 usable scoped rows and 501,339 conservative provider-only rows, but those rows must pass quality filtering, dedupe and false-merge-safe linking before they can influence a canonical Place.
- Never let either external ID replace a Phokarta UUID. Conflicting or weak records
  remain source observations or enter quarantine.

The product owner subsequently selected **Didim Core 6 km** as the first M5.5B
pilot, superseding the earlier Foça recommendation. The pipeline requires no
row-by-row approval. Because this is the first external canonical import, one
operational authorization remains immediately before Stage 1. Authorization allows
the deterministic staged system to proceed; it is not approval of individual Places.
Legal/attribution review remains a separate prerequisite for broader public or
national rollout.

## 21. M5.5B — Didim Core autonomous pre-canary checkpoint

The product owner selected a smaller first pilot after M5.5A. **Didim Core** is
frozen at `37.3751, 27.2678`, radius `6,000 m`; the previous Didim `12,000 m`
benchmark circle is explicitly excluded and remains future expansion scope.

### Persistence and operational boundary

Additive migration `V17__external_place_provenance.sql` introduces:

- explicit canonical Place `origin` (`MANUAL_COMMUNITY` or `EXTERNAL_IMPORT`) and
  provisional/active/retired catalog status;
- versioned provider sync runs and append-only source observations with release,
  snapshot, source-transformation method, source hash, bounded provenance, and
  observed/retrieved time. Source observations retain their validated historical
  method (`didim-canonicalization-v2` for the frozen corrected package, or v3 for a
  current package) separately from the decision's
  `didim-autonomous-validation-v2` method;
- external aliases with provider-ID uniqueness, active/tombstoned state, merge
  redirect lineage, plus append-only alias events;
- field-level canonical overrides preserving prior/new values, actor, reason, and
  originating source linkage.
- versioned autonomous decisions keyed to pilot run and candidate, with final action,
  reason, bounded evidence, hard blockers, canary eligibility and quarantine replay
  state.
- immutable operational-authorization lineage per pilot, expiring import claim
  leases for crash recovery, a server-derived frozen candidate/source-plan digest
  shared by every stage, exact decision/run/Place write-journal foreign keys, and
  monotonic stage/gate progression. A new authorization can supersede only the
  contained leaf failed attempt for the same stage. External-reference redirect
  mutations are serialized before deferred cycle, liveness and canonical-identity
  checks.

Checks added to the existing `places` table are installed `NOT VALID` and then
validated, avoiding a strong add-constraint lock throughout the legacy-row scan.
V17 remains one transactional Flyway migration, so its ordinary catalog indexes
cannot safely use `CONCURRENTLY`; production rehearsal and a bounded maintenance
window remain required for those index builds.

Active-catalog filters are applied to existing Place list, search, nearby, bounds,
and aggregate queries. Provider removal retires only an imported-only Place with no
active source reference and no Phokarta-owned graph. Visits, saved Places,
Collection membership, acknowledgements, and all visit-dependent Planım,
Experience Collection, and conversation relationships protect the canonical UUID.
A merge may connect aliases only when both resolve to the same Phokarta UUID.

The importer is a disabled-by-default operational job, not a public endpoint. It
accepts only a bounded, hash-verified, canary manifest for the exact 6 km scope,
pinned provider versions, pilot run ID and validation method version. Candidate
transactions are isolated and idempotent. Only `CANARY_ELIGIBLE` `AUTO_CREATE`
candidates may be created; quarantine and rejection are structurally excluded.
The importer recomputes each candidate hash without `candidate_hash` or
`selected_for_stage`, requires Python-compatible UUIDv5 identities for every source
and eligible Place, and requires/persists the exact provider-category array rather
than manufacturing an empty fallback. The source UUID also binds provider, external
ID, source transformation method and source hash.
Selected Places remain `PROVISIONAL` and therefore absent from every public catalog
query until all selected writes and the terminal `SUCCEEDED` transition commit in
one transaction. Failure recording and graph-safe containment likewise commit in
one transaction; if containment itself fails, the run remains recoverable and its
partial Places remain non-public.
The global redirect-graph lock is acquired before any selected Place row is created
or reactivated and again before terminal activation, so provider redirects cannot
race either exposure boundary.
Every successful canary import also stores a server-timed gate deadline. The
autonomous workflow renews that bounded lease immediately before post-import probes
from the complete sequential request budget: five performance requests per configured
sample plus four correctness requests per selected Place, each at the bounded timeout,
with a completion margin. A plan requiring more than the 24-hour hard ceiling fails
closed before any import; the default 65-minute lease covers the preparation interval.
An always-on startup and scheduled reconciler serializes on the same pilot/redirect
locks and records an immutable `FAILED` gate plus graph-safe containment when a
deadline expires without a result. A new `PASSED` gate at or after the deadline is
rejected by both the service and V17 trigger; an already committed timely PASS remains
idempotent.
Later stages must reproduce the Stage 1 source/candidate plan and may select only a
candidate that already has a matching immutable eligible decision. Reused source
UUIDs must match every persisted observation field, not merely provider identity.
Eligibility is recomputed from source timestamps and negative operating-state or
unresolved provider flags; a caller's `usable`/confidence labels cannot substitute
for those checks. The backend also inspects preserved Overture provenance and rejects
an otherwise eligible Overture/FSQ pair when Overture identifies Foursquare as an
upstream source, so one upstream observation cannot masquerade as two independent
providers during authorized manifest validation.
Trusted canonical overrides win over provider refreshes. No autonomous canary
manifest may run until the one pre-canary operational authorization is received.
The companion `build-canary-manifest` command is likewise authorization-gated. It
requires the exact `CONTINUE AUTONOMOUS CANARY` confirmation, revalidates and
replays the source and autonomy packages, emits every source and decision in the
backend's structured JSON envelope, assigns stable source/Place UUIDs, verifies the
exact stage rank range, and computes the canonical content hash. It excludes
quarantine from selection, refuses output above the backend's 128 MiB ceiling,
never overwrites an existing file, and performs no database or network operation.
It emits only a lowercase `.json` path, canonical UUIDs and UTC timestamps, enforces
the database's 200-character authorization-reference bound, and retains raw rejected-
source reasons in provenance only; rejected-source reasons are never converted into
candidate or backend decision reason codes.
The generator and backend hash the same compact UTF-8 JSON contract: object keys use
Unicode code-point order, arrays retain their order, and strings and finite floating
point numbers use Python `json.dumps` spelling. Cross-language digest fixtures cover
Unicode, control escapes, decimal values, exponent values and negative zero.
This checkpoint has not invoked that command and has produced no authorized manifest.
The corrected `20260926_1855_accounting_fix` replay uses reporting schema
`didim-autonomy-accounting-v1`; its accounting audit is `PASS`. It enforces both
population invariants: `18,924 source records = 17,630 usable + 1,294 SOURCE_REJECTED`
and `16,135 candidate groups = 0 AUTO_LINK + 71 AUTO_CREATE + 0 AUTO_ENRICH +
0 AUTO_REJECT + 16,064 QUARANTINE`. The superseded `20260926_170944` summary is not
importable under this contract because it appended the 1,294 pre-grouping source
rejects as synthetic candidate rejects. Its 17,429-candidate/113,434,802-byte manifest
estimate is withdrawn; no replacement manifest was measured or written because
operational authorization has not been given. The taxonomy-only eligibility population moved
from 161 to 159 when unknown non-allowlisted category siblings began failing closed;
requiring every corroborating source to pass the backend-aligned freshness gate then
reduced the intermediate eligible population to 76. The final identity-safety pass
removed five more: three hostname-only identity matches, the promotional
Yılbaşı/Munzur record, and one candidate with both hostname-identity and shared
Foursquare-lineage blockers. This measurement did not write a file, authorize an
import or start a stage.

### Live Didim dry run

The first package at
`C:\Users\Emir\Documents\Phokarta_Place_Pilot\M5_5B_Didim_DryRun\20260925_1742`
is superseded and must not be ingested. Human review found that unrestricted
substring category rules mapped `barber` to `BAR`,
`hardware_home_and_garden_store` to `NATURE`, `public_*` to `BAR`, and
parking/amusement/trailer concepts to `NATURE`. The same audit found related
collisions for restaurant equipment, supermarket, retail garden center,
cafeteria/gaming/internet café, and ski-resort concepts. Generic retail
market/marketplace/bazaar values were also removed from the `ATTRACTION` mapping;
they remain unmapped because the production Place taxonomy has no safe retail
equivalent.

The corrected authoritative package is
`C:\Users\Emir\Documents\Phokarta_Place_Pilot\M5_5B_Didim_DryRun\20260925_2145_category_fix`.
It pins Overture release `2026-09-23.0` / schema `v2.0.0` and FSQ snapshot
`2325979374271449319` resolved at `2026-09-15 20:07:45.157000`.

| Measure | Overture | FSQ | Total |
|---|---:|---:|---:|
| Source observations | 3,799 | 15,125 | 18,924 |
| Usable observations | 3,638 | 13,992 | 17,630 |
| Rejected observations | 161 | 1,133 | 1,294 |

The 17,630 usable observations produce 16,135 canonical candidate groups:
`AUTO_LINK 0`, `REVIEW_REQUIRED 13,111`, and `CREATE_NEW 3,024`. The current beta
Place feed contains zero canonical Places in Didim Core, so existing matches and
genuine AUTO_LINK candidates are both zero. Cross-provider agreement can group
source observations but cannot manufacture a pre-existing canonical UUID.

Compared with the superseded package, 584 candidates moved from `CREATE_NEW` to
`REVIEW_REQUIRED`; no candidate moved in the less conservative direction.
Production category proposals changed for 462 candidates. Website proposals
changed for 509 candidates, including 484 values removed from canonical proposals
while their raw provider values remain in source provenance. The corrected package
contains the complete row-level audit and before/after category counts.

All candidate geometries are within the frozen radius (maximum `5,999.996 m`). The
30-row physical-validation sample is geographically distributed and contains 15
historical review cases, 10 historical create-new cases, and 5 explicit conflict
cases. It is retained only for calibration, regression fixtures, decision
explanations and spot auditing. Its completion is not a prerequisite for canary or
later autonomous operation. Exact source observations were preserved from the
matching pinned M5.5A normalized files after the live query; reconciliation found
18,924 expected and actual unique hashes, with zero missing or unexpected hashes.

### Autonomous validation and deferred acceptance

Matching is deterministic and staged: trusted existing external reference,
provider redirect/crosswalk, high-confidence cross-provider grouping, then
multi-signal comparison with an existing canonical Place. Distance or name alone
never authorizes a merge. Overture proposes canonical fields first and FSQ may fill
gaps; geometry is selected by provenance rather than averaged. Production category
mapping uses only exact values, whole-token sets, and bounded provider-taxonomy
prefixes with explicit unsafe contexts. Generic token containment is not a taxonomy
rule: equipment/supply, retail-garden, beer-garden, gaming/internet-cafe and similar
contexts remain unmapped unless an explicit provider rule covers them. Every category
value on one provider record is evaluated; multiple distinct mapped Place categories
on that record are a `CATEGORY_CONFLICT`, even if another provider independently
agrees with one of them. Unknown and partially unmapped provider categories require
quarantine. Generic taxonomy parents such as `food_and_drink` are neutral only when
listed in the versioned `ignored` allowlist; they cannot turn an unknown sibling into
a mapped category and do not themselves invalidate a specific, safely mapped child.
Canonical websites require a public HTTP(S) domain and deterministic Place-name
identity evidence; parseable but identity-unverified values and social-handle-like
values are retained only in provenance. Valid website domains also participate in
entity-hierarchy detection, so a restaurant-like record on a hotel/resort domain can
be quarantined as a likely subvenue. Category conflict,
tourism nesting, same-provider duplicates, branch ambiguity, and weak evidence stay
`QUARANTINE`. Clearly unusable observations are `SOURCE_REJECTED` before grouping;
`AUTO_REJECT` remains available only as a candidate decision.

An independent row-level audit of the corrected `20260926_1855_accounting_fix` package found
18,924 unique source identities and hashes. The 1,294 unique `SOURCE_REJECTED`
identities (Overture 161; FSQ 1,133) have zero overlap with candidate source references.
All 17,630 usable identities are referenced exactly once, with no missing or unexpected
identity, by 16,135 sorted, unique candidate IDs. Candidate decisions are
`AUTO_LINK 0`, `AUTO_CREATE 71`, `AUTO_ENRICH 0`, `AUTO_REJECT 0`, and
`QUARANTINE 16,064`.

Hard-blocker occurrences over the full candidate ledger overlap by design:
`UNSUPPORTED_CATEGORY 13,263`, `UNSAFE_CANONICAL_ATTRIBUTE 12,088`,
`POSSIBLE_SUBVENUE 1,317`, `TOURISM_NESTING 1,145`, `SUSPICIOUS_WEBSITE 1,022`,
`SAME_NAME_MULTIPLE_NEARBY 737`, `SAME_PROVIDER_DUPLICATE_CONFLICT 667`,
`POSSIBLE_BRANCH_CONFUSION 506`, `UNVERIFIED_IDENTITY 413`,
`PROVIDER_GEOMETRY_CONFLICT 172`, `CATEGORY_CONFLICT 97`, and
`SOURCE_LINEAGE_DEPENDENCY 63`.

The 71 canary-eligible IDs exactly match `canary_eligible.csv`, have unique contiguous
ranks 1–71, are all blocker-free cross-provider `AUTO_CREATE` decisions, and are all
selected for Stage 1. Their category distribution is Restaurant 28, Beach 15, Cafe 15,
Bar 8, Nature 3, Nightlife 1, and Attraction 1. Accepted optional-field evidence is
present for address 47, phone 32, and website 5 candidates; these counts overlap and
require both a non-empty accepted canonical proposal and an accepted provider
observation. Geographic distribution is 28 candidates at 0–2 km, 26 at 2–4 km, and
17 at 4–6 km; quadrants are NE 8, NW 17, SE 37, and SW 9. The nearest candidate is
65.60 m from the frozen center, the farthest is 5,720.46 m, and none is outside 6 km.
The replay preserved the exact candidate ledger and decision digest
`6746d690b2a22eac98080d88b9a5867a7e3f5a8c80a9022e15723e146ab900f3` from the
superseded package, with zero changed candidate classifications and zero changed
eligible-selection ranks.

Search, Map, Place detail, Composer, and both mobile clients were audited. Their
public identity remains the Phokarta UUID, and existing empty-Place behavior needs
no mobile/runtime source change. Real-beta search/map acceptance and import counts are
deferred until after the one operational authorization because beta has neither V17
nor imported Didim candidates.

Rollback is separated into source-link rollback and canonical Place retirement.
Aliases and observations retain audit history. A Place that acquires user-owned graph
data is never hard-deleted: rollback preserves its UUID and graph edges while retiring
public catalog/source exposure through the graph-safe path. No ad-hoc destructive
delete or blind graph rewrite is part of the plan.
Rollback computes the transitive redirect dependency closure before disabling owned
aliases, so an unowned/newer `A → B → C` chain cannot be left pointing at an inactive
target. Deterministic alias events are retryable only when the complete stored payload
matches; a reused command identity with a different target or timestamp aborts the
state change.

Provider/license provenance is retained, but this is not legal approval. **Human
legal review is still recommended before broader public or national rollout.**

The autonomous replay itself reports only
`ARTIFACTS_VALIDATED_PENDING_FULL_RELEASE_GATE`; artifact generation cannot claim
canary readiness. The release gate separately requires the eligibility report,
backend/migration verification and operational checks. V17 remains test-only until
authorization and deployment work is explicitly begun; canonical beta writes are not
performed, and the 6 km → 12 km expansion is not authorized.

## 22. Autonomous canary contract

`CANARY_ELIGIBLE` is a derived property, not another decision action. A candidate is
eligible only when it is `AUTO_CREATE`, has high explainable existence confidence,
has no hard blocker, has a valid name and safe in-scope coordinate, has a compatible
or explicitly approved category, has no unresolved duplicate or subvenue ambiguity,
and retains complete provider and decision provenance. Phone and website may be null
when the remaining identity evidence is strong.

High existence confidence also requires every corroborating provider observation to
be deterministically fresh and free of negative operating-status/safety signals.
Freshness is evaluated against the latest pinned provider release date, not the
package's latest observation or the replay machine's wall clock, so identical inputs
remain reproducible. Every corroborating observation must fall within the freshness
window; one missing, stale or future observation prevents high confidence. A legacy
classification label cannot promote missing or stale evidence.
Provider names alone do not establish independence. When an Overture observation's
preserved source provenance identifies Foursquare upstream data, including the same
FSQ record, the pair receives `SOURCE_LINEAGE_DEPENDENCY` and cannot be canary
eligible. Each manifest source separately preserves its validated source
`method_version` (`didim-canonicalization-v2` for the frozen corrected package or
v3 for current packages); the manifest-level validation method remains
`didim-autonomous-validation-v2`.

The autonomous replay reports action counts and rates plus decision reasons, hard
blockers, provider composition, category distribution, evidence strength,
source-quality reasons and quarantine reasons. Safety diagnostics include
cross-provider grouping conflict, same-provider duplicate risk, category and
coordinate conflict, subvenue risk, invalid canonical websites, unmapped categories,
quarantine rate and evidence sufficiency. Precision, false-merge and false-create
metrics are reported only when a labeled ground-truth fixture supports them; the
pipeline must never manufacture a precision estimate from unlabeled provider data.

Count populations are not mixed: `source_records` counts scoped provider
observations, and `source_record_states.rejected_before_canonical_grouping` counts
`SOURCE_REJECTED` observations. `candidate_groups` counts grouped canonical candidates
after that source-quality rejection. Every candidate decision—including candidate
`AUTO_REJECT`—uses the candidate-group denominator; source rejection is reported
separately as `source_rejection_rate` over source records. Canary eligibility and stage
sizes are subsets of candidate groups. A count with no labeled ground-truth denominator
is reported as unavailable rather than inferred.

Canary order is deterministic. Candidates are ranked by a versioned safety tuple
derived from evidence strength and completeness, then selected with bounded category
and geographic-cell diversity and a stable candidate-ID tie-breaker. It is never a
random sample. The planned stages are:

1. Stage 1: exactly ranks 1 through `min(100, eligible)`;
2. Stage 2: exactly ranks 101 through `min(500, eligible)` after Stage 1 is green;
3. Stage 3: exactly ranks 501 through the final eligible rank after Stage 2 is green.

Every eligible candidate carries one positive, unique rank and the full set is
contiguous from one. A stage with no remaining ranks is invalid; operators cannot
shrink, skip, replace or hand-pick a stage.

The product owner authorizes the autonomous system once before Stage 1 with
`CONTINUE AUTONOMOUS CANARY`. No Place-by-Place decision follows. `QUARANTINE` is
excluded from every stage by both manifest generation and importer validation.

Before the first record, the disabled-by-default autonomous runner captures real,
reproducible HTTP timing and result baselines for backend health, Place search,
Didim nearby and bounds Map queries, and a stable active Place detail read. It then
imports the exact authorized manifest, retries that same manifest to prove importer
idempotency, and repeats sampled performance probes plus one Search, nearby Map,
bounds Map, and Place Detail correctness probe for every selected canonical UUID.
The gate compares the complete probed UUID set with the database-selected decision
set, so unprobed tail rows cannot pass. The
runner persists absolute before/after latency and error-rate measurements plus the
computed relative changes. A material regression stops expansion; no blended score
may hide an individual regression. Operator-supplied PASS values, zero values or
relative-change labels are not accepted as measurements.

After every stage the automated gate verifies:

- database constraints and canonical UUID uniqueness;
- external-reference uniqueness and provider-ID isolation;
- source, field and decision-provenance linkage;
- absence of quarantined or hard-blocked candidates in the batch;
- same-manifest idempotency;
- duplicate-canonical and same-coordinate diagnostics;
- Search correctness and bounded Map behavior;
- Place detail correctness, backend health and API error rate;
- search, Map and Place-detail latency against the pre-import baseline.

The database derives constraint, reference-collision, provider-isolation, UUID,
provenance, quarantine and hard-blocker results directly from the committed run.
The same autonomous process derives Search/Map/Place-detail/API correctness and
performance results from its actual HTTP observations; reporting `PASS` or zero in
an operator file cannot make an unsafe batch pass.
External-reference provenance must match its current source record's hash, release
and snapshot at both the database-write boundary and the gate. Run completion cannot
precede start; gate time cannot precede completion or be materially future-dated.

The success-capable operational entry point is only the disabled-by-default
`place-import` one-shot profile/job. It requires both that profile and
`PHOKARTA_PLACE_IMPORT_ENABLED=true`, plus the manifest path, exact SHA-256,
authorization reference and a configured loopback base URL whose port must equal
this process's actual local HTTP port and whose path must exactly equal
`server.servlet.context-path`. Public Place probes use that application endpoint.
The health endpoint is derived separately from this process's
`local.management.port`, its configured management base paths, and the same
loopback host; this supports the production split management port without allowing
an arbitrary probe target. Redirects are not followed. The web servers remain active
while these real endpoint probes run, and a passing one-shot closes its Spring
application context so the job exits cleanly. A failed gate throws after durable
containment so startup exits as a failure. Optional inputs select a stable baseline
Place, sample count and bounded request timeout. Normal API
processes never accept an import manifest, and the one-shot importer cannot report
success until its autonomous probes, anomaly audit and gate have completed. A failed
probe or invariant is durably recorded as `FAILED` with graph-safe containment; the
batch is never left active merely because orchestration threw.

The separate disabled-by-default `place-canary-gate` profile is an emergency
failure-containment path only. It accepts a run UUID, a bounded JSON diagnostic
record and optional audited checked time, but it can record only `FAILED`;
`PHOKARTA_PLACE_CANARY_GATE_REQUESTED_PASS=true` is rejected. There is no HTTP
controller and no operator-authored path to a passing gate.

Any failed gate stops later stages automatically. The affected pilot batch is
contained and the graph-safe rollback policy is applied; the operator receives a
diagnostic report rather than a request to manually approve each failed row.

## 23. Pilot rollback and catalog anomaly audit

Every canary-created Place is traceable to a pilot run ID, manifest, autonomous
decision and `EXTERNAL_IMPORT` origin. Rollback first disables its source links and
catalog exposure. If the Place has no Experience, saved state, Collection membership,
acknowledgement, conversation or other Phokarta-owned graph, the bounded pilot policy
may retire it. Once any user graph exists, automatic hard deletion is forbidden;
the UUID and historical relationships remain intact while catalog visibility and
source state receive graph-safe treatment.

A safely contained failed attempt may be reauthorized without replacing its stable
Place UUID. The successor decision and write explicitly reference the retired
predecessors, the canonical/community-owned fields and graph remain unchanged, and
only `RETIRED` or `RETIRED_GRAPH_PROTECTED` rows can be reused.
`CONTAINED_NEWER_REFERENCES` is never eligible for automatic reuse.

The deterministic catalog anomaly audit runs before expansion and after each batch.
Its compact report detects at least:

- sudden same-coordinate clusters and unusually dense marker cells;
- same-name, same-category near-duplicates;
- impossible category spikes or distributions;
- invalid or out-of-scope coordinates;
- provider-reference collisions and source orphan records;
- canonical Places without required provenance;
- one external reference assigned to multiple canonical UUIDs, or duplicate
  canonical UUID assignments in one decision set.

The audit establishes a pre-import baseline so changes can be attributed to a batch.
It is a bounded safety job, not a new observability platform.

## 24. Community Place Corrections architecture

Community corrections are future evidence inputs, not direct canonical writes. The
contract is:

```text
account-bound suggestion
        ↓
normalized proposal + retained evidence
        ↓
deterministic evidence / blocker engine
        ↓
AUTO_ACCEPT | WAIT_FOR_EVIDENCE | REJECT | QUARANTINE
        ↓
audited canonical change only when safe
```

Supported suggestion concepts are `PLACE_CLOSED`, `PLACE_MOVED`, `WRONG_LOCATION`,
`WRONG_NAME`, `WRONG_CATEGORY`, `DUPLICATE_PLACE`, `PLACE_DOES_NOT_EXIST`,
`MISSING_INFORMATION`, and `OTHER`. A future missing-Place proposal follows the same
evidence path and cannot create a Place directly.

The future persistence contract is conceptually `place_change_suggestions` with a
suggestion ID, optional Place ID, suggestion type, bounded proposed value,
account-bound submitter reference, reason, optional evidence metadata, timestamps,
status, resolution and decision method version. The final table design is deferred
until the product and privacy review; M5.5B does not add a speculative moderation
schema.

Decision behavior is intentionally asymmetric:

- `AUTO_ACCEPT` requires field-appropriate strong evidence, no blocker, and no
  protected override conflict. The resulting canonical update records old/new value,
  contributing evidence, actor class, method version and rollback linkage.
- `WAIT_FOR_EVIDENCE` keeps a plausible but insufficient proposal pending for new
  provider, official or independent community evidence.
- `REJECT` records a deterministic invalid, contradictory or abusive proposal and
  its reason without changing the Place.
- `QUARANTINE` isolates ambiguous identity, duplicate, hierarchy or coordinate cases
  for later automatic re-evaluation.

Canonical field ownership remains authoritative. A normal user cannot bypass the
evidence engine, and a provider refresh cannot erase an accepted trusted correction.
Accepted changes are reversible through append-only decision history; rollback
restores the prior value or catalog exposure without deleting the evidence trail.

### Moved and duplicate semantics

“Place moved” is not a blind coordinate edit. The recommended model preserves the
old Place and its historical Experience context, records an operating assessment of
`MOVED`, and may link `moved_to_place_id` to a newly validated canonical Place. This
requires a separate audit of Place/Experience expectations before any migration is
finalized.

“These two Places are the same” creates duplicate evidence only. A canonical merge
requires the same high-confidence autonomous identity policy as provider evidence.
If canonical redirects are introduced later, both historical UUID resolution and
all user graph edges must remain safe; bulk graph rewrites are forbidden.

### Privacy, abuse and trust

Suggestions are account-bound for rate limiting and investigation, while reports and
provider-facing diagnostics expose the minimum contributor data necessary. Evidence
metadata is bounded, retention is documented, free text is treated as untrusted, and
access to submitter identity is restricted. The public Place never exposes a
contributor's private evidence by default.

The service must anticipate spam, coordinated or malicious closure reports,
business-owner manipulation, duplicate attacks and coordinate vandalism. The future
ingress therefore supports per-account and per-Place rate limits, duplicate-submission
collapse, evidence requirements for high-impact changes, immutable audit history,
reversal and abuse investigation. Trust may later weight a contributor's historical
correction accuracy as one moderation-only signal. It cannot override hard blockers,
and there are no public mapper levels, badges, leaderboards or gamified incentives.

## 25. Future mobile correction UX contract

No Android or iOS correction UI is part of M5.5B. A later approved milestone may add
the following Place Detail overflow entry:

| English | Turkish |
|---|---|
| Suggest an edit | Düzenleme öner |
| Place is permanently closed | Mekan kalıcı olarak kapandı |
| Place moved | Mekan taşındı |
| Wrong location | Konum yanlış |
| Wrong name | İsim yanlış |
| Wrong category | Kategori yanlış |
| Duplicate Place | Bu mekan başka bir mekanla aynı |
| Place does not exist | Bu mekan burada yok |
| Missing information | Eksik bilgi |
| Other | Diğer |

Search and Map may later add `Add missing Place / Eksik mekan ekle`. Submission copy
must make clear that a suggestion is evidence awaiting automated validation, not an
immediate edit.

## 26. Current milestone boundary

M5.5B ends at the autonomous Didim 6 km canary and its acceptance checks. It does
not deploy V17 or write beta Places before authorization, does not bulk scrape web
sources, does not implement mobile community-edit UI, and does not expand to Didim
12 km. After a successful pilot, the next decision is separately authorized: expand
6 km → 12 km, tune rules, or implement Community Place Corrections.

## 27. Bounded post-PASS rollback-only operational entry point

The product owner authorized this safety repair separately from beta execution.
The exact current canary selection remains 71 under
`didim-autonomous-validation-v2`; no threshold, blocker, source grouping, quarantine
decision, ranking or scope changes are part of the repair. The approved checkpoint
and sealed Stage 1 manifest remain immutable.

`PlacePilotRollbackApplication` is a private, disabled-by-default one-shot command,
not a REST endpoint. It uses an isolated non-web Spring context containing only
the JDBC transaction infrastructure, graph-protection repository, existing rollback
domain service and operational inspection/audit adapter. It cannot start migrations,
API controllers, background workers or gate reconciliation. A startup guard refuses
to load the rollback profile into the ordinary API application or permit unsafe
web/migration configuration.

Both modes require one canonical run UUID and its exact expected internal manifest
SHA-256. Inspection defaults to dry-run and uses a read-only repeatable-read
transaction; execution additionally requires explicit mutually consistent mode
flags and a bounded reason code. The command does not accept SQL predicates,
geographic/provider wildcards or an all-imports option. An incompatible migration
level, unknown/mismatched run, unsafe provenance or cross-run retirement plan is
rejected before any mutation. Inspection and execution share the domain service's
write-selection, reference-preservation and graph-protection rules.

Execution delegates only to `PlacePilotRollbackService.retireRun`. It retires
catalog exposure and run-owned provider references without deleting the canonical
UUID, raw observations, decisions or user graph. Graph-protected Places remain
physically present with their Experience/Visit, Saved/Want-to-Go, Collection and
acknowledgement relationships intact. Pre-existing linked/enriched canonical rows
are never treated as pilot-created catalog writes. Newer/redirect-dependent
references retain the domain service's existing protection.

V17 is still undeployed and gains one small append-only operational-event table.
Separate requested, started and completed events record later containment; the
original `PASSED` gate and completed import identity stay immutable. Events and
retirement are atomic and idempotent. A second execution cannot create duplicate
audit/alias rollback events or cause secondary loss.

See [the private containment command](OPERATIONS_RUNBOOK.md#private-place-pilot-containment-after-a-passing-gate)
for dry-run/execution syntax. The implementation gate requires the full backend
suite, PostgreSQL/PostGIS Testcontainers, V17 in tests, dry-run zero-mutation and
mutation scenarios, production image and no-public-endpoint audit. After that gate,
stop again: no beta backup, V17 deployment, canonical beta write or Stage 1 execution
is authorized by this repair request. SSH access and a fresh explicit continuation
are still required.

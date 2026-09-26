# Phokarta Place ingestion and canonicalization tooling

This isolated Python tool benchmarks external Place providers without starting the
Spring backend, changing the production database, or exposing provider IDs through
mobile contracts. External identifiers remain source aliases; Phokarta's Place UUID
is always canonical.

## Requirements

- Python 3.11–3.13 recommended (the pinned DuckDB release may build from source on newer Python versions)
- Dependencies in `requirements.lock`
- Network access for live provider runs
- For FSQ only: a Places Portal token and the connection values copied from the
  Portal's current DuckDB/Iceberg snippet

Install into a virtual environment:

```powershell
python -m pip install -r requirements.lock
```

Run deterministic offline tests:

```powershell
$env:PYTHONPATH = "src"
python -m unittest discover -s tests -v
```

Run the Overture benchmark. `latest` is resolved through Overture's STAC catalog
and the resolved release and schema version are recorded in `provider_metadata.json`.

```powershell
$env:PYTHONPATH = "src"
python -m phokarta_place_ingestion benchmark `
  --providers overture `
  --release latest `
  --output "C:\Users\Emir\Documents\Phokarta_Place_Benchmark\M5_5A\<timestamp>"
```

## Foursquare Open Source Places access

1. Open the official [Foursquare Places Portal documentation](https://docs.foursquare.com/data-products/docs/access-fsq-os-places).
2. Obtain access to Open Source Places and create a programmatic Places Portal token.
3. Do not paste the token into source code, chat, logs, screenshots, or committed files.
4. Open `tools/place-ingestion/.env.local`.
5. Set `FSQ_PLACES_TOKEN=<your token>` and save the file.
6. Return to the same Codex task and say `CONTINUE`.

The repository contains a safe `.env.example`; `.env.local` and local variants are
ignored by Git. The loader uses this precedence without overwriting process state:

1. explicit `FSQ_PLACES_TOKEN` process environment variable;
2. `tools/place-ingestion/.env.local`;
3. missing credential.

Check presence safely (the command reports only `YES` or `NO`):

```powershell
$env:PYTHONPATH = "src"
python -m phokarta_place_ingestion doctor
```

The adapter defaults to the current Portal connection (`places` warehouse,
`places.datasets.places_os` table, and Foursquare Iceberg endpoint). If the Portal
snippet changes, override `FSQ_ICEBERG_CATALOG_URI`, `FSQ_ICEBERG_WAREHOUSE`, and
`FSQ_PLACES_TABLE` with its new non-secret values.

Then request both providers:

```powershell
python -m phokarta_place_ingestion benchmark --providers overture,fsq --output <path>
```

The token is read only for the in-memory connection secret. It is never printed,
logged, included in benchmark artifacts, or returned in errors. FSQ failures expose
only a safe category such as `UNAUTHORIZED`, `FORBIDDEN`, `CATALOG_UNAVAILABLE`,
`DATASET_NOT_GRANTED`, or `NETWORK_FAILURE`. The adapter does not fall back to the
retired anonymous S3 dataset.

## Reproducibility and scope

- Pilot regions are fixed center/radius circles in `config/pilot_areas.json`.
- The versioned configuration contract is documented in
  `config/config.schema.json` (JSON Schema 2020-12).
- Provider taxonomies map into neutral benchmark concepts using
  `config/category_mappings.json`; uncertain values stay `UNMAPPED`.
- A fixed seed controls the human-review sample.
- `config/benchmark_lock.json` cryptographically freezes all fair-comparison inputs.
- Provider-supplied common, translated, official, alternate, and short names are
  evaluated without changing the conservative distance or similarity thresholds.
- `RAW` means every fetched row inside the exact circle.
- `USABLE` requires a valid coordinate, non-empty name, no reliable closed signal,
  and no documented severe provider-quality exclusion.
- Duplicate candidates use 30 m proximity and Unicode-safe normalized-name
  similarity. They are candidates, not automatic merges.
- Live extracts and generated reports belong outside Git. Only code, small fixtures,
  configuration, and architecture documentation are tracked.

## Commands

```text
benchmark       fetch, normalize, measure, sample, and write reports
validate-config validate pilot, registry, mapping, and gold-set fixtures
doctor          report only whether an FSQ credential is configured
```

`fetch_delta()` is an explicit provider extension point. M5.5A records release and
delta capabilities but does not schedule or apply production synchronization.

## M5.5B Didim Core dry run

The first canonicalization pilot is frozen to **Didim Core**, centered at
`37.3751, 27.2678` with a `6,000 m` radius. The earlier `12,000 m` benchmark
circle is future expansion scope and is deliberately excluded. Production
category proposals map into the existing `PlaceCategory` enum through
`config/production_category_mappings.json`; the benchmark taxonomy is not reused
as a production taxonomy. Production mappings are limited to exact values,
whole-token sets, and bounded provider-taxonomy prefixes. Raw substring matching
is forbidden, so concepts such as `barber`, `public_plaza`, `parking`, and
`hardware_home_and_garden_store` cannot inherit categories from embedded words.
Unknown categories stay unmapped and require review. A provider row that contains
one known category plus an unknown sibling also fails closed. Generic taxonomy
parents such as `food_and_drink` are neutral only when they appear in the explicit
`ignored` allowlist, so they do not conceal unknown provider meanings.

Canonical website proposals must use a public HTTP(S) host and have a meaningful
identity token in common with the provider Place name. Bare/social-handle-like,
social-profile, malformed, and identity-unverified values remain in source
provenance but are excluded from the canonical proposal and require review.
Public-host validation is offline and deterministic: the ASCII top-level label must
be present in the bundled IANA root-zone snapshot `2026092600`; undelegated/private
suffixes and the non-web infrastructure suffix `.arpa` fail closed without requiring
DNS or network reachability.
Identity uses exact bounded hostname labels or a complete normalized brand, never
arbitrary substring containment (`Opera Cafe` cannot validate `operation.com`).
Canonical names reject embedded phone/contact text, prices or currencies,
reservation/menu advertising, and excessively promotional copy.

Run the read-only planner against both pinned providers and the current canonical
Place feed:

```powershell
$env:PYTHONPATH = "src"
python -m phokarta_place_ingestion didim-dry-run `
  --release latest `
  --output "C:\Users\Emir\Documents\Phokarta_Place_Pilot\M5_5B_Didim_DryRun\<timestamp>"
```

To reproduce the complete plan from the exact preserved observations, provider
versions, source hashes, and original retrieval metadata in an earlier package:

```powershell
python -m phokarta_place_ingestion didim-dry-run `
  --source-package <prior-dry-run-package> `
  --output <new-empty-output-directory>
```

The replay verifies every normalized source hash, the complete replay-envelope
hash (including observation/retrieval time, source sequence and provenance),
provider count, candidate identity set, and the 6 km geometry. It also writes
before/after category counts and a row-level candidate-change audit.
Overture observations derived from Foursquare provenance are dependent lineage, not
a second independent provider observation, and therefore remain quarantined.

The planner performs no database writes. It retains source observations, produces
one decision per candidate (`AUTO_LINK`, `REVIEW_REQUIRED`, or `CREATE_NEW`),
records unusable inputs separately as `REJECT`, and emits CSV, JSON, and GeoJSON
evidence outside Git. Overture is the preferred proposal source; FSQ fills missing
fields. Coordinates are never averaged, external IDs remain aliases, and ambiguous
or conflicting records require review.

To rebuild only the deterministic field-review sample without re-querying a
provider:

```powershell
python -m phokarta_place_ingestion rebuild-didim-review-sample --package <dry-run-package>
```

If an exact pinned M5.5A package must be used to recover source observations, the
tool verifies release/snapshot compatibility and reconciles source hashes:

```powershell
python -m phokarta_place_ingestion restore-didim-source-records `
  --package <dry-run-package> `
  --benchmark <matching-m5.5a-package>
```

The backend contains a disabled-by-default, private one-shot import job for an
operationally authorized autonomous-canary manifest. It has no controller
and requires the `place-import` Spring profile,
`PHOKARTA_PLACE_IMPORT_ENABLED=true`, the exact out-of-band manifest SHA-256, and
the matching authorization reference; normal API processes cannot invoke it.
Run it only after the explicit `CONTINUE AUTONOMOUS CANARY` authorization with
its web server active, a loopback base URL bound to that process's actual local
HTTP port, and an immutable manifest path. Do not use
`--spring.main.web-application-type=none`: the autonomous gate measures the real
Search, Map, Place Detail, and health endpoints. This checkpoint has generated no
authorized import manifest, deployed no migration, and written no canonical Places.

Additional commands:

```text
didim-dry-run                 fetch and build the read-only Didim Core plan
rebuild-didim-review-sample   rebuild the deterministic physical-review sample
restore-didim-source-records  recover hash-matched observations from M5.5A
didim-autonomous              replay the corrected package through autonomous validation
re-evaluate-quarantine        replay quarantined candidates under current validated rules
build-canary-manifest         create the backend JSON envelope after explicit authorization
```

## M5.5B autonomous validation replay

The first autonomous run accepts only the corrected
`20260925_2145_category_fix` package: the exact hash-frozen historical
canonicalization v2 output, pinned Overture/FSQ releases and snapshot, exact source
counts, and exact source/candidate hashes. It rejects the superseded 1742 package.
New replay packages use the current canonicalization method and must reproduce their
candidate grouping semantically from source observations. Output must be a new
directory outside Git. The command does not deploy V17, write a canonical Place, or
start a canary. The 30-row sample becomes calibration/audit output only and has no
completion columns or approval role.

```powershell
$env:PYTHONPATH = "src"
python -m phokarta_place_ingestion didim-autonomous `
  --source-package "<validated-source-package>" `
  --output "<new-empty-directory-outside-git>"
```

Stage 1 is not operator-sized: it is exactly the first `min(100, eligible)` rows
of one full, contiguous deterministic ranking. Stage 2 selects ranks 101–500 and
Stage 3 selects the remainder from that same frozen plan.

Operational correctness probes cover every selected Place through Search, nearby
Map, bounds Map, and Place Detail. Performance timings remain reproducibly sampled;
the immutable gate also compares the exact probed UUID set with the selected database
decisions before a stage can pass.

Autonomy counts use explicit populations. `source_records` is every scoped provider
observation; `SOURCE_REJECTED` is a pre-grouping source-record state, reported through
`source_record_states` and `source_rejection_rate / source_records`. `candidate_groups`
contains only canonical candidate groups after those unusable observations are removed.
`AUTO_LINK`, `AUTO_CREATE`, `AUTO_ENRICH`, `AUTO_REJECT`, and `QUARANTINE` are mutually
exclusive candidate decisions, and their counts/rates use candidate groups as the
denominator. `canary_eligible` and every stage count are subsets of candidate groups,
never source rows. The package writes and hashes the full candidate evidence ledger,
candidate-only action files (including header-only files for zero-row actions), and a
separate `source_rejected.csv` provenance ledger.

Quarantine re-evaluation is a separate, bounded replay. It verifies the prior
autonomy package's artifact hashes, decision digest and quarantine subset, then
re-evaluates those records against a validated current source package and current
versioned rules. Provider-reference lineage resolves deterministic regrouping; a
missing or ambiguous mapping stays quarantined. The operation writes a deterministic
prior-to-current transition audit, a JSON lineage envelope, preserved synthetic
quarantine decisions for missing/ambiguous lineage, and SHA-256 hashes for every
output. Its full current evidence and field-proposal ledger plus compatible
`autonomous_summary.json` make the result directly chainable into a later bounded
re-evaluation without discarding unresolved lineage.
It performs no catalog write or deploy.

```powershell
python -m phokarta_place_ingestion re-evaluate-quarantine `
  --source-package <corrected-2145-package> `
  --prior-autonomy-package <prior-autonomy-package> `
  --output <new-empty-directory-outside-git>
```

After—never before—the explicit operational authorization, build the importer's
structured JSON envelope with the exact confirmation phrase. The generator
revalidates both packages, recomputes every autonomous decision and rank, excludes
quarantine from selection, assigns stable source/Place UUIDs, enforces the exact
stage range, preserves each validated source's own canonicalization method (`v2` for
the frozen correction package or `v3` for current replay packages) separately from
the autonomous decision method, computes the backend-compatible canonical JSON hash, refuses files
over 128 MiB, and never contacts the database:

```powershell
python -m phokarta_place_ingestion build-canary-manifest `
  --source-package <validated-source-package> `
  --autonomy-package <validated-autonomy-package> `
  --output <new-manifest.json> `
  --stage STAGE_1 `
  --run-id <uuid> `
  --pilot-run-key <stable-pilot-key> `
  --authorization-reference <out-of-band-reference> `
  --authorization-confirmation "CONTINUE AUTONOMOUS CANARY"
```

The output path must not already exist. Merely having this command available is
not authorization; without the exact confirmation it fails before loading either
package and writes nothing. The output suffix is exactly lowercase `.json`; run IDs
and timestamps are emitted in canonical backend-compatible form, authorization
references are bounded to 200 characters, and raw source-rejection details remain in
source provenance only; they are never converted into candidate or backend decision
reason codes. A row-level audit of the superseded `20260926_170944` autonomy package
found 18,924 unique source identities:
1,294 are `SOURCE_REJECTED` before grouping and are absent from candidate decisions;
the other 17,630 are each referenced exactly once by 16,135 unique candidate decisions.
Candidate actions are `AUTO_LINK 0`, `AUTO_CREATE 71`, `AUTO_ENRICH 0`,
`AUTO_REJECT 0`, and `QUARANTINE 16,064`. All 71 eligible candidates are unique,
blocker-free, cross-provider `AUTO_CREATE` decisions and occupy the complete Stage 1
rank range 1–71. Their accepted optional-field evidence counts are address 47, phone
32, and website 5; counts overlap. That package's summary is not importable under the
current accounting contract because it conflated the 1,294 source rejects with
candidate `AUTO_REJECT` decisions.

The corrected replay is `20260926_1855_accounting_fix`, reporting schema
`didim-autonomy-accounting-v1`. Its accounting audit is `PASS` and enforces
`18,924 = 17,630 usable + 1,294 SOURCE_REJECTED` and
`16,135 candidate groups = 0 + 71 + 0 + 0 + 16,064` mutually exclusive decisions.
Independent replay found zero changed classifications and zero changed eligible ranks;
the decision digest remains
`6746d690b2a22eac98080d88b9a5867a7e3f5a8c80a9022e15723e146ab900f3`.
The previous 17,429-candidate/113,434,802-byte manifest estimate is withdrawn, and no
replacement manifest was measured or written because operational authorization has not
been given. The preceding freshness-only result was 76; bounded
hostname identity, promotional-name rejection, and shared-source-lineage rejection
removed five candidates: three hostname-only
identity matches, the promotional Yılbaşı/Munzur record, and one record blocked by
both hostname identity and shared Foursquare lineage. The taxonomy-only eligibility pass changed from
161 to 159 after unknown sibling categories began failing closed, and the
backend-aligned requirement that every corroborating source be fresh reduced the
intermediate eligible population to 76.
Successful `didim-autonomous` output is labeled
`ARTIFACTS_VALIDATED_PENDING_FULL_RELEASE_GATE`; it does not claim canary readiness
before the backend, migration, and operational release gates complete.

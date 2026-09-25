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
Unknown categories stay unmapped and require review.

Canonical website proposals must use a public HTTP(S) host and have a meaningful
identity token in common with the provider Place name. Bare/social-handle-like,
social-profile, malformed, and identity-unverified values remain in source
provenance but are excluded from the canonical proposal and require review.

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

The replay verifies every normalized source hash, provider count, candidate
identity set, and the 6 km geometry. It also writes before/after category counts
and a row-level candidate-change audit.

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

The backend contains a disabled-by-default, private one-shot import job for a
future human-approved manifest. It has no controller and requires both the
`place-import` Spring profile and `PHOKARTA_PLACE_IMPORT_ENABLED=true`; normal API
processes cannot invoke it. Run it only in Phase B with
`--spring.main.web-application-type=none` and an immutable manifest path. The
current Phase A checkpoint does not generate an approved manifest, deploy V17, or
write canonical Places.

Additional commands:

```text
didim-dry-run                 fetch and build the read-only Didim Core plan
rebuild-didim-review-sample   rebuild the deterministic physical-review sample
restore-didim-source-records  recover hash-matched observations from M5.5A
```

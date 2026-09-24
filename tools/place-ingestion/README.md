# Phokarta Place ingestion benchmark

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

For an FSQ run, create a Places Portal account and generate an access token. The
minimal required configuration is:

```powershell
$env:FSQ_PLACES_TOKEN = "<token>"
```

The adapter defaults to the current Portal connection (`places` warehouse,
`places.datasets.places_os` table, and Foursquare Iceberg endpoint). If the Portal
snippet changes, override `FSQ_ICEBERG_CATALOG_URI`, `FSQ_ICEBERG_WAREHOUSE`, and
`FSQ_PLACES_TABLE` with its new non-secret values.

Then request both providers:

```powershell
python -m phokarta_place_ingestion benchmark --providers overture,fsq --output <path>
```

The token is read only at connection time. It is never printed, logged, included
in SQL text, or written to benchmark artifacts. FSQ fails closed if any required
Portal connection setting is missing. The adapter does not fall back to the retired
anonymous S3 dataset.

## Reproducibility and scope

- Pilot regions are fixed center/radius circles in `config/pilot_areas.json`.
- The versioned configuration contract is documented in
  `config/config.schema.json` (JSON Schema 2020-12).
- Provider taxonomies map into neutral benchmark concepts using
  `config/category_mappings.json`; uncertain values stay `UNMAPPED`.
- A fixed seed controls the human-review sample.
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
```

`fetch_delta()` is an explicit provider extension point. M5.5A records release and
delta capabilities but does not schedule or apply production synchronization.

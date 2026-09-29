# Opt-in Place Detail diagnostics

This is diagnostic instrumentation, not a performance gate or a repair for the
historical 5-second client timeout. It is **disabled by default**. A future
deployment requires separate product-owner authorization. This change does not
raise that timeout, run an importer, or change Place data/query semantics.

Configuration:

```text
PHOKARTA_PLACE_DETAIL_OBSERVABILITY_ENABLED=false
PHOKARTA_PLACE_DETAIL_SLOW_THRESHOLD_MS=350
```

Only an exact `GET /api/v1/places/{uuid}` path is traced. The UUID and existing
validated `X-Request-Id` bind the trace; neither arbitrary URLs nor headers are
stored. Other endpoints and disabled Detail requests allocate no trace. When
enabled, every traced Detail request records one bounded-cardinality Micrometer
timer (`phokarta.place.detail.duration`, outcome success/client_error/
server_error/exception). Requests slower than the emission threshold produce
one structured `slow place detail` record. Exceptions and HTTP 5xx emit a
sanitized record even below the ordinary successful-request threshold. The
threshold has no effect on HTTP status, timeout, returned DTO, or acceptance
criteria.

## Event and timing contract

All offsets use `System.nanoTime` relative to `REQUEST_FILTER_ENTER`:

| Event pair or marker | Meaning |
|---|---|
| `REQUEST_FILTER_ENTER` / `REQUEST_FILTER_EXIT` | Outer existing request-ID filter duration, excluding its existing completion-log write. |
| `SECURITY_CHAIN_COMPLETE` | MVC `preHandle` handoff, after normal security authorization has allowed the dispatcher; not a separate security filter exit timer. |
| `CONTROLLER_ENTER` / `CONTROLLER_EXIT` | Inclusive controller method body. |
| `SERVICE_ENTER` / `SERVICE_EXIT` | Inclusive read-only Detail service body; nested within controller. |
| `PLACE_REPOSITORY_START/END` | Active Place lookup; includes Spring Data/ORM work. |
| `RATING_AGGREGATE_START/END` | PUBLIC Visit score aggregate repository method. |
| `DIMENSION_AGGREGATE_START/END` | PUBLIC dimension aggregate repository method. |
| `RECENT_VISITS_START/END` | Privacy-filtered recent Visit repository method. |
| `DTO_MAPPING_START/END` | Two disjoint mapping segments; their durations are summed. |
| `SERIALIZATION_START/END` | MVC response-body advice to interceptor completion, a converter/dispatcher **window**, not exact Jackson-only CPU time. An exceptional path can end at `afterCompletion` or outer filter fallback. |
| `RESPONSE_COMMIT` | Emitted only when `HttpServletResponse.isCommitted()` is true at filter exit. It cannot prove client receipt. |

`phase_events` includes every observed named event, in order. Missing phases on
exceptional paths remain missing rather than receiving invented durations.
Controller/service durations are *inclusive context*. Repository, DTO mapping
and serialization-window intervals are leaf measurements. Their union is
`measured_nonoverlapping_ms`, and
`unattributed_ms = total_ms - measured_nonoverlapping_ms` (nonnegative).
`leaf_overlap_ms` exposes any accidental overlapping leaves; their durations
are never double-counted. `remaining_framework_ms` is a separate, overlapping
explanatory dimension (`total - controller - serialization window`), not an
additional additive component. Unattributed time must not be labelled SQL, GC,
JIT, network or framework time without separate evidence.

## Structured slow record

One `place_detail_observation` structured value contains only:

- `request_id` (validated UUID), `canonical_place_id` (UUID), fixed
  `route_template`, numeric `status` and `total_ms`;
- `phase_model`, `phase_events` (name and offset only), `phases` (numeric
  durations), and four fixed `row_counts` labels for completed operations;
- `serialization_window_observed`, `response_committed` (servlet snapshot),
  `response_delivery_confirmed=false`, numeric/boolean failure indicators,
  `completed_at`, and `diagnostic_threshold_ms`;
- bounded numeric `jvm` context: uptime, heap used, live threads, cumulative
  GC count/time, and process CPU load when supported;
- bounded numeric `pool` context (active/idle/pending/max) when the existing
  datasource exposes Hikari MXBean.

The repository intervals are **not** SQL execution timings. They can include
connection acquisition, Spring proxy work, Hibernate compilation, JDBC,
result retrieval and entity materialization. No separate connection acquisition,
JDBC statement duration, or materialization timer is claimed. Row counts are
returned Java result sizes, not PostgreSQL row-visit counts. GC values are
cumulative snapshots, not per-request pause attribution.

No request/response body, review, user name, email, password, private memory,
authorization/cookie/token, environment value, SQL text/bind value, exception
message or database credential is inspected for this record. `IOException`
is reported generically; only the known Tomcat `ClientAbortException` type
sets the more specific client-abort/write-failure indicators. Neither servlet
commit nor an exception type proves successful remote delivery.

The instrumentation is fail-open for product behavior: timing/log/metric
failure cannot replace the existing response or exception. The diagnostic
record is intentionally opt-in and requires separate approval before beta
activation. This document does not authorize deployment or calibration.

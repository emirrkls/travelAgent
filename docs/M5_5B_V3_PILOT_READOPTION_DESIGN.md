# M5.5B — V3 Pilot & Re-Adoption Design

## Scope and authority

This document implements the owner's pilot-policy decision, not permission to execute it.
Source/design/tests only. No new operational run UUID, final sealed manifest, beta deployment,
reactivation, import, rollback, schema change or calibration run is created by this task.

The historical run `d70adea5-6e3f-4c32-92c0-49695eeeb9ce` remains permanently
SUCCEEDED at the import layer, FAILED at the v2 gate, and contained: 71 RETIRED Places,
142 INACTIVE refs, immutable source observations/provenance, and 16,064 unchanged QUARANTINE
candidates. These are the owner's authoritative current-state facts, not a fresh live audit.
Current beta is `06c3bbf7d5d7076cb6cdefdeddf6b16d2aa2f418`, V17 SUCCESS.

## V3 performance policy

`didim-autonomous-validation-v3` is limited to re-adopting the complete existing 71 eligible
Places within the frozen Didim Core 6 km scope. No Stage 2, replacement candidates, thresholds,
taxonomy, matching, source snapshots, quarantine promotions or scope changes.

Hard failures remain independent and fail closed:

- Any five-second HTTP deadline breach (including preconditioning), 5xx or invalid semantics.
- Wrong canonical identity, public/mobile provider-ID leakage, unbounded Map results.
- Backend/database unhealthy, unexpected process/identity/restart change or OOM.
- Source accounting, provenance, UUID/ref uniqueness, idempotency or critical anomaly failure.
- QUARANTINE or hard-blocker exposure, graph-safety or rollback-safety failure.
- Any functional/product acceptance failure or missing mandatory evidence.

Relative latency is **ADVISORY_ONLY**, never latency PASS and never a containment predicate.
`PERFORMANCE_ADVISORY` has three descriptive values:

- NORMAL: comparable sentinel identities; neither median nor p90 increased on any surface.
- DEGRADED: comparable identities; at least one median or p90 increased, however slightly.
- INCOMPARABLE: sentinel result identity/cardinality changed across or within snapshots.

DEGRADED is deliberately sensitive descriptive telemetry, not a statistical claim or material
regression threshold. INCOMPARABLE does not fabricate equivalence and requires owner review;
it alone is not automatic containment. Missing/invalid evidence, wrong sentinel UUID, unhealthy
target, or changed process/path is a separate hard failure, not an advisory escape hatch.
Historical v2 retains its original hard relative gate. Gate policy is selected from the persisted
run's method version; a caller-supplied advisory flag cannot change a v2 run's policy.

## Persistent telemetry contract

The target is the already-running, long-lived persistent beta backend, never a one-shot Spring
process. Use the same private Docker/internal operational health route pre/post; this bypasses
public Caddy/TLS and must be labelled explicitly. Caddy health remains a separate hard preflight
and final-health check. A public-route option must be separately frozen; paths must not be mixed.

Frozen stable manual sentinel: Staging Harbor Cafe,
`aa000000-0000-4000-8000-000000000001`, Istanbul `(41.022, 28.9784)`.

| Surface | Exact GET path |
| --- | --- |
| Search | `/api/v1/places?search=Staging+Harbor+Cafe&page=0&size=100&sort=name%2Casc` |
| Nearby | `/api/v1/places/nearby?lat=41.0220000&lon=28.9784000&radiusMeters=250.0&limit=200` |
| Bounds | `/api/v1/places/bounds?west=28.9684000&south=41.0120000&east=28.9884000&north=41.0320000&limit=200` |
| Detail | `/api/v1/places/aa000000-0000-4000-8000-000000000001` |

N=20 is a fixed small descriptive collection, not renewed calibration or a new noise/detection
study. Sequence: Search → Nearby → Bounds → Detail, one explicit preconditioning GET then
20 timed GETs per surface. Exactly 84 requests per snapshot, 168 pre/post; no arbitrary sleep,
retry or discarded first failure. Preconditioning is recorded, excluded only from statistics.
Median is the average of ordered observations 10/11; p90 is nearest-rank observation 18/20,
not max. Keep the existing five-second deadline on every GET and Detail observability at 350 ms.

Baseline completes immediately before mutation, no more than 15 minutes before run start.
After telemetry starts after successful import completion. The gate verifies this temporal
bracket, new run/hash binding and the same container/image/Java identity/start/path/health.
All 168 individual records are validated before a private typed evidence receipt can be minted.
Gate diagnostics store recomputed summaries and hashes, not raw bodies or source/user data.

Each sanitized `persistent-pilot-v3-v1` snapshot contains run/hash/role, start/end, target identity
and health, and the individual requests. Every request contains UTC start, surface, exact path,
preconditioning flag, monotonic `latency_ms`, HTTP status, semantic validation, response bytes,
result count, canonical ID list and SHA-256 digest of sorted UUIDs joined with newline. The
producer must validate actual HTTP body semantics (UUID/name/category/coordinates/bounds),
then discard bodies. Counts/bytes are explanatory dimensions, never milliseconds/result.
Target metadata is allow-listed and requires LONG_LIVED_PERSISTENT plus enabled 350 ms Detail
observability. The operational adapter must confirm this identity against the actual backend;
a JSON assertion alone is not evidence that a different application is the approved target.

Report median/p90/count/median bytes/ID digest and relative delta separately for all four
surfaces. Payload byte changes do not fabricate result equivalence. Result-set drift within a
block also makes it incomparable. Search is limited to 100, Maps to 200, Detail to the sentinel.

The external probe is GET-only: no datasource, Flyway, importer, rollback, canary state or write
credentials. The implementation closure adds a private plain-Java launcher and a filesystem IPC
adapter to the disabled-by-default operational runner. There is no public endpoint. The separate
read-only Docker coordinator pins the actual persistent container network namespace, image,
Java PID/start ticks, health and instrumentation before and after each snapshot. Actual management
GETs independently check readiness/liveness and JVM start time. The ordinary one-shot runner
refuses v3 before writes without valid PRE artifacts and fresh telemetry/product worker readiness;
there is no one-shot relative fallback. See [the private adapter contract](M5_5B_V3_PERSISTENT_ADAPTER.md).

Didim workload/cardinality effects remain a distinct functional/operational lane. Fixed regional
terms and geometries and all selected-place coverage remain bounded and semantically validated.
Do not combine them with the stable-sentinel statistic or normalize latency by result count.

## Existing 71 Places and data model

No migration is required. **V17 is not edited.** Its existing successor-run/decision/write/ref
lineage supports re-adoption. The domain changes are narrow versioned guards and policy routing,
not query/index/entity/public-contract changes.

Canonical UUID generation retains the v2 namespace/name basis. V3 changes the validation policy,
not durable identity. A new v3 manifest must carry:

- New run UUID and new authorization reference.
- `reauthorizes_run_id` equal to the historical contained run.
- Exact `predecessor_manifest_hash`.
- `canonical_identity_method_version: didim-autonomous-validation-v2`.
- `performance_policy: ADVISORY_ONLY`; `canary_stage: STAGE_1`.
- The exact same full source observations, candidate payloads/hashes, decisions, selection,
  ranks, canonical UUIDs, snapshots, providers and 6 km geometry as A.

Exactly 71 eligible and selected AUTO_CREATE candidates are required, not the nominal 100.
Python validation compares the entire frozen predecessor content. The importer independently
verifies the persisted contained v2 leaf, matching old hash, all candidate hashes/decisions/
selection/UUIDs and the complete immutable source ID set under the pilot lock. Existing full
source-payload collision checks, canonical-field checks and accounting remain mandatory.

No new Place INSERT occurs for B. Only RETIRED EXTERNAL_IMPORT Places with matching predecessor
journal/decision/hash and canonical fields can transition to PROVISIONAL; B's successful terminal
import atomically activates its own exposure. Manual/community Places, active conflicting owners,
missing lineage, changed candidates or duplicate UUIDs are refused. PROVISIONAL is not public.

## Run A → Run B ownership/history

| Entity | Historical A | New approved B |
| --- | --- | --- |
| Canonical Place | Created durable UUID; later RETIRED | Same row/UUID, PROVISIONAL → ACTIVE |
| Sync run | SUCCEEDED import, immutable FAILED gate | New run/method/hash/authorization; explicit predecessor |
| Source observation | Immutable original owner A | Reused by ID, never copied/re-owned/re-timestamped |
| Decision | Immutable v2 decision | New v3 decision, derived `supersedes_decision_id` |
| Write journal | Terminal contained A write | New B write, `supersedes_write_id`, exclusive live owner |
| External ref | INACTIVE after A containment | Same `(provider, external_id)` row, ACTIVE, owner B |
| Ref event | A LINKED/rollback events immutable | New deterministic B LINKED event, READOPT_RETIRED metadata |
| Operational events | A containment records immutable | New B containment records if B needs containment |

V17 journal `write_action=AUTO_CREATE` denotes the unchanged candidate decision, not necessarily
a physical INSERT. B checkpoint/ref-event metadata explicitly says READOPT_RETIRED, expected
71 re-adoptions / zero new Place rows. The legacy `created_count=71` therefore means 71 selected
catalog exposures; closure must separately report physical new rows=0 and re-adopted rows=71.
B LINKED event time is its immutable attempt-start time; the journal records write time and the
successful run completion records the atomic activation time. Provider observation/last-seen
timestamps retain their original meaning; re-adoption does not pretend sources were refreshed.

Source ownership is explicitly A, operational catalog ownership is B, decision lineage connects
both. Server-derived accounting includes the sealed source ID set even where observations belong
to A, and catches any extra source rows owned by B. The existing 16,064 quarantine recheck queue
rows are not reset or re-owned by B; immutable B decision history still records unchanged states.

## Idempotency

Exact B replay returns the completed import without another Place/source/ref/event/journal row
or activation. Crash recovery uses the existing claim/lease and deterministic B decision, ref
event and journal IDs. Payload mismatch fails closed. Partial candidate transactions are atomic.
No old gate/run identity/history is rewritten; no final result can be changed by a replay.

## Graph-safe rollback

Only the established run-aware rollback service is used, never direct SQL cleanup. B containment
inactivates B-owned active refs and retires B-owned external catalog exposure while preserving
canonical rows, all provenance and A/B history. User visits, Experiences, Collections and Want
to Go remain intact; user graph alone does not require continued provider catalog exposure.
The private rollback-only wrapper accepts v3 Stage 1 in addition to its unchanged v2 paths;
unknown method versions and v3 expansion stages remain refused before mutation.

Legitimate newer independent ref/redirect ownership is protected; containment can leave ACTIVE
exposure under that newer owner and records CONTAINED_NEWER_REFERENCES. Replaying A containment
after B adoption is a no-op because A has a successor. Manual/community Places are untouched.
Any graph/containment failure is a hard safety failure, never compensated by replacement rows.

The V3 operational repair requires an immutable FAILED gate and committed containment request
before a separate read-only exact-run/hash inspection. The inspection derives the 71 UUIDs and
142 references from frozen predecessor decisions and persisted B ownership, validates schema and
graph guards, and pins ownership/action digests. Only a matching locked revalidation may invoke
retirement. Inspection failure leaves a durable requested-but-not-started state requiring owner
intervention, not blind rollback. V2 automatic gate containment remains unchanged.

## Product acceptance and cold-path safety

Unchanged mandatory product gates: Search and Turkish Search, bounded Nearby/Bounds Maps,
selected canonical identity/coverage, zero-Experience Detail, Planım/Want to Go, Collections,
synthetic V2 Experience publication against an imported canonical UUID, Detail after publication,
and Experience-first Explore. Public provider-ID isolation, rollback/graph safety, accounting,
provenance, anomalies and idempotency remain mandatory.

V3 executes verified PRE → import → exact replay → POST → advisory → provenance → accounting
→ anomaly → read-only functional/graph checks → real product worker → final health. Known hard
failure prevents any remaining mutation-capable product acceptance. Valid DEGRADED/INCOMPARABLE
telemetry remains advisory. The private worker binds readiness, actual evidence and atomically
hashed/read-back receipt to the future exact run/manifest/method/schema; it cannot mutate in an
unbound standalone mode. See the repaired [private adapter contract](M5_5B_V3_PERSISTENT_ADAPTER.md).

The one-shot retains readiness/health/first Search/Nearby/Bounds/Detail and the mandatory durable
25-record baseline artifact read-back. Cold correctness/deadline failures block/contain. Cold
latency is retained but is not a steady-state relative statistic in v3. V2 artifact compatibility
is unchanged; v3 artifact identity must bind the new run/hash/method.

## Deterministic verification

Added tests cover all owner-required classes: contained A → exact-71 B; durable UUID/no duplicate
Place; 142 reactivated refs; immutable/shared observations; unchanged A run/gate/journals/ref and
operational events; explicit B ownership; replay; B graph-safe containment; user graph/manual
protection; advisory degradation cannot auto-contain; absolute timeout/product/accounting/
provenance/anomaly failures still fail closed. Existing newer legitimate ownership protection,
rollback, source-accounting, baseline artifact, migrations and ingestion regressions are retained.

Tests use only synthetic fixtures and isolated Testcontainers databases. They are not real run
creation or operational manifest sealing. Full Backend CI must pass Maven verify, Testcontainers,
Flyway, ingestion, production image build and both private-launcher fail-closed image checks
before this design is declared READY. Record the actual source SHA, CI URL and test counts in
the completion report; do not infer CI success from local unit tests.

## Approval boundary / next step

Await explicit product-owner approval before preparing a new operational run UUID and final
sealed manifest. Preparation must pin the operations plan, GET-only probe image, existing
persistent/DB/Caddy identities, shared private artifact ownership and separately authorized
product-workflow readiness/evidence. The implemented private entry points do not authorize their
execution. No probes, synthetic publication, deployments, imports or rollback are launched by
this source task. Execution requires separate explicit authorization afterward.

The beta release/process/database, 350 ms instrumentation configuration, V17 and contained
catalog remain untouched by this task. No performance calibration or other milestone is resumed.

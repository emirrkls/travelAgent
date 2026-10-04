# M5.5B V3 Private Persistent Telemetry Adapter

This is an implemented operational contract, not execution authorization. No beta contact,
backup, live run UUID, final manifest or activation is part of implementation closure.

## Architecture and trust boundary

`PlacePilotPersistentTelemetryApplication` is a plain Java external process. It does not start
Spring or load a datasource, Flyway, importer, rollback, scheduler, authentication token or DB
credentials. It performs only GETs, parses bodies in memory, and writes sanitized local evidence.
The production image can launch this class through PropertiesLauncher without starting the app.

`operations/persistent_telemetry_worker.py` is a separate private coordinator, not a product
workflow/importer. It uses the established Docker or `sudo -n docker` path. It projects only safe
Docker inspect fields and reads `/proc/*/comm` and `stat` for exactly one Java PID/start tick pair.
It never dumps full inspect/config/env/cmdline. It never deploys, restarts, stops, migrates,
imports, rolls back or issues product mutations. It launches a plain GET probe in a separate
read-only container with no capabilities, no privileges, no Docker socket, no env file and no
write credentials, sharing **the pinned existing backend's network namespace**.

Frozen route: `PRIVATE_PERSISTENT_CONTAINER_NETNS`; application `http://127.0.0.1:8080`, management
`http://127.0.0.1:8081`. Loopback refers to that persistent container, NOT the importer/tool's
independent application. This is the established internal health route, bypassing public
Caddy/TLS. Neither route nor sentinel has a CLI override. No DNS/public-route mixing is possible.
Target identity is not inferred from an unverified JSON assertion: the coordinator observes the
actual pinned container and forces the probe into its network namespace before requesting any API.

The filesystem adapter is `PlacePilotPersistentOperationsAdapter`. The disabled private import
profile accepts `phokarta.place-import.v3-evidence-directory` and
`phokarta.place-import.v3-operations-plan-sha256`. These configure evidence, not policy. The sealed
manifest selects the workflow; persisted DB method version selects gate policy and lease budget.
V2 remains unchanged even if V3 configuration is present. No public controller/endpoint is added.

## Approved plan and operational preparation

Future preparation must create a private directory owned by the same UID used by the coordinator,
plain GET helper and one-shot artifact reader (0700 directories, 0600 files, no symlink ancestors).
Do not mount Docker/DB credentials into the GET helper. The operations plan is independently
SHA-256 pinned through the importer configuration and worker CLI, with exactly these fields:

- `version: v3-private-operations-plan-v2`, `validation_method: didim-autonomous-validation-v3`.
- New approved `run_id`, exact future sealed `manifest_hash` (neither created in this task).
- `authorization_reference`: exact future manifest authorization, supplied independently to the worker/reader.
- `target_policy`: approved source SHA, locally built expected image SHA, release image reference,
  frozen application/management origin and route, fixed sentinel, 20 samples, one preconditioning
  request, 5000 ms deadline, 350 ms observability threshold and PRE/POST roles. No future container,
  Java PID/start ticks, network ID or container-start placeholder is allowed in the sealed policy.
- `selected_canonical_ids`: exactly the same 71 unique approved UUIDs, compared to the manifest.
- `product_checks`: all fourteen explicit product/coverage/graph checks from V3 policy.

After the future approved deployment, the coordinator observes the actual source/release/image,
container ID/start, Java PID/start ticks, network-ID digest, DB/Caddy health, restart/OOM state and
350 ms instrumentation. It executes two actual five-second complete-response management health
GETs in that container's namespace, independently reads their bounded artifact, and checks that
the target did not change during health verification. Only then does it atomically finalize
`EXECUTION_TARGET.json` and `EXECUTION_TARGET_RECEIPT.json`. The receipt binds exact bytes/hash,
new run, manifest hash and sealed operations-plan SHA. Both Python and Java reopen and verify it.
The attestation precedes PRE and catalog mutation; PRE/POST must match that same actual target.
Missing attestation cannot be regenerated once PRE or a mutation IPC marker exists. A replaced
attestation, restart, Java/image/container/route/network change is hard failure, not advisory.

The worker also requires full frozen DB/Caddy container IDs and a pinned probe-image SHA. That
image is a tool process; it does not replace the persistent backend image. The coordinator's
runtime is bounded at 65 minutes; no automatic extension or rebaseline. It produces PRE once,
then waits for the adapter's POST request. It keeps safe target/readiness attestations fresh every
two seconds. This polling is separate from the uninterrupted measured GET stream.

The separate `operations/product_evidence_worker.py` must publish fresh
`PRODUCT_WORKER_READY.json` (version `v3-authorized-product-workflow-v1`, run/hash/observed_at,
validation method and evidence schema)
and fulfill the later product request. The telemetry worker **cannot** execute publication,
Planım, Collections or graph checks, nor synthesize their PASS results. A missing product worker
is a pre-write blocker, not a reason to bypass those gates. Operational preparation must bind
and verify that worker, including real observation evidence, before execution approval.

## Snapshot and independent read-back

PRE/POST each contain Search → Nearby → Bounds → Detail. Each surface has one recorded
preconditioning GET, then 20 timed GETs: 84 recorded requests per snapshot, 168 pre/post
(80 timed per snapshot; the four preconditioning records are excluded from statistics).
There are additionally six separately labelled management GETs per snapshot: liveness,
DB-aware readiness and Prometheus process-start evidence before/after. They are never included
in latency statistics. All 180 measured/management GETs have the same five-second hard deadline.

The end-to-end deadline includes headers and complete body, not just connection/headers.
Redirects/retries are disabled; bodies are capped at 4 MiB.
Java 21 transport retries are explicitly limited to one attempt using flags in the GET helper
JVM only; the launcher requires these flags before any GET. Backend/importer JVM flags are unchanged.
Actual response validation checks
DTO field allow-lists, canonical UUID uniqueness, sentinel name/category/coordinates, Search
pagination, limits (100 Search/200 Map), Nearby geometry/distance/order, Bounds geometry, Detail
shape and provider-key isolation. No raw bodies, text, reviews or error messages are retained.

Each `PRE.json`/`POST.json` includes version `persistent-pilot-v3-v1`, run/hash/role, start/end,
target, outcome, process-start value, all individual request records and management checks.
Records contain exact surface/path, UTC start, preconditioning flag, monotonic latency,
headers/body timing, HTTP status, validation, byte count, canonical UUID list/count/digest.
Digest is SHA-256 of sorted canonical UUIDs joined with newline. No milliseconds/result metric.

Each role's separately finalized receipt contains raw artifact SHA-256 and byte size, role/run/hash,
and independently observed before/after target attestations. The reader reopens bounded bytes,
rejects duplicate keys/trailing JSON, unknown metadata/raw bodies, unsafe paths, wrong hashes,
wrong roles, incomplete records, bad geometry/identity/probe evidence, unhealthy or changed targets,
and missing management evidence. Atomic finalization cannot overwrite an old observation.
Heartbeat files alone may be atomically refreshed. Partial failed snapshots are retained and
never receive a successful receipt; the coordinator emits a sanitized worker-failure marker.

PRE completion must be no more than 15 minutes before run start and no later than mutation.
Before writes, the adapter requires fresh (≤30 s) actual target and both worker attestations,
valid pinned plan, and independently read-back complete PRE. POST is requested only after successful
import and starts no earlier than that request. The DB gate independently verifies PRE/import/POST
temporal bracketing. Same container/image/Java PID/start/container start/route/health and actual
Prometheus process start are required. Process/path changes are HARD FAIL, never advisory drift.

POST and product artifact IPC waits each have a nine-minute bound. They do not issue retries or
API probes. Missing/invalid POST/product evidence after mutation follows existing new-run
graph-safe containment. DB-derived V3 lease calculation includes both IPC budgets; V2 stays intact.

## Product evidence

After valid POST, provenance, accounting, anomaly and read-only graph safety, the adapter
publishes `PRODUCT_REQUEST.json` with new run/hash/request time, exact authorization reference,
method and schema. The product worker independently validates both future sealed manifest and
its unchanged v2 predecessor, their pinned raw hashes, execution attestation, fresh current target,
POST receipt, selected UUID set and graph proof. There is no standalone production mutation mode:
registration/publication cannot start without this private, exact-bound authorization lifecycle.
Its CLI requires predecessor path/hash as well as future manifest path/hash, plan SHA, private
directory and authorization reference; there is no origin or deadline override.

The actual product worker writes `PRODUCT.json` and a separately finalized
`PRODUCT_RECEIPT.json` with run/hash/raw artifact SHA-256/bytes. No product credentials enter the
telemetry worker or GET helper. The artifact contains version `v3-product-evidence-v1`, run/hash,
start/end, exact selected UUIDs, all fourteen product checks and final liveness/readiness evidence.

It performs exact-name and Turkish Search for every selected UUID, bounded Didim Nearby/Bounds
coverage/geometry/order/dense-marker checks, and zero-Experience v1/v2 Detail for every Place.
Only after those read-only checks does it create its own synthetic account, verify Want-to-Go
and Collections, publish/read a V2 Experience, verify Detail afterward and Experience-first
Explore. It deletes only its own synthetic account/graph, verifies the zero-Experience state
again and checks final health. Credentials/tokens/bodies remain only in memory and anonymous
pipes. Each HTTP operation uses a disposable external client process, one parent monotonic
five-second deadline from setup through full response, no redirects/retries and a 4 MiB body cap.
Failed/slow observations remain sanitized evidence; uncertain synthetic cleanup requires owner
intervention and never receives a success receipt. No private user content is persisted.

`PRODUCT.json` is independently reopened, byte/schema/binding/coverage/status/semantics/deadline
validated, then its exact hash and byte count are atomically finalized in `PRODUCT_RECEIPT.json`.
The Java consumer independently validates these same bytes again. Neither READY nor PASS is
manufactured by the telemetry coordinator.

Each check retains status, timestamp, selected canonical UUIDs and sanitized individual evidence:
operation, HTTP/inspection status, semantic validation, latency and safe request ID. PASS alone
is insufficient. All observations must be valid/2xx and below five seconds; all identities and
times bind the new run. Planım, Collections and synthetic V2 publication require both POST and
GET evidence. Rollback/graph safety require read-only graph-inspection evidence, not execution of
rollback. Other checks require GET evidence. Actual product assertions remain the responsibility
of the authorized product workflow, not the GET worker. Gate diagnostics retain verified hashes
and bytes of PRE, POST and PRODUCT plus existing hard product results.

## Policy and closure

Median and nearest-rank p90 use the 20 timed samples only. Count, median bytes, identity digest
and relative deltas are reported separately. Comparable/no increase is NORMAL; any comparable
median/p90 increase is DEGRADED; result identity/cardinality drift is INCOMPARABLE. None is latency
PASS. Relative/advisory drift alone cannot contain V3; all absolute safety/correctness evidence
remains hard. Detail 350 ms remains diagnostic only, and all historical timeout evidence remains.

## V3 order and containment

Actual V3 order is preconditions and cold safety → verified PRE → re-adoption import → exact
idempotent replay → POST → advisory computation → provenance → accounting → anomaly → read-only
functional/graph safety → product acceptance → final health → immutable final gate. V2 keeps its
original order/policy. A known hard failure short-circuits before any remaining mutation-capable
product step; valid DEGRADED or INCOMPARABLE advisory does not short-circuit.

A V3 FAILED gate never directly retires catalog exposure. Failure records immutable gate evidence,
commits `PILOT_CONTAINMENT_REQUESTED`, then performs a separate genuinely read-only repeatable-read
inspection. It verifies persisted exact B run/hash/method/stage and frozen A lineage/decisions,
the exact 71 UUIDs and 142 refs, source ownership, schema/graph guards, ownership/action digests,
manual protection and newer legitimate references. Mismatch stops with owner intervention and
leaves exposure untouched; there is no blind fallback. Under existing pilot/graph locks, the
inspection is revalidated without silently replanning before the existing retirement-only domain
service can execute. Existing STARTED/COMPLETED events carry the inspection and phase ordering.
Retries and ALREADY_CONTAINED preserve history; user graph and both run histories remain intact.
V17 and its existing event vocabulary are unchanged; no migration is introduced.

Implementation tests use mock HTTP servers/synthetic files/isolated PostGIS Testcontainers only.
CI builds the production image and verifies the private GET launcher fails closed without
configuration, alongside the original rollback/baseline launcher checks. No live snapshot or
new operational run/sealing/deployment/activation is authorized by this document.

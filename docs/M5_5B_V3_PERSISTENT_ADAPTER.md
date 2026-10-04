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

- `version: v3-private-operations-plan-v1`, `validation_method: didim-autonomous-validation-v3`.
- New approved `run_id`, exact future sealed `manifest_hash` (neither created in this task).
- `target`: LONG_LIVED_PERSISTENT kind, frozen origin/route, full container ID, actual image SHA,
  Java `PID:start_ticks`, container start, restart count zero, OOM false, backend/DB healthy,
  Caddy running, Detail observability enabled and threshold exactly 350 ms.
- `selected_canonical_ids`: exactly the same 71 unique approved UUIDs, compared to the manifest.
- `product_checks`: exactly the ten unchanged product/graph acceptance checks from V3 policy.

The worker also requires full frozen DB/Caddy container IDs and a pinned probe-image SHA. That
image is a tool process; it does not replace the persistent backend image. The coordinator's
runtime is bounded at 65 minutes; no automatic extension or rebaseline. It produces PRE once,
then waits for the adapter's POST request. It keeps safe target/readiness attestations fresh every
two seconds. This polling is separate from the uninterrupted measured GET stream.

The separately authorized existing product workflow must publish fresh
`PRODUCT_WORKER_READY.json` (version `v3-authorized-product-workflow-v1`, run/hash/observed_at)
and fulfill the later product request. The telemetry worker **cannot** execute publication,
Planım, Collections or graph checks, nor synthesize their PASS results. A missing product worker
is a pre-write blocker, not a reason to bypass those gates. Operational preparation must bind
and verify that worker, including real observation evidence, before execution approval.

## Snapshot and independent read-back

PRE/POST each contain Search → Nearby → Bounds → Detail. Each surface has one recorded
preconditioning GET, then 20 timed GETs: 84 measured requests per snapshot, 168 pre/post.
There are additionally six separately labelled management GETs per snapshot: liveness,
DB-aware readiness and Prometheus process-start evidence before/after. They are never included
in latency statistics. All 180 measured/management GETs have the same five-second hard deadline.

The end-to-end deadline includes headers and complete body, not just connection/headers.
Redirects/retries are disabled; bodies are capped at 4 MiB. Actual response validation checks
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

After POST, the adapter publishes `PRODUCT_REQUEST.json` with new run/hash/request time. The
existing authorized workflow writes `PRODUCT.json` and a separately finalized
`PRODUCT_RECEIPT.json` with run/hash/raw artifact SHA-256/bytes. No product credentials enter the
telemetry worker or GET helper. The artifact contains version `v3-product-evidence-v1`, run/hash,
start/end, exact selected UUIDs and all ten product checks.

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

Implementation tests use mock HTTP servers/synthetic files/isolated PostGIS Testcontainers only.
CI builds the production image and verifies the private GET launcher fails closed without
configuration, alongside the original rollback/baseline launcher checks. No live snapshot or
new operational run/sealing/deployment/activation is authorized by this document.

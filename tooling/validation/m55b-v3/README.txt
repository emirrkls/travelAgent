M5.5B validation infrastructure only

Application source: e93cae3028b44ba47f407ce19f7001b8b2983011, separate checkout.
No changes to application code or old operational packages. No live targets.
No workflow_dispatch, deployment environments, production secrets, or paid runner.
The existing accepted backend CI is not rerun by these path-scoped changes.

Fixture classification: exclusively synthetic. The pinned test fixture generator
and real decision/manifest validator produce all source and candidate records.
Only opaque candidate keys and the accepted 71 canonical UUIDs are input data.
Synthetic content/history fingerprints are NOT production fingerprint evidence.
All credentials and CA material are generated on the ephemeral runner; no inputs
from beta backups, raw provider datasets, local environment files or user records.

The standalone fixture helper runs the real V17/import/failure/containment services
to establish historical A. It is compiled separately, never added to the app JAR.
The original SQL is an independent pre-mutation reference oracle, not a fallback.
The real decomposed producer's preflight must PASS and independently read back
before continuation: 38 expressions, one read-only RR snapshot, 5 seconds each,
one non-resetting 30-second subprocess budget. No live audit retry occurs.

All fixture containers are in an INTERNAL Docker network, with no published ports.
The unchanged B1 Transport uses https://m55b-api:8443, a trusted ephemeral CA and
matching certificate. Negative hostname verification is required. The B1 worker
has no datasource credentials or Docker socket. TLS checks do not warm Detail;
first persistent Detail remains PRE preconditioning and is preserved in PRE.json.

Initial measured capacity: >=12 GiB total, >=8 GiB available, >=4 CPU, >=12 GiB
free workspace disk, >=100,000 free inodes, cgroup v2 memory/swap/CPU enforcement.
The 7.875 GiB concurrent memory envelope includes all services and probe worker;
build and predecessor seed run sequentially. Immediately before importer require
>=4 GiB available and >=4 GiB free disk. No resource or host swap setting changes.
Importer uses 3072 MiB memory / 2048 MiB heap / 1.5 CPU / 5376 MiB total memory+swap.
cgroup memory.swap.max must equal the 2304 MiB allowance. No host swap is created;
if the host has none, report it separately. Cgroup peak samples are observed peaks
through last sample, NOT guaranteed final shutdown maxima. Heap is sampled, not
an exact lifetime maximum. Resource samples include pressure, OOM and throttling.

Actual operational application and workers perform PRE, cold baseline, readoption,
replay, POST advisory, all hard checks, all 14 real product checks and cleanup.
Final independent readback requires 71 readoptions, 0 physical INSERTs, 142 active
refs, no new sources/quarantine promotions, exact four-surface HTTP identity sets,
preserved old A/manual/user history, restored graph counts, and PASSED gate.
No artifact or flag is fabricated to bypass a gate. On failure no retry is made.

Only explicit sanitized JSON projections are uploaded (7-day retention): counts,
hashes, safe identities, statuses/latencies, producer timings, resource samples.
No manifests, raw provider records, HTTP bodies, private logs/env, tokens or keys.
Finally remove only exact created container IDs and network on the isolated runner.
Failures require owner review; this workflow never promotes master or reseals.

Pinned release image assembly (validation infrastructure only)

The old ISOLATED_SERVICES artifact did not preserve the failed Docker operation.
Its exact historical daemon/registry cause remains unresolved. New observations
must not be retroactively represented as retained evidence from that old job.
Docker Hub and Quay MinIO acquisition failed in previous controlled runs. Do not
repeat either path. Application source and all upstream release versions remain
unchanged. Only test-local identities are assembled, never pushed to a registry:
phokarta-test-minio:RELEASE.2025-07-23T15-54-02Z
phokarta-test-mc:RELEASE.2025-07-21T05-28-08Z
PostGIS, Caddy and Python references are unchanged. No latest/substitute releases.

Before full fixture generation, dependency_preflight verifies the Linux/amd64
engine, built application ID/platform, and every exact approved external image.
It acquires/inspects only PostGIS, Caddy and Python from their previous registries,
using an explicit empty Docker CLI auth configuration. MinIO and MC are acquired
from their exact official GitHub Release binary, checksum and minisig assets.
The tag object and resolved source commit are pinned. GitHub must report a valid
signed tag. Downloads enforce HTTPS/allowlisted redirects, finite total and socket
deadlines, size bounds, exact release/asset names and SHA-256 matching both the
published checksum and independently retained prepublication hash. ELF headers
must be 64-bit little-endian x86-64. No file is executed before verification.

Minisign requires the ED prehash format, matching key ID and both detached and
trusted-comment Ed25519 signatures. Existing OpenSSL verifies the signatures;
Python only performs Blake2b-512, packet framing and public-key DER encoding.
The public key is independently selected from the exact signed upstream MinIO
commit's Dockerfile.release, whose SHA-256 is also pinned. Trust still depends
on HTTPS/GitHub upstream source, not an owner-provided out-of-band key. Same-origin
checksum matching alone is explicitly NOT independent authenticity evidence.
Regression tests use a freshly generated disposable test key, never committed.

Both exact assets were actually downloadable and signature-valid during local
prepublication checks, so source-build fallback is unnecessary and not used.
Missing/unavailable release assets fail closed without trying another binary,
registry, release, community mirror or automatically switching acquisition route.
The owner's conditional exact-tag source-build authority is not exercised.

The trusted runtime base is the already approved Python Linux/amd64 image, pinned
by its actual independently inspected immutable registry digest in FROM. Local
assembly IDs are checked against Docker's iidfile; labels, entrypoint and inherited
base layers are independently inspected. Binary hashes INSIDE both assembled
images, actual runtime releases and shell/subcommand contracts must also match.
No historical upstream Docker byte equivalence is claimed. Containers use
--pull=never. Any failed download/integrity/identity/version/contract is terminal.
Network-none contract checks precede internal network or DB initialization.

dependency-NNN-operation.json artifacts contain only explicit fields: stable ID,
PULL/INSPECT/LOAD/CREATE type, approved public image or private-reference hash,
platform, timestamps, elapsed/deadline, exit/status/category, safe presence flags,
verified IDs/digests. stderr is classified only in ephemeral process memory.
No stderr snippet, arbitrary URL, Docker environment, certificate or raw daemon
output is published. REGISTRY_AUTH means an observed access/authentication denial;
it does not prove whether a repository is private, missing or inaccessible.

Initial missing local-image INSPECT evidence is retained as FAIL/IMAGE_NOT_FOUND
and may be followed by exactly one approved external acquisition. All subsequent
external verification failures are terminal. No MinIO registry call is allowed.
Container CREATE operations also use sanitized dependency observations. Synthetic
selection, audit producer, transport/deadlines and all import/product assertions
remain unchanged. One owner-authorized branch-push validation run is permitted;
another dependency failure requires stopping, not another publication/retry.

Initialization order for this authorized run: all image acquisitions/identities
and MinIO executable contracts -> internal network/volume capability -> isolated
DB/media/Caddy -> MC ready/bucket creation/JSON stat readback -> disposable small
object upload/readback -> byte/SHA-256 equality -> remove only that smoke object
and independently list the empty bucket -> separately compiled
helper's V17 and two manual sentinels ONLY -> persistent backend and unchanged B1
verified HTTPS -> capacity recheck -> full synthetic manifest -> real predecessor
import/containment via seed-import -> actual decomposed historical audit preflight
-> unchanged real V3 coordinator/worker/runner. The tiny manual-only helper mode
does not create users or run rows. It enables semantic HTTPS checks before heavy
fixture work; the existing manual UUIDs/data and seed-import guard are preserved.
The persistent backend is not restarted after fixture import. No TLS check calls
Detail or substitutes for its first measured PRE preconditioning request.

Persistent target / HTTPS diagnostics

The prior harness 8f4467ca82c38c2da3afc725df4ce3a50aab4874 worker exited 1
at PERSISTENT_TARGET_AND_TLS. Its retained evidence does not establish an internal
failure phase. Caddy running and exit code 1 do NOT establish HTTPS readiness or a
TLS root cause. No speculative TLS/routing/application correction is made here.
The evidenced infrastructure defect is loss of substep diagnostics: wait() raised
before tls.json readback, and earlier worker failures emitted no safe phase data.

The parent now publishes backend creation/identity/readiness state, exact image
and process/container identity, restart/OOM state and measured startup duration.
The worker emits an ordered, bounded, schema-validated stdout contract, including
APPLICATION_IMPORT, DNS, TLS_CONTEXT, TCP, TLS_CHAIN_HOSTNAME,
TLS_NEGATIVE_HOSTNAME, B1_HEALTH_LIVE, B1_HEALTH_READY, B1_SEARCH, ARTIFACT_WRITE.
Every substep carries UTC/monotonic timing and explicit status. Failures contain
only fixed categories and allowlisted exception classes, never exception messages,
tracebacks, response bodies, arbitrary headers/URLs, environment or credentials.
Only fixed safe fields (HTTP status/latency/request ID/semantic result, TLS version,
verification booleans, private-DNS count) are permitted. Malformed/duplicate/extra
fields or partial PASS evidence are rejected. Unknown failures remain unclassified.

The parent reads the safe worker contract BEFORE propagating a nonzero exit, so
an artifact permission error also remains visible. Missing/invalid diagnostic
evidence cannot authorize continuation even if the worker exits zero. Original
B1 Transport, requests, TLS verification, negative hostname assertion and five-
second complete-response deadlines are unchanged. TCP+handshake share at most
five seconds per connection. No Detail warmup or additional GET/retry is added.
The existing approved MinIO acquisition/smoke/fixture/audit/import/product gates
and resource policy are unchanged. One controlled branch-push run is authorized;
if it yields a newly identified failure, stop and report rather than patch/rerun.

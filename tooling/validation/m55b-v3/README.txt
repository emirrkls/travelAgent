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

Docker dependency repair (validation infrastructure only)

The old ISOLATED_SERVICES artifact did not preserve the failed Docker operation.
Its exact historical daemon/registry cause remains unresolved. New observations
must not be retroactively represented as retained evidence from that old job.
Application source and all upstream release versions remain unchanged. The only
registry migrations are the owner-approved exact references:
minio/minio:RELEASE.2025-07-23T15-54-02Z ->
quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z
minio/mc:RELEASE.2025-07-21T05-28-08Z ->
quay.io/minio/mc:RELEASE.2025-07-21T05-28-08Z
PostGIS, Caddy and Python references are unchanged. No latest/substitute images.

Before full fixture generation, dependency_preflight verifies the Linux/amd64
engine, built application ID/platform, and every exact approved external image.
It independently inspects registry manifest/platform metadata, acquires missing
images once, and re-inspects actual loaded image IDs/digests. Both Quay images
require an actual pull even if already cached, using an explicit empty Docker
CLI auth configuration (no credentials/helpers). Their RepoDigests must identify
the exact Quay repository; no expected digest or cross-registry byte equivalence
is invented. Embedded releases and executable/shell/subcommand contracts are
checked in bounded network-none containers before network or DB initialization.
No retries, TLS bypass, credentials acquisition or further image substitution.
Unknown/malformed output and unavailable auth/platform stop before DB creation.
Internal network ownership and local volume capability are verified afterward.

dependency-NNN-operation.json artifacts contain only explicit fields: stable ID,
PULL/INSPECT/LOAD/CREATE type, approved public image or private-reference hash,
platform, timestamps, elapsed/deadline, exit/status/category, safe presence flags,
verified IDs/digests. stderr is classified only in ephemeral process memory.
No stderr snippet, arbitrary URL, Docker environment, certificate or raw daemon
output is published. REGISTRY_AUTH means an observed access/authentication denial;
it does not prove whether a repository is private, missing or inaccessible.

Initial missing local-image INSPECT evidence is retained as FAIL/IMAGE_NOT_FOUND
and may be followed by exactly one approved acquisition. For the two Quay images
ONLY, an unavailable remote manifest inspection remains recorded FAIL, distinct
from the actual anonymous pull and independent local identity inspection. It is
not represented as a successful remote inspection. An explicit remote platform
conflict, daemon/storage failure, failed pull, failed local identity inspection,
missing registry digest or release/command mismatch is terminal. For other image
dependencies the previous terminal remote-inspection policy remains unchanged.
Container CREATE operations also use sanitized dependency observations. Synthetic
selection, audit producer, transport/deadlines and all import/product assertions
remain unchanged. One owner-authorized branch-push validation run is permitted;
another dependency failure requires stopping, not another publication/retry.

Initialization order for this authorized run: all image acquisitions/identities
and MinIO executable contracts -> internal network/volume capability -> isolated
DB/media/Caddy -> MC ready/bucket creation/JSON stat readback -> separately compiled
helper's V17 and two manual sentinels ONLY -> persistent backend and unchanged B1
verified HTTPS -> capacity recheck -> full synthetic manifest -> real predecessor
import/containment via seed-import -> actual decomposed historical audit preflight
-> unchanged real V3 coordinator/worker/runner. The tiny manual-only helper mode
does not create users or run rows. It enables semantic HTTPS checks before heavy
fixture work; the existing manual UUIDs/data and seed-import guard are preserved.
The persistent backend is not restarted after fixture import. No TLS check calls
Detail or substitutes for its first measured PRE preconditioning request.

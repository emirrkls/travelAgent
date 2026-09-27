# M5.5B — Preimport HTTP baseline diagnosis

This bounded repair does not authorize deployment, backup, canary execution or rollback.
The sealed Didim Core 6 km run remains `d70adea5-6e3f-4c32-92c0-49695eeeb9ce`:
71 eligible, 16,064 quarantined, `didim-autonomous-validation-v2`.

## Reproduced defect

The operational one-shot intentionally probes its own loopback HTTP/management ports.
Previously it ran inside an ApplicationRunner. Spring Boot 3.5.7 publishes actual
ACCEPTING_TRAFFIC readiness only after runners and ApplicationReadyEvent listeners finish.
With health probes enabled, the root health endpoint therefore returns 503 during that runner.
A real Spring/Actuator HTTP regression reproduces five health failures while the other
four surfaces succeed; the identical plan passes after actual readiness.

The original failed live job discarded individual observations. Its historical per-sample
status/latency cannot be recovered retroactively. The reproduction is evidence of the
implementation defect, not an invented reconstruction of that missing live record.

The runner now only prepares configuration. The operational main invokes it exactly once
after SpringApplication.run returns, verifies actual readiness, and closes/rethrows on failure.
There is no fabricated readiness, retry, delay, network change or increased timeout.
The default API profile remains unaffected.

## Locked baseline contract

Five surfaces, five samples each, five-second request/connect timeout, no redirects/retries:

| Surface | Existing request and semantics |
| --- | --- |
| Health | Derived private management base + /health; unauthenticated; 2xx and JSON status UP |
| Search | Frozen first-selected name query, page 0, size 100, name ascending; current Page.content contract, no required unimported result |
| Nearby Map | Frozen first-selected coordinates, 250 m, limit 200; nested Place/distance DTO, geodesic bounds/order validation |
| Bounds Map | Same coordinates ±0.01 degrees, limit 200; current flat Place DTO, geographic bounds validation |
| Place Detail | Configured stable existing manual UUID; current flat canonical DTO and exact identity |

The frozen search query and geometry are reproducible even when the current catalog has
no Didim results. Empty preimport search/maps remain valid current contracts; postimport
coverage still requires the selected canonical Places. Targets were not replaced.
Live stable detail UUID: `aa000000-0000-4000-8000-000000000001`.
Accept: application/json; User-Agent: phokarta-autonomous-canary/1. No auth/cookie headers.
Credential-free loopback origins only. Non-2xx, timeout, transport and semantic failures
remain unhealthy. The existing 10% postimport performance threshold is unchanged.

## Safe diagnostics

An immutable per-probe record retains surface, 1-based sample index, GET, closed path
template, UTC start, duration, optional HTTP status, timeout flag, transport category,
validation result and failure category. It never stores request query/name/UUID, origin,
headers, response payload or exception text.

Every surface includes sample/success/failure/timeout counts, status distribution,
min/median/average/p95/max latency, final health and first failure category.
All five surfaces and all 25 samples survive a failure.
Detailed baseline records are bounded to 20 per surface; production sampling remains five.
Compact gate summaries preserve full coverage counts and the existing gate-size limit.

The normal canary now has a mandatory durable artifact/read-back gate for PASS and FAIL.
All records use the shared `preimport-http-baseline-diagnostics-v1` diagnostic format;
normal artifacts additionally bind a fresh baseline execution UUID, sealed run UUID,
internal manifest hash, actual envelope SHA-256, validation method and start/completion UTC.
No authorization reference, query values, provider IDs or secret-bearing paths enter the JSON.

Configure `PHOKARTA_PLACE_IMPORT_DIAGNOSTICS_DIRECTORY` as an absolute path into the
established private operational diagnostics mount, for example `/diagnostics`, backed by
an operator-prepared directory under `/opt/phokarta/diagnostics`. It must already exist;
the normal canary has no ephemeral/public/default fallback. Prepare it for the runtime UID
(the production image uses 10001), owner-only mode 0700; mount it persistently and privately.
No host ownership/permissions are changed automatically by this source repair.

Final relative path:
`<run-uuid>/<baseline-execution-uuid>/PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json`.
Existing run directories must be private; execution directories are created exclusively.
On Linux, directory/file creation uses 0700/0600. Windows unit tests inherit the private
temporary-root owner ACL. There is no new HTTP route or public artifact exposure.

Normal ordering is frozen:
prepare sealed plan → actual readiness → exact 25 probes → persist → read-back verify
→ only if baseline PASS, importApproved. The existing lifecycle repair remains unchanged.

Write uses a private same-directory temporary file, full writes, force(true), successful
close, ATOMIC_MOVE with no non-atomic fallback, then directory fsync on POSIX. Failures at
any step stop before import; incomplete files cannot use the canonical final filename.
An already finalized execution is never reused or overwritten.

The final path is independently reopened with bounded reads (128 KiB), strict JSON
duplicate/trailing-token rejection, and identity/schema/count validation. It verifies all
five surfaces, all 25 unique ordered sample IDs (1–5), GET/closed templates, sane finite
nonnegative durations, status/timeout/transport/semantic success, summary reconstruction,
and exact persisted content equality/SHA-256. Required probe records are never truncated.

The verified receipt reports SHA-256, byte count, run/execution association and a safe
UUID-based relative path; it is emitted as `PREIMPORT_HTTP_BASELINE_ARTIFACT_VERIFIED=`
and linked in the eventual private canary gate. It is not a pilot database write before import.
Write, force/close, atomic move, reopen, parse, identity, sample, summary or hash failure
always prevents both the first import and its replay.

The full sanitized record is also retained in the private job log as
`PREIMPORT_HTTP_BASELINE_DIAGNOSTICS=`. FAIL emits this fallback before any filesystem
attempt, preserving detailed evidence even if durable publication fails.
The typed HTTP failure exposes a defensive copy. No preimport database gate/event is created.
Diagnosis-only can additionally write `PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json` with
CREATE_NEW (no overwrite), a 128 KiB limit and forced durable file flush. Its existing
read-only behavior and option contract are unchanged; it does not substitute for the
normal canary's durable artifact gate.

## Diagnosis-only entry point

`PlacePilotBaselineDiagnosisApplication` is a pure Java main: no Spring context,
datasource, Flyway, workers, importer, pilot run, source observation, gate or event.
It verifies the input envelope SHA-256, derives the exact same frozen baseline target,
then performs only the 25 GET requests. It cannot accept import/rollback/database options,
sample/timeout overrides or public remote origins.

Use the validated production jar's PropertiesLauncher:

```text
java -Dloader.main=com.emirrkls.phokarta.backend.operations.PlacePilotBaselineDiagnosisApplication -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher --base-url=http://127.0.0.1:8080 --health-base-url=http://127.0.0.1:8081/actuator --manifest-path=<sealed-envelope> --expected-envelope-sha256=6a3e2e6c7a963d662463d9f1b45a353afc3714c415463db17301315849d4e8c7 --baseline-place-id=aa000000-0000-4000-8000-000000000001 --output-path=<private-directory>/PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json
```

Exit 0 means all five surfaces pass; exit 1 means failed diagnostics or invalid input.
Do not provide database credentials/environment or start the normal application main.
A safe beta check, only after exact-source CI success, may share the already-running
backend network namespace and use read-only jar/manifest mounts. It does not redeploy the
backend or change Docker network topology. Compare read-only catalog/pilot fingerprints
before and after. Any failure ends this diagnosis; it never invokes import or rollback.

## Verification

Tests cover all five contracts, 500/404/401/403/redirect rejection, timeout/transport/malformed
responses, successful surfaces retained, secret exclusion, immutable copies, durable
non-overwriting artifacts, failure-before-import, real lifecycle reproduction, actual
readiness ordering, once-only execution, exact 25-sample accounting and five-second limits.
PostGIS integration compares all pilot/catalog counters and the full catalog fingerprint
before/after both passing and stale-UUID failing diagnosis; Flyway maximum version is 17.
Existing source-accounting, rollback and full backend/ingestion regression remain required.
CI also builds the production image and verifies both private launchers fail closed
without authorized/configured input, with no network.

No beta redeployment, backup, migration, canonical write or live rollback is part of this repair.
Wait for separate explicit canary continuation authorization.

## Final contract freeze

CURRENT PREIMPORT CONTRACT = FROZEN for the sealed 71-candidate canary.
No additional speculative preimport observability/safety requirement may block it unless
an actual test fails, live preflight detects a concrete defect, or an existing locked gate
cannot execute as specified. No canary continuation is implied by this source repair.

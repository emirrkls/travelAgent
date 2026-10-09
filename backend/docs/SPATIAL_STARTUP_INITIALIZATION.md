# Deterministic spatial startup initialization

The historical live 8.129-second Detail incident remains **UNRESOLVED**.
Isolated Java 21 evidence establishes a narrower, intervention-sensitive
Geolatte CRS first-use component; this change hardens only that component.

`SpatialStartupInitialization` is an eager `SmartInitializingSingleton`.
Its synchronous callback completes during singleton bootstrap, before context
refresh, ApplicationReadyEvent and Boot's ACCEPTING_TRAFFIC transition. It
does not depend on, or modify, the operational importer. A process/classloader
single-flight state prevents repeated work across contexts. Runtime exceptions
and class-initialization errors remain sticky and abort startup, never a retry
or a fabricated readiness success.

The only library call is
`CrsRegistry.getGeographicCoordinateReferenceSystemForEPSG(4326)` with a required
non-null result and no fallback. Bytecode of the pinned Geolatte 1.9.1 shows that
this initializes `CrsRegistry`, its concurrent registry, `CrsWktDecoder`,
`CrsWktTokenizer` and the related CRS value/parsing classes using the bundled
`spatial_ref_sys.txt` resource. Geolatte itself reads that resource in its static
initializer. Application code does not enumerate or independently initialize
every CRS, register a replacement CRS, or decode any Place coordinates.

No datasource, Place row, sentinel, HTTP call, sleep, retry or catalog mutation
is required. Existing Hibernate Spatial/PostGIS materialization and all SQL,
DTO, privacy, rating/dimension/recent-visit semantics remain unchanged. Other
Hibernate, JIT, GC and framework cold costs can remain.

Safe structured startup records expose `started` and `completed`/`failed`, with
monotonic `duration_ms`. `phokarta.spatial.startup.duration` is recorded once,
with only the bounded `completed`/`failed` outcome tag, through existing metrics.
There is no new endpoint, configuration switch or duration acceptance gate.
No resource contents, exception messages or user/credential data are logged.

Validation requires the complete Linux CI and five fresh isolated production
processes, retaining the true first Detail after health/Search/Nearby/Bounds.
The full-volume V3 success rehearsal remains separate from CI. The unchanged
five-second complete-response deadline remains mandatory; no tighter SLO is
introduced. Historical containment, V17 and reserved Run B/seal remain untouched.

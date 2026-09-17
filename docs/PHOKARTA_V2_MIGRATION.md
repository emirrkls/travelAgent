# Phokarta V2 Migration

## Milestone 0: Invariant & Compatibility Foundation

This milestone establishes a reversible read-compatible foundation for Experience V2. It does not enable a V2 composer, V2 writes, new root navigation, or any visible V2 product surface.

## Architecture

The existing `Visit` remains the aggregate root and the only content identity. An Experience uses the existing Visit UUID, author, Place, date, visibility, mutation identity, media ownership, account-deletion ownership, and offline publication machinery.

Native V2 semantics are additive:

```text
visits (stable aggregate and Experience ID)
  └── 0..1 visit_experience_details
        ├── 0..n visit_experience_vibes
        └── 0..n visit_experience_practical_signals
```

There is no independent Experience table, second content root, Visit rewrite, media reparenting, or V2 write path.

## Schema additions

Flyway V13 adds:

- `visit_experience_details`, keyed by and cascading from `visits.id`;
- ordered, unique `visit_experience_vibes` values;
- ordered, unique `visit_experience_practical_signals` values;
- nullable `semantic_state_code` and `template_version` columns on `visit_dimension_scores`;
- indexes for future primary-Experience, Feeling, Vibe, Practical Signal, and semantic-dimension queries.

Legacy dimension rows keep their original numeric score and both new columns remain null. Native semantic rows retain both the state and its locked compatibility score. Deleting a Visit cascades through every new table and its existing dimensions. Legacy Visits require no sidecar row.

## Taxonomy version 1

Persistence and wire identity use nonlocalized stable codes. Backend constraints and the backend catalog are authoritative. Version 1 contains:

- five Overall Feelings and provenance `EXPLICIT` / `DERIVED_LEGACY`;
- five Dimension States with compatibility scores 10 / 8 / 6 / 4 / 2;
- ten Experience Families;
- the complete 55-value Primary Experience catalog, including `OTHER`;
- Companion, Time of Day, eight Vibes, sixteen Practical Signals, and `GENERATED` / `CUSTOM` title sources.

Android and iOS read these codes with explicit unknown-code fallbacks. Neither client changes its existing persistence schema or write queue in this milestone.

## Read compatibility

`GET /api/v2/experiences/{id}` is read-only and reuses `ViewerAccessPolicy.canViewVisit`. Owner, PUBLIC, mutual-friend FRIENDS, PRIVATE, and symmetric-block behavior therefore remain aligned with direct V1 Visit reads.

For a legacy Visit without a sidecar:

- Experience ID is the Visit ID;
- title is the current Place name as a neutral read-time compatibility title;
- `titleSource` is null and `titlePersisted` is false;
- Story falls back to `publicReview`;
- Tip is absent;
- Feeling is derived from the unchanged numeric rating using the Product Contract thresholds;
- provenance is `DERIVED_LEGACY` and the numeric rating remains present;
- Primary Experience is the DTO-only `UNKNOWN_LEGACY`, never fabricated from Place category and never persisted as taxonomy;
- numeric dimensions remain unchanged with no fabricated stored semantic state;
- legacy URL media and managed media retain their source order, with no six-item read truncation;
- visibility is unchanged;
- `privateMemory` is not a field in the V2 response and is never used as Story.

For a native sidecar, the adapter returns the persisted title and source, explicit Feeling and provenance, Primary Experience, Context, ordered Vibes and Practical Signals, Story, Tip, taxonomy version, and semantic dimension metadata.

## iOS selected-media durability

Previously, newly selected composer media was prepared into a temporary online-first path, and a process death before publication could lose it. The composer now accepts a selection only after:

1. the draft parent is durably saved;
2. sanitized media is atomically imported into `DurableMediaStore` under the account directory;
3. the owner, Place/draft identity, client media identity, position, relative path, MIME type, byte size, dimensions, and upload state are registered in SQLite.

Draft restoration rebuilds the ordered composer selection only from records matching the active account and Place. Reorder, removal, discard, account purge, and file reconciliation use a real shared asynchronous media mutation lock. Committing an offline mutation transfers the durable path to the pending-mutation rows without deleting the file; successful sync remains responsible for final local-file cleanup.

## Backward-compatibility guarantees

- V1 API routes and response semantics are unchanged.
- V1 Visit writes remain valid and create no required V2 row.
- Existing queued Visit payloads and fingerprints are unchanged.
- Existing Visit UUIDs, visibility, media, and account ownership remain unchanged.
- Reads do not backfill or mutate legacy records.
- No V2 publication fingerprint exists yet because V2 writes are intentionally deferred.
- No new secrets, storage credentials, signing material, location persistence, TLS exception, or presigned-URL logging is introduced.

## Validation record

Tests must be reported only when actually executed. The final Milestone 0 run records authoritative counts and links in the completion report. During implementation:

- baseline Backend CI ran the repository `mvn verify` workflow successfully at the Product Contract commit;
- baseline Android `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `compileReleaseKotlin` completed successfully;
- focused backend taxonomy and adapter tests completed successfully;
- focused Android V2 mapping tests completed successfully;
- Apple compilation and XCTest validation are delegated to the configured Xcode Cloud push workflow.

## Remaining phases

Milestone 0 deliberately defers V2 writes and all visible V2 product behavior. The next recommended milestone is Privacy & Aggregate Foundation, including profile privacy and the aggregate rules required before later social/product surfaces. Composer migration, Ben de Yaşadım, threads, Planım, new navigation, and V1 deprecation remain later work.

## Milestone 1: Privacy & Aggregate Foundation

Milestone 1 adds the privacy boundary required by later V2 publication and social surfaces. It does not enable V2 Experience writes, change root navigation, or begin the Experience-first UI.

### Profile privacy and rollout

Flyway V14 adds `users.profile_visibility` with stable `PUBLIC` / `PRIVATE` values, a `NOT NULL` constraint, and a `PUBLIC` default. Existing users therefore remain public without inference or data rewriting.

Private-profile activation is controlled by `phokarta.features.profile-privacy-v2.enabled` / `PHOKARTA_PROFILE_PRIVACY_V2_ENABLED`. The application default and production example are false. Automated backend tests enable it; the staging example enables it for supported-client validation. Production activation requires minimum supported Android and iOS versions that understand `REQUEST_PENDING`. `GET /api/v2/capabilities` exposes the effective capability. Disabling the switch prevents new private activation but does not weaken privacy for an already-private account.

The V1 follow route keeps immediate approval for public targets. It never silently approves a private target: it returns `FOLLOW_APPROVAL_REQUIRED`. The V1 full-profile route fails closed for an unapproved private-profile viewer rather than serializing protected counts into a DTO that cannot express redaction. V1 search remains identity-only and discoverable. V1 numeric Place aggregates are unchanged.

### Follow requests and Friend invariant

V14 adds `follow_requests` separately from `user_follows`. Pending rows have a partial unique requester/target index and cannot be self-directed. Status and resolution constraints cover `PENDING`, `APPROVED`, `REJECTED`, and `CANCELLED`. Both participant foreign keys cascade on account deletion.

- Following a public target creates the existing approved directed `user_follows` edge.
- Following a private target creates one idempotent pending request and no follow edge.
- Approval resolves the request and materializes one directed follow edge.
- Rejection and requester cancellation resolve without a follow edge.
- Unfollow removes only the approved edge and never recreates a request.
- Blocking cancels pending requests in both directions and removes approved edges in both directions.
- Unblocking restores nothing, and stale blocked requests cannot be approved.

Friend remains exactly mutual approved follow. There is no friendship table, and one approved private-profile follower is not a Friend.

### Central viewer policy

`ViewerAccessPolicy` remains the authority for direct Visit/Experience, attached media, Collection, profile, and symmetric-block decisions. Profile privacy is an upper bound:

| Author/profile state | PUBLIC Experience | FRIENDS Experience | PRIVATE Experience |
|---|---|---|---|
| Owner | visible | visible | visible |
| Public profile | visible | mutual Friends | owner only |
| Private profile | approved followers | mutual Friends | owner only |
| Blocked either direction | hidden | hidden | hidden |

Anonymous viewers cannot read cards from private profiles. A V2 private-profile read remains discoverable with identity, short bio, privacy, and action state, while city/country, follow/friend, and visible-Experience counts are returned as null rather than populated. Block-separated profile reads use generic not-found/unavailable behavior and never reveal block direction.

The SQL-backed V1 Community list and recent-review paths implement the same profile upper bound so pagination cannot leak private-profile authored cards. Direct V1 and V2 Experience UUID reads and attached-media access use the centralized policy.

### Anonymous Community contributions

`CommunityContributionPolicy` is deliberately separate from viewer authorization:

- `PUBLIC`: eligible;
- `FRIENDS`: eligible;
- `PRIVATE`: excluded.

Eligibility is independent of author profile privacy, follower state, friendship, and viewer block state. Aggregates never include contributor identity. Blocking hides attributable profile/content surfaces but does not change a Place's global anonymous population.

`visibleExperienceCount` is viewer-relative and applies ownership, profile privacy, Experience visibility, approved follow, mutual Friend, and symmetric block rules. `communityContributionCount` is global and counts eligible `PUBLIC` plus `FRIENDS` Experiences. Neither is derived from the other.

### V2 aggregate read

`GET /api/v2/places/{id}` returns Place identity plus the two separate counts and identity-free aggregate foundations:

- all five Overall Feeling buckets, using persisted native Feelings or the locked read-time legacy numeric mapping without mutation;
- dimension key, eligible contribution count, numeric average, numeric-only legacy count, and semantic-state distribution only where actually stored;
- persisted Practical Signal counts with the eligible Community population as denominator.

No freshness weighting, arbitrary minimum-sample suppression, author identity, user-facing 0–10 primary aggregate, or V1 aggregate replacement is introduced.

### Versioned API and native foundations

V2 adds viewer-aware profile/search, follow/request lifecycle, owner visibility update, capabilities, and Place aggregate routes. Relationship responses represent `NONE`, `REQUEST_PENDING`, `FOLLOWING`, `FRIENDS`, and generic `UNAVAILABLE`.

Android and iOS add separate V2 privacy/follow/aggregate network and domain models, safe unknown-code fallback, Friend/pending helpers, block invalidation, and account-switch clearing for new ephemeral privacy state. Existing V1 UI, navigation, persistence, and the Milestone 0 iOS durable-media flow remain unchanged.

### Validation record and limitations

During implementation on Windows:

- the pre-change Android gate (`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `compileReleaseKotlin`) passed;
- 39 focused backend privacy/relationship/aggregate unit tests passed (46 changed-area tests including media-policy regression coverage);
- the expanded backend suite and PostgreSQL privacy/aggregate integration matrix compiled;
- Android `testDebugUnitTest`, including eight new deterministic V2 privacy/aggregate tests, passed;
- local Docker/Testcontainers was unavailable, so authoritative PostgreSQL migration, request lifecycle, aggregate SQL, account deletion, and full backend validation require pushed Backend CI;
- Xcode/Swift/XCTest is unavailable on Windows, so the appended iOS tests require pushed Xcode Cloud Build and Test validation.

Freshness/recency weighting and minimum-sample privacy suppression remain intentionally deferred. V2 publication and all Milestone 2 product work remain disabled.

## Milestone 2: V2 Publication

Milestone 2 enables authenticated native Experience publication while retaining `Visit` as the single aggregate root and content identity. It does not introduce an Experience table, replace V1 publication, change root navigation, or begin Experience-first read surfaces.

### V2 create API and transaction

`POST /api/v2/experiences` accepts the native semantic contract and returns the canonical `ExperienceV2Response` with HTTP 201. The request contains a client mutation UUID, Place, visit date, Primary Experience and optional `OTHER` label, explicit Overall Feeling, optional Companion and Time, distinct Vibes and Practical Signals, semantic dimensions, title intent, Story, Tip, owner-only private memory, visibility, and ordered confirmed media IDs. It contains no client-authored numeric overall rating or dimension score.

The backend transaction locks the account and mutation identity, enforces policy acceptance, resolves the authenticated owner and Place, canonicalizes the complete payload, creates one `Visit`, writes its native sidecar and semantic dimensions, attaches media through the existing ownership/confirmation path, and maps the canonical response. Any failure rolls back the entire publication. Account deletion continues to cascade from the existing owner and Visit foreign keys through sidecar, dimensions, and media.

### Compatibility values and canonical identity

The backend alone derives the native Overall Feeling compatibility value:

| Overall Feeling | Visit numeric compatibility |
|---|---:|
| `BAYILDIM` | 10.0 |
| `GUZELDI` | 8.0 |
| `EH_ISTE` | 6.0 |
| `BEKLENTIMI_KARSILAMADI` | 4.0 |
| `BIR_DAHA_TERCIH_ETMEM` | 2.0 |

Semantic dimension states remain dual-written with their locked 10 / 8 / 6 / 4 / 2 compatibility scores. Clients persist stable semantic codes and never submit authoritative numeric V2 values. Legacy numeric-to-Feeling read thresholds are unchanged.

The V2 fingerprint is independent of the V1 fingerprint format. It covers the Place, date, Primary Experience, normalized `OTHER` label, Feeling and derived compatibility value, Companion, Time, canonical Vibe and Practical Signal sets, canonical dimension keys/states/template versions and derived values, Story, Tip, private memory, resolved persisted title and source, visibility, and ordered media IDs. Set-like fields are sorted; ordered media is not. The same user and mutation UUID with the same canonical payload returns the original canonical Experience after a lost acknowledgement. Any semantic change conflicts rather than creating a duplicate.

### Taxonomy, title, content, and media

Backend taxonomy and dimension-family catalogs remain authoritative. `OTHER` requires a nonblank raw label and every non-`OTHER` Primary rejects one. Vibes are at most two distinct canonical values; the optional Companion and Time accept at most one; invalid write codes, duplicate set values, invalid family dimensions, and unsupported dimension template versions are rejected. Mobile read models retain safe unknown-code fallbacks.

Generated titles have one canonical owner: the backend deterministically resolves and persists the accepted title with source `GENERATED`. A nonblank user-edited title is persisted with source `CUSTOM`. Reads use the persisted resolved text and never regenerate historical titles.

A native Experience requires at least one of Story, Tip, or media. Private memory remains stored only in the existing owner-only Visit field and is absent from all V2 responses. Native publication accepts zero through six ordered managed images. The global/V1 media capacity and legacy Experiences with more than six images remain unchanged and readable. Upload intent, upload, and confirmation still occur before create; attachment reuses existing owner, readiness, order, and transaction checks.

### Versioned mobile persistence and offline publication

V1 and V2 pending payloads are separate. Existing queue and draft rows migrate with `payloadVersion = 1`; new native composers use version 2 and mutation type `PUBLISH_EXPERIENCE_V2`. Sync routes version 1 only to `/api/v1/visits` and version 2 only to `/api/v2/experiences`. The same mutation UUID, exact semantic payload, and media order survive retries, process death, and Edit & Retry. A policy-acceptance failure pauses later Visit/V2 publishes in that drain; accepting policy and retrying resumes the unchanged queued payload. Local media is removed only after canonical acknowledgement.

Android Room schema 8 adds explicit payload versions and stable V2 draft columns, semantic dimension state/template columns, and separate V2 pending payload/dimension tables. The Room 7→8 migration is additive and preserves V1 rows and their legacy meaning. The Android composer removes numeric V2 overall input and captures Primary Experience, Feeling, Context, up to two Vibes, Practical Signals, semantic dimensions, generated/custom title state, Story, Tip, private memory, visibility, and up to six ordered images.

iOS SQLite schema 2 makes the equivalent additive changes: explicit version columns, V2 draft semantics, semantic dimension metadata, and separate V2 pending payload/dimension tables. Schema-1 rows retain version 1. The SwiftUI composer and durable mutation engine use the same V2 contract, six-image cap, ordered durable media, account isolation, retry recovery, and backend-owned numeric compatibility.

### Compatibility, privacy, and security

- V1 create, fingerprinting, queue routing, media limit, and response behavior are unchanged.
- Existing queued V1 rows remain version 1 and are never coerced into V2.
- Existing visibility, private-profile upper bound, symmetric blocking, direct-read, attached-media, and anonymous aggregate rules remain authoritative.
- V2 publication does not change Community aggregate eligibility or mutate legacy Visits.
- Account purge/deletion continues to remove owned drafts, pending payloads, durable media, Visits, sidecars, dimensions, and attachments without crossing account boundaries.
- No infrastructure/storage/database/JWT/signing secret, TLS bypass, release cleartext, presigned-URL logging, exact user-location persistence, or cross-account data path is introduced.

### Validation record and limitations

During implementation on Windows:

- the pre-change Android gate (`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `compileReleaseKotlin`) passed;
- the focused backend `ExperienceWriteServiceTest` suite passed 8 tests with no failures or errors;
- Android `testDebugUnitTest` passed after the native composer and queue changes;
- Android V2 instrumentation sources, including Room 7→8 migration and pending-payload coverage, compiled successfully;
- local backend `mvn verify` compiled and ran non-container tests, but its 25 Testcontainers-dependent errors were solely caused by the unavailable local Docker daemon;
- Localizable string-catalog JSON parsing passed;
- Xcode and XCTest are unavailable on Windows, so authoritative Swift compilation and XCTest execution require the pushed Xcode Cloud Build and Test workflow.

Connected Android execution, authoritative PostgreSQL/Testcontainers coverage, and iOS Build/Test results are reported only when actually run. Milestone 3 Experience-first read surfaces and navigation remain intentionally deferred.

## Milestone 3: Experience-First Read Surfaces & Navigation

Milestone 3 makes Experience the primary read unit while preserving `Visit` as the content identity and compatibility root. It adds no schema migration, does not replace V1 APIs, does not add planned-Experience relations, and does not reinterpret legacy rows.

### Feed API and lens definitions

`GET /api/v2/experiences/feed` accepts an opaque cursor, bounded page size, deterministic text query, canonical Primary Experience and Vibe filters, and one of four lenses:

- `FOR_YOU` is the default recency feed. Its first algorithm takes the viewer-authorized keyset page and performs deterministic page-local greedy diversity across adjacent author, Place, and Primary Experience values. It never drops a selected item or fabricates affinity.
- `FOLLOWING` requires authentication and selects authors with an approved one-way outgoing follow. This does not weaken `FRIENDS` visibility: a FRIENDS card still requires two approved reciprocal edges.
- `NEARBY` requires validated coordinates and a 100–100,000 meter radius. PostGIS filters and orders by canonical Place geography. Clients request approximate location only after the user selects Nearby; coordinates are request-scoped and are not persisted or logged.
- `POPULAR` orders by the real Milestone 1 Community contribution population for the Place (`PUBLIC` plus `FRIENDS`, excluding `PRIVATE`), followed by Experience recency and UUID. It introduces no likes or synthetic engagement score.

All lenses apply profile privacy, Experience visibility, ownership, mutual-Friend, and symmetric-block authorization in SQL before `LIMIT`. Anonymous access is restricted to PUBLIC Experiences from public profiles. The URL-safe cursor binds version, query scope, snapshot time, ranking value, creation time, and UUID so pages remain dense, stable, and non-overlapping.

The bounded deterministic search covers Place name, persisted title/Story metadata, raw Experience labels, canonical code text, and explicit EN/TR aliases such as sunset, breakfast, and nature walk. It does not claim semantic or AI search.

### Experience summary DTO and card hierarchy

`ExperienceSummaryV2Response` contains only card-safe data: Experience identity/classification; author identity/avatar and viewer relationship; Place identity/location/category/cover and optional distance; experienced date; persisted/compatibility title and source; Primary Experience; Feeling; bounded Story and Tip previews; Companion and Time; Vibes; at most two Practical Signals; one media preview; total media count; and visibility. It contains no private memory, dimensions, contact metadata, follower totals, or full Place detail.

Cards lead with media (or a typographic no-media treatment), then author/relationship, title, Place, date/distance, Story/Tip preview, Feeling/context, media count, and a graceful legacy marker. `UNKNOWN_LEGACY` is presented generically and never shown as an internal code. The native Android and iOS opening interaction briefly raises and scales the card while navigation begins immediately; Android honors disabled system animators and iOS explicitly honors Reduce Motion. Detail navigation does not wait for animation completion.

The detail surface reads `GET /api/v2/experiences/{id}`, renders the complete authorized Story, Tip, context, practical signals, Place link, and ordered media. Zero images retain a typographic hierarchy, one image is a single hero, two through six are browsable galleries, and legacy media above six remains readable without API/persistence truncation. Existing managed-media authorization and short-lived URL renewal remain in use.

### Place and profile reads

`GET /api/v2/places/{placeId}/experiences` provides viewer-authorized cursor pagination and an optional canonical Primary Experience filter. `GET /api/v2/users/{userId}/experiences` provides the same dense authorization and owner semantics for profile content. The V2 Place aggregate now includes viewer-visible Primary Experience distribution alongside the already separate global Community Feeling, dimension, Practical Signal, visible-count, and contribution-count concepts.

Place screens put Experience count/distribution, Feeling, dimensions, Practical Signals, and the Experience feed ahead of secondary traditional Place details while preserving Save and Map actions. Profile makes `Deneyimlerim` / Experiences the first content section and keeps the existing Places, Lists, Map, Trips, identity, and social behavior. `Ben de Yaşadım` remains deferred and no dead action is displayed.

### Explore, root navigation, and Planım

Explore is now an Experience feed with For You, Following, Nearby, and Popular lenses, debounced deterministic search, compact canonical discovery chips, cursor pagination, retry/empty/loading states, and stale-response generation guards. The root order on both clients is:

`Keşfet | Harita | + | Planım | Profil`

The central `+` continues to the existing working V2 composer; Activity is no longer a root destination, while V1 Activity APIs remain unchanged. Planım is a real transitional root containing the existing Saved Places and Place Collections flows. It does not advertise Experience planning or mixed Experience Collections. Map remains Place-marker based and preserves its existing camera, bounds, nearby, category, Saved, Visited, friend enrichment, and navigation behavior; no unbounded per-marker Experience work or architecture rewrite was introduced.

### Mobile implementation and design system

Android adds shared feed/detail models and mappers, an Experience repository/gateway, StateFlow controllers for Explore/Place/Profile/detail, reusable Compose cards and context/media components, the Planım segmented destination, and native navigation. Cursor append de-duplicates by Experience UUID, lens/search generations reject stale responses, and managed detail URLs renew near expiry.

iOS adds equivalent Codable models, endpoint/service abstractions, `@Observable` controllers, SwiftUI card/detail/gallery surfaces, Place/Profile integration, a composer-backed central Add tab, and native `NavigationStack` routing. The Xcode project explicitly includes the new sources, tests, and localized approximate-location usage text.

Both clients use semantic background, surface, soft-surface, primary, primary-soft, text, border, success, warning, and error roles with the current off-white/white/mist-blue/cool-blue-gray direction in Light and Dark appearances. New static UI and accessibility strings are localized in English and Turkish; taxonomy wire codes remain language-independent.

### Performance and privacy

Feed rows are authorized and keyset-paged in one SQL selection. Page hydration batches Visit/author/Place, sidecar, Vibe, Practical Signal, managed-media descriptor, and relationship reads; clients do not issue per-card author, Place, relationship, or media-access calls and do not preload full details. Mobile lists use lazy containers. Client-side filtering is presentation-only for already-authorized Place Primary Experience selections and never repairs backend privacy.

No new response contains `privateMemory`; no exact location is persisted; no token, signed URL, or coordinates are logged; no TLS or cleartext-release exception is added. Profile privacy remains an upper bound, block checks remain symmetric, Follow remains directed, Friend remains reciprocal, media authorization remains attached to the central viewer policy, and account-scoped persisted state is unchanged.

### Validation record and limitations

During implementation on Windows:

- the pre-change Android gate (`testDebugUnitTest`, `lintDebug`, `assembleDebug`, `compileReleaseKotlin`) passed;
- 21 focused backend Experience read, relationship, and Place aggregate unit tests passed, and backend packaging including test compilation passed;
- local `mvn verify` reached 120 tests with zero assertion failures, while 26 Testcontainers-dependent test classes errored solely because Docker was unavailable;
- Android focused feed/mapping tests and the complete 228-test `testDebugUnitTest` suite passed;
- the final Android `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `compileReleaseKotlin` gate passed;
- the iOS String Catalog parses as valid JSON and the Xcode project has balanced object structure with all new source/test/resource references;
- Xcode/Swift/XCTest is unavailable on Windows, so authoritative Swift compilation and the four new deterministic Experience discovery tests require Xcode Cloud;
- connected Android acceptance is attempted only when a configured device/emulator is available and is otherwise reported `NOT RUN`.

Authoritative Backend CI, Android CI, and Xcode Cloud status are never inferred from local execution. Milestone 4 remains deferred: Planım Experiences and `Ben de Yaşadım` are not implemented by this milestone.

## Milestone 3.5: Visual System & Core UX Polish

Milestone 3.5 is a bounded mobile presentation pass over the completed Milestone 3 product. It adds no backend, schema, Flyway, API, identity, privacy, aggregate, media-ownership, offline-publication, or idempotency change and does not begin Milestone 4.

### Semantic visual system and navigation

Android and iOS now share the mist/air/sea light direction and deep-ocean dark direction through centralized semantic tokens: background, surface, soft surface, primary, strong primary, primary-soft/selected surface, primary and secondary text, muted text, border, divider, and the existing semantic status roles. Bottom navigation retains Explore, Map, Add, My Plan, and Profile while replacing dominant cobalt treatment with the softer selected surface and sky-blue action role.

Explore gives the For You, Following, Nearby, and Popular lenses top-level segmented emphasis while smaller discovery chips remain secondary. Search and vertical spacing are compact enough for the feed to lead the viewport. Following has a specific explanatory empty state; Nearby retains its approximate-location fallback. Planım and Map preserve their existing Place behavior while presenting the locked legacy 9+ range as `Loved` / `Bayıldım` rather than a numeric threshold. Profile receives only the shared token/localization treatment and remains intentionally transitional.

### Experience and Place presentation

Experience Cards lead with photo or a deliberate mist no-media composition, followed by author/relationship, strong Experience title, Story, semantic Feeling, at most two compact context indicators, optional Tip, Place, and a restrained `View details` / `Detayı gör` affordance. Follow is smaller and lower contrast while retaining its existing relationship semantics and accessible target. Stable taxonomy, Feeling, context, vibe, practical-signal, and dimension codes use centralized English/Turkish display mappings. `UNKNOWN_LEGACY` is never rendered to users, and read-time titles equal to the Place name are presentation-deduplicated without mutating persisted data.

Experience Detail mirrors the card hierarchy: hero, author, title, Feeling/core context, Story, Tip, secondary details, and a soft `About the place` section. Place Page now says `N experiences` and `Based on N community evaluations`, omits unknown legacy filters, uses semantic Feeling distribution, presents native dimension states semantically, and places legacy numeric-only aggregates under a neutral `Past ratings` meter with raw values de-emphasized. The working creation action is `Share experience` / `Deneyimini paylaş` or `Continue draft` / `Taslağa devam et` when applicable.

### Composer and localization

The default composer now concentrates on Primary Experience, Feeling, and concise companion/time context. `Enrich your experience` / `Deneyimini zenginleştir` progressively reveals vibes, practical signals, and optional dimensions. Each dimension is one compact row with a native menu instead of five repeated horizontal choices, preserving accessibility at 120% font scale. `Tell your story` / `Hikâyeni anlat` owns photos, Story, Tip, and optional title customization. Private Memory is a separate lock-marked owner-only card. All visible creation copy uses Experience terminology and the final action is `Share experience` / `Deneyimi paylaş`.

Android app-locale changes persist through AppCompat and recompose localized resources; connected English and Turkish runs confirm the switch. iOS adds a persisted System/English/Turkish selection, injects the selected locale at the root, and resolves dynamic display mappings against that locale. The String Catalog remains the source for static localized SwiftUI copy.

### Accessibility and validation record

- The final Android local gate passed `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `compileReleaseKotlin`; 232 unit tests passed with zero failures, errors, or skips.
- All 102 production connected Android tests passed on the Pixel_7 API 35 AVD across bounded class runs: 53 UI tests and 49 repository/database/migration/media/sync tests. The monolithic runner was not used as the acceptance result because this API 35 x86_64/16K AVD encountered a native Compose/JIT process crash after a long serial run; the affected classes passed when isolated.
- Deterministic screenshot capture passed for light, dark, 120% font-scale, English, and Turkish scenarios. The external package contains 28 individual PNGs, a manifest, contact sheet, and ZIP.
- Critical choices do not clip at 120%; dimension selection remains compact; discovery chips remain horizontally scrollable; card-open, Follow, and primary actions retain accessible semantics and targets. Existing motion-aware card behavior continues to honor disabled/reduced system animation.
- The iOS String Catalog parses as valid JSON with 423 keys; Swift and Xcode project delimiters are balanced, and this milestone adds no source file requiring a project-reference change.
- Swift/Xcode/XCTest are unavailable on Windows. Authoritative iOS compilation, XCTest, and Xcode Cloud Build/Test remain not run and must be reported as such rather than inferred.
- Remote Android CI was not run from this Windows workspace; the results above are local Gradle and connected-device results.

The design-review package is stored outside Git under `C:\Users\Emir\Documents\Phokarta_Design_Review\Milestone_3_5\20260917_161036`. Human review of that package is the next gate; Milestone 4 must not start automatically.

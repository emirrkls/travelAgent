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

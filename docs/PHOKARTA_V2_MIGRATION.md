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

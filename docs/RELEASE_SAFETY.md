# Release safety

Closed-beta user-safety baseline: **blocking** and **abuse reporting**. This is not a moderation console. Reports do not hide content, ban users, or notify the reported person.

This document describes product behavior. It is not a legal policy.

## Block model

`user_blocks` stores a **directed** row: `blocker_user_id` blocked `blocked_user_id`. Mirrored rows are not created.

Product visibility is a **symmetric barrier**. If a block exists in either direction, the two accounts are block-separated:

- neither can see the other's profile or user-authored content on authenticated surfaces
- neither can follow the other
- existing follow edges are deleted in the same transaction as the block
- they are no longer mutual friends
- neither appears in the other's search, discovery, followers/following/friends lists, activity, or reviews
- neither contributes to the other's friend metrics (score, visited count, map badges, saved-place friend signals)
- authenticated media access is denied even for PUBLIC attached media

Unblock deletes only the block row. Follows are **not** restored.

## Block transaction

`PUT /api/v1/me/blocks/{userId}` uses the authenticated principal as blocker.

1. Validate target exists (404 if not).
2. Reject self (`400 CANNOT_BLOCK_SELF`).
3. Insert the row idempotently (`ON CONFLICT DO NOTHING`).
4. Delete follow edges in both directions in the same database transaction.
5. Commit. No object-storage I/O.

Repeated PUT is success with one row. Repeated DELETE is success with no row.

Rate limit: 60 block/unblock operations per user per hour.

## Block visibility matrix

| Surface | Anonymous | Authenticated unrelated | Authenticated block-separated |
|---|---|---|---|
| Public profile | Visible if the current public profile API allows it | Normal | **404** (no name/avatar/bio/counts) |
| User search | Not personalized; PUBLIC identities may appear | Blocked accounts omitted in SQL | Omitted both directions |
| Follow | Auth required | Allowed | `409 BLOCKED_RELATIONSHIP` — client copy is generic (“This action isn't available.”). Do not say who blocked whom. |
| Friends Activity | N/A (auth) | Mutual-friend PUBLIC/FRIENDS visits | Blocked authors excluded in SQL |
| Community Activity | PUBLIC visits | PUBLIC visits minus blocked authors | Blocked authors excluded even if PUBLIC |
| Friends reviews | N/A | Mutual-friend reviews | Blocked authors excluded |
| Community reviews | PUBLIC reviews | PUBLIC reviews minus blocked authors | Blocked authors excluded; **Community score/count unchanged** |
| Direct PUBLIC Visit `GET /api/v1/visits/{id}` | PUBLIC allowed | PUBLIC allowed | **404** |
| Friend metrics / map / saved friend signals | N/A | Mutual friends contribute | Blocked users contribute **0** |
| Attached media signed GET | PUBLIC attached media allowed | Follows Visit visibility | Block barrier **before** visibility; no signed URL |
| Ready unattached media | Owner only | Owner only | Unchanged |
| Collections | PUBLIC collection if the API allows anonymous | PUBLIC / FRIENDS as today | Blocked viewer **404** for others' collections |
| `GET /api/v1/me/blocks` | Auth required | The caller's outbound block list only | Never lists “users who blocked you” |

Anonymous PUBLIC remains globally visible because there is no authenticated viewer identity to associate with a block. Do not force login solely to apply blocks.

## Community aggregate policy

**Block does not change global Community score or rating count.** Those are Place-level content statistics, not personalized lists.

If A blocks B, B's PUBLIC rating still contributes to the Place Community aggregate. A's authenticated Community **list** (reviews/activity identity) omits B.

## Report model

`POST /api/v1/reports` (authenticated).

| Field | Values |
|---|---|
| Target type | `USER`, `VISIT` |
| Reason | `SPAM`, `HARASSMENT`, `HATE_OR_ABUSE`, `SEXUAL_CONTENT`, `VIOLENCE_OR_THREAT`, `IMPERSONATION`, `PRIVACY`, `OTHER` |
| Status | New reports are `OPEN`. Also `REVIEWED`, `ACTIONED`, `DISMISSED` for future moderation. |
| Details | Optional plain text, max 2000 characters |

Server derives ownership. Self-report is `400 CANNOT_REPORT_SELF`. Missing target is `404 REPORT_TARGET_NOT_FOUND`.

Duplicate OPEN report for the same reporter + target returns the existing report (`200`) instead of inserting another OPEN row.

Rate limit: 10 reports per user per hour (`429 REPORT_RATE_LIMITED`).

Reports **do not** hide content, ban accounts, or notify the target. They are moderation records only.

Reporting remains possible after a block (capture the target id first, or report from the blocked-users list via stored id). Block and report are independent APIs; a combined UI is not atomic.

VISIT reporting requires the reporter to be allowed to resolve the Visit: PUBLIC for any authenticated user; FRIENDS if currently visible; PRIVATE is owner-only and therefore not reportable by others.

## Report privacy

- No target-facing report API.
- The reported user is not told who reported them, the reason, details, or status.
- Logs may include `requestId`, `reportId`, `targetType`, and `reason` enum. They must not include details, names, emails, or media URLs.
- Metrics: `phokarta.report.created` (`target_type`, `reason`), `phokarta.block.operation` (`action`, `outcome`), `phokarta.safety.rate_limited` (`action`). No user IDs.

## Report retention

Deliberate safety exception to product deletion:

- `reporter_user_id`, `target_user_id`, and `target_visit_id` are `ON DELETE SET NULL`.
- After reporter or target account/content deletion, the report **row remains** with reason, details, timestamps, `target_type`, and status. Live user/visit linkage is lost.
- Former UUIDs are not copied aside from the nulled FK. Closed-beta moderation may lose target identity after deletion.
- Reporter-entered details may remain after the reporter deletes their account.
- No automated expiration. Retention until a human removes or moderates the row is an operational/legal decision still open.

There is **no public or ordinary-user read API** for reports. Authorized operators inspect OPEN reports through private database access until an admin UI exists. Closed-beta operator steps: [MODERATION_OPERATIONS.md](MODERATION_OPERATIONS.md).

## Moderation operational gap

- No admin dashboard, automated bans, shadow banning, appeals, or content-takedown jobs.
- Large OPEN backlog is an operational concern (`phokarta.report.created` vs human review capacity). No alert integration in this milestone.

## Current limitations

- Anonymous PUBLIC profile, search, Visit, reviews, activity, and attached media remain globally visible.
- Community aggregates are global, so a blocked author's PUBLIC score still affects Place rating math.
- Reports do not change product visibility.
- No “users who blocked you” list (intentional).
- No offline block/report queue; safety boundaries exist only after the server commits.
- Report details are not a full content snapshot.
- Flyway **V12** adds `user_policy_acceptances`. V1–V11 are unchanged. Account deletion cascades those rows; they are not retained as report data.

# iOS v0.7 Social, Friends, and Community Parity

This document records the production backend contract, the Android parity semantics, and the native iOS v0.7 implementation for Social, Friends, and Community feeds and navigation.

---

## 1. Real Backend Contracts

### User & Profile Endpoints
- `GET /api/v1/users/{userId}`:
  - Fetches public profile for a specific user ID.
  - Returns `PublicProfileResponse` containing: `id`, `username`, `displayName`, `bio`, `avatarUrl`, `countryCount`, `cityCount`, `followerCount`, `followingCount`, `friendCount`, and `relationship` (`RelationshipResponse`: `following`, `followedBy`, `friend`, `canMessage`).
  - Strict privacy: Does NOT expose owner-private fields (email, private memories, unaccepted drafts).
  - Returns `404 USER_NOT_FOUND` if absent, `403` if forbidden.
- `GET /api/v1/me/profile`:
  - Returns authenticated owner profile: `id`, `email`, `username`, `displayName`, `bio`, `avatarUrl`, `followerCount`, `followingCount`, `friendCount`.

### Follow & Relationship Endpoints
- `POST /api/v1/users/{userId}/follow`:
  - Idempotent desired-state follow action.
  - Self-follow is rejected with `400 BAD_REQUEST` (`CANNOT_FOLLOW_SELF`).
  - Returns updated `RelationshipResponse` (`following: true`, `friend: followsYou`).
- `DELETE /api/v1/users/{userId}/follow`:
  - Idempotent desired-state unfollow action.
  - Returns updated `RelationshipResponse` (`following: false`, `friend: false`).

### Network Lists Endpoints
- `GET /api/v1/me/followers?page={page}&size={size}`:
  - Returns paginated `PageResponse<UserSummaryResponse>` of users following the current user.
- `GET /api/v1/me/following?page={page}&size={size}`:
  - Returns paginated `PageResponse<UserSummaryResponse>` of users followed by the current user.
- `GET /api/v1/me/friends?page={page}&size={size}`:
  - Returns paginated `PageResponse<UserSummaryResponse>` of mutual follow connections (`isFollowing && followsYou`).
- `GET /api/v1/users/search?query={query}&page={page}&size={size}`:
  - Case-insensitive search on `username` and `displayName`.
  - Self-user is returned by backend if matching; iOS client-side excludes self from search results.

### Activity Feed Endpoints
- `GET /api/v1/activity?scope={community|friends}&page={page}&size={size}`:
  - `scope=community`: Public published visits across all platform travelers, newest first.
  - `scope=friends`: Published visits strictly from mutual friends (`isFollowing && followsYou`).
  - Returns `PageResponse<PublicActivityResponse>`.
  - Structural privacy: DTO includes `id` (visitId), `author` (`id`, `username`, `displayName`, `avatarUrl`), `place` (`id`, `name`, `category`, `city`, `coverImage`), `visitedAt`, `overallScore`, `publicReview`.
  - Strictly excludes `privateMemory`, draft visits, and unconfirmed offline mutations.

---

## 2. Android Parity & Behavioral Alignments

- **Friendship Definition (S1 & S2)**:
  - Friendship is exclusively defined as mutual following (`isFollowing && followsYou`).
  - There are no separate "friend requests" or manual approvals. Following someone who already follows you immediately transitions the relationship to friends.
- **Follow Action UX**:
  - Unfollowed -> Button shows "Follow" (filled primary accent).
  - Following (one-way) -> Button shows "Following" (bordered secondary).
  - Mutual Follow (Friends) -> Button shows "Friends" (bordered secondary with mutual friend status).
  - "Follows you" badge displays when `followsYou == true && !isFollowing`.
- **Feed Segments**:
  - Activity feed features a segmented picker: **Community** (global public) and **Friends** (mutual circle).
  - Empty states distinguish between:
    1. No friends connected yet ("No friends yet — Mutual follows become friends on Phokarta" + Find People CTA).
    2. Friends connected, but no visits published yet ("No friend activity yet").
    3. Community feed empty ("No community activity yet").
- **Profile Navigation**:
  - Tapping an author avatar/name on an Activity card, Review row, or Friends Preview row navigates directly to that user's public profile.
  - User profiles show counter statistics (Followers, Following, Friends) and travel statistics (Visited X countries, Y cities).
  - On own profile, counters navigate to followers/following/friends lists. On other users' profiles, counters are display-only (preserving backend privacy).

---

## 3. iOS Architecture & Implementation

### Models & Endpoints (`Features/Social/`)
- `SocialModels.swift`: `RelationshipState`, `PublicUserProfile`, `OwnerUserProfile`, `UserSummary`, `ActivityEvent`, `ActivityAuthor`, `ActivityPlace`, `ActivityScope`, `SocialListKind`.
- `SocialAPIEndpoints.swift`: Typed endpoints adhering to `APIEndpoint` protocol and `APIJSON` decoders.
- `SocialServices.swift`: `SocialService` and `ActivityService` implementing `SocialServing` and `ActivityServing`.

### Concurrency & State Management
- `SocialStateStore.swift`:
  - `@MainActor` observable store managing account-isolated social state.
  - **Desired-state serial coordinator**: `per-target-user` mutation task chain ensuring rapid intent toggles (`Follow -> Unfollow -> Follow`) merge deterministically to the user's latest intent without race conditions.
  - **Optimistic UI with rollback**: Local overlay immediately updates `isFollowing`, `friend`, and counter deltas. On network error, rolls back cleanly without counter drift.
  - **Generation protection**: Every fetch and mutation increments a generation token; stale background responses arriving out-of-order are dropped.
  - **Friendship change invalidation**: Invokes `onFriendshipChanged` when mutual friendship edges change, causing dependent feeds (Activity Friends feed) to invalidate and refresh.

### Feature Slices & Screens
- `UserProfileScreen.swift` & `UserProfileController.swift`: Public profile viewer and personal profile with travel stats, counter navigation, follow toggle, find people, and logout.
- `ActivityFeedScreen.swift` & `ActivityFeedController.swift`: Dual-scope feed with pagination, visit UUID deduplication, empty states, and author/place drill-downs.
- `SocialListScreen.swift` & `SocialListController.swift`: Paginated followers, following, and friends lists with inline follow/unfollow capability.
- `UserSearchScreen.swift` & `UserSearchController.swift`: Debounced live search excluding current user.
- `ActivityEventCard.swift` & `SocialComponents.swift`: Reusable SwiftUI design system components honoring Phokarta theme and accessibility.
- `AppRouteDestinationView.swift`: Centralized deep-link and navigation destination coordinator.

---

## 4. Invariants Enforcement

| Invariant | Description | Verification |
|-----------|-------------|--------------|
| **S1** | Friend = mutual follow edge | Asserted in `RelationshipState`, unit tests, and feed controller |
| **S2** | Immediate friend state flip on reciprocal follow | Verified in `SocialFollowCoordinatorTests` |
| **S3** | Lists scope: followers/following/friends for `/me/*` only | Enforced in navigation rules & `SocialListController` |
| **S4** | Activity feed pagination & deduplication by visit UUID | Verified in `ActivityFeedControllerTests` |
| **S5** | Optimistic follow with immediate feedback | Verified in `SocialFollowCoordinatorTests` |
| **S6** | Desired-state serial coordination without sleeps | Tested under rapid concurrency in `SocialFollowCoordinatorTests` |
| **S7** | Follow count delta management & rollback protection | Zero drift verified in unit tests |
| **S8** | Stale response protection via generation counters | Verified in feed, profile, and search tests |
| **S9** | Profile navigation from Place Detail & Activity cards | Wired in `PlaceDetailScreen`, `ActivityEventCard`, `AppRouteDestinationView` |
| **S10** | Strict privacy: No `privateMemory` in social feeds | Structurally enforced in `SocialModels.swift` and mirrored in tests |
| **S11** | Pre-ACK visits excluded from social feeds | Enforced by backend contract; offline sync engine publishes only after ACK |
| **S12** | Account isolation on switch / logout | Verified in `SocialAccountIsolationTests` |
| **S13** | Strict Swift 6 concurrency (`complete`, zero warnings) | Verified via Xcode Cloud CI with `SWIFT_STRICT_CONCURRENCY: complete` |
| **S14** | Self-follow rejection | Rejected with `.badRequest` without issuing network mutation |
| **S15** | Out of scope discipline | Block/Report, chat, likes, map markers deferred to subsequent milestones |

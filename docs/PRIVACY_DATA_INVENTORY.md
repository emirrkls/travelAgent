# Privacy data inventory

Factual technical behavior a privacy policy must reflect. **Not a privacy policy.** **LEGAL/OWNER REVIEW REQUIRED.** Do not publish this file as the store policy.

Related: [PLAY_DATA_SAFETY.md](PLAY_DATA_SAFETY.md), [ACCOUNT_DELETION.md](ACCOUNT_DELETION.md), [RELEASE_SAFETY.md](RELEASE_SAFETY.md), [MEDIA_STORAGE.md](MEDIA_STORAGE.md).

## Who the product is

Phokarta is an Android travel/local app (`com.emirrkls.phokarta`) with email/password accounts. Users explore a place catalog, publish Visits (ratings, optional public review, optional private memory, optional photos), save places, make collections, follow others, block, and report.

## Data the backend stores (owned by an account)

- Email, username, display name, optional bio/avatar URL, password hash
- Refresh session hashes (not raw refresh tokens)
- Visits, dimension scores, public review text, private memory
- Saved places, collections
- Follow edges, block edges
- Media metadata and private object-storage keys for uploaded photos

## Data other users may see

Depends on Visit and collection visibility (`PUBLIC` / `FRIENDS` / `PRIVATE`) and on block separation. Anonymous viewers can still see PUBLIC catalog surfaces because a block needs an authenticated viewer. Community place score/count still includes PUBLIC ratings even if two users have blocked each other.

## Device data

- Session: access token, refresh token, user id, email, username, display name, bio, avatar URL in EncryptedSharedPreferences (`phokarta_secure_session`). This file is excluded from Android backup and device transfer rules.
- Onboarding completion in ordinary SharedPreferences (`travel_agent_preferences`)
- Room cache of owner-scoped visits/drafts/saved/collections and a shared place cache
- Optional one-shot location when the user taps my-location on the map
- Photos chosen through the system Photo Picker (no broad media permission). JPEG GPS EXIF is stripped before upload; other metadata is not fully sanitized

## Location

Not background location. Map and Explore work without the permission. Granting location is only for centering/nearby convenience.

## Reports (retention exception)

Abuse reports keep `reason`, optional `details`, timestamps, target type, and status after the reporter or target account is deleted. Foreign keys are set null. This is a deliberate safety exception: not all user-entered text is erased. There is no public report-read API.

## Media after deletion

Object bytes are deleted asynchronously. Outstanding signed GET URLs may work until a short TTL. Durable jobs cover late PUTs.

## Third parties (technical)

- API hosting / PostgreSQL / object storage: service providers the operator chooses
- Google Maps SDK and Play services location on the device
- No analytics or crash SDK in the app
- Play distribution, if used, has Play’s own processes (Vitals, billing none)

Do not invent a “we never share data” claim. Service providers process data to run the product.

## International / legal

This inventory does not choose a legal entity, DPA, GDPR role, or retention schedule. Counsel must do that.

## In-app policy link

Settings does **not** open a privacy URL until a real URL exists. Play still requires an in-app link or policy text at submission.

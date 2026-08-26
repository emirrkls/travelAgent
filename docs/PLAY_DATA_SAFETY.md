# Play Data Safety — technical inventory

Factual mapping of what the Phokarta **code** collects, transmits, and stores. This is not a legal opinion and is not a submitted Play Console form.

**Must be manually reconciled with the current Play Console Data safety questionnaire before submission.**

Encryption in transit: TLS for release API and object-storage signed URLs (HTTPS required). Encryption at rest on device: Android Keystore-backed `EncryptedSharedPreferences` for session tokens. Server-side encryption at rest depends on the future host/provider (not claimed here).

No advertising SDK. No analytics SDK. No crash SDK. Google Maps SDK and Play services location are used on device. Object storage is a **service provider** for user-uploaded Visit photos, not an ads partner.

Third-party vs service-provider labels below are **technical**. Legal categorization needs owner/counsel review.

| Data | Collected on device? | Sent off device? | Stored server-side? | Required or optional? | User-deletable? | Shared with third parties (technical)? | Encrypted in transit? | Purpose (code) |
|---|---|---|---|---|---|---|---|---|
| Account identifiers (user UUID) | Yes (session) | Yes | Yes | Required to use an account | Yes, with account deletion | Service provider (API host). Not an ads SDK | Yes (release HTTPS) | Auth, ownership, social graph |
| Email | Yes | Yes | Yes | Required for password registration | Yes, account deletion | API host only | Yes | Account login identity |
| Username / display name | Yes | Yes | Yes | Required | Yes, account deletion | Visible to other users per profile/Visit visibility | Yes | Identity, social |
| Password | Entered on device; not stored in app prefs as plaintext | Yes, over TLS at register/login/delete | BCrypt hash only | Required for local accounts | Hash deleted with account | API host only | Yes | Authentication |
| Auth tokens (JWT access, opaque refresh) | Yes, EncryptedSharedPreferences | Refresh token sent to API | Refresh **hash** in `refresh_sessions`; JWT is stateless | Required while signed in | Logout / account deletion invalidates | API host only | Yes | Session |
| Approximate / precise location | Optional; requested in-map for “my location” | Place search/nearby may send coordinates the user chose or the one-shot location | Places have coordinates in the catalog; the app does not keep a private location history table | Optional; map/explore work without it | N/A for one-shot device location; catalog coordinates are shared place data | Google Play services location + Maps on device; API if the user searches nearby | Yes for API | Nearby / center map |
| User-generated reviews / Visit public text | Yes | Yes | Yes | Optional | Yes with Visit/account deletion | Other users per visibility | Yes | UGC |
| Private memory | Yes | Yes | Yes | Optional | Yes | **Not** on public review APIs; owner only | Yes | Personal journal |
| Photos (Visit media) | Yes (Photo Picker) | Yes (signed PUT to private bucket) | Object bytes + metadata | Optional | Yes; durable cleanup after account deletion. Issued signed GETs may work until short TTL | S3-compatible storage provider (processing). JPEG GPS EXIF stripped on import | Yes | Visit photos |
| Saved places | Yes (Room cache) | Yes | Yes | Optional | Yes | API host; not shown as a public social product surface | Yes | Want to go |
| Collections | Yes | Yes | Yes | Optional | Yes | Other users if PUBLIC/FRIENDS | Yes | Lists of places |
| Follows / friends / blocks | Yes | Yes | Yes | Optional | Yes (blocks cascade on account deletion) | Other users see follow relationships as implemented; block lists are owner-only | Yes | Social / safety |
| Reports | Yes | Yes | Yes; **retained** after account deletion with FKs nulled (reason/details/status) | Optional | Reporter cannot delete the safety row via product UI | Operators via private DB; not shown to the reported user | Yes | Abuse reporting |
| App activity (Explore/map/navigation) | On device | API calls for catalog, visits, social | Server logs/metrics as configured on host | Required to use those features | Account deletion removes owned product data; logs are operational | No analytics SDK | Yes | App functionality |
| Diagnostics / crash | No third-party crash SDK | None from an SDK in this app | Future host logs only | N/A | N/A | Play Android Vitals if distributed via Play (Play’s own pipeline — confirm in Console) | N/A in app code | Stability (Play, later) |

## Location (Data Safety implication)

The app requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` for an in-context map control. It does **not** request background location. Precise location is not required to browse the map or Explore. If declaring Data Safety location: collected **only** if the user grants permission and uses my-location; approximate may be enough (`PRIORITY_BALANCED_POWER_ACCURACY`). Confirm the Play questionnaire’s “approximate vs precise” wording at submission time.

## Not collected (in current code)

- Contacts, SMS, calendar, microphone, camera hardware, health, financial/payment, ads ID
- `READ_MEDIA_*` / broad storage
- Notification permission / FCM
- Child-directed data programs

## Deletion caveats the form must not hide

- Abuse `reports` rows remain after account deletion (safety exception).
- Already issued object-storage **GET** URLs may work until their short read TTL.
- Late **PUT** uploads are cleaned by durable deletion jobs, not by revoking the PUT URL.

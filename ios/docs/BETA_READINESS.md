# Phokarta iOS — Beta & Release Readiness Audit

## Overview
This document evaluates the readiness of Phokarta iOS for closed beta (TestFlight) and public App Store submission following the completion of milestone v0.9 (Maps + Final Parity).

---

## 1. Release Readiness Classification

### 1.1 BLOCKERS (Required before Public App Store Submission)
1. **Infrastructure & Production Hosting**:
   - Production HTTPS API deployment behind valid TLS certificates.
   - Hosted Postgres with PostGIS extension and automated backup verification drills.
   - Production S3/MinIO bucket with pre-signed upload credentials and lifecycle cleanup.
2. **Legal & Policy Web Endpoints**:
   - Publicly hosted Privacy Policy URL (`https://phokarta.com/privacy`).
   - Publicly hosted Terms of Service URL (`https://phokarta.com/terms`).
   - Publicly hosted Community Guidelines URL (`https://phokarta.com/community-guidelines`).
   - Hosted Account Deletion web page with support request form (Apple Guideline 5.1.1(v)).
3. **Apple Developer & Provisioning**:
   - Production distribution certificate & provisioning profiles.
   - App Store Connect app record configuration and bundle ID registration (`com.emirrkls.phokarta`).
   - Apple Privacy Manifest (`PrivacyInfo.xcprivacy`) declaring API usage reasons for user defaults/file timestamps if required.
   - App Store privacy nutrition labels filled in App Store Connect.

### 1.2 IMPORTANT (Required before Open Beta / TestFlight External)
1. **Interactive Runtime QA on Physical Hardware**:
   - Interactive MapKit manual QA: Pan, pinch, zoom, search this area, pin selection, dark mode.
   - Physical camera & photo library permission flow with real photos (HEIC conversion, GPS EXIF stripping).
   - Real cellular to offline transitions verifying SQLite queue drain.
   - Push notifications / background sync (if enabled in future milestones).
2. **Crash Reporting & Production Telemetry**:
   - Privacy-preserving crash reporting (e.g. Sentry or MetricKit) without coordinate or PII logging.
3. **App Store Visual Assets**:
   - Final production App Icon set (1024x1024 master and all generated asset catalog sizes).
   - 6.7" and 6.5" iPhone screenshots and iPad screenshots showcasing discovery, reviews, and map.

### 1.3 NICE-TO-HAVE (Post-Launch / Fast-Follow)
1. Dynamic map pin clustering for very dense metropolitan areas (current server-side cap of 50-200 places is well within native MapKit performance limits).
2. Offline cached vector map tiles for completely disconnected map browsing.
3. Custom map styling overlays matching dark/light brand palette further.

---

## 2. Technical Feature Completeness (Native iOS)
All client-side native features required for Phokarta iOS product parity are complete:
- [x] v0.1: Authentication, Keychain storage, token refresh coordinator, networking.
- [x] v0.2: Explore catalog, search, category filtering, place details.
- [x] v0.3: Saved / Want to Go, collections management.
- [x] v0.4: Visit creation, category dimension ratings, private memories, public reviews.
- [x] v0.5: Media picker, EXIF GPS stripping, HEIC to JPEG conversion, presigned uploads.
- [x] v0.6: SQLite + WAL persistence, offline visit mutation queue, auto-sync engine.
- [x] v0.7: Social profiles, follows, mutual friends, activity feed, user search.
- [x] v0.8: Safety, user/visit reporting, user blocking, account deletion, UGC policy gate.
- [x] v0.9: Native MapKit place discovery, contextual CoreLocation, Search This Area, filter synchronization.

---

## 3. Next Steps
Following the verification of Xcode Cloud green on milestone v0.9, transition codebase to:
`Phokarta iOS — Beta & Release Hardening`
*(Do NOT automatically submit to App Store Connect or TestFlight).*

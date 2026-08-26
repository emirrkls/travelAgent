# Android release build

How to produce a closed-beta Android App Bundle. This is not a Play Console runbook and contains no secrets.

Application ID / package: `com.emirrkls.phokarta`  
Current closed-beta version: `versionName` `0.6.0-beta.1`, `versionCode` `6`.

## Versioning policy

- `versionCode` is a positive integer. **Every Play upload must increase it.** Never reuse a `versionCode` that was uploaded to any Play track.
- `versionName` is human-readable. Closed-beta format: `0.6.0-beta.N` (example: `0.6.0-beta.1`).
- Git tags (create only when a real beta artifact exists): `v0.6.0-beta.N`.
- Do not encode dates in `versionName`.
- Bump both values in `app/build.gradle.kts` `defaultConfig` before a new Play upload.

## API base URL

Release builds require an absolute `https://` URL with a trailing slash.

```powershell
.\gradlew.bat bundleRelease -PPHOKARTA_API_BASE_URL=https://api.example.invalid/
```

If the property is omitted, release configuration uses the non-production placeholder `https://api.phokarta.invalid/`. That host is not a live backend. A real beta backend URL is an external infrastructure input.

Debug still defaults to `http://10.0.2.2:8080/` and permits cleartext only for `10.0.2.2`, `127.0.0.1`, and `localhost`.

## Signing variables

Do not commit a keystore or passwords. Preferred closed-beta path: a local **upload** keystore, with Play App Signing enabled later in Play Console.

Set all four, or none:

| Variable | Meaning |
|---|---|
| `PHOKARTA_UPLOAD_STORE_FILE` | Path to the upload `.jks` / `.keystore` |
| `PHOKARTA_UPLOAD_STORE_PASSWORD` | Keystore password |
| `PHOKARTA_UPLOAD_KEY_ALIAS` | Key alias |
| `PHOKARTA_UPLOAD_KEY_PASSWORD` | Key password |

Resolution order: environment variable, then Gradle `-P` / `gradle.properties`, then ignored `keystore.properties` in the repo root.

Example `keystore.properties` (gitignored):

```properties
PHOKARTA_UPLOAD_STORE_FILE=C:/keys/phokarta-upload.jks
PHOKARTA_UPLOAD_STORE_PASSWORD=
PHOKARTA_UPLOAD_KEY_ALIAS=upload
PHOKARTA_UPLOAD_KEY_PASSWORD=
```

- All four set and the store file exists: `bundleRelease` is signed with the upload key.
- Some but not all set: Gradle **fails fast**.
- None set: Gradle signs with the **local debug keystore** and prints a warning. The artifact is for **structural verification only** and is **not Play-upload-ready**.

Do not generate a production/upload keystore in this repository.

## Commands

From the repository root, JDK 17:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat compileReleaseKotlin
.\gradlew.bat bundleRelease -PPHOKARTA_API_BASE_URL=https://<beta-api-host>/
```

Output (unsigned/local-debug-signed or upload-signed):

`app/build/outputs/bundle/release/app-release.aab`

## Verify the artifact

SHA-256 (PowerShell):

```powershell
Get-FileHash -Algorithm SHA256 app\build\outputs\bundle\release\app-release.aab
```

Record the hash only for a **final** upload-signed beta AAB. Do not treat a debug-signed AAB hash as a Play artifact identity.

Inspect signing:

```powershell
.\gradlew.bat signingReport
```

Play-upload-ready means: upload keystore configured, `PHOKARTA_API_BASE_URL` is the live HTTPS beta API, and the AAB is signed with that upload key — not the debug keystore.

## Local QA APK (optional)

Prefer the AAB for Play. For device QA only, `assembleRelease` produces an APK at `app/build/outputs/apk/release/`. The same signing rules apply. A universal APK from bundletool is optional and is not required for this milestone.

## Maps key

Put the Android Maps SDK key in ignored `secrets.properties`:

```properties
MAPS_API_KEY=your_android_maps_key
```

`local.defaults.properties` contains only `MAPS_API_KEY=DEFAULT_API_KEY`. Release/debug keys may differ.

Future production/release key restrictions (operator task, not in repo):

- Application restriction: Android apps
- Package name: `com.emirrkls.phokarta`
- SHA-1 and SHA-256 of the **upload** certificate (and the Play App Signing cert once Play Console issues it)
- Enable only the Maps SDK for Android

## R8 / minify

Release `isMinifyEnabled` is **false**. Do not enable R8 solely for closed beta; kotlinx serialization / Hilt / Retrofit shrinking is a later hardening step.

## Cleartext

Release: HTTPS only (`usesCleartextTraffic=false`, main network security config denies cleartext).  
Debug: local emulator/device HTTP to `10.0.2.2` / `127.0.0.1` / `localhost` only.

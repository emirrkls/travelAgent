# Phokarta — Android native prototype

Phokarta is a native Kotlin/Jetpack Compose prototype for social travel discovery, personal travel memory, category-aware ratings, and trusted recommendations.

**Android application identity:** `com.emirrkls.phokarta`

## Run locally

1. Open the repository in Android Studio.
2. Use JDK 17 or the Android Studio bundled runtime.
3. Let Gradle sync and run the `app` configuration on an Android 8.0+ emulator or device.

### Google Maps API key

The Map tab uses Google Maps Compose (`maps-compose` 6.4.1). API credentials are intentionally excluded from version control.

1. Enable **Maps SDK for Android** in a Google Cloud project.
2. Create `secrets.properties` in the repository root (next to `settings.gradle.kts`).
3. Add your key:

   ```properties
   MAPS_API_KEY=your_android_maps_key
   ```

4. Restrict the key to Android apps:
   - **Package name:** `com.emirrkls.phokarta`
   - **Certificate:** debug SHA-1 from `./gradlew signingReport` (Windows: `.\gradlew.bat signingReport`)
   - **API restriction:** Maps SDK for Android
5. Sync Gradle and run the app. A build without `secrets.properties` still succeeds using the non-secret placeholder in `local.defaults.properties`, but Google map tiles will not authenticate.

Both `secrets.properties` and `local.properties` are ignored. Never commit either file or paste a real key into the manifest, Gradle files, or `local.defaults.properties`.

The prototype uses remote demo photography, so `INTERNET` permission is enabled. Place and social discovery content remain mocked; user-generated state is durable and local.

## Architecture

- `core/model`: backend-compatible domain entities. `Place` and `Visit` are intentionally separate.
- `core/data`: `DefaultTravelRepository` combines the static mock place catalog with a Room-backed local user-state source.
- `core/database`: Room v1 entities, normalized relationships, DAOs, domain mappers, and exported schema.
- `core/di`: Hilt bindings for the database, DAOs, data sources, and repository.
- `feature/*`: screens and StateFlow ViewModels organized by product capability. The Map feature owns viewport, selection, and filter state while observing Room-backed user state through `TravelRepository`.
- `ui/components` and `ui/theme`: shared Compose design system.

The Map tab renders the existing mock catalog on Google Maps. Its category, rating, trusted, visited, and Want-to-Go filters are local and deterministic; moving the camera far enough reveals **Search this area**, which applies the visible map bounds without any backend request.

### MapViewModel scope

Bottom navigation uses `popUpTo(startDestination) { saveState = true }` with `restoreState = true`, which removes tab back-stack entries when switching destinations. To preserve map viewport, filters, and selection across tab switches, `MapViewModel` is intentionally scoped to the host `ComponentActivity` via `hiltViewModel(viewModelStoreOwner = LocalActivity.current)`.

This is a deliberate trade-off for the current navigation architecture. Revisit the scope if navigation is refactored to keep Map destinations on the back stack without destroying their ViewModel store.

## Local persistence

Room stores Visits, optional per-Visit rating dimensions, saved/Want-to-Go place IDs, collections, and collection membership. The mock place catalog keeps deterministic IDs and is not copied into the database. Demo user state is inserted only when the database is first created, using stable IDs; social Activity remains mocked. Onboarding completion remains a lightweight application preference.

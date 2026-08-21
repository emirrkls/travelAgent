# Rota — Android native prototype

Rota is a native Kotlin/Jetpack Compose prototype for social travel discovery, personal travel memory, category-aware ratings, and trusted recommendations.

## Run locally

1. Open the repository in Android Studio.
2. Use JDK 17 or the Android Studio bundled runtime.
3. Let Gradle sync and run the `app` configuration on an Android 8.0+ emulator or device.

The prototype uses remote demo photography, so `INTERNET` permission is enabled. Place and social discovery content remain mocked; user-generated state is durable and local.

## Architecture

- `core/model`: backend-compatible domain entities. `Place` and `Visit` are intentionally separate.
- `core/data`: `DefaultTravelRepository` combines the static mock place catalog with a Room-backed local user-state source.
- `core/database`: Room v1 entities, normalized relationships, DAOs, domain mappers, and exported schema.
- `core/di`: Hilt bindings for the database, DAOs, data sources, and repository.
- `feature/*`: screens and StateFlow ViewModels organized by product capability.
- `ui/components` and `ui/theme`: shared Compose design system.

The map screen is a credential-free interactive shell. To enable Google Maps later, add the Maps SDK/API key, implement a map renderer behind the map feature boundary, and keep the existing repository-provided place coordinates as its input.

## Local persistence

Room stores Visits, optional per-Visit rating dimensions, saved/Want-to-Go place IDs, collections, and collection membership. The mock place catalog keeps deterministic IDs and is not copied into the database. Demo user state is inserted only when the database is first created, using stable IDs; social Activity remains mocked. Onboarding completion remains a lightweight application preference.

# Rota — Android native prototype

Rota is a native Kotlin/Jetpack Compose prototype for social travel discovery, personal travel memory, category-aware ratings, and trusted recommendations.

## Run locally

1. Open the repository in Android Studio.
2. Use JDK 17 or the Android Studio bundled runtime.
3. Let Gradle sync and run the `app` configuration on an Android 8.0+ emulator or device.

The prototype uses remote demo photography, so `INTERNET` permission is enabled. All product data and state changes are otherwise local and backed by `MockTravelRepository`.

## Architecture

- `core/model`: backend-compatible domain entities. `Place` and `Visit` are intentionally separate.
- `core/data`: repository contract, mock implementation, and onboarding preference store.
- `core/di`: Hilt bindings. A future remote repository can replace the mock without changing UI code.
- `feature/*`: screens and StateFlow ViewModels organized by product capability.
- `ui/components` and `ui/theme`: shared Compose design system.

The map screen is a credential-free interactive shell. To enable Google Maps later, add the Maps SDK/API key, implement a map renderer behind the map feature boundary, and keep the existing repository-provided place coordinates as its input.

# Local-First Personal Media Hub

A privacy-focused media tracker for Android. Books today; movies and TV are planned.

Local-first in the strict sense: a single local SQLite database, no accounts, no cloud sync, no
analytics, and no crash-reporting SDK. Nothing about your library leaves the device unless you
explicitly export it yourself. That is a design constraint the codebase is held to, not a
marketing line — Android's own Auto Backup is deliberately turned off for app data, and the
logging facility is documented to never record what you are reading.

## Status

Pre-1.0 and semi-functional: usable for tracking real reading, still gaining features. Versioning
is `0.y.z` with roughly one minor release per completed task; `1.0.0` lands when the app is
comfortably daily-drivable.

**For what actually shipped and when, read [`CHANGELOG.md`](CHANGELOG.md).** It is kept current per
release and is the single source of truth for feature history — this file deliberately does not
duplicate a feature list, because that is the part that would rot.

## Build and run

Requires JDK 21 and a recent Android Studio. No API keys or local configuration are needed — the
metadata providers this app uses (Open Library, Google Books) require none.

```bash
./gradlew :app:assembleDebug        # build the APK
./gradlew :app:installDebug         # build and install onto a connected device/emulator
```

- `minSdk` 28, `targetSdk` 36, Kotlin JVM target 11, Gradle toolchain 21.
- The app version lives only in `[versions] app` in `gradle/libs.versions.toml`; `versionCode` is
  derived from it. Never hand-edit `versionCode`.

## Tests

```bash
./gradlew :shared:jvmTest :shared:testDebugUnitTest
```

Compose screens have instrumented tests, which need a connected device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

That run **uninstalls the app when it finishes**, so to get a working debug build back with sample
data in it:

```bash
./gradlew :app:seedDebugDevice
```

**Not `./gradlew test`** — that omits `:shared:jvmTest`, which is the authoritative run for the
data layer. Every Room-touching test is excluded from the Android unit-test variants, so
`./gradlew test` passes while skipping them entirely. See AGENTS.md §7 and the comment block in
`shared/build.gradle.kts`.

## Layout

Two Gradle modules:

- **`shared/`** — Kotlin Multiplatform. All business logic: Room database and migrations, Ktor
  networking, content-addressed cover storage, use cases, and the shared ViewModels. Targets
  Android and JVM (the JVM target exists so the data layer is testable without a device).
- **`app/`** — the Android application: Jetpack Compose screens, navigation, and the entry point.
  No business logic.

Dependency injection is a hand-rolled composition root (`AppContainer`), not Hilt or Koin.

## Documentation

| File | What it is for |
| :--- | :--- |
| [`CHANGELOG.md`](CHANGELOG.md) | What shipped, per release. Keep a Changelog format. |
| [`ROADMAP.md`](ROADMAP.md) | What is planned and, more importantly, *why* — including decisions deliberately made and rejected. |
| [`AGENTS.md`](AGENTS.md) | Architectural rules and coding standards. Binding on AI agents working in this repo, and the best short description of how the codebase is meant to fit together. |

ROADMAP is unusually detailed on rationale by design: it records rejected alternatives so they are
not silently revisited later.

## License

None yet — all rights reserved by default until one is chosen.

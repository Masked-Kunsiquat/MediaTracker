# Changelog

All notable changes to the Local-First Personal Media Hub will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Compose UI screens & navigation** (Task3 Phase C, `app/.../ui/`): `LibraryScreen` (stateless
  card list, empty-state message, FloatingActionButton to add book, long-press/icon delete with
  confirmation dialog wired to ViewModel) and `AddBookScreen` (OutlinedTextField for ISBN,
  Loading/Error/Success state rendering, back navigation via TopAppBar icon, success triggers
  LaunchedEffect to navigate to library and reset ViewModel). `CoverImage` off-thread composable
  using `produceState` on `Dispatchers.IO` to load images from disk via `BitmapFactory.decodeFile`,
  falling back to a book-icon placeholder on decode failure or null hash. Stateless/stateful split
  honors AGENTS.md §5 (each screen has a route-level wrapper connecting ViewModel + navigation
  callbacks, plus a @Preview-able stateless composable accepting state + callbacks). Navigation
  via navigation-compose `NavHost` with sealed `Route` type-safe destinations (library start,
  add_book), routes in `AppNavigation.kt`.
- **`MediaTrackerApplication`**: Application subclass holding lazily-created `AppContainer` on
  first access; retrieved by `MainActivity` via `(application as MediaTrackerApplication).appContainer`.
- **ViewModel factories**: `LibraryViewModelFactory` and `AddBookViewModelFactory` (AGENTS.md §5 —
  manual DI, no framework) wiring dependencies from `AppContainer` to ViewModel constructors.

### Changed

- **`MainActivity` wired to navigation + dependency injection**: Now reads `AppContainer` from
  the Application instance, passes to `MediaTrackerApp` which constructs a `NavController` and
  calls `AppNavigation` to set up the graph. Removed placeholder screen; all real navigation
  happens here.
- **App module converted from Java template to Kotlin + Jetpack Compose (Material 3) shell**
  (Task3 Phase A): `app` now applies `org.jetbrains.kotlin.android` +
  `org.jetbrains.kotlin.plugin.compose`, adds a Compose BOM-managed dependency set
  (Compose UI, Material 3, activity-compose, lifecycle-runtime-compose,
  navigation-compose — navigation wired into the catalog now for Phase C), and drops the
  AppCompat/Material XML dependencies. `MainActivity` is a `ComponentActivity` rendering a
  minimal `MediaTrackerTheme` (dynamic color on Android 12+, light/dark) placeholder screen;
  the manifest theme is a bare framework `android:Theme.Material...NoActionBar` bootstrap
  style with all real theming done in Compose. No feature screens or navigation graph yet —
  shell only.

## [0.1.0] - 2026-08-01

First tagged milestone: complete data foundation — database, storage, networking, and the
end-to-end book ingestion pipeline. No UI yet. **Room schema v1 is frozen as of this
release**; all future schema changes require a version bump plus a tested migration.

### Added

- **Book ingestion pipeline** (`AddBookByIsbnUseCase`): ISBN in, fully persisted book out —
  normalizes/validates ISBN-10/13, fetches metadata, downloads the cover, stores it
  content-addressed, and inserts all rows in one atomic transaction. Cover failures degrade
  gracefully (book saved without cover). (`602006d`)
- `coverImageHash` column on `MediaItemEntity` for content-addressed cover lookup. (`602006d`)
- **Networking layer**: engine-injectable Ktor `HttpClient` factory (lenient JSON, 15s
  timeouts); Open Library client (primary) with failure-tolerant author sub-fetches;
  Google Books client (keyless fallback); provider fallback chain; cover image downloader.
  16 MockEngine tests — no test ever hits the real network. (`06d7b6b`)
- **Repository layer**: sealed `Resource` result wrapper; `BookRepository` with atomic
  `addBook` (`@Transaction` DAO, verified by a forced mid-transaction rollback test) and
  Flow-based reads; `ReadingSessionRepository` with timestamp/duration validation. (`81c0fcb`)
- **Content-addressable image storage** (`LocalImageStorageManager`): SHA-256-hashed
  `<hash>.jpg` files with automatic deduplication; known-vector and corrupt-input tests. (`c314081`)
- **Database foundation**: polymorphic Room KMP schema v1 — `MediaItems` universal table
  with `BookDetails`, `ExternalIdentifiers` (composite key), and `ReadingSessions` child
  tables; UUID string primary keys, FK cascade deletes, Flow-based DAOs, exported schema,
  in-memory DAO test suite. (`5eb8250`)
- **Project foundation**: Kotlin Multiplatform `shared` module (Android + JVM targets)
  wired to the Android app; version catalog with Kotlin 2.2.21, Room KMP 2.8.4, Ktor 3.5.1,
  kotlinx serialization/coroutines/datetime. (`a22d87c`)

### Known issues / tech debt

- AGP 9 workaround: `android.builtInKotlin=false` + `android.newDsl=false` are required
  until KSP supports the new `com.android.kotlin.multiplatform.library` plugin
  (google/ksp#2476); both flags are removed in AGP 10.
- Room-touching tests run only on `:shared:jvmTest` (no Robolectric); the Android
  unit-test variant excludes them by package filter.
- `BookFormat` defaults to `PHYSICAL` on ingestion — ISBN metadata does not reliably
  expose edition format; needs a user-facing correction flow later.

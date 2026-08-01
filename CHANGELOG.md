# Changelog

All notable changes to the Local-First Personal Media Hub will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`ReadingTimer`** (`shared/.../features/books/timer/`, Task4 Phase A): coroutine/Flow-based
  reading stopwatch — `start()`/`pause()`/`resume()`/`stop()`, a `StateFlow<Long>` of elapsed
  seconds ticking ~1/second while running, and a `StateFlow<ReadingTimerState>`
  (Idle/Running/Paused) for UI. `stop()` returns a `ReadingTimerResult` (timestampStart,
  timestampEnd, durationSeconds) with duration accumulated by counting ticks rather than
  subtracting clock readings, so paused time is never counted and the class is exactly
  reproducible under `kotlinx-coroutines-test` virtual time. Invalid state transitions (e.g.
  `stop()` while Idle) throw `IllegalStateException`. Pure common Kotlin — injects a
  `kotlin.time.Clock` and an external `CoroutineScope`, no Android dependency.
- **`LogReadingSessionUseCase`** (`shared/.../features/books/domain/`): connects a finished
  `ReadingTimerResult` (or explicit start/end/duration bounds, for manual session entry) plus
  page/percent position bounds to `ReadingSessionRepository.logSession`. Validates that
  `startUnit`/`endUnit` are non-negative; `endUnit < startUnit` is allowed (flipping back to
  reread a chapter is a legitimate input, not an error). Returns `Resource<String>` per the
  existing use-case convention.
- 14 virtual-time tests for `ReadingTimer` (ticking, pause/resume, stop result, 0-second
  sessions, invalid-transition contract, reuse after stop) and 7 tests for
  `LogReadingSessionUseCase` (happy path via both overloads, negative-unit validation,
  endUnit-less-than-startUnit allowed, 0-second/0-page edge cases, propagated repository
  validation errors).
- **`BookDetailViewModel` + `BookDetailUiState`** (`shared/.../ui/`, Task4 Phase B): drives the
  (not-yet-built) book-detail screen. `uiState` combines `BookRepository.observeBookDetail`
  (new: book metadata + `BookDetailsEntity`, reactively) with
  `ReadingSessionRepository.observeSessionsForMedia` plus in-memory UI-only state into a single
  `StateFlow<BookDetailUiState>` (`Loading`/`NotFound`/`Ready`). `Ready.currentProgress` derives
  the latest session's `endUnit` on read (never stored, so it can't drift via `copy()`); `Ready`
  also carries a `pendingSession` (a finished timer run awaiting user-entered position bounds)
  and an `errorMessage`, chosen over separate `StateFlow`s so the screen always renders from one
  state object. Owns a `ReadingTimer` on `viewModelScope`; `startReading`/`pauseReading`/
  `resumeReading`/`stopReading` gate on the timer's current state first so a UI double-fire
  no-ops instead of hitting `ReadingTimer`'s throw-on-bad-transition contract. `saveSession`
  persists the pending timer result via `LogReadingSessionUseCase`, clearing it on success and
  keeping it (with `errorMessage` set) on validation failure so the user can retry without
  re-timing; `logManualSession` is the explicit-bounds path with no timer involved;
  `discardPendingSession` abandons a pending result; `deleteSession` removes a logged session.
- `BookRepository.observeBookDetail`: reactive `MediaItemEntity` + `BookDetailsEntity` combo
  (as `BookWithDetails`), composed from two existing DAO Flows via `combine` — no DAO/entity
  changes (Room schema v1 stays frozen).
- `AppContainer` now also wires `LogReadingSessionUseCase`, consumed by `BookDetailViewModel`.
- 10 tests for `BookDetailViewModel` against a real in-memory `AppDatabase` (Loading->Ready with
  derived progress, unknown-id NotFound, timer start/stop producing a pending session, save
  success clearing it, save validation failure keeping it, the no-pending no-op case, manual
  session logging, session deletion, and double-fire guards on every timer action never
  throwing).
- **Book Detail screen** (Task4 Phase C, `app/.../ui/screens/BookDetailScreen.kt`): tap a library
  card to open cover + metadata, a start/pause/resume/stop reading timer, manual session entry,
  and session history for a single book. Route-level `BookDetailScreenRoute` wires
  `BookDetailViewModel` (via the new `BookDetailViewModelFactory`) to the stateless
  `BookDetailScreen`, auto-navigating back via `LaunchedEffect` if the book is deleted while the
  screen is open (`BookDetailUiState.NotFound`). Header shows page-style ("Page 142 / 350") or
  percent-style ("37%") current progress depending on whether `BookDetailsEntity.totalPages` is
  known. The timer card's buttons are gated by `ReadingTimerState` (Idle/Running/Paused). A
  finished timer run (`pendingSession`) opens a save dialog whose visibility is entirely
  state-driven (`pendingSession != null`): a validation failure keeps it open with
  `errorMessage` displayed for retry-without-re-timing, a success clears `pendingSession` and the
  dialog closes itself — no local dismiss logic needed. Manual entry is a separate dialog with a
  duration-minutes field plus a session date/end-time selection (Material 3 `DatePickerDialog`
  for the date, digit-filtered hour/minute fields for the time — see Task4 Phase D below);
  `timestampEnd` is derived from that selection and `timestampStart = timestampEnd - duration`.
  Position/duration/pages fields are digit-and-decimal-point filtered so a negative value (the
  one thing `LogReadingSessionUseCase` rejects) can't be typed. Session history lists most-recent-
  first with a delete icon per row and a confirmation dialog matching `LibraryScreen`'s pattern.
  Dates render via `java.time` (not `kotlinx-datetime`, which isn't exposed to this Android-only
  app module) since `java.time` is available unconditionally at `minSdk 28`. Previews cover
  Ready-with-sessions, Ready-with-pending-session, and Loading.
- **Navigation**: `Route.BookDetail` (`book_detail/{bookId}`) with a `createRoute(bookId)` helper;
  wired into `AppNavigation`'s `NavHost` with a `navArgument`-typed `bookId`.
- **`BookDetailViewModelFactory`** (`ui/ViewModelFactories.kt`): per-navigation-argument factory
  (constructed fresh per book detail route, unlike the reused `LibraryViewModelFactory`/
  `AddBookViewModelFactory`), wiring `BookDetailViewModel`'s repository/use-case dependencies
  from `AppContainer` plus the route's `bookId`.
- `delete_session_content_description` string resource for the session-row delete icon.

### Changed

- **`LibraryScreen`**: cards are now tappable (outside the delete button) to navigate to the new
  book detail screen, via `Modifier.clickable` on the card row. `LibraryScreenRoute` gains an
  `onNavigateToBookDetail: (String) -> Unit` parameter threaded through to a new `onBookClick`
  callback on the stateless `LibraryScreen`; the existing delete-button confirmation-dialog flow
  is unchanged.

### Fixed

- **Field-level cover fallback** (Task4 Phase D, `shared/.../features/books/network/`
  `FallbackBookMetadataProvider`): Open Library edition records can succeed with `covers: null`
  (e.g. ISBN 9798217298976 / edition OL61570965M), and previously the secondary provider
  (Google Books) was only ever consulted after a primary *failure*, so a coverless-but-otherwise-
  valid primary success meant no cover was ever fetched. Now, a primary success with a null
  `coverImageUrl` also probes the secondary as a cover-only lookup: a secondary success with a
  cover is merged in (primary's `BookMetadata` `.copy()`'d with only `coverImageUrl` replaced —
  every other field still wins from the primary); a secondary error or another coverless result
  leaves the primary's result unchanged. A cover is a nice-to-have and this probe can never turn
  a primary success into a failure. 4 new tests added to `FallbackBookMetadataProviderTest`
  covering the merge, no-op, and secondary-error branches, alongside the existing (renamed)
  primary-success-with-cover "secondary never called" case.
- **Manual session entry backdating** (Task4 Phase D, `app/.../ui/screens/BookDetailScreen.kt`
  `ManualSessionDialog`): the manual-entry dialog now has a session date field (Material 3
  `DatePickerDialog`) and hour/minute end-time fields, both defaulting to today/now so the
  zero-extra-tap "I just finished reading" flow is unchanged, but a session can now be backdated
  to a past date/time instead of always timestamping at the moment Save is pressed.
  `onLogManualSession`/`ManualSessionDialog.onSave` gain a `timestampEnd: Instant` parameter
  derived from the selection via the same `java.time`-based local-timezone conversion already
  used for session-history date display; `timestampStart` is still `timestampEnd - duration`. No
  shared-module or ViewModel changes were needed — `logManualSession` already accepted explicit
  timestamps.

## [0.2.0] - 2026-08-01

First usable UI milestone: the app is now interactive end-to-end — browse the library,
add a book by ISBN (with cover), and delete items, all through Jetpack Compose screens.

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

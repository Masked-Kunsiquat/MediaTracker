# Changelog

All notable changes to the Local-First Personal Media Hub will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Edit book metadata** (ROADMAP Task 6 Phase A) — a user-facing correction flow for provider
  metadata that's wrong in a specific edition (the motivating real case: Open Library reports 384
  pages for an edition that physically has 366):
  - `BookFormat` gains `PAPERBACK` and `HARDCOVER` (`shared/.../core/database/entities/BookFormat.kt`).
    `format` is a `TEXT` column and Room's exported schema records only column type/nullability,
    never an enum's set of allowed values, so this required **no schema version bump and no
    migration** — verified by building and confirming `git status --porcelain shared/schemas/` is
    empty. `Converters.bookFormatToName`/`nameToBookFormat` persist by `name` already, so the new
    constants round-trip with no code change there. `PHYSICAL` stays the generic/legacy value —
    ISBN metadata rarely distinguishes binding, so ingestion keeps defaulting to it; existing rows
    and new per-book corrections upgrade to `PAPERBACK`/`HARDCOVER` only via this edit flow.
  - `BookRepository.updateBookMetadata` (`shared/.../features/books/data/BookRepository.kt`):
    atomically updates `media_items` (title/releaseYear/purchasePrice) and `book_details`
    (totalPages/format) in ONE transaction via a new `BookWriteDao.updateBookMetadataAtomically`
    (`@Transaction` default-body method, same pattern as `insertBookAtomically`), rather than two
    sequential awaits. Takes field parameters (not a whole-entity overload) since the two entities
    being merged come from different concerns (universal vs. book-specific); ISBN is intentionally
    NOT a parameter — it's an identity/lookup key, out of scope for this edit surface. Validates:
    blank title rejected; negative purchasePrice rejected; totalPages must be `> 0` when non-null
    (`null` = unknown, valid); releaseYear must fall within new `BookRepository.MIN_RELEASE_YEAR`
    (1450, the Gutenberg-era floor) .. `MAX_RELEASE_YEAR` (2100, a static far-future ceiling chosen
    over a `Clock`-derived "current year + N" so the bound stays deterministic for tests). A book
    with no `BookDetailsEntity` row (the data-integrity edge case documented on
    `observeBookDetail`) self-heals: the `MediaItemEntity` update still applies, and a fresh
    `BookDetailsEntity` is INSERTed with the given format/totalPages and a null isbn, rather than
    silently discarding the input or failing the whole update. 13 new `BookRepositoryTest` cases
    cover the happy path (both tables updated, `observeBookDetail` emits the new values), a null
    `totalPages` update, each validation rejection (persisting nothing), the unknown-id error, and
    the no-existing-`BookDetails`-row self-heal; a forced-mid-transaction-failure test analogous to
    `addBook`'s rollback test wasn't constructible (documented inline) since the two writes share
    no independently-triggerable constraint — atomicity is structural, via the same `@Transaction`
    mechanism `insertBookAtomically` already uses.
  - `EditBookViewModel` + `EditBookUiState` (`shared/.../ui/`): a dedicated ViewModel rather than
    extending `BookDetailViewModel`, since `Route.EditBook` is a separate Compose Navigation
    destination with its own `ViewModelStoreOwner` — sharing one `BookDetailViewModel` (with its
    live `ReadingTimer`) across two destinations would need nav-graph-scoped ViewModel wiring this
    project doesn't otherwise use. `uiState` combines `BookRepository.observeBookDetail` with
    in-memory local state (`errorMessage`/`isSaving`/`saved`) into
    `Loading`/`NotFound`/`Ready`/`Saved`, matching `BookDetailViewModel`'s combine-based shape;
    `save(...)` calls `updateBookMetadata`, has a `saveInFlight` double-tap guard mirroring
    `saveSession`'s, and settles `uiState` into `Saved` for good once a save succeeds (checked
    first in the `combine` lambda, so a later unrelated DB re-emission can't flip it back to
    `Ready`). 9 new `EditBookViewModelTest` cases (Room-backed, excluded from the android
    unit-test variant by exact class name in `shared/build.gradle.kts` alongside
    `BookDetailViewModelTest`/`StatsViewModelTest`).
  - **Edit Book screen** (`app/.../ui/screens/EditBookScreen.kt`): a full screen (five fields plus
    a format picker), not a dialog. Prefilled from the book's current title/release
    year/purchase price/total pages/format; a radio-button group covers all five `BookFormat`
    values via the (now shared, `BookFormatDisplay.kt`) `displayLabel()` extension, exhaustive over
    the enum so a future format addition is a compile error here until a label is added. Each
    optional numeric field is parsed once, above the fields (the same fix `ManualSessionDialog`'s
    duration field already has): blank, unparseable, and out-of-range are kept distinct rather than
    collapsing to a silent `null`, and only the validated value ever reaches `onSave`. Release-year
    bounds are read from `BookRepository.MIN_RELEASE_YEAR`/`MAX_RELEASE_YEAR` rather than a second
    hardcoded copy. Save is disabled while any field is invalid or a save is in flight; a failed
    save surfaces `errorMessage` inline and stays on the form; a successful save navigates back via
    the route wrapper's `LaunchedEffect` on `EditBookUiState.Saved`. Reachable from a new `Edit`
    icon (`Icons.Filled.Edit` — present in `material-icons-core`'s curated set, unlike the missing
    chart glyph noted in v0.4.0) in `BookDetailScreen`'s TopAppBar actions, next to the existing
    delete icon. **Navigation**: `Route.EditBook` (`edit_book/{bookId}`), mirroring
    `Route.BookDetail`'s own-`PATH`-constant pattern as a separate destination; wired into
    `AppNavigation`'s `NavHost` with a `navArgument`-typed `bookId`, and a new
    `EditBookViewModelFactory` (per-navigation-argument, like `BookDetailViewModelFactory`).
    Previews cover Ready (prefilled), an inline error message, and Loading.

- **Session editing** (ROADMAP Task 6 Phase B) — reading sessions were delete-only until now:
  - `ReadingSessionRepository.updateSession(...)` (`shared/.../features/books/data/`): validates
    `timestampEnd >= timestampStart` and `durationSeconds >= 0` (when non-null) — the *exact* same
    two checks, same messages, as `logSession` — because the create and edit paths persist the
    same shape of data and must reject the same inputs for the same reasons. Fetches the existing
    row first and applies the edit via `.copy(...)`, so `mediaId`/`id` can never drift and an
    unresolvable session id returns `Resource.Error` rather than Room's silent-no-op `@Update`
    behavior on a missing primary key.
  - `LogReadingSessionUseCase.executeUpdate(...)` (`shared/.../features/books/domain/`): the
    position-bounds check (finite, non-negative `startUnit`/`endUnit`) is now factored into a
    single private `validatePositions` helper shared by both the create-path `execute` overload and
    `executeUpdate`, so create and edit can never validate positions differently by accident.
  - `BookDetailViewModel.updateSession(...)`: mirrors `logManualSession`'s shape — same
    `saveInFlight` double-tap guard shared with `saveSession`/`logManualSession`, same
    `Resource.Error` → `errorMessage` surfacing, doesn't touch `pendingSession`.
  - App: each session row in history gains an edit `IconButton` (`Icons.Filled.Edit`, next to the
    existing delete icon — mirrors the TopAppBar's Edit/Delete icon pair for the book itself from
    Phase A, kept explicit rather than making the whole row tappable) that opens
    `ManualSessionDialog` prefilled from that row; Save updates the row in place instead of
    inserting a new one. Delete is unchanged.
  - 5 new `ReadingSessionRepositoryTest` cases, 6 new `LogReadingSessionUseCaseTest` cases, and 3
    new `BookDetailViewModelTest` cases cover: happy-path field updates, a rejected edit leaving the
    row completely unchanged, and editing a nonexistent session id.

### Changed

- **Manual-entry redesign** (ROADMAP Task 6 Phase B) — `ManualSessionDialog`/`PendingSessionDialog`
  (`app/.../ui/screens/BookDetailScreen.kt`):
  - **`Pages read` is now auto-derived, not asked for, when tracking by page.** There's no explicit
    "page vs. percent" flag in the data model (schema stays frozen at v2 this phase — see Phase C),
    so the book's `BookDetailsEntity.totalPages` being known is reused as that signal: it's already
    exactly the mode signal `formatProgress` uses elsewhere on this same screen to decide "Page 142
    / 350" vs. a bare percentage, so this keeps one source of truth for "page or percent" instead of
    inventing a second, parallel one (e.g. a per-dialog toggle, or inferring from position magnitude
    — which would misfire for round-number percent-complete values). In page mode, `deltaPages` is
    computed as `endUnit - startUnit` and shown as a read-only "Pages read (auto): N" line instead of
    an editable field; in percent mode (no fixed denominator to derive a page count from) the manual
    field is unchanged, just newly validated (below).
  - **Every numeric field is now parsed once, above the fields, with `isError`/`supportingText` and
    Save-gating** — the same fix already applied to the duration field is now applied to start/end
    position and (percent-mode) pages-read too: a blank-or-overflowing value used to collapse
    silently to `0.0`/`null` via a `?: 0.0` fallback at Save time; it's now rejected with visible
    feedback instead, and Save stays disabled until every field is valid. Blank *optional* fields
    (duration, percent-mode pages-read, notes) remain legitimately `null` — only a non-blank,
    unparseable, or non-finite value is flagged.
  - **Layout grouped into labeled sections** — "When" (date/time), "How long" (duration), and
    "Progress" (positions + pages) — inside the dialog's existing scrollable column; still a
    dialog, not a full screen.
  - All new/changed strings added via string resources (`app/src/main/res/values/strings.xml`).

## [0.4.0] - 2026-08-01

Stats milestone, plus the project's first Room schema migration. Schema v2 makes manual
session durations optional (null = unknown, never a fake zero); the Stats screen shows
weekly/monthly reading time, sessions, pages, and the current reading streak.

### Added

- **Stats layer (ROADMAP Task 5 Phase B)** — reactive reading-statistics queries and shared
  `StatsRepository`/`StatsViewModel`, laid down in `shared/` ahead of the stats screen itself
  (Phase C):
  - `StatsDao` (`shared/.../core/database/dao/StatsDao.kt`), registered on `AppDatabase` with no
    schema/version change (a new DAO does not alter the exported schema, which is derived solely
    from `@Entity` tables — confirmed via `git status` on `shared/schemas/` after building).
    Provides four `Flow`-based aggregate queries over `reading_sessions`, all bucketed by a
    session's `timestampStart` over a half-open `[from, to)` range (a session starting exactly at
    `to` is excluded, at `from` is included): total known duration (`SUM` over non-null
    `durationSeconds`), total session count (all sessions, null-duration included), total pages
    read (`SUM` over non-null `deltaPages`), and raw `timestampStart` values for streak
    computation. SQLite's `SUM()` over zero/all-null matches returns `NULL`, surfaced faithfully
    as `Long?`/`Int?` rather than coerced to `0` — see the DAO's KDoc for the full null-vs-`0`
    rationale.
  - `StatsRepository` (`shared/.../features/stats/data/`) wraps the DAO with the ROADMAP Task 5
    domain semantics: time-read and pages-read totals sum only *known* values (a null
    `durationSeconds`/`deltaPages` contributes nothing to its respective total but still counts
    toward the session count — the entire point of schema v2), and page totals are always summed
    per-session, never inferred from position continuity between sessions ("no gap
    reconciliation"). Adds `observeReadingStreak(timeZone, clock)`: the current consecutive-day
    reading streak, day-bucketed in Kotlin via kotlinx-datetime with an explicitly injected
    `TimeZone` (never SQL/UTC-default — SQLite has no timezone-aware date function), counting
    backward from today; a streak that ran through yesterday survives a today-with-no-session-yet,
    but any other gap day stops the count. `timeZone`/`clock` are both injected parameters
    (defaulting to the real system zone/`Clock.System`) so the day-math is deterministic under
    test. Also adds `thisWeekBounds`/`thisMonthBounds` helpers computing `[from, to)` `Instant`
    bounds for the current ISO week (Monday start) and calendar month in an injected timezone.
  - `StatsUiState`/`StatsViewModel` (`shared/.../ui/`): a single `StateFlow` (via
    `stateIn`/`WhileSubscribed`, matching `LibraryViewModel`/`BookDetailViewModel`) combining
    week/month time-read, session-count, and pages-read totals (grouped into a reused
    `StatsUiState.Period`) with the current streak. Period bounds are computed once at
    construction, not re-derived as real time crosses midnight/week/month boundaries while a
    `StatsViewModel` instance stays alive — an accepted simplification documented on the class,
    matching the assumption that the stats screen gets a fresh ViewModel per visit.
  - `AppContainer` now exposes `statsRepository`, wired the same way as `bookRepository`/
    `readingSessionRepository`; the ViewModel factory wiring for the stats screen itself is
    deferred to Phase C.
  - Tests: `StatsDaoTest` (10, direct SQL semantics), `StatsRepositoryTest` (15, including
    deterministic fixed-`Clock`/`TimeZone` streak cases — a 3-day streak, a gap breaking it,
    today-without-a-session preserving yesterday's streak, zero sessions, a single today-only
    session, and a timezone edge case proving late-evening sessions are bucketed by local calendar
    date rather than UTC), and `StatsViewModelTest` (4, Loading → populated, reacting to a new
    session insert, and null-duration non-contribution to time). The Room-backed classes
    (`StatsRepositoryTest`, `StatsViewModelTest`) are excluded from the android unit-test variant
    in `shared/build.gradle.kts` (package/exact-class-name filters), mirroring the existing books
    DAO/repository/ViewModel test exclusions — `:shared:jvmTest` remains the authoritative gate.
- **Stats screen** (ROADMAP Task 5 Phase C, `app/.../ui/screens/StatsScreen.kt`): a new screen
  showing "This week"/"This month" cards (time read, session count, pages read) plus a current
  reading streak card. Route-level `StatsScreenRoute` wires `StatsViewModel` (via the new
  `StatsViewModelFactory`, `ui/ViewModelFactories.kt`) to the stateless `StatsScreen`, following
  the same route-wrapper/stateless-screen split as `LibraryScreen`/`BookDetailScreen`.
  `StatsUiState.isLoading` renders a centered `CircularProgressIndicator`; otherwise, each period's
  `timeReadSeconds`/`pagesRead` render the real value when known, or the `stats_unknown_value`
  ("—") marker string when `null` — a `null` sum means no session in the period has a known value
  at all (schema v2), which must stay visually distinct from a legitimate `0` (e.g. a session
  logged with 0 pages read); `sessionCount` is always a real, non-negative count and is never
  shown with the unknown marker, including when it is legitimately `0`. Time read is formatted
  `H:MM` (hours unpadded, minutes zero-padded) — no seconds, unlike the book detail timer's
  `H:MM:SS`, since this is an aggregate total rather than a live-ticking display. The streak card
  uses a new `stats_streak_days` `<plurals>` resource (`one`/`other`, both rendering "N day
  streak" — "day" stays a singular noun-adjunct regardless of count, e.g. "a 5-day streak", not "a
  5-days streak") for a positive streak, or `stats_no_streak` ("No current streak") when the streak
  is `0`. Previews cover Loading, a populated week/month/streak, and an all-null/zero/empty state.
  **Navigation**: new `Route.Stats` ("stats"), wired into `AppNavigation`'s `NavHost` with a
  `navigateUp()` back callback; entry point is a new `IconButton` (`Icons.Filled.Info` —
  `material-icons-core`'s curated ~49-icon set has no chart/analytics/bar-graph icon, so `Info` is
  the closest available fit) in `LibraryScreen`'s TopAppBar actions slot, wired through a new
  `onNavigateToStats: () -> Unit` parameter threaded through `LibraryScreenRoute` per AGENTS.md §5
  state hoisting.

### Changed

- **Room schema v2 — optional reading-session duration** (ROADMAP Task 5 pre-phase,
  `shared/.../core/database/`): `ReadingSessionEntity.durationSeconds` is now `Long?` (`null` =
  duration unknown) instead of non-nullable `Long`. Storing `0` for "unknown" would have
  collided with the legitimate 0-second-session edge case and silently corrupted future
  time-read stats, so `AppDatabase`'s Room version bumps `1 -> 2` (schema v1 was frozen as of
  `v0.1.0` per AGENTS.md §8) with a hand-written `MIGRATION_1_2` (`Migrations.kt`, commonMain):
  the standard SQLite table-rebuild (create `reading_sessions_new` with the v2 shape verified
  against the Room-exported `shared/schemas/.../2.json`, copy every row across, drop the old
  table, rename, re-create the `mediaId` index). No existing rows are lost — every v1 row already
  had a real duration, so the migration only relaxes the constraint going forward. Wired into both
  platform `DatabaseFactory` actuals via the single shared `buildAppDatabase` function. Validated
  by `MigrationTest` (`shared/src/jvmTest/.../core/database/`, 3 tests) using Room KMP's
  `androidx.room.testing.MigrationTestHelper` against the real exported schemas — no fallback to
  hand-rolled raw-SQL setup was needed, since `room-testing`'s JVM artifact works directly on
  `:shared:jvmTest` with a plain file path and `SQLiteDriver` (no Android instrumentation
  required). Tests cover: both a real-duration row and a legitimate 0-duration row surviving the
  migration with values intact, the rebuilt table genuinely accepting a `NULL` insert (which the
  v1 `NOT NULL` column would have rejected), and the `mediaId` index surviving the rebuild.
  `androidx.room:room-testing` (test-only, androidx toolchain) added to the version catalog and
  `shared`'s `jvmTest` dependencies.
- **Manual reading-session duration is now optional** (`app/.../ui/screens/BookDetailScreen.kt`
  `ManualSessionDialog`, `shared/.../features/books/data/ReadingSessionRepository.kt`,
  `LogReadingSessionUseCase`, `BookDetailViewModel.logManualSession`): the duration-minutes field
  can be left blank for a backlogged manual entry with no known duration — the Save button no
  longer requires it (only start/end position are required, matching `PendingSessionDialog`).
  When left blank, `timestampStart` is set equal to `timestampEnd` (a zero-length interval — the
  session is still date/time-anchored via `timestampEnd`, only its true time-span is
  unrepresented, consistent with a `null` duration). `ReadingSessionRepository.logSession` and
  `LogReadingSessionUseCase`'s explicit-bounds overload now take `durationSeconds: Long?`,
  validating `>= 0` only when non-null; the timer-backed path is unchanged (a live
  `ReadingTimerResult` always has a real duration). Session history (`SessionRow`) renders the
  positions line without a duration segment (new `session_positions` string resource) when
  `durationSeconds` is null, instead of a misleading "0:00:00".

## [0.3.0] - 2026-08-01

Reading tracking milestone: the dormant `ReadingSessions` table becomes a feature — live
reading timer, session logging (timer-backed or manual with backdating), session history,
and a full Book Detail screen. Also hardens the cover-fetch fallback chain and survives
two external review rounds plus an internal multi-agent review.

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
  book-detail screen (see Task4 Phase C below). `uiState` combines `BookRepository.observeBookDetail`
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
  for the date — see Task4 Phase D below — and, as of Phase E, a Material 3 `TimePicker` for the
  end time, see Phase E below); `timestampEnd` is derived from that selection and
  `timestampStart = timestampEnd - duration`. Position/duration/pages fields are
  digit-and-decimal-point filtered so a negative value (the one thing `LogReadingSessionUseCase`
  rejects) can't be typed. Session history lists most-recent-first with a delete icon per row and
  a confirmation dialog matching `LibraryScreen`'s original pattern (deletion itself later moved
  to a book-level action — see Phase E below). Dates render via `java.time` (not
  `kotlinx-datetime`, which isn't exposed to this Android-only app module) since `java.time` is
  available unconditionally at `minSdk 28`. Previews cover Ready-with-sessions,
  Ready-with-pending-session, and Loading.
- **Navigation**: `Route.BookDetail` (`book_detail/{bookId}`) with a `createRoute(bookId)` helper;
  wired into `AppNavigation`'s `NavHost` with a `navArgument`-typed `bookId`.
- **`BookDetailViewModelFactory`** (`ui/ViewModelFactories.kt`): per-navigation-argument factory
  (constructed fresh per book detail route, unlike the reused `LibraryViewModelFactory`/
  `AddBookViewModelFactory`), wiring `BookDetailViewModel`'s repository/use-case dependencies
  from `AppContainer` plus the route's `bookId`.
- `delete_session_content_description` string resource for the session-row delete icon.
- **Delete-book action on Book Detail screen** (Task4 Phase E, `app/.../ui/screens/BookDetailScreen.kt`):
  a `Delete` icon in the TopAppBar actions slot (shown only for `BookDetailUiState.Ready`) opens a
  confirmation dialog (`delete_book_title`/`delete_book_body`/`delete_book_content_description`
  string resources, mirroring the wording the old `LibraryScreen` delete button used). On confirm,
  the route wrapper's `onDeleteBook: () -> Unit` parameter is invoked, wired to the new
  `BookDetailViewModel.deleteBook()` (see below) — a subsequent revision replaced an earlier
  destination-scoped-`LibraryViewModel` workaround with this shared-module method once a
  `Resource.Error` from deletion needed to surface to the screen (`BookDetailUiState.Ready.errorMessage`),
  which the workaround had no path for. Deletion success is reflected reactively via
  `BookDetailUiState.NotFound`, which the existing `LaunchedEffect`-driven auto-navigate-back
  (Task4 Phase C) already handles, so no separate post-delete navigation logic was needed.
- **`BookDetailViewModel.deleteBook()`** (`shared/.../ui/BookDetailViewModel.kt`): deletes the
  book this screen was opened for via `BookRepository.deleteBook(bookId)`, mirroring
  `deleteSession`'s pattern — fire-and-forget on `Resource.Success` (the resulting `NotFound`
  state drives navigation as above), `Resource.Error` surfaced via
  `BookDetailUiState.Ready.errorMessage` so a failed delete gives the user feedback instead of a
  confirm tap that silently does nothing.
- **Material 3 `TimePicker` for manual session entry** (Task4 Phase E,
  `app/.../ui/screens/BookDetailScreen.kt` `ManualSessionDialog`): the end-time field is now an
  `OutlinedButton` (label formatted via `android.text.format.DateFormat.getTimeFormat`, so it
  renders in the device's locale and respects its 12h/24h display preference) that opens a
  `TimePicker` hosted in a custom `AlertDialog` with OK/Cancel — this Material3 version has no
  built-in `TimePickerDialog` wrapper analogous to `DatePickerDialog`. `is24Hour` on
  `rememberTimePickerState` is likewise sourced from `android.text.format.DateFormat.is24HourFormat`.
  Cancel restores the hour/minute selected before the picker was opened (mirroring the date
  picker's existing cancel-restore behavior); OK keeps the in-dialog selection. `TimePickerState`
  always exposes `hour` in 0-23 regardless of `is24Hour`, so the old hour/minute range validation
  (and the `timeIsValid`/enabled-gating on it) is gone entirely — there is no invalid time state
  to guard against anymore. `time_label`/`select_time_title` string resources added; no
  hour/minute label string resources existed to remove (they were deliberately left as inline
  labels, never extracted).
- **Start-position autofill** (Task4 Phase E, `app/.../ui/screens/BookDetailScreen.kt`
  `PendingSessionDialog` + `ManualSessionDialog`): both session-entry dialogs now prefill their
  start-position field from `BookDetailUiState.Ready.currentProgress` (formatted via the existing
  `formatUnit` helper, dropping a trailing `.0`; `null` progress — no session logged yet — leaves
  the field empty as before), as a "resume where you left off" convenience. The user can still
  freely edit or clear the field.
- **`INTERNET` permission** declared explicitly in `AndroidManifest.xml` (Task4 Phase E): previously
  relied entirely on Ktor's OkHttp engine (`ktor-client-okhttp`, via its `okhttp-android`
  transitive dependency) merging the permission into the final manifest, so a future engine swap
  away from OkHttp could have silently dropped network access with no manifest-level signal.

### Changed

- **`LibraryScreen`**: cards are now tappable to navigate to the book detail screen, via
  `Modifier.clickable` on the card row. `LibraryScreenRoute` gains an
  `onNavigateToBookDetail: (String) -> Unit` parameter threaded through to a new `onBookClick`
  callback on the stateless `LibraryScreen`. The per-card delete `IconButton`, its confirmation
  dialog, and the `onDeleteBook` callback have been removed from this screen entirely (Task4
  Phase E) — deletion now lives on the Book Detail screen instead (see Added, above);
  `LibraryViewModel.deleteBook` itself is unchanged and unmoved, just no longer wired into this
  screen directly.

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
  `DatePickerDialog`) and an end-time selection (initially two digit-filtered hour/minute text
  fields, replaced by a Material 3 `TimePicker` in Phase E above), both defaulting to today/now so
  the zero-extra-tap "I just finished reading" flow is unchanged, but a session can now be
  backdated to a past date/time instead of always timestamping at the moment Save is pressed.
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

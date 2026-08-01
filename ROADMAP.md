# Roadmap

Living document tracking the project's task sequence. Updated as tasks complete or plans
change; details for the active task live in the orchestration session, not here.
Versioning follows AGENTS.md §8 — roughly one minor release per completed task.

## Done

- **Task 1 — Data foundation** (`v0.1.0`): KMP `shared` module, Room KMP schema v1
  (MediaItems + BookDetails + ExternalIdentifiers + ReadingSessions), content-addressed
  cover storage (SHA-256).
- **Task 2 — Ingestion pipeline** (`v0.1.0`): Ktor networking (Open Library primary,
  Google Books fallback), cover downloader, repositories, atomic `AddBookByIsbnUseCase`.
- **Task 3 — Compose UI shell** (`v0.2.0`): Kotlin + Compose app module, Material 3 theme,
  Library and Add Book screens, navigation, shared ViewModels, `AppContainer` manual DI.
- **Task 4 — Reading tracking** (`v0.3.0`): coroutine/Flow-based reading timer and
  `LogReadingSessionUseCase` (Phase A); `BookDetailViewModel` + UI state (Phase B); Book
  Detail screen with timer, manual session entry, and session history (Phase C); field-level
  cover fallback and manual-entry backdating via a session date/end-time picker (Phase D).

## Task 5 — Stats (next)

- **Pre-phase: Room schema v2 — optional session duration.** Backlogged manual sessions
  don't always have a known duration (and users may not want the timer), but
  `ReadingSessionEntity.durationSeconds` is non-nullable in frozen schema v1, and storing
  0 as "unknown" would collide with the legitimate 0-second-session edge case and silently
  corrupt time-read stats. Bump Room to schema v2 making `durationSeconds` nullable (null =
  unknown), with a tested Migration per AGENTS.md §8; make duration optional in the
  manual-entry UI; define stats semantics around it up front (sum only known durations;
  session counts and page progress unaffected). Do this before the stat queries so Task 5
  doesn't ship assumptions v2 breaks.

Aggregate queries in `features/stats`: time read per week/month, books finished, streaks.
Depends on Task 4 producing session data.

## Task 6 — Movies & TV

- TMDB client (primary API per AGENTS.md §4); TMDB requires an API key even on the free
  tier and keys must never be hardcoded — plan is a user-supplied key entered in settings.
- `MovieDetails` / `TVDetails` child tables + `WatchLogs` → next Room schema bump (v3,
  assuming the Task 5 pre-phase lands v2 first) under the §8 schema-freeze rule (version
  bump + tested `Migration`).
- Library/media-type UI generalization (type filter, non-book detail screens).

## Backlog / tech debt

- AGP 9 workaround: remove `android.builtInKotlin=false` + `android.newDsl=false` once KSP
  supports `com.android.kotlin.multiplatform.library` (google/ksp#2476); flags die in AGP 10.
- Edit-book-metadata screen: no UI for correcting title, release year, purchase price,
  total pages, or format after ingestion (`BookFormat` also defaults to `PHYSICAL` and has
  no correction path). Provider edition records can carry wrong values — e.g. Open Library
  reports 384 pages for an edition that's physically 366 — so a user-facing correction flow
  covering all five fields is needed, not just format.
- On-device smoke test of the full add-book flow (only exercised via JVM tests so far).
- Lifecycle pinned to 2.10.0 and core-ktx to 1.17.0 until compileSdk 37.
- Cover re-fetch for pre-fix books: the field-level cover fallback (secondary provider's
  cover merged into a coverless primary success) only runs at ingestion time, so books added
  before that fix have no stored cover and no re-fetch path. Needs a "re-fetch cover"
  affordance (per-book, or a bulk backfill over existing library entries). Could also add a
  further fallback via Open Library's ISBN-keyed cover URL with `?default=false` (which
  404s instead of serving a placeholder image, making it safely probeable) for cases where
  both providers' records are coverless.

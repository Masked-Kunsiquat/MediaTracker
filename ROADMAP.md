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

## Task 4 — Reading tracking (next)

Turn the dormant `ReadingSessions` table into a feature; the gap between "can add books"
and "daily-drivable."

- **Phase A (shared):** coroutine/Flow-based reading timer (stopwatch: start/pause/stop,
  elapsed-time Flow) in `features/books`; `LogReadingSessionUseCase` connecting timer
  output + page/percent bounds to `ReadingSessionRepository`. Virtual-time tests.
- **Phase B (shared):** `BookDetailViewModel` + UI state — book metadata, session history,
  current-progress derivation, timer integration. Tested against fakes.
- **Phase C (app):** Book Detail screen — tap a library card to open; cover + metadata,
  start/stop timer, manual session entry, session history list. Navigation route with
  `bookId` argument.

## Task 5 — Stats

Aggregate queries in `features/stats`: time read per week/month, books finished, streaks.
Depends on Task 4 producing session data.

## Task 6 — Movies & TV

- TMDB client (primary API per AGENTS.md §4); TMDB requires an API key even on the free
  tier and keys must never be hardcoded — plan is a user-supplied key entered in settings.
- `MovieDetails` / `TVDetails` child tables + `WatchLogs` → **Room schema v2**: first real
  migration under the §8 schema-freeze rule (version bump + tested `Migration`).
- Library/media-type UI generalization (type filter, non-book detail screens).

## Backlog / tech debt

- AGP 9 workaround: remove `android.builtInKotlin=false` + `android.newDsl=false` once KSP
  supports `com.android.kotlin.multiplatform.library` (google/ksp#2476); flags die in AGP 10.
- `BookFormat` defaults to `PHYSICAL` on ingestion — needs a user-facing correction flow.
- On-device smoke test of the full add-book flow (only exercised via JVM tests so far).
- Lifecycle pinned to 2.10.0 and core-ktx to 1.17.0 until compileSdk 37.
- Edit-book screen (title/year/price corrections) — no UI for updates yet.

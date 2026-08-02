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
  cover fallback and manual-entry backdating via a session date/end-time picker (Phase D);
  delete-book moved to the Book Detail screen, a Material 3 `TimePicker` for manual-entry end
  time, and start-position autofill from current progress (Phase E).
- **Task 5 pre-phase — Room schema v2, optional session duration.** Backlogged manual sessions
  don't always have a known duration, but `ReadingSessionEntity.durationSeconds` was non-nullable
  in frozen schema v1, and storing 0 as "unknown" would have collided with the legitimate
  0-second-session edge case and silently corrupted time-read stats. Bumped Room to schema v2
  making `durationSeconds` nullable (null = unknown), with a tested `Migration_1_2` per
  AGENTS.md §8; duration is now optional in the manual-entry UI. Session-gap semantics for the
  upcoming stat queries remain as documented below.
- **Task 5 — Stats** (`v0.4.0`): reactive aggregate reading-stats queries (`StatsDao`,
  `StatsRepository`) for this week/this month time read, session count, and pages read, plus the
  current consecutive-reading-day streak (Phase B); a Stats screen (Phase C) showing "This
  week"/"This month" cards and a streak card, reachable from a new icon in `LibraryScreen`'s
  TopAppBar. Session gaps are NOT auto-reconciled -- sessions are independent facts recorded as
  start/end position pairs, not a continuous log, so stats sum per-session deltas rather than
  assume continuity between one session's end and the next session's start. Time-read/pages-read
  totals sum only *known* values (a null `durationSeconds`/`deltaPages` contributes nothing to its
  total but still counts toward the session count); the stats screen renders an unknown-value
  marker rather than a misleading `0` when a period's sum is entirely `null`.

## Task 6 — Books polish (next)

The book domain gets finished before any other media type starts: real-world use of
v0.3.0/v0.4.0 surfaced too many rough edges (wrong provider page counts with no way to
correct them, redundant form fields, no session editing, no reading status) to justify
going wide. Movies & TV move to Task 7.

- **Phase A — Edit book metadata (done).** User-facing correction flow for title, release year,
  purchase price (in the schema since v1 but never displayed or editable anywhere), total
  pages, and format. Provider edition records carry wrong values — e.g. Open Library reports
  384 pages for an edition that's physically 366. Includes expanding `BookFormat` with
  `PAPERBACK` and `HARDCOVER`: the column is TEXT so **no schema migration is needed** —
  existing rows keep `PHYSICAL` as the generic/legacy value and get upgraded per-book via
  the edit flow itself.
- **Phase B — Session editing + manual-entry redesign.** Edit an existing reading session
  (reuse the manual-entry form prefilled from the row; delete-only today). Redesign the
  manual-entry dialog: `Pages read` (`deltaPages`) is redundant when positions are page
  numbers — auto-derive it as `end - start` in page mode and only expose a manual field for
  percent-based tracking; general layout cleanup.
- **Phase C — Reading status.** `TO_READ` / `READING` / `FINISHED` / `DNF` status on books —
  the missing concept that forced deferring the "books finished" stat. Requires **Room
  schema v3** (status column + tested `Migration_2_3` per AGENTS.md §8). Unlocks: the
  books-finished stat (Stats screen), library filtering/sorting by status.
- **Phase D — Detail screen tabs.** Split the single scrolling column into tabs: Details /
  Reading history now; a Purchase & Borrow tab is deferred until purchase/borrow tracking
  exists as a feature (data model + schema work of its own).
- **Phase E — Cover improvements.** Re-fetch affordance for coverless books (per-book, or a
  bulk backfill — books added before the field-level cover fallback have no stored cover and
  no re-fetch path); Open Library ISBN-keyed cover URL with `?default=false` as a further
  probeable fallback (404s instead of serving a placeholder image); try all Google Books
  image sizes (only thumbnail is used today); manual cover entry (paste a URL or pick a
  local image). Scraping Google Images or DuckDuckGo image results is a ToS violation and
  is ruled out.

## Task 7 — Movies & TV

- TMDB client (primary API per AGENTS.md §4); TMDB requires an API key even on the free
  tier and keys must never be hardcoded — plan is a user-supplied key entered in settings.
- `MovieDetails` / `TVDetails` child tables + `WatchLogs` → next Room schema bump (v4,
  assuming Task 6 Phase C lands v3 first) under the §8 schema-freeze rule (version bump +
  tested `Migration`).
- Library/media-type UI generalization (type filter, non-book detail screens).

## Task 8 — Search & discovery

- Title/author type-ahead search of external providers when adding a book: Open Library's
  search API (keyless) for as-you-type results with a ~300ms debounce, a 2-3 character
  minimum before querying, cancel-previous-request-on-new-keystroke, and an in-memory LRU
  cache for repeated prefixes (typing then backspacing shouldn't re-hit the network). Google
  Books is consulted only on selection or as a fallback, not for every keystroke -- its
  keyless per-IP quota is limited and 429s have already been observed against it. Typed
  result styling (author vs. title vs. collection) driven by Open Library's typed `docs`
  results, so the dropdown can visually distinguish match kinds.
- Local library search as part of the same task: filter/search the user's own already-added
  books by title/author, independent of the external-provider search above.

## Backlog / tech debt

- AGP 9 workaround: remove `android.builtInKotlin=false` + `android.newDsl=false` once KSP
  supports `com.android.kotlin.multiplatform.library` (google/ksp#2476); flags die in AGP 10.
- On-device smoke test of the full add-book flow (only exercised via JVM tests so far);
  first migration-on-device check (v1 → v2) rides along with the v0.4.0 install.
- Lifecycle pinned to 2.10.0 and core-ktx to 1.17.0 until compileSdk 37.
- Stats screen visual polish (acceptable as v1 today: plain cards, `Icons.Filled.Info` as
  the library-toolbar entry because material-icons-core has no chart glyph).
- Purchase/borrow tracking (lending a book out, borrow sources, purchase history) — data
  model + schema work; prerequisite for the Book Detail Purchase & Borrow tab (Task 6
  Phase D note).
- Orphaned cover files: deleting a book leaves its content-addressed cover on disk
  (dedup means the file may be shared by other books, so deletion needs a reference check
  or a periodic sweep).
- Selectable/copyable text: no text in the app is currently selectable or copyable. Wants
  tap-to-copy on the ISBN (the identifier users re-use elsewhere) and a `SelectionContainer`
  around the Book Detail metadata block so titles/notes/etc. can be long-press selected. On
  Android 13+ (API 33+) the system shows its own "copied" confirmation, so an in-app
  toast/snackbar must be suppressed there to avoid a doubled message (minSdk is 28, so both
  paths matter); the Compose clipboard API has shifted from `LocalClipboardManager` to
  `LocalClipboard`/`ClipEntry`, so use whichever is current in the project's Compose BOM;
  `SelectionContainer` must be applied narrowly, since long-press selection conflicts with
  the clickable library cards and session rows (`DisableSelection` carves out exceptions).

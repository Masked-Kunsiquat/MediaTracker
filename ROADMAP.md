# Roadmap

Living document tracking the project's task sequence. Updated as tasks complete or plans
change; details for the active task live in the orchestration session, not here.
Versioning follows AGENTS.md §8 — roughly one minor release per completed task.

**Task numbers are stable identifiers, not priority.** Priority has been reshuffled several
times (Movies & TV alone moved from 6 to 13), and renumbering sections meant hand-editing every
cross-reference — which is how a stale "Task 9" reference survived into a PR once already.
Numbers are now assigned once, on creation, and never change; the running order lives in the
single list below and reordering is a one-line edit there.

## Execution order

1. **Task 9 — Search & discovery** ← next. *Partially done*: Phase A (authors + local library
   search) shipped; still outstanding: external title/author type-ahead, barcode scanning,
   manual entry, and paste-to-add. It was paused in favour of Task 14, and that dependency has
   since been paid off — Task 14's backfill re-queries providers for `BookMetadata`, which carries
   **both** the cover URL and the authors, so the one rate-limited crawl that repaired the covers
   also filled the authors Phase A could not add retroactively for books predating it. Nothing
   blocks the remaining phases now.
2. Task 10 — Re-read modeling (ratings land here)
3. Task 11 — Analytics & stats revamp
4. Task 12 — Genre tracking
5. Task 13 — Movies & TV
6. Task 16 — Signing & distribution. Sequenced late because nothing about it blocks a feature, but
   the signing half is separable and slightly cheaper to do sooner — see that task's own note.

## Done

- **Task 14 — Bulk operations & cover backfill**: bulk cover/author backfill over the whole library
  behind one shared rate limiter (Phase A); library multi-select with bulk delete and reference-aware
  cover cleanup, which retires the orphaned-cover-files backlog item (Phase B). Bulk reading-status
  change was floated as the companion and deliberately not built — see that task's section.

- **Compose UI test harness** (`v0.9.0`+): 18 instrumented tests in `app/src/androidTest/`,
  covering the log viewer and changelog screens at the screen level (stateless composables driven
  with fake callbacks) plus `SettingsNavigationTest`, which starts at the real `MainActivity` and
  taps through — the only kind that can prove a *route* passes a real callback rather than a stub.
  Verified by mutation against the bug that motivated it: re-stubbing the Settings route's
  changelog callback fails exactly one test, the navigation one. Instrumented, so they need a
  device and cannot join AGENTS.md §7's gate; that constraint, and the reasoning, is recorded in
  §7 itself rather than only here.

- **Task 15 — Logging** (`v0.8.0`+): a hand-rolled KMP `Logger` with centrally-gated verbosity and
  adoption at the three failure paths that had been discarding their cause (Phase A); a capped,
  buffered, appending file store with rollover and disk-derived sequence numbering, plus the
  Android Auto Backup carve-out that scoping it uncovered — cloud backup had been sweeping the
  whole app-private directory to Google Drive since the first commit (Phase B1); an in-app log
  viewer on a snapshot/refresh model with a sequence-anchored divider, and a user-adjustable
  verbosity setting (Phase B2a); an in-app changelog viewer with a three-level fold (Phase B2b);
  and adoption across the remaining error sites plus `INFO` lifecycle tracing, which moved the
  default verbosity to `INFO` and retired the `Debug` picker option (Phase C).

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

## Task 6 — Books polish (done — ready for release)

The book domain gets finished before any other media type starts: real-world use of
v0.3.0/v0.4.0 surfaced too many rough edges (wrong provider page counts with no way to
correct them, redundant form fields, no session editing, no reading status) to justify
going wide. Movies & TV move to Task 13.

- **Phase A — Edit book metadata (done).** User-facing correction flow for title, release year,
  purchase price (in the schema since v1 but never displayed or editable anywhere), total
  pages, and format. Provider edition records carry wrong values — e.g. Open Library reports
  384 pages for an edition that's physically 366. Includes expanding `BookFormat` with
  `PAPERBACK` and `HARDCOVER`: the column is TEXT so **no schema migration is needed** —
  existing rows keep `PHYSICAL` as the generic/legacy value and get upgraded per-book via
  the edit flow itself.
- **Phase B — Session editing + manual-entry redesign (done).** Reading sessions gained an edit
  path: `ReadingSessionRepository.updateSession`/`LogReadingSessionUseCase.executeUpdate` reuse
  the exact same validation as the create path (a shared private `validatePositions` helper, plus
  the repository's identical timestamp/duration checks) rather than a second, divergent copy; an
  edit icon on each session row (next to the existing delete icon) opens the manual-entry dialog
  prefilled from that row, and Save updates the row in place. `Pages read` (`deltaPages`) is now
  auto-derived as `end - start` whenever the book's `totalPages` is known — the same signal
  `formatProgress` already used elsewhere on the screen to distinguish page- from percent-based
  tracking, so no new schema/flag was needed — and only asked for manually in percent mode. Every
  numeric field in both session dialogs (positions, pages, duration) now gets the parse-once +
  isError + Save-gating validation the duration field already had, instead of silently collapsing
  bad input to `0`/`null`; fields are grouped into "When"/"How long"/"Progress" sections.
- **Phase C — Reading status (done).** `ReadingStatus` (`TO_READ`/`READING`/`FINISHED`/`DNF`) added
  to `BookDetailsEntity` — book-specific semantics, not promoted to `MediaItemEntity`; Task 8 will
  scope its own movie/TV watch-state model rather than inherit this one. Room schema v3
  (`book_details.status` + `book_details.finishedAt`) via a tested `MIGRATION_2_3` (simple
  `ALTER TABLE ADD COLUMN`s, not a table rebuild — no existing constraint needed relaxing): a
  pre-existing book with a `reading_sessions` row derives `READING`, every other pre-existing row
  defaults to `TO_READ`; `finishedAt` starts `NULL` for all pre-existing rows (no pre-v3 signal ever
  supports `FINISHED`). `BookRepository.updateBookMetadata`/new `updateReadingStatus` share a
  `resolveFinishedAt` helper (stamps on entering `FINISHED`, preserves on staying, clears on
  leaving); ingestion defaults new books to `TO_READ`. Unlocked: the books-finished stat — a
  lifetime total (exact from v3 onward, no timestamp needed) plus a period-scoped "this week/month"
  count via `finishedAt` (honest about only reflecting finishes recorded after this phase, never
  fabricated for pre-v3 history) — and a library status filter (`FilterChip` row, client-side/
  in-memory, sorting unchanged/still title-only).
- **Phase D — Detail screen tabs (done).** Split the single scrolling column into a
  `PrimaryTabRow` with two tabs: Details (cover/metadata header, reading status, progress) and
  Reading history (timer, manual-entry affordance, session history). A Purchase & Borrow tab is
  deferred until purchase/borrow tracking exists as a feature (data model + schema work of its
  own — see backlog). `sessionToDelete`/`showManualEntry`/`sessionToEdit` and the new
  `selectedTabIndex` are hoisted above the tab content in `BookDetailContent` so a dialog opened
  from the Reading history tab keeps working regardless of which tab is later selected; the
  three session/pending-session dialogs render unconditionally on that same state, outside the
  `when (selectedTabIndex)` branch. Bundled alongside (same file, same phase): the backlog's
  selectable/copyable-text item — the Details tab's metadata block is wrapped in a
  `SelectionContainer` (with `DisableSelection` around the clickable status chip and the new ISBN
  copy button), and the ISBN gets an explicit tap-to-copy `IconButton` using `LocalClipboard`/
  `ClipEntry` (verified against the actual resolved `androidx.compose.ui:ui-android:1.11.4`
  artifact — both the deprecated `LocalClipboardManager` and the current `LocalClipboard`/
  `ClipEntry`/`Clipboard` are present; the latter was used), with the in-app "copied" `Snackbar`
  suppressed on API 33+ (system confirmation takes over there) and shown below it (minSdk 28).
- **Phase E — Cover improvements (done).** Per-book re-fetch affordance for coverless books (a
  new `RefetchCoverUseCase`, wired to a Details-tab button, disabled with an explanation when the
  book has no ISBN on record); Open Library ISBN-keyed cover URL with `?default=false` wired as a
  third, last-resort fallback step in `FallbackBookMetadataProvider` (404s instead of serving a
  placeholder image, so it's safely probeable) after both Open Library's own record and the
  Google Books probe are coverless. Bulk backfill across a whole library and manual cover entry
  (paste a URL or pick a local image) were both deliberately **not** implemented this phase — see
  backlog. Scraping Google Images or DuckDuckGo image results is a ToS violation and is ruled out.
  - **Google Books image size was the real gap, and is now fixed.** `GoogleBooksImageLinksDto`
    previously declared only `thumbnail` (~128px), so the larger `small`/`medium`/`large`/
    `extraLarge` links were discarded even when a volume provided them — this mattered more than
    it looked, since the field-level cover fallback consults Google Books precisely *when Open
    Library has no cover*, so books that got a cover from Google were exactly the ones stuck with
    the smallest image. Fixed by declaring the remaining fields and selecting the largest present
    (`extraLarge > large > medium > small > thumbnail > smallThumbnail`).
  - **Open Library sizing was verified and left unchanged** — covers are requested as
    `/b/id/{coverId}-L.jpg`, and `L` is the largest that API offers (S/M/L only). Keying by
    cover ID rather than ISBN is also deliberate: ISBN/OCLC/LCCN-keyed cover lookups are
    rate-limited to 100 requests per IP per 5 minutes, ID-keyed ones are not.
  - Consequence for the `?default=false` probe above: it is ISBN-keyed, so unlike current
    cover fetches it *is* subject to that 100/IP/5-min limit — fine for the interactive
    one-book-at-a-time re-fetch affordance, but explicitly NOT used in any bulk/loop context this
    phase (see backlog for the deferred bulk-backfill item this constrains).

## Task 7 — UI revamp & settings (done — ready for release)

Task 6 made the book domain functionally complete; this task makes it pleasant. Prioritized
ahead of search because these are the screens in daily use, and the rough edges were reported
from real use rather than inferred.

- **Details tab revamp (done).** Replaced the plain metadata list plus the timer with a considered
  layout/visual hierarchy: a title/release-year heading block, a promoted `ProgressSection` card,
  `TimerCard` restyled as the tab's primary action, and metadata (ISBN/format/total pages/tracking
  mode) as a two-column key/value grid instead of stacked prefix-string `Text` rows.
- **Reading history revamp (done).** Replaced the flat list with a timeline view (a continuous
  Canvas-drawn rail with per-day date separators and per-session nodes), with each session's
  duration/position-range/pages-read rendered as distinct visual chips instead of one concatenated
  string.
- **Edit screen revamp (done).** Replaced the bare column of text fields and vertical radio groups
  with four titled cards (Book details / Physical / Status / Purchase, matching `SettingsScreen`'s
  card convention) and a per-enum control choice instead of three identical radio groups: a
  `SingleChoiceSegmentedButtonRow` for the two-value tracking mode, a read-only
  `ExposedDropdownMenuBox` for the five-value format, and a `FilterChip` row (matching
  `LibraryScreen`'s status filter) for the four-value reading status. Save/Cancel moved to a
  persistent bottom action bar. Parse-once validation (blank/unparseable/out-of-range, mirroring
  `BookRepository`'s real bounds) is unchanged.
- **Explicit per-book tracking mode (pages vs percent).** Today the mode is *inferred* from
  whether `totalPages` is known, which is invisible to the user and flips silently the moment
  total pages is edited. Replace with an explicit per-book field, editable on the Edit screen,
  defaulted intelligently on ingestion (known page count → pages; ebook without one → percent).
  Requires **Room schema v4** (tracking-mode column + tested `Migration_3_4` per AGENTS.md §8).
- **Settings screen (done).** The app's first home for app-wide preferences. First occupant:
  **week start day** (Monday per ISO-8601, or Sunday per US convention), which drives the Stats
  screen's period bounds. `WeekStartDay` (`MONDAY`/`SUNDAY`), persisted by name (not ordinal) via
  `SettingsRepository` under a `week_start_day` key added in `shared/.../features/settings/data/
  WeekStartDay.kt`; default (unset or malformed) is `MONDAY`, matching the app's pre-Phase-B
  hardcoded behavior exactly. `StatsRepository.thisWeekBounds` gained a `weekStartDay` parameter
  (default `MONDAY`, so no existing call site broke); `thisMonthBounds` is unchanged (calendar
  month, per the decision below). `observeReadingStreak` was checked and confirmed **unaffected**:
  it walks backward one calendar day at a time with no notion of "week" at all, so there is no
  week boundary for the preference to shift. `SettingsViewModel` (new) exposes the current
  preference reactively and a `setWeekStartDay` action; `StatsViewModel` was extended (not just
  wired) to *react* to the setting live — its week period now re-buckets via `flatMapLatest` over
  `observeWeekStartDay()` the moment the preference changes, rather than only on the next
  ViewModel recreation (the existing "this month"/`today`-fixed-at-construction staleness is
  otherwise unchanged and remains documented). Settings screen built as an extensible list of
  titled sections (one `SettingsSection` per group of related rows) rather than hardcoded around
  this one preference, so a future setting is a new row/section, not a restructure; the
  week-start-day choice is a two-option `SingleChoiceSegmentedButtonRow` (Material 3's fit for a
  small, fixed, side-by-side binary choice, versus the vertical radio rows `EditBookScreen` uses
  for its longer option lists). Reachable via a new `Icons.Filled.Settings` icon in `LibraryScreen`'s
  TopAppBar — confirmed present in the curated `material-icons-core` set, no local vector drawable
  needed.
  - Stats period semantics decided: "this week"/"this month" stay **calendar** periods (week =
    the chosen start day 00:00 → same weekday next week; month = 1st → 1st, local timezone),
    NOT rolling 7/30-day windows. Only the week's start day becomes configurable. The previously
    documented staleness (bounds computed once at ViewModel construction) is now *partially*
    resolved: a live week-start-day change re-buckets immediately; a real midnight/week rollover
    while the screen stays open still needs a re-subscribe, as before.
- **Session-logging dialogs revamp (done).** The manual-entry and save-after-timer dialogs were the
  last surface still in the pre-revamp style — Task 6 Phase B made them functionally solid but never
  gave them a visual pass, and both launch from the screens the phases above just restyled. Both now
  render as a full-screen `Dialog` (Material 3's own guidance for a form this involved on a compact
  screen, over a bigger `AlertDialog`/`ModalBottomSheet`) through a shared `SessionDialogFrame`:
  card-backed titled sections (matching `EditBookScreen`/`SettingsScreen`'s convention) and a pinned
  bottom action bar. Every Task 6 Phase B validation/precision/dismissal rule (parse-once validation,
  duration-precision preservation on untouched edits, the `MAX_MANUAL_DURATION_MINUTES` bound,
  `TrackingMode`-driven page/percent mode, date/time picker cancel-restore, `currentProgress`
  prefill, edit-mode prefill, and `PendingSessionDialog`'s not-dismissible-except-by-Discard rule)
  is unchanged — layout only.

## Task 8 — Data portability (done — ready for release)

The vision doc calls CSV export/import and `.sqlite` backups "first-class support," but none of
it exists — which means an app whose entire premise is *no cloud* currently offers the user no
way to get their data out or back it up. Real reading data is accumulating now, so this is
scheduled ahead of search. (`android:allowBackup="true"` means Android Auto Backup may be
snapshotting the database to Google Drive, but that is invisible, size-capped, not restorable on
demand, and depends on exactly the cloud this app's premise rejects — it is not the answer.)

- **CSV export (Phase A — done)**: `library_export.csv` and `reading_logs_export.csv`, generated
  via a new `features/portability/` module (pure Kotlin CSV formatting + `ExportDataUseCase`) and
  written to user-picked locations from the Settings screen via SAF `ActivityResultContracts.
  CreateDocument` (no new permission). Covers every `MediaItemEntity`/`BookDetailsEntity`/
  `ExternalIdentifierEntity` field for books and every `ReadingSessionEntity` field for sessions —
  including nullable `durationSeconds` (exports as an empty field, never `0`), reading status,
  `finishedAt`, and formats (enums by name). Hand-rolled RFC 4180 escaping, no CSV dependency. A
  `csv_schema_version` column on every row is the version marker Phase B's importer will read.
  **`app_settings` (the key-value settings table added at schema v4) is not exported at all — it
  has no row-per-book/session shape to fit into either CSV file — and any other data outside the
  four entities above is likewise excluded. The CSV files are not a full database backup; the
  `.sqlite` backup (Phase C below) is the only export that covers the whole database, `app_settings`
  included.** No schema change.
- **CSV import (done)**: the harder half. A hand-rolled RFC 4180 reader (`CsvReader`, not
  `split(",")`, handling quoted commas/quotes and embedded newlines as single fields) plus
  `CsvTableReader`'s structural validation (header/column-count/`csv_schema_version` compatibility
  — refuses a file newer than this build understands) sit under `ImportDataUseCase`, which
  supports an explicit, user-visible `DuplicatePolicy` (`SKIP`/`REPLACE`/**`MERGE`**) matching an
  incoming book by `media_id`, then ISBN, then title+release-year, then (PR review, second round)
  title alone as a genuinely last resort — reached only when the release years disagree or either
  side is missing one (see the `releaseYear` backlog item for why: ISBN ingestion stores an
  edition's year, Goodreads import stores the work's year), with every title-only match surfaced in
  `ImportSummary.notes` for the user to verify rather than applied silently. MERGE only
  backfills fields the existing row left null, never overwriting a value already set — exactly
  what a later Goodreads re-import needs to backfill fields the model doesn't have a home for yet
  (see that bullet below), without a staging table or blocking on Task 12. Every resolved
  insert/update is applied through one new `ImportWriteDao.importAtomically` transaction
  (all-or-nothing, verified by a forced-mid-failure rollback test); structural file problems fail
  the whole import before any write, while a semantically bad row (or an orphaned reading session
  whose book isn't known) is skipped and reported rather than aborting everything else. Validation
  reuses the existing use-case-layer rules (`BookMetadataValidation`/`ReadingSessionValidation`,
  extracted from `BookRepository`/`ReadingSessionRepository`/`LogReadingSessionUseCase`) rather
  than forking a divergent copy. No schema change; Room stays at v4.
- **`.sqlite` backup + restore (done)**: a new "Backup & restore" Settings section, visually and
  structurally separated from CSV export/import by risk. Backup runs SQLite's own `VACUUM INTO`
  against the live database (via Room KMP's `useWriterConnection`/`Transactor.usePrepared`) rather
  than a naive file copy or a manual `PRAGMA wal_checkpoint` + copy: `RoomDatabase.Builder` defaults
  to `WRITE_AHEAD_LOGGING` and this app never overrides it, so the most recent commits can live only
  in the `-wal` sidecar until SQLite next checkpoints them -- `VACUUM INTO` reads through the normal
  pager (transparently merging main file + WAL, the same path every ordinary query already uses) and
  writes one fresh, compacted, WAL-free snapshot, proven in `DatabaseBackupUseCaseTest` (`jvmTest`)
  against a real file-backed database with a row confirmed to still be sitting only in `-wal` at
  backup time. Restore validates a picked file *before* touching anything, in two passes: pass 1
  parses the first 100 bytes directly (no SQLite driver at all) for the magic string and `PRAGMA
  user_version` at its fixed header offset, refusing a non-SQLite file or a `user_version` newer than
  `APP_DATABASE_VERSION` with a clear message. Pass 2 opens the candidate **read-only**
  (`SQLITE_OPEN_READONLY` -- validation has no business needing, or being granted, write access) with
  a real `BundledSQLiteDriver` connection and runs `PRAGMA integrity_check`, then confirms the tables
  that candidate's own reported `user_version` should have are present -- a valid-looking 100-byte
  header can't tell a truncated/corrupt file, or a structurally-valid SQLite file from a different
  program entirely, apart from a genuine MediaTracker database, and this is the single most
  destructive action in the app (AGENTS.md §1). An **older** version that passes both passes is
  accepted and swapped in as-is, since the very next open goes through the exact same registered
  migration chain every normal launch already uses -- verified end to end in
  `RestoreDatabaseUseCaseTest` (a real v2 file restored, reopened, and confirmed migrated to v4 with
  its data intact; pass 2's required-table set is chosen from the candidate's own `user_version`,
  requiring `app_settings` only once that version is 4 -- so a legitimate older backup is never
  itself rejected, but a v4 candidate genuinely missing `app_settings` is correctly refused rather
  than silently waved through). A truncated file with an otherwise-valid header, a structurally
  valid but unrelated SQLite file, and a v3-schema file hand-bumped to claim `user_version = 4`
  while genuinely missing `app_settings` are all covered by dedicated tests proving the header check
  (or an unconditional v1-only table check) alone would have wrongly accepted them. The live file is
  never deleted until the replacement is staged and validated: the picked document is streamed to a
  private temp file, validated there, then swapped in via same-directory atomic renames
  (`ATOMIC_MOVE` only -- no non-atomic fallback if the platform provider rejects it, since a
  non-atomic copy could leave a truncated file a plain existence check can't tell from a genuine
  one) -- old file (and its `-wal`/`-shm` sidecars, moved only once the main-file rename itself
  succeeded, and only if both sidecar renames also succeed) to a fixed-name `.pre-restore-bak` safety
  net, then the validated file into the live path; if a sidecar rename fails, the rollback puts back
  whichever sidecar(s) actually moved *first* (the two can disagree -- one landing, one failing) then
  the main file, so "nothing was changed" is only ever reported once that's genuinely true, not
  assumed from the main file alone. A failed final rename rolls the backup back automatically
  (sidecars first, main file last), and a `selfHealDatabaseIfNeeded` check at every cold start closes
  the one unavoidable gap between those two renames (a process death in that exact window would
  otherwise make Room create an empty database on next launch) using the same sidecars-first-main-last
  ordering, so its own "already healed" sentinel can never go true before the WAL that belongs next
  to the live file has arrived.
  `AppContainer` is closed before the swap and the whole process is killed and relaunched immediately
  after, success or failure -- the only clean way back to a fully working `AppContainer` rather than a
  half-live one -- with the outcome persisted to a small marker file and surfaced once on the next
  launch. Confirmation is a dedicated modal requiring an explicit checkbox before a
  destructively-styled button enables, reached only after the file already passed validation. No
  schema change; no new dependency; no new permission (SAF only, reusing the `CreateDocument`/
  `OpenDocument` plumbing Phases A/B established).
- Establishes the Storage Access Framework / file-picker plumbing the app has never needed
  before — which also makes the deferred **manual cover entry** backlog item cheap afterward.
- **Goodreads CSV import (Phase D — done)**: a distinct "Import from Goodreads" action on the
  Settings screen's "Data" section (own `DuplicatePolicy` picker, own button, single-file SAF
  picker — never sharing a control with the app's own CSV import, so the two can't be confused).
  Reuses the exact machinery the phases above built — `CsvReader`, `ImportDataUseCase`'s duplicate
  matching, the shared `BookMetadataValidation`, `ImportWriteRepository.importAtomically`'s
  all-or-nothing transaction — through a new `features/portability/goodreads/` mapping layer, not a
  parallel import path: `ImportDataUseCase.execute()`'s book-row duplicate-matching/insert/update
  logic was extracted into a private `resolveBookRows(...)` operating on already-parsed rows, and a
  new `executeGoodreads(...)` (added to the `ImportUseCase` interface) feeds it rows from the
  Goodreads mapping layer instead of `LibraryCsvImporter`.
  - Columns are matched **by header name** (`GoodreadsCsvTableReader`), never by position — a
    reordered export, unknown/extra columns, or missing optional columns all import cleanly; only
    `Title` is required, and its absence refuses the whole file with a clear message.
  - Mapping decided: `Title`; `Number of Pages` → `totalPages`; `Binding` → `BookFormat` (Hardcover/
    Library Binding/Board Book/Leather Bound → `HARDCOVER`; Paperback/Mass Market Paperback/Trade
    Paperback/Spiral-bound/Unbound → `PAPERBACK`; Kindle Edition/ebook/Nook → `EBOOK`; Audiobook/
    Audio CD/Audible Audio → `AUDIOBOOK`; anything else, including blank, → `PHYSICAL`); `Exclusive
    Shelf` (`read`/`currently-reading`/`to-read`) → `ReadingStatus` (blank/unrecognized → `TO_READ`;
    **nothing maps to `DNF`** — Goodreads' exclusive shelf has no such state, a user-tracked DNF
    lives in the dropped `Bookshelves` column instead, and guessing at it risked mislabeling a book
    the user never abandoned); `Date Read` → `finishedAt`, but only when the shelf resolved to
    `FINISHED` (a stray value alongside `currently-reading`/`to-read` is never used, so it can't
    contradict `BookDetailsEntity.finishedAt`'s "when status most recently became FINISHED"
    invariant); `Date Added` → `createdAt`, falling back to import time when blank/unparseable.
  - **`releaseYear` decided: `Original Publication Year` preferred over `Year Published`**, falling
    back to `Year Published` only when the original-year column is blank. `Original Publication
    Year` is the year the *work* first appeared; `Year Published` is the specific *edition/printing*
    Goodreads happened to catalog, which can be a much later reprint — the "2026 anniversary
    printing masks an original 1926 publication" edition-vs-work problem this bullet was written to
    flag. A personal library tracker is about the work someone read, not one exact printing, so the
    work-identity year wins whenever Goodreads recorded one; falling back to the edition year for
    the (common) case where `Original Publication Year` is blank is still better than leaving
    `releaseYear` `null`.
  - **`My Rating`, `Bookshelves`, and `Read Count` are dropped, not staged — the decision made**:
    no import-staging table and no schema bump (`AppDatabase` stays at v4). Instead, `ImportSummary`
    gained a `notes: List<String>` field (default-empty; always empty for the app's own CSV import)
    that this path always populates with an explicit notice naming the three dropped columns, why
    they have nowhere to go yet (Task 10 for read-throughs/ratings, Task 12 for genres/shelves), and
    that **the recovery path depends on the user keeping `goodreads_library_export.csv`**: once
    those tasks land, re-importing that same file again will match every book already imported (by
    ISBN, or by title+year) and backfill the new fields, because `DuplicatePolicy.MERGE` only fills
    a blank and never overwrites a value already set — proven end-to-end in
    `ImportDataUseCaseTest.executeGoodreads_mergePolicy_reimportBackfillsBlankReleaseYear_neverOverwritesTitle`.
    The Settings screen's import-summary dialog renders every note in full, never truncated to a
    count — this is the load-bearing mitigation for what would otherwise be a silent data loss on
    `My Rating`, and it must keep working if Task 10/12 change the entity shapes those columns will
    eventually feed.
  - ISBN Excel-armor handled: Goodreads writes `="9780593135204"` (including the empty-ISBN case
    `=""`) to stop Excel mangling it; stripped before any ISBN handling.
  - No schema change; no new dependency; no new permission.

## Task 9 — Search & discovery

ISBN-only entry is the book domain's remaining bottleneck — a book can only be added while
physically in hand (or with its ISBN hunted down), so title/author search is what actually
completes the add-books experience, and local library search matters as the library grows.
Movies & TV is a much larger lift (new API + user-supplied key, two new tables, a schema
migration, library UI generalization) and shouldn't gate that. The *local library search* half
of this task needs no schema change (a title/author search index would itself be a schema
change + migration if ever added, but personal-scale libraries don't need one) — but **Phase A
below did require one anyway**, not for the search itself: both providers already resolved
author names during ISBN ingestion and nothing was keeping them, and search-by-author is
obviously pointless without an author to search stored anywhere first.

- **Phase A — author capture + local library search (done).** Closed the gap where both book
  providers already resolved author names (Open Library makes an extra `/authors/{key}` round
  -trip specifically for this) and every one of them was discarded, since no column existed to
  hold one.
  - **Room schema v5** (`book_details.authors`, tested `MIGRATION_4_5`): a single denormalized
    `String` column, multiple names joined with `"; "` (`BookDetailsEntity.AUTHOR_SEPARATOR`) —
    **deliberately not a normalized authors table.** A table's headline benefit (author-level
    dedup, "tap an author → all their books") isn't free: providers disagree on name formatting
    ("J.R.R. Tolkien" vs. Goodreads' `Author l-f` column's "Tolkien, J. R. R.") and Goodreads
    splits one book's authorship across three separate columns, so a table would still need to
    fuzzy-match strings to establish author identity — it would only move that problem from
    display time to insert time, for a feature ("tap an author") nothing in the app does today.
    Denormalized-first is also the reversible choice: a table can always be *derived* from the
    strings stored here later, but data never captured in the first place is unrecoverable.
    **Follow-up, not scheduled**: promoting `authors` to a normalized table (plus a join table)
    is a contained, self-contained piece of future work if/when "tap an author → all their
    books" becomes a real feature — see the backlog entry below.
  - `AddBookByIsbnUseCase` now persists `BookMetadata.authors` (joined via
    `joinAuthors`) instead of discarding it; existing pre-v5 books show no author until
    re-fetched or hand-edited (honest, not fabricated by the migration).
  - `library_export.csv` gained an `authors` column (`CSV_SCHEMA_VERSION` bumped 1 → 2); a
    pre-existing `v1` file still imports cleanly via a registered legacy-header adapter in
    `CsvTableReader` (pads the missing column, then parses normally). The Goodreads importer
    now maps `Author` + `Additional Authors` (Goodreads' own comma-separated co-author list,
    re-joined with this app's `"; "` separator) into the same column; `Author l-f` is skipped
    as a redundant re-formatting of `Author`.
  - Author displays on library list rows and the Book Detail screen's metadata block, omitted
    (not placeholder text) when unknown.
  - Local library search: a search field on the Library screen, filtering the already-loaded
    reactive book list by title-or-author (case-insensitive substring, in-memory — no DB
    query/index, personal-scale libraries don't need one). Composes with the pre-existing
    reading-status `FilterChip` row as an **intersection (AND)**: both narrow the same list
    together, e.g. "Reading" + a query shows only currently-reading books matching that query,
    never the union of either filter alone.

**Phase plan for what remains (decided, not yet started).** The four outstanding pieces below are
sequenced deliberately rather than taken in the order they happen to be listed:

- **Phase B — title/author type-ahead.** First, because it is the one that actually retires this
  task's stated bottleneck: today a book can only be added with its ISBN in hand. Split in two, so
  the half that can be proven by tests is not entangled with the half that cannot:
  - **B1 (shared module):** a `searchByTitleOrAuthor` provider method over Open Library's keyless
    search API, plus the use case and DTOs, with an in-memory LRU cache for repeated prefixes.
    Entirely `commonTest`-able with `MockEngine`, exactly as the ISBN clients are. Note the
    cancellation rule now applies from the start here (Task 15 Phase C): a superseded keystroke
    cancels its request, and a cancelled search must not be logged as a provider failure.
  - **B2 (app module):** the type-ahead UI — ~300ms debounce, a 2-3 character minimum,
    cancel-previous-on-keystroke, and typed result styling. Needs device verification, not just
    instrumented tests: debounce and cancellation are exactly the kind of behaviour that passes a
    test and still feels wrong in the hand.
- **Phase C — manual entry and paste-to-add.** Second because it needs no permissions and no new
  dependency, and because the Edit Book screen and `BookMetadataValidation` already carry most of
  the form work. The two flows are **not** the same path underneath, though they are easy to
  describe as if they were:
  - **Manual entry** is the genuinely provider-independent one: a create-book form writing
    straight through `BookRepository.addBook` (which already runs `BookMetadataValidation` on the
    way in), with no lookup and no network call at all. This is the honest fallback for a book no
    provider knows about, which type-ahead cannot fix.
  - **Paste-to-add** is local only in its *extraction* step. A regex pulls an ISBN out of the
    pasted blob; from there it hands off to `AddBookByIsbnUseCase.execute`, which owns
    normalization, checksum validation, the provider lookup, the best-effort cover fetch, and
    persistence. So it needs the network exactly as much as typing an ISBN does — it only spares
    the user the typing. **No offline variant is intended**; a paste with no reachable provider
    fails the same way ISBN entry does, and manual entry is the answer in that case.
- **Phase D — barcode scanning.** Last, and **blocked on a decision that is the user's to make, not
  mine**: Google Code Scanner needs no CAMERA permission but hard-depends on Play Services (awkward
  against the local-first ethos, and rules out de-Googled devices and F-Droid), while bundled ML Kit
  + CameraX avoids Play Services at the cost of the CAMERA permission, a preview implementation and
  ~2.2MB. Both are recorded in full below. Sequenced last so the other three are not held up by it.

- Title/author type-ahead search of external providers when adding a book (not yet done): Open
  Library's search API (keyless) for as-you-type results with a ~300ms debounce, a 2-3 character
  minimum before querying, cancel-previous-request-on-new-keystroke, and an in-memory LRU
  cache for repeated prefixes (typing then backspacing shouldn't re-hit the network). Google
  Books is consulted only on selection or as a fallback, not for every keystroke -- its
  keyless per-IP quota is limited and 429s have already been observed against it. Typed
  result styling (author vs. title vs. collection) driven by Open Library's typed `docs`
  results, so the dropdown can visually distinguish match kinds.
- Scan a book's barcode instead of typing its ISBN. Integration surface is small: the scanner
  is an app-module-only adapter producing an ISBN string that feeds the existing
  `AddBookByIsbnUseCase` (which already normalizes/validates ISBN-10/13), so no shared-module
  or KMP impact. ML Kit barcode scanning runs fully on-device — no image data leaves the phone.
- Flavor decision and its tradeoff: **Google Code Scanner** (`play-services-code-scanner`) is
  preferred — Play Services hosts the scanning UI, so the app needs NO camera permission, no
  CameraX, and no preview implementation, keeping the manifest at just INTERNET; the cost is a
  hard Google Play Services dependency (sits awkwardly with the local-first/privacy-focused
  ethos, and rules out de-Googled devices/F-Droid). The alternative, bundled ML Kit + CameraX,
  avoids Play Services but requires the CAMERA permission, a camera preview implementation, and
  ~2.2MB of APK.
  - **Play Feature Delivery / dynamic feature modules do not solve this** — worth recording, since
    it looks like it should. Dynamic modules are downloaded *by the Play Store* from an app bundle,
    so the mechanism presupposes Play is installed; isolating the scanner that way would make
    F-Droid distribution harder, not easier.
  - The established way to ship both is **Gradle product flavors** — a Play flavor and a FOSS
    flavor behind one scanner interface — deferrable until F-Droid distribution actually matters,
    since the interface is the only part that must exist up front.
  - Note for the FOSS flavor: bundled ML Kit avoids Play Services *at runtime* but is itself a
    proprietary binary, so F-Droid's main repo would reject it too. A genuinely F-Droid-acceptable
    scanner means **ZXing / zxing-cpp** (Apache 2.0) with CameraX — more scanning UI to write and
    somewhat lower decode accuracy than ML Kit, in exchange for being fully open.
- Implementation notes: restrict the scanner to EAN-13 (book barcodes are Bookland EAN-13;
  restricting formats speeds detection and avoids capturing the EAN-5 price add-on barcode
  printed beside the ISBN on many covers), and use the 978/979 prefix to confirm a scan is a
  book rather than an unrelated product.
- This is a new third-party dependency approved by the user per AGENTS.md §5 ("no unnecessary
  dependencies without explicit project context approval").
- **Manual entry** belongs here too: the vision doc lists "manual entry, ISBN typing, and future
  barcode scanning" as three input paths, but only ISBN exists today — a book with no usable
  ISBN (older editions, self-published, damaged barcode) simply cannot be added. Needs a
  create-book form that writes straight through `BookRepository.addBook` with no provider lookup.
- **Paste-to-add**: accept a pasted blob of arbitrary text (a message, a listing, a review) and
  pull the ISBN out of it. Deliberately **regex-first, not ML** — ISBN-10/13 have well-defined
  shapes, `AddBookByIsbnUseCase` already owns normalization and checksum validation, and a regex
  costs no dependency, no Play Services, and no runtime model download. ML Kit Entity Extraction
  was considered here and rejected for this job: it is designed to *discover* entities in unknown
  text, which is a poor trade when the only entity we want has a strict, cheaply-matched format.
  See Unscheduled features for the one case where it would actually pay off.

## Task 10 — Re-read modeling

Today a re-read is invisible: sessions just keep appending to the same flat list, status flips
back to `READING`, and `finishedAt` is overwritten — so "I read this in 6 weeks the first time
and 4 days the second" is unrecoverable. The vision doc asks for "completion milestones and
individual session histories per read-through."

- Group reading sessions into numbered read-throughs, each with its own completion state and
  finish date; the Book Detail reading-history view separates them.
- **A Room schema bump** (read-through table or session grouping column + a tested migration per
  AGENTS.md §8), with the migration assigning every existing session to read-through #1 so no
  history is lost. This entry used to read "schema v5 / `Migration_4_5`" — v5 was spent on Task 9
  Phase A's `authors` column instead, so the number was already wrong. See the note under Task 13:
  absolute versions are no longer pinned to unscheduled tasks.
- Per-read stats become possible (duration per read, pages/day per read) and feed Task 11.
- **Ratings belong here, per read-through** (user decision), not as a standalone book column.
  The model has no rating field today and Goodreads exports one, so it is tempting to add
  `rating` to `book_details` early — but a rating is per *read* ("loved it the first time, dragged
  the second"), so a book-level column added now would have to be migrated onto the read-through
  entity by this task anyway. Adding it as a column on the read-through table the migration
  already creates costs one migration instead of two and puts it on the right entity first time.
  The Goodreads importer (Task 8 Phase D, done) landed before this task with a plan for `My
  Rating` rather than waiting on it: the column is dropped on import, with an explicit notice
  telling the user to keep `goodreads_library_export.csv` so a re-import once this task adds a
  rating field can backfill it via `DuplicatePolicy.MERGE` — see that phase's ROADMAP bullet.
  When this task lands the read-through rating column, double check that a re-import genuinely
  fills it in for a book imported before this task existed.

## Task 11 — Analytics & stats revamp

The vision doc's "rich offline analytics" pillar, minus the financial half (explicitly declined
for now — see Unscheduled features for why the purchase-data prerequisite is parked).

- **Reading velocity**: pages/hour and minutes/page. Derives from existing session data with no
  schema change, but must exclude null-duration sessions from any rate (an unknown duration
  cannot contribute to a speed) the same way time-read totals already do.
- **GitHub-style activity heatmap** of reading days. Derives from session dates, no schema
  change, and gives the Stats screen the visual anchor it currently lacks.
- Absorbs the former "stats screen visual polish" backlog item, including replacing
  `Icons.Filled.Info` as the library-toolbar entry point (material-icons-core has no chart glyph,
  so this needs a local vector drawable — the same approach used for the ISBN copy icon).

## Task 12 — Genre tracking

Specified in `docs/project-idea.md` from the outset — "multi-genre tagging per title with custom
user taxonomies and hex color coding," plus genre filtering — but never carried into this roadmap
and never implemented: there is no genre column, table, or UI anywhere today.

- Genres table plus a many-to-many join table (a title holds several genres), user-defined
  taxonomies, a hex color per genre, a management UI, and library filtering by genre.
- **A Room schema bump** (+ a tested migration per AGENTS.md §8).
- Open question when scheduled: whether to seed suggestions from provider metadata (Open
  Library `subjects` are numerous and messy; Google Books `categories` are cleaner but coarse) or
  keep the taxonomy purely user-authored.

## Task 13 — Movies & TV

- TMDB client (primary API per AGENTS.md §4); TMDB requires an API key even on the free
  tier and keys must never be hardcoded — plan is a user-supplied key entered in settings.
- `MovieDetails` / `TVDetails` child tables + `WatchLogs` → a Room schema bump under the §8
  schema-freeze rule (version bump + tested `Migration`).
  - **Absolute schema versions are deliberately not pinned to unscheduled tasks anymore.** This
    bullet used to claim v7 "assuming v4 tracking mode, v5 read-throughs, and v6 genres land
    first" — but v5 went to Task 9 Phase A's `authors` column, and every downstream number was
    silently off by one from that moment. Whichever of read-throughs (Task 10), genres (Task 12)
    and this one lands last takes the highest number; the live version is
    `APP_DATABASE_VERSION` in `AppDatabase.kt` (currently **5**), which is the only place worth
    trusting. Same failure mode as the stale task numbers called out at the top of this file,
    on a different axis.
- Note this has now slid from Task 6 to Task 13 as book-domain work kept taking priority. That
  reflects a real preference — the book side is what's in daily use — but it is worth an explicit
  decision rather than continued drift if multi-media matters sooner.
- The vision doc's Phase 2 also describes a **unified dashboard** (cross-media totals, combined
  spending, active timers, an "Up Next" queue). Not separately scheduled; it only makes sense
  once a second media type exists.
- Library/media-type UI generalization (type filter, non-book detail screens).

## Task 14 — Bulk operations & cover backfill

Prompted by real use: importing a Goodreads library produced dozens of books with no covers,
leaving the per-book re-fetch (Task 6 Phase E) as the only remedy — one tap at a time. Neither
item here is a bugfix; both are missing capabilities, so this is a **minor** release, not a patch.

- **Bulk cover & author backfill (Phase A — done).** Promoted from the backlog, where it was
  deferred out of Task 6 Phase E. Serves three cases that all produce coverless books: a Goodreads
  import (`GoodreadsCsvImporter` sets `coverImageHash = null` by design — Goodreads exports carry no
  cover data), a CSV import onto a **new device** (the CSV carries cover *hashes* but no image
  bytes, so every hash points at a file that isn't there), and books added before the field-level
  cover fallback existed. Scope was widened from "cover backfill" to **cover-and-author** backfill
  before implementation started (see this task's entry in "Execution order" above): a provider
  lookup already returns both, so one rate-limited crawl over the library fixes covers *and* the
  authors Task 9 Phase A can't fill in retroactively — a cover-only pass would have meant crawling
  the same rate-limited API twice.
  - **One limiter, shared by every ISBN-keyed probe.** `OpenLibraryCoverRateLimiter`
    (`shared/.../features/books/network/`) is a sliding-window (100 req/5 min) limiter with a
    server-refusal backoff layer on top, living *inside* `OpenLibraryIsbnCoverProbe` as an injected
    dependency. `AppContainer` constructs exactly one instance and hands it to the bulk backfill,
    `RefetchCoverUseCase` (interactive re-fetch), and `AddBookByIsbnUseCase` alike, so all three draw
    on one combined per-device budget.
  - **429/5xx no longer collapse into "no cover."** `OpenLibraryIsbnCoverProbe.probeCoverUrl` now
    returns `CoverProbeResult` (`Found` / `NotFound` / `RateLimited`) instead of a bare `String?`.
    The bulk backfill acts on the distinction (pauses and defers on `RateLimited` rather than
    writing the book off); `FallbackBookMetadataProvider` — used by the single-book interactive
    paths — still folds `RateLimited` into "no cover for this call," which is an intentional,
    documented choice: a one-off lookup has no retry loop to pause, so the two cases have the same
    practical outcome there.
  - **Resume state lives in `app_settings`** (`BulkBackfillState` in
    `features/settings/data/BulkBackfillState.kt`) — no schema change: a comma-joined pending-media-id
    list plus a few `Int` counters, checkpointed after *every* book, not just at the end. Survives
    interruption by quota, cancellation, or process death; a resumed run reuses the original
    candidate scan rather than rescanning the library (so newly-added books aren't silently folded
    into an in-flight resume chain — they're picked up by the next fresh run instead).
  - **Only touches books missing data**, never refreshes a book that already has a cover/authors —
    this is a repair pass for gaps, not a re-sync, and won't fight a future manual-cover-entry edit.
  - **`RefetchCoverUseCase` was left as-is (sibling use case, not generalized/wrapped).** It only
    ever touches the cover column and returns a single-book UX `Resource`; neither shape fits a
    many-book, resumable, cover-*and*-author operation, so `BulkBackfillUseCase` is new and
    independent, sharing dependency shape and the rate limiter but not code/inheritance.
  - Offered from a new "Cover & author backfill" section on the Settings screen (live progress,
    cancel, and an honest "N of M done, paused until the quota resets" state), and as a one-tap
    "Start backfill" action on the import summary dialog once an import actually adds books.
  - Books with no ISBN are computed once at scan time, reported as skipped, and never enter the
    retry queue — manual cover entry (still in the backlog) remains their only route.
- **Library multi-select and bulk delete (Phase B — done).** Long-press a library card to enter selection mode,
  with a contextual app bar for actions across the selection. Bulk delete is the motivating case;
  bulk reading-status change is the obvious companion and probably cheap once selection exists.
  Deletion of several books at once deserves the same confirmation care the single-book delete
  already has.
  - **Cover cleanup must be decided explicitly, not left implicit.** Covers are stored
    content-addressed (SHA-256 of the image bytes), so two books with the same cover share **one
    file** — deleting a book therefore cannot simply delete its cover file without checking whether
    anything else still references it. Bulk delete multiplies both the risk and the waste: delete
    the file naively and a surviving book loses its cover; delete nothing and a bulk purge strands
    that many files forever. Pick one and say so:
    - **Reference-aware removal** through `LocalImageStorageManager`: delete a cover only when no
      remaining `MediaItemEntity` references that hash. This also retires the standing
      orphaned-cover-files backlog item rather than growing it.
    - **Or explicitly defer** cleanup, and document the resulting disk growth as accepted — but
      then say it in the release notes, because "deleted books still cost storage" is surprising.
    - **Decided: reference-aware removal.** `DeleteBooksUseCase` reads the candidate hashes, deletes
      the rows, then counts remaining references per hash and removes only the files that reach
      zero. **This retires the orphaned-cover-files backlog item.** Ordering is the load-bearing
      part and is documented in that class: counting *after* the delete is what makes zero
      trustworthy (counting before always includes the books being deleted, so nothing would ever
      be cleaned up), and deleting rows before files means a crash between them leaks disk rather
      than leaving surviving books pointing at artwork that is gone.
    - **Known consequence, accepted:** restoring a `.sqlite` backup taken *before* a deletion brings
      those books back pointing at files now removed, so their covers show as missing until a
      backfill re-fetches them. Recoverable, not data loss, and the same situation a CSV import onto
      a new device already produces — for which the Task 14 Phase A backfill is the documented
      remedy.
    - **Selection survives filter and search changes, and a bulk action touches all of it.**
      Reversed in `v0.10.1` after using the app. The original scoped actions to the *visible*
      selection, reasoned as "never act on something the user cannot see" -- and in practice the
      count moved as filters moved, which reads as the selection being silently lost, and the
      delete then half-finished leaving the rest selected and invisible with nothing to explain it.
      Selection is a property of the books, not of the current view.
      - What replaces the safety argument is the **confirmation naming each book it will remove**,
        so "something you cannot see" no longer applies -- it is listed in the dialog. Long
        selections name the first eight and state the remainder as a count rather than truncating
        silently, since a silent truncation would recreate the problem the listing exists to solve.
      - Worth recording how this was found: nine instrumented tests covered the selection mechanics
        and all passed, because they tested that the code did what it was written to do. The
        problem was that what it was written to do was wrong, which only surfaced when a person
        used it.
    - **Bulk reading-status change was not built.** It was floated here as the obvious companion and
      remains cheap now that selection exists, but bulk delete was the motivating case and shipping
      the destructive action with full test coverage was worth more than widening scope. Left in the
      backlog rather than silently dropped.
  - Tests must cover **both** directions, since each failure mode is invisible in the other's test:
    a shared cover file that must **survive** deletion of one of its referencing books, and an
    unreferenced file that must actually **be removed**. A cleanup that only tests the second
    passes while silently breaking surviving books' covers.

## Task 15 — Logging

**Scheduled deliberately early, before the app gets more featureful**, because retrofitting
logging gets monotonically worse and "we can't tell you why it failed" has already become a
recurring answer. `shared/` had no logging facility at all before this task — no `Logger`, no
`Napier`, not even a `println` — and that gap had forced three separate compromises:

- `OpenLibraryIsbnCoverProbe` swallowed a network/TLS failure to `null`, making it
  **indistinguishable from a confirmed "this book has no cover"**. Its KDoc already named itself
  as the first catch block that should adopt logging.
- `BackfillViewModel`'s failure state deliberately **discarded the exception**, so a failed
  backfill could only say "something went wrong" — the cause was thrown away at the catch.
- The vacuous-test investigation on PR #16 needed temporary `println` instrumentation to discover
  that a closed Room database throws `CancellationException` rather than `SQLiteException`. That
  diagnosis should not have required editing production code to obtain.

### Phase A — Logging facility + adoption (done)
- **KMP-clean facility** in `shared/core/util/` (alongside `Resource`/`newId` — a small
  cross-cutting utility, not a domain concept warranting its own `core/logging/` package): a
  `Logger` interface (four levels, a tag, an optional `Throwable`, a lazy message lambda so a
  suppressed call costs nothing to build) plus `platformLogger()`, an `expect`/`actual` seam
  following the `DatabaseFactory`/`DatabaseFileOps` precedent (`android.util.Log` on Android,
  stdout/stderr on JVM). No new dependency (AGENTS.md §5) — hand-rolled, a few dozen lines.
- **Verbosity gated centrally.** `AppLogger` (the production default every adoption site injects,
  overridable exactly like `OpenLibraryIsbnCoverProbe`'s existing `clock` parameter) wraps the
  platform sink with a minimum-level threshold, defaulting to `WARN` until configured.
  `MediaTrackerApplication.onCreate` — the one place `BuildConfig.DEBUG` is visible (`shared/`
  cannot see it; `app/build.gradle.kts` gained `buildFeatures.buildConfig = true` for this) —
  configures `DEBUG` for a debug build or reaffirms `WARN` for a release build. **A release build
  therefore never emits `DEBUG`/`INFO`, only `WARN`/`ERROR`, and a filtered-out call's message
  lambda is never evaluated at all.**
- **Identifier rule, enforced at every adoption site, not just documented**: a `mediaId` or an
  ISBN is fine to log (opaque/edition identifiers, not personal content); a title, author, or
  session note never is. Log *what failed and why*, never *what the user is reading*. No
  crash-reporting service — every sink stays purely local (logcat/stdout), matching AGENTS.md §1's
  no-cloud premise; this is a permanent choice, not a gap, and Phase B's "no encryption at rest"
  decision below leans on this same rule rather than duplicating the guarantee.
- **`RecordingLogger`** (`commonTest`) makes the "does not log user content" half of adoption
  testable, not eyeballed: a test asserts both that a failure was logged (level, tag, cause) and
  that the message contains no book content.
- **Adopted at the three known gaps**, each proven with a `RecordingLogger` test:
  `OpenLibraryIsbnCoverProbe` (logs at `WARN` before still folding to `NotFound` — the KDoc that
  named it "first to adopt logging" is updated to match), `BackfillViewModel` (logs at `ERROR`
  before settling to `Failed`), and the restore/migration paths (`DefaultRestoreDatabaseUseCase`'s
  `stage`/`commit`/swap failures, `validateStagedDatabaseIntegrity`, and every registered
  `Migration` via a `loggedMigration` wrapper that logs the failing schema-version transition and
  rethrows unchanged).
- Incidental fix needed to keep this green: `android.util.Log` throws "not mocked" on the
  `testDebugUnitTest`/`testReleaseUnitTest` variant (host JVM against the android.jar stub, no
  Robolectric — see `shared/build.gradle.kts`'s pre-existing Context-dependent exclusions for the
  same underlying gap). `AndroidLogger` now catches `Throwable` around every `Log.*` call — a
  logging call must never itself become a new source of failure for its caller, on that test
  variant or on a real device.

### Phase B — Persistent, user-owned log store

**B1 (done):** the persistent sink itself — a capped pair of files with single rollover, buffered
appending writes, and sequence numbers derived from both retained files at startup. Plus the
backup/export carve-out, which grew well past its original scope: `backup_rules.xml` and
`data_extraction_rules.xml` turned out to still be the untouched Android Studio sample templates
with every rule commented out, so `allowBackup="true"` had been sweeping the whole app-private
directory — database and covers included — to Google Drive since the first commit. Cloud backup
now transfers nothing; device transfer carries the content-addressed covers and deliberately not
the database, since a raw file copy taken at an instant the app cannot checkpoint is exactly the
hazard `DatabaseBackupUseCase` avoids with `VACUUM INTO`. Reinstall therefore no longer
auto-restores anything, which is intended: the app's own `.sqlite` backup/restore is the path.

**B2a (done):** the in-app log viewer — a snapshot with a refresh divider, genuinely selectable
text, oldest-first with auto-scroll to the tail, and "export full log" for everything beyond the
on-screen window — plus the user-adjustable verbosity setting, both reached from a new Diagnostics
section in Settings.

**B2b (done):** the companion changelog viewer — a build-time copy of `CHANGELOG.md` into assets,
a hand-rolled parser for the Keep a Changelog subset this file actually uses, and the three-level
fold decided below.

Phase A's facility is enough to make a failure diagnosable *while a debugger/logcat is attached*,
but logcat is unreachable for a normal user on a release build — a facility they cannot read does
not serve a personal, local-first app whose whole support model is the user themselves. Phase B
makes logging always-on (not debug-build-gated) and gives the user a way to see and share it.

- **Persistent sink** behind Phase A's `Logger` interface: a capped file in app-private storage
  with single rollover (current + previous — worst case 2× the cap, a few MB total). **Not a Room
  table** — that would bloat the database that gets backed up and CSV-exported.
- **Must be excluded from backup and export — the non-obvious hazard.** The app now has `.sqlite`
  backup, CSV export, and `android:allowBackup="true"`, so without a deliberate carve-out a log
  file would ride along inside a backup the user shares, and Android Auto Backup would sweep it to
  Google Drive — directly contradicting the local-first premise. Needs `dataExtractionRules`/
  `backup_rules` exclusions *plus* explicit exclusion in the export/backup code paths themselves,
  and tests proving a backup and a CSV export both contain no log data.
- **Buffered writes.** A bulk backfill over hundreds of books must not hit disk synchronously per
  log entry — a bounded in-memory buffer, flushed periodically and on demand.
- **In-app viewer** in Settings, with genuinely selectable text (`SelectionContainer`), not just a
  copy-everything button. Compose constraint to design around: selection across a `LazyColumn`
  breaks as items recycle, so this is a bounded recent-entries view in a scrollable `Column` inside
  `SelectionContainer`, plus a separate "export full log" path for everything beyond that window.
  - **Snapshot viewer, not a live tail.** The view shows entries as of when it was opened; logging
    continues in the background but the view does not auto-update. A Refresh action pulls in
    what's accumulated since, with a divider marking where the newly-arrived entries begin.
    Rationale: a live-tailing view actively fights text selection (new entries reflow the list
    mid-drag), so auto-update and genuinely selectable text are mutually exclusive — freezing the
    view is what makes selection usable.
  - **Boundary marking uses a monotonic per-entry sequence number, not timestamps** — two entries
    can share a millisecond, and the wall clock can jump backwards (NTP sync, a user changing the
    device time), either of which would scramble a timestamp-based boundary. The viewer keeps
    `snapshot` plus a `boundary` seq: on open, `boundary = null` (nothing is new); on refresh,
    `boundary = snapshot.maxOf { it.seq }` *before* reloading. The divider renders before the first
    entry with `seq > boundary`. This survives repeated refreshes and rotation dropping old
    entries, since it never depends on list position or count.
  - **Sequence numbering across process restarts: an in-memory counter, initialized at startup from
    the maximum sequence found across the retained log files (current *and* rolled-over) — no
    separately persisted counter.** A counter persisted independently (e.g. in `app_settings`) can
    drift from the store — write one, crash before the other, and the app starts assigning sequence
    numbers *below* entries already on disk, silently corrupting every later boundary comparison.
    Deriving from the store makes the store the single source of truth, so drift is impossible by
    construction. Must scan **both** retained files: a rotation that just started a fresh, empty
    current file would otherwise reset the counter while higher sequences still exist in the
    previous one. The degenerate case (no retained entries at all) is safe — starting from zero is
    correct, since there is nothing yet for a boundary to be compared against.
  - **Ordering decided: oldest-first, newest at the bottom** — the terminal convention (`tail -f`,
    log files), read top-to-bottom chronologically. This is what makes the snapshot divider read
    correctly: in ascending order the divider sits *above* the fresh entries ("everything below
    this line is new"), whereas newest-first would place the marker below the entries it marks.
    Paired with **auto-scroll to the bottom on open**, as a terminal parks you at the tail.
    **Deliberately not a user setting** — considered and rejected: the cost of changing ordering
    later is social (user muscle memory), not technical, so a setting doesn't solve the real
    problem, it just pushes the decision onto the user, and it would double the tested surface
    since both the divider placement and the auto-scroll direction flip with it. Express the order
    as a single named constant in the viewer instead, so it can be flipped in one place if this is
    ever revisited.
  - **No pending-count badge.** "3 new entries" requires live observation, which reintroduces
    exactly what the snapshot model avoids. A plain Refresh that reveals what's new is simpler and
    consistent.
  - Consequence: the viewer needs **no reactive `Flow`** — a suspend "read current entries" call is
    enough, less machinery than a live stream.
- **User-adjustable verbosity** in Settings, persisted via the existing `app_settings` store, with
  a sane (not chatty) default — a chatty default would blow through the size cap and capture more
  than intended.
- **Companion: an in-app "What's new" changelog viewer.** Deliberately scheduled *with* this phase
  rather than on its own, because it is the same shape — a read-only, genuinely selectable text
  view reached from a Settings row — so solving that pattern once covers both, instead of building
  it twice.
  - **Build-time copy, not a second file.** A Gradle task in the app module copies the root
    `CHANGELOG.md` into assets before asset merging; the copy is a gitignored build artifact, so
    `CHANGELOG.md` stays the single source of truth and a stale duplicate can never be committed.
    Read at runtime via `context.assets`. No new dependency.
  - **The `AnnotatedString` parser is scheduled into B2, not deferred past it.** Originally listed
    as optional polish on top of a plain-text dump. Moved into B2's scope because the structural
    decision below will need it anyway: the `**bold**` lead the parser extracts *is* the
    collapsible header text, so the two compose rather than compete, and the marginal cost over
    plain text is a fold state map plus an expander row. Still the same tiny, predictable subset
    (bold + inline code) — a full Markdown dependency for one screen would still not clear
    AGENTS.md §5.
  - `versionName` is already single-sourced from `[versions] app`, so the app knows its own
    version and can open on the matching section by default, with older releases behind a scroll.
  - **Known tension — RESOLVED: three-level collapsible, keeping the developer-facing text as
    written.** Neither original option survived measuring the actual file. Raw dump loses because
    the sections are wildly uneven — `[0.7.0]` is 335 lines / 4,138 words against `[0.1.0]`'s 38 /
    313, a 13× spread, and the single longest bullet is 109 lines, nearly 3× the whole `[0.1.0]`
    section. That is dozens of phone screens with no way to skim. Summary-paragraph-only loses
    because it discards the detail that makes this changelog useful to the only user who reads it,
    and because the assumption it rested on is not actually uniform: `[Unreleased]` — the section
    most often being looked at during development — has *no* preamble at all, and `[0.8.0]` has
    *two* paragraphs, so "the summary paragraph" is not reliably singular. The robust rule is
    "everything before the first `###`".
  - **Why extraction is safe here:** the structure measured consistent enough to fold on. Nesting
    is effectively two levels (70 bullets at depth 0, 100 at depth 2, only 3 deeper), and 58 of 70
    top-level bullets lead with `- **Bold title**`. Critically, the 12 that don't are all short
    one-liners, so the entries that *need* folding are exactly the ones carrying a clean header,
    and the ones without a header are already short enough to render flat. Degradation needs no
    fallback logic beyond "no bold lead → render inline", so a format drift in a future entry
    yields a slightly-less-tidy screen, never a broken or empty one.
  - **Shape to build in B2:** version (collapsed except the running `versionName`'s) → preamble
    always visible once a version is expanded → each `**bold**` top-level bullet its own
    collapsible row. **Nothing in this bullet is built yet** — B1 shipped only the log store and
    the backup carve-out; the decision above is recorded here so B2 starts from a settled design
    rather than re-deriving it, not because any of it exists.
  - **Consequence for the pairing rationale, recorded honestly:** with collapsibles the two
    viewers are no longer quite "the same shape" — the log viewer stays a flat `SelectionContainer`
    `Column` while this one gains structure. The shared part (read-only genuinely-selectable text
    behind a Settings row) still holds, so pairing them is still right, just a thinner win than
    written above. Note also that expand/collapse reflows text mid-drag exactly the way live
    tailing would; what makes it acceptable here and not there is that it is user-initiated rather
    than arriving on its own, so it never fights a selection unpredictably.
- **Encryption at rest considered and NOT planned** — recorded here so it isn't silently revisited:
  app-private storage is already sandboxed; the obvious library
  (`androidx.security:security-crypto`) is deprecated and Android-only (would break KMP purity);
  and since the user can read and send these logs themselves, at-rest encryption doesn't change who
  ultimately sees them. Phase A's "never log the library as data" rule is the stronger guarantee —
  you cannot leak what was never written. Revisit only if a concrete threat model demands it.

**C (done):** adoption across the remaining error sites and `INFO` lifecycle tracing, with the
default verbosity moved to `INFO` — see that phase's section for what the sweep uncovered. Task 15
is complete.

### Phase C — Adoption coverage and lifecycle tracing (done)

Phase A adopted logging at exactly three sites and stopped, which was right for the problem it
solved and left most of the codebase folding its causes into a message string and dropping them.
Phase C closed that, and added the `INFO` tracing that makes the log worth reading *before*
something breaks.

Measured on `shared/src/commonMain`, before and after:

| | Before | After |
| :--- | ---: | ---: |
| Log calls | 14 | 43 |
| Files that log at all | 7 of 113 | 20 of 113 |
| `INFO`/`DEBUG` call sites | 0 | 6 |

Adopted at `BookRepository` (6), `ReadingSessionRepository` (3), the whole portability layer
(import, export, backup, import-write), and the ingestion clients (Open Library, Google Books,
cover download). `INFO` lifecycle tracing at app start, import/export completion, and backfill
start/finish.

**What the sweep actually turned up, none of which was the mechanical work it was scoped as:**

- **`BackfillViewModel.init` could crash the app.** It launched an unguarded suspend DB read into
  `viewModelScope`, where an uncaught exception takes the whole scope down — a crash on opening
  Settings, from a read whose only job is to restore a progress bar. This was the intermittent CI
  failure on the `v0.10.1` branch, misread at first as test-lifecycle noise.
- **Two silent drops that map to real user questions.**
  `OpenLibraryClient.fetchAuthorName` swallowed every failure and returned `null` — the exact "why
  has this book got no author?" symptom — and it swallowed along *two* paths, the `catch` and a
  non-2xx return that never throws, the latter being the likelier one. `saveImage(...).getOrNull()`
  in ingestion and backfill did the same for "why has this book got no cover?".
- **Cancellation was being logged as failure.** None of the adopted catches rethrew
  `CancellationException`, which on JVM *is* an `Exception` — so adoption alone would have written
  a spurious ERROR every time a screen closed mid-write, making the log worse as it got wider.
  Every adopted site now rethrows first.
- **Rejection reasons embed raw cell values.** `reject("$field ... : '$raw'")` means logging them
  would put arbitrary column contents, titles included, into a file that outlives the import. Import
  rejections are therefore summarised by count and row number, one bounded entry per run rather
  than one per row.

**The default moved to `INFO`, and `Debug` left the picker.** Adding `INFO` sites alone would not
have fixed the empty-log complaint: the never-set case resolves to the build-type bootstrap in
`MediaTrackerApplication`, not to `DEFAULT_LOG_VERBOSITY`, so both had to move. `DEBUG` is no longer
offered because there is still not one `DEBUG` call site — it promised detail identical to
`Detailed`. A value already persisted as `DEBUG` is left working rather than rewritten. Restore it
to the picker the moment anything logs at that level.

**Deliberately still unlogged**, so a future sweep does not read these as misses: `FileLogSink` and
`LogFileStore` (the logging facility itself — logging from it recurses), and the row-level CSV
parsers, which return `Rejected(reason)` as a value rather than discarding it and are covered by the
summary above.


## Task 16 — Signing & distribution

Not scheduled out of ambition — this exists because the app is sideloaded, has no update path, and
one of the decisions involved is genuinely irreversible. Recording it before it is needed is the
whole point.

### The part that cannot be undone: the signing key
`app/build.gradle.kts`'s `release` block sets `isMinifyEnabled` and `proguardFiles` but **no
`signingConfig`**, so `assembleRelease` currently produces an unsigned APK. Fixing that means
generating a keystore, and two consequences follow that are worth stating plainly *before* anyone
generates one:

- **An APK signed with a different key cannot update an installed app.** Android requires
  uninstall-then-reinstall, which wipes app-private storage — the database and every downloaded
  cover. Development has been sideloading *debug*-signed builds (`installDebug`), so the first
  release-signed install is exactly this case. Do it at a moment when a fresh `.sqlite` backup has
  just been taken and verified; the backup/restore built in Task 8 is precisely the mechanism, but
  it only helps if someone remembers to use it first.
- **Losing the keystore is permanent.** No update can ever be shipped to an installed copy again,
  by anyone, ever. Back it up somewhere separate from this repository, and never commit it.

### Debug builds install as a separate app (done)
`applicationIdSuffix = ".debug"` on the debug build type, with a distinct label in
`src/debug/res`. Recorded here because the reason is easy to lose and expensive to relearn.

Android identifies an app by `applicationId`. Without the suffix, debug and release are the *same*
app signed with *different* keys, so installing either over the other forces an uninstall — which
wipes the database and every downloaded cover. **This happened during development and took a real
library with it**, via a routine `installDebug` during instrumented-test work. The suffix makes them
coexist as separate installs with separate data, which also means `connectedDebugAndroidTest` runs
against the debug app and can never reach a release build's data.

Note what it does *not* fix: re-signing the **release** app with a different key still forces a
wipe. That is the keystore hazard below, unchanged. `DebugApplicationIdTest` guards the suffix,
since losing it would silently restore the hazard and nothing else would notice.

### Build and publish (CI)
GitHub Actions on tag push: build the release APK, sign it with a keystore held in repository
secrets, attach it to the GitHub Release for that tag. `versionCode` already derives from
`[versions] app` (AGENTS.md §8), so it increases monotonically with no extra step — which is what
Android requires of an update.

Note the same workflow should run `./gradlew :shared:jvmTest :shared:testDebugUnitTest`, since
nothing currently runs the test suite anywhere but a developer's machine. The instrumented tests
(AGENTS.md §7) need a device and would require a hosted emulator, so they stay a manual step unless
that is separately worth setting up.

### In-app update check — decided: talk to GitHub directly, not through a proxy
`https://api.github.com/repos/OWNER/REPO/releases/latest` already returns the tag and the APK's
download URL. The app compares that tag against `BuildConfig.VERSION_NAME` and offers the download.
Unauthenticated GitHub API allows 60 requests per hour per IP, which is irrelevant at this app's
scale. **No third-party service is required for any of this**, which matters: an update check is
the only network call this app would make that is not a user-initiated metadata lookup, and routing
it through infrastructure the developer operates would be the first piece of always-on phone-home
in a codebase whose whole premise (AGENTS.md §1) argues against exactly that.

**A Cloudflare Worker (or equivalent) was considered and is NOT planned, with two specific
conditions that would change the answer** — recorded so this is re-decided on evidence rather than
re-argued from scratch:
1. **The repository becomes private.** A Worker holding a token could then serve a minimal
   `{"version","url"}` publicly without exposing the repo.
2. **The update URL needs to outlive its host.** The app would hard-code the GitHub API path;
   renaming the repo or moving hosts silently breaks update checks for every installed copy, with
   no way to notify them. One indirection the developer controls fixes that permanently.

Neither is true today, and adding a moving part for a hypothetical is how a local-first app
acquires a backend by accident. Trimming a ~10 KB API response to ~80 bytes and edge-caching it are
real but do not justify the dependency on their own.

**Explicitly not planned:** remote config, feature flags, and kill switches. For an app whose user
is also its developer, "ship a new build" is as fast as "change a config value", so these would add
a network dependency and a standing phone-home to solve a problem that does not exist here. This is
the same reasoning that rules out crash reporting (see `AppLogger`'s KDoc) and is expected to hold
for the same reasons.

### Sequencing note
The signing half can be pulled forward independently of the CI and update-check work, and there is
a mild argument for doing so: the cost of the first release-signed install is a backup-and-restore
cycle, and that is easier to do deliberately now than to discover later, mid-something-else.

## Blocked on external changes

Nothing to schedule — these unblock when an upstream dependency moves.

- AGP 9 workaround: remove `android.builtInKotlin=false` + `android.newDsl=false` once KSP
  supports `com.android.kotlin.multiplatform.library` (google/ksp#2476); flags die in AGP 10.
- Lifecycle pinned to 2.10.0 and core-ktx to 1.17.0 until the project moves to compileSdk 37.

## Backlog / tech debt

Actionable, none of it blocking. Anything here that grows past "small" should be promoted to a
numbered task rather than left to be rediscovered.

- **Nothing tests that logging is actually wired up.** The store, codec, sink, composite logger,
  viewer state and viewer rendering are all covered, but the composition that connects them is not:
  `MediaTrackerApplication.onCreate`'s `AppLogger.configure(minLevel, FileLogSink(store)
  .withPlatformLogger())` could be deleted and the entire suite — unit and instrumented — would
  still pass while nothing was ever written to a log file. Same for `applyPersistedLogVerbosity`:
  if that collector never ran, changing "Log detail" in Settings would silently do nothing. Both are
  the wired-to-nothing shape this project has now shipped three times.
  - **Eyeballing it does not substitute.** Adoption sites log on failure paths only, so an empty log
    is the *expected* result when nothing has gone wrong — "no entries" cannot distinguish
    working-and-quiet from completely unwired.
  - **Cheapest test with real teeth**, and it needs no device: configure `AppLogger` exactly as the
    Application does (a real `FileLogSink` over a temp directory), log through `AppLogger`, assert
    the entry reaches the file. That covers the composition seam in `commonTest`. It still would not
    cover the literal `onCreate` line, which would need an instrumented test that triggers a known
    failure and reads the store back.

- **Read-state-too-early audit: complete.** Four tests across three classes failed CI by asserting
  on ViewModel state before it propagated. All are fixed, and every remaining `.value` read in
  `shared/src/commonTest/.../ui/` has now been classified rather than left as an open question.
  - **The criterion is not which class, it is how the ViewModel exposes state.** The race exists
    only where `uiState` is built with `combine` -> `stateIn` over a Room `Flow`: there is a real
    dispatch between the action and the emission. A ViewModel that exposes a `MutableStateFlow`
    directly (`ImportViewModel`, `ExportViewModel`, `AddBookViewModel`, `ChangelogViewModel`) sets
    `.value` synchronously, so reading it straight after an action is safe *by construction* — not
    by timing luck, and not something a loaded CI runner can change.
  - **Room-backed and disciplined:** `BackfillViewModelTest` (`waitUntilOrTimeOut`) and
    `LogViewerViewModelTest` (`awaitLoaded`) already await before every post-action read.
  - **`SettingsViewModelTest` and `StatsViewModelTest`** hold one `.value` read each, both of the
    *initial* state with no preceding action — nothing to race. Worth noting these are Room-backed
    and were not on the list of classes flagged for audit; the audit found the risk profile splits
    by state-exposure shape, not by the classes anyone guessed at.
  - AGENTS.md §7 states the blanket rule deliberately ("never read `.value` straight after an
    action"), rather than this nuance. The nuance is for auditing existing tests; the blunt rule is
    the right thing to follow when writing new ones.
  - **One failure in this family was not in this family at all**, and it took three attempts to see
    that. `EditBookViewModelTest.save_doubleTapBeforeCompletion_persistsOnlyOnce` was not reading
    state too early — it could not *create* the condition it tested. Under the eager default
    dispatcher the first save sometimes ran to completion inside the call that started it, clearing
    `saveInFlight`, so the second save legitimately proceeded and won. The guard was correct
    throughout; on those runs the test never exercised it. Fixed with a `StandardTestDispatcher`
    across the two calls, and verified by removing the guard — the test now fails, which it did not
    reliably do before.
  - **The lesson that generalises is about diagnosis, not dispatchers.** Two of the three attempts
    were reasoned from a CI console log that truncates the comparison values. Downloading the
    workflow's test-report artifact (`gh run download <run> -n test-reports`) gave
    `expected:<[First] Call Title> but was:<[Second] Call Title>`, which ended the guessing in
    seconds. Reach for the artifact before theorising.

- **The single-book cover re-fetch still reports a rate-limit as "no cover".** Task 14 Phase A
  taught `OpenLibraryIsbnCoverProbe` to distinguish 429/5xx (`RateLimited`) from 404 (`NotFound`),
  and the bulk backfill acts on that — but `FallbackBookMetadataProvider`, which the interactive
  per-book path goes through, still folds `RateLimited` back into "no cover for this call". The
  reasoning is sound as far as it goes (a one-off lookup has no retry loop to pause) and nothing
  incorrect is *persisted* — the book simply keeps no cover — but the user is told the book has no
  cover when the provider merely refused, which is misleading right after a backfill has consumed
  the quota. Small fix: surface the rate-limited case to the interactive caller so it can say
  "try again shortly" instead.

- On-device smoke test of the full add-book flow (only exercised via JVM tests so far); the
  first on-device migration checks (v1 → v2, then v2 → v3) ride along with the next installs.
- Session edit truncates `timestampEnd` seconds to `:00`. Material 3's `TimePicker` has no
  seconds field, so re-saving an edited session rebuilds its end timestamp at `:00` even
  when the time was never touched. Judged low priority rather than fixed alongside the
  duration-precision fix: seconds are never displayed (history renders `HH:mm`), truncation
  only ever moves the value earlier *within the same minute* so it cannot shift a session
  across a calendar day (streaks/period bounds are unaffected), and `durationSeconds` is now
  preserved exactly, so nothing sums the drift. Fix if it ever matters by applying the same
  "was it touched?" tracking used for duration to the date/time pickers.
- **`releaseYear` now means different things depending on how a book was added.** ISBN ingestion
  reads Open Library's *edition* record, so it stores the printing's year (a 2026 anniversary
  edition of a 2016 novel stores 2026). The Goodreads importer (Task 8 Phase D) deliberately
  prefers `Original Publication Year`, so the same book imported that way stores 2016. Both are
  defensible in isolation, but one library holding both conventions means sorting or filtering by
  year silently mixes "when this work was written" with "when my copy was printed". Decide on one
  meaning and make both paths agree — and note that whichever is chosen, the *other* is genuinely
  useful information the schema has nowhere to put, so the real fix may be storing both.
  **Update (PR review, second round):** this was also a duplicate-book hazard, not just a
  display/sort inconsistency — `ImportDataUseCase`'s `media_id` → `isbn` → `title`+`release_year`
  matching could miss all three tiers for the exact "same book, disagreeing years, and/or a
  different edition's ISBN" case this bullet describes, silently inserting a second copy instead of
  the `DuplicatePolicy.MERGE` backfill the user expects. Mitigated **without a schema change**: a
  fourth, last-resort case-insensitive **title-only** matching tier now catches this (reached only
  when the stronger tiers, including exact title+year, all fail), with every such match surfaced in
  `ImportSummary.notes` for the user to verify (title-only matching has a real false-positive risk
  — two unrelated books sharing a title, with no year or author to disambiguate — so it is reported,
  never applied silently). This closes the silent-duplicate hazard but does not fix the underlying
  ambiguity above: the **proper fix is still the schema change this bullet already called for** —
  add a second nullable year column (e.g. `originalReleaseYear` alongside the existing
  edition-oriented `releaseYear`) so both are stored, `ImportDataUseCase` can match on either, and
  sort/filter can pick one deliberately instead of whichever import path happened to run last. Not
  done this round because a schema bump was out of scope for this fix; revisit alongside whichever
  future task next needs a schema version bump.
- Bulk cover backfill — **promoted out of the backlog to Task 14**, prompted by a Goodreads import
  leaving a whole library coverless. See that task for the throttling constraints.
- Manual cover entry (paste a URL or pick a local image, Task 6 Phase E). Deferred because a
  local-image picker needs a file-picker permission story (`ACTION_OPEN_DOCUMENT`/photo picker
  scoped storage considerations) — Task 8 establishes exactly that plumbing for CSV/backup, so
  this becomes cheap once Task 8 lands and should be picked up right after it.
- **No book rating field.** The schema has nothing to hold a per-book rating today, which is also
  why the Goodreads import (Task 8) has no home for the `My Rating` column. Worth considering on
  its own merits independent of that import, not only as an import-completeness gap.
- **Normalized `people` table with role-qualified relations** — the eventual right shape, and a
  better target than the "authors table" this note originally proposed. A `people` table plus a
  `person ↔ media ↔ role` join generalizes past authors to narrators and cover artists, and then
  to directors and actors when Task 13 adds movies/TV; an authors-only table would have to be
  rebuilt at that point. Promotes `book_details.authors` off Task 9 Phase A's single denormalized
  `String` column.
  - Not urgent, and deliberately not done first: the column already captures every name this app
    can source, and Phase A's priority was that author data was being *destroyed* on every
    ingestion, so capturing something immediately beat capturing it perfectly. The choice is
    reversible in the direction that matters — people rows can be *derived* from the stored
    strings (split on `AUTHOR_SEPARATOR`, dedupe by normalized name, backfill the join table),
    whereas names never stored are unrecoverable.
  - Note the table does **not** solve name identity by itself: providers disagree on formatting
    ("J.R.R. Tolkien" vs Goodreads' `Author l-f` "Tolkien, J. R. R."), so establishing that two
    strings are one person is fuzzy-matching work that has to happen on the way in either way.
    That work — not the schema — is the real cost, which is why it waits until a feature needs
    per-person identity ("tap an author → every other book by them").
  - Sequencing note: worth doing **before or with Task 13**, since movies/TV will otherwise
    invent their own parallel credits model. See `BookDetailsEntity.authors`'s KDoc for the
    per-column rationale.

## Unscheduled features

One item. Everything else that used to sit here now has a task number — if this section grows
past a couple of entries again, that is the signal to schedule rather than to keep adding.

- **Purchase/borrow tracking** (lending a book out, borrow sources, purchase history), implied
  by the vision doc's "filtering across ... purchase dates" and its "captures ... purchase date,
  price, and store source". `purchasePrice` exists on `MediaItemEntity`, but there is no purchase
  *date*, no store source, no lending state, and no borrow source. Prerequisite for two deferred
  things: the Book Detail "Purchase & Borrow" tab (Task 6 Phase D) and the vision's **financial
  analytics** (shelf totals, average cost per book, cost per page read), which were explicitly
  declined for now — cost-per-page in particular also needs page counts to be trustworthy.
  Unscheduled because nothing downstream is currently waiting on it; schedule it when the
  financial analytics or the lending workflow actually matter. Schema bump + tested migration
  required.
  - **Receipt parsing is where ML Kit Entity Extraction would pay off**, and the only place in
    this app it plausibly does. It runs on-device (fits the privacy stance) and detects dates,
    money, and addresses in one pass — so "photograph or paste a receipt → purchase date, price,
    store source" is a real fit, unlike ISBN extraction where a regex is strictly better (see
    Task 9's paste-to-add bullet). Costs: a Play Services/ML Kit dependency in the same family as
    the barcode-scanner decision, plus a multi-MB language model downloaded at first use. Only
    worth taking on if this feature is scheduled *and* receipt capture is actually wanted;
    hand-entering a date and price is not obviously worse.

## Source of truth note

`docs/project-idea.md` is the original vision and contains requirements that never reached this
roadmap (genre tracking and data portability both went missing for months, and were only caught
by auditing that document against this one). When adding a task, check it. Items from it now
accounted for: data portability (Task 8), manual entry (Task 9), re-read mechanics (Task 10),
velocity + heatmap (Task 11), genres (Task 12), unified dashboard (Task 13 note), purchase/store
source and financial analytics (Unscheduled), orphaned-cover garbage collection (backlog).

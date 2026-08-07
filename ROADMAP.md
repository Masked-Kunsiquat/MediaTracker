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

1. **Task 9 — Search & discovery** ← in progress (Phase A done: authors + local search)
2. Task 14 — Bulk operations & cover backfill
3. Task 10 — Re-read modeling (ratings land here)
4. Task 11 — Analytics & stats revamp
5. Task 12 — Genre tracking
6. Task 13 — Movies & TV

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
- **Room schema v5** (read-through table or session grouping column + tested `Migration_4_5`),
  with the migration assigning every existing session to read-through #1 so no history is lost.
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
- **Room schema v6** (+ tested `Migration_5_6`).
- Open question when scheduled: whether to seed suggestions from provider metadata (Open
  Library `subjects` are numerous and messy; Google Books `categories` are cleaner but coarse) or
  keep the taxonomy purely user-authored.

## Task 13 — Movies & TV

- TMDB client (primary API per AGENTS.md §4); TMDB requires an API key even on the free
  tier and keys must never be hardcoded — plan is a user-supplied key entered in settings.
- `MovieDetails` / `TVDetails` child tables + `WatchLogs` → next Room schema bump (v7, assuming
  v4 tracking mode, v5 read-throughs, and v6 genres land first) under the §8 schema-freeze rule
  (version bump + tested `Migration`).
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

- **Bulk cover backfill.** Promoted from the backlog, where it was deferred out of Task 6 Phase E.
  Serves three cases that all produce coverless books: a Goodreads import (`GoodreadsCsvImporter`
  sets `coverImageHash = null` by design — Goodreads exports carry no cover data), a CSV import
  onto a **new device** (the CSV carries cover *hashes* but no image bytes, so every hash points at
  a file that isn't there), and books added before the field-level cover fallback existed.
  - **Throttling is the hard part, and the reason this was deferred.** The last-resort
    `?default=false` ISBN probe (`OpenLibraryIsbnCoverProbe`) is ISBN-keyed and therefore subject
    to Open Library's 100-requests-per-IP-per-5-minutes cover limit, unlike the ID-keyed fetches
    `OpenLibraryClient` normally uses. A naive loop over `RefetchCoverUseCase` would trip it partway
    and look like a broken feature.
  - **One limiter, shared by every ISBN-keyed probe — not a bulk-only one.** The quota is per IP,
    so a backfill and the interactive per-book re-fetch draw on the *same* budget: giving the bulk
    path its own limiter while the interactive path stays unthrottled means a user tapping
    "re-fetch cover" during a backfill can silently push the total over the limit, and the backfill
    takes the blame. The limiter belongs at the `OpenLibraryIsbnCoverProbe` layer that both call
    paths already funnel through, tracking consumed quota across both. Requirements:
    - **Shared quota tracking** across bulk and interactive callers.
    - **Honour 429s with backoff** rather than treating a rate-limit response as "this book has no
      cover" — the current probe maps every non-2xx to "no cover", which would permanently mark
      books coverless for what is really a temporary refusal. This is the one place the existing
      probe's behaviour is actively wrong for bulk use and must change, not just be wrapped.
    - **Persisted resume state**, so a backfill interrupted by the quota, by cancellation, or by
      process death continues from where it stopped instead of restarting or being abandoned.
      Partial progress must be reported honestly ("312 of 480 done, paused until the quota
      resets"), never surfaced as an all-or-nothing failure.
  - Needs progress and cancellation UI — this is a long-running network operation over a whole
    library, not a single tap. Consider offering it directly after an import completes, since that
    is the moment the need is obvious, as well as from Settings for a one-off pass.
  - Books with no ISBN can never be backfilled from a provider; report them rather than retrying
    forever, and note that manual cover entry (still in the backlog) is their only route.
- **Library multi-select and bulk actions.** Long-press a library card to enter selection mode,
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
  - Tests must cover **both** directions, since each failure mode is invisible in the other's test:
    a shared cover file that must **survive** deletion of one of its referencing books, and an
    unreferenced file that must actually **be removed**. A cleanup that only tests the second
    passes while silently breaking surviving books' covers.

## Blocked on external changes

Nothing to schedule — these unblock when an upstream dependency moves.

- AGP 9 workaround: remove `android.builtInKotlin=false` + `android.newDsl=false` once KSP
  supports `com.android.kotlin.multiplatform.library` (google/ksp#2476); flags die in AGP 10.
- Lifecycle pinned to 2.10.0 and core-ktx to 1.17.0 until the project moves to compileSdk 37.

## Backlog / tech debt

Actionable, none of it blocking. Anything here that grows past "small" should be promoted to a
numbered task rather than left to be rediscovered.

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
- Orphaned cover files: deleting a book leaves its content-addressed cover on disk
  (dedup means the file may be shared by other books, so deletion needs a reference check
  or a periodic sweep).
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
- **Normalized authors table** (promote `book_details.authors` off Task 9 Phase A's single
  denormalized `String` column). A contained follow-up, not urgent: the current column already
  captures every author this app can source, and nothing today needs per-author identity — this
  only becomes worth doing once "tap an author → see every other book by them" is an actual
  feature, at which point the table can be *derived* from the strings already stored (parse on
  `AUTHOR_SEPARATOR`, dedupe by normalized name, backfill a join table) rather than requiring a
  fresh migration from a blank column. See `BookDetailsEntity.authors`'s KDoc for the full
  denormalized-vs-table rationale.

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

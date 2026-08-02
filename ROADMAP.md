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

## Task 6 — Books polish (done — ready for release)

The book domain gets finished before any other media type starts: real-world use of
v0.3.0/v0.4.0 surfaced too many rough edges (wrong provider page counts with no way to
correct them, redundant form fields, no session editing, no reading status) to justify
going wide. Movies & TV move to Task 9.

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

## Task 7 — UI revamp & settings (next)

Task 6 made the book domain functionally complete; this task makes it pleasant. Prioritized
ahead of search because these are the screens in daily use, and the rough edges were reported
from real use rather than inferred.

- **Details tab revamp.** Currently a plain metadata list plus the timer. Needs a considered
  layout/visual hierarchy rather than stacked `Text` rows.
- **Reading history revamp.** A timeline view rather than a flat list, with the individual
  session events rendered more cleanly (dates, durations, progress deltas as visual elements
  instead of concatenated strings).
- **Edit screen revamp.** Same treatment — it is currently a bare column of text fields.
- **Explicit per-book tracking mode (pages vs percent).** Today the mode is *inferred* from
  whether `totalPages` is known, which is invisible to the user and flips silently the moment
  total pages is edited. Replace with an explicit per-book field, editable on the Edit screen,
  defaulted intelligently on ingestion (known page count → pages; ebook without one → percent).
  Requires **Room schema v4** (tracking-mode column + tested `Migration_3_4` per AGENTS.md §8).
- **Settings screen** — the app has no home for app-wide preferences and now needs one. First
  occupant: **week start day** (Monday per ISO-8601, or Sunday per US convention), which drives
  the Stats screen's period bounds.
  - Stats period semantics decided: "this week"/"this month" stay **calendar** periods (week =
    the chosen start day 00:00 → same weekday next week; month = 1st → 1st, local timezone),
    NOT rolling 7/30-day windows. Only the week's start day becomes configurable. Note the
    existing documented staleness: period bounds are computed when the ViewModel is constructed,
    so a session spanning midnight/Monday rollover needs a re-subscribe to re-bucket.

## Task 8 — Data portability

The vision doc calls CSV export/import and `.sqlite` backups "first-class support," but none of
it exists — which means an app whose entire premise is *no cloud* currently offers the user no
way to get their data out or back it up. Real reading data is accumulating now, so this is
scheduled ahead of search. (`android:allowBackup="true"` means Android Auto Backup may be
snapshotting the database to Google Drive, but that is invisible, size-capped, not restorable on
demand, and depends on exactly the cloud this app's premise rejects — it is not the answer.)

- **CSV export**: `library_export.csv` and `reading_logs_export.csv` per the vision doc. Must
  round-trip everything the schema holds, including nullable `durationSeconds` (unknown must not
  export as `0`), reading status, `finishedAt`, and formats.
- **CSV import**: the harder half. Needs a duplicate policy (match on ISBN? on title+year?
  skip/merge/replace), validation mirroring the use-case layer rather than a second divergent
  copy, and an all-or-nothing transaction so a malformed row can't half-import a library.
- **`.sqlite` backup + restore**: whole-database file copy out, and restore back in. Restore must
  refuse a file whose `user_version` is newer than the running app understands, rather than
  letting Room fail obscurely at open time.
- Establishes the Storage Access Framework / file-picker plumbing the app has never needed
  before — which also makes the deferred **manual cover entry** backlog item cheap afterward.

## Task 9 — Search & discovery

ISBN-only entry is the book domain's remaining bottleneck — a book can only be added while
physically in hand (or with its ISBN hunted down), so title/author search is what actually
completes the add-books experience, and local library search matters as the library grows.
Movies & TV is a much larger lift (new API + user-supplied key, two new tables, a schema
migration, library UI generalization) and shouldn't gate that. No schema change is needed for
this task (a title/author index would itself be a schema change + migration if ever added, but
personal-scale libraries don't need one).

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
- Bulk cover backfill across a whole library (Task 6 Phase E only implemented the per-book
  re-fetch affordance). The last-resort `?default=false` ISBN cover probe
  (`OpenLibraryIsbnCoverProbe`) that a backfill would lean on most heavily is ISBN-keyed and
  therefore subject to Open Library's 100-requests-per-IP-per-5-minutes cover rate limit
  (unlike the ID-keyed fetches `OpenLibraryClient` normally uses), so a bulk pass needs its own
  throttling before it can safely loop `RefetchCoverUseCase` over an entire library.
- Manual cover entry (paste a URL or pick a local image, Task 6 Phase E). Deferred because a
  local-image picker needs a file-picker permission story (`ACTION_OPEN_DOCUMENT`/photo picker
  scoped storage considerations) — Task 8 establishes exactly that plumbing for CSV/backup, so
  this becomes cheap once Task 8 lands and should be picked up right after it.

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

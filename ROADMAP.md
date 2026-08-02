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
going wide. Movies & TV move to Task 8.

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

## Task 7 — Search & discovery

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

## Task 8 — Movies & TV

- TMDB client (primary API per AGENTS.md §4); TMDB requires an API key even on the free
  tier and keys must never be hardcoded — plan is a user-supplied key entered in settings.
- `MovieDetails` / `TVDetails` child tables + `WatchLogs` → next Room schema bump (v4,
  assuming Task 6 Phase C lands v3 first) under the §8 schema-freeze rule (version bump +
  tested `Migration`).
- Library/media-type UI generalization (type filter, non-book detail screens).

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
  scoped storage considerations) that belongs in its own phase rather than riding along with the
  rest of Phase E's provider-driven cover fixes.

# Changelog

All notable changes to the Local-First Personal Media Hub will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **"What's new" no longer shows build-pipeline notes.** This file is copied into the app verbatim, so entries written for whoever maintains the repository — CI jobs, lint configuration, secret scanning — had been appearing on that screen alongside the release notes written for you. They now live under an `### Internal` heading that the viewer skips, so the screen shows only the half addressed to its reader. One file still, and nothing is deleted: the entries are still here for anyone reading the repository.

### Internal
- **Automated changelog enforcement (CI)** — The CI pipeline now verifies that every pull request modifying core code (`shared/` or `app/src/main/`) also includes an update to `CHANGELOG.md`. This ensures architectural and user-visible changes are documented as they happen, maintaining the project's "Keep a Changelog" discipline (AGENTS.md §8).
- **Room schema protection (CI)** — CI now enforces that changes to Room entities are accompanied by updated schema export files, preventing silent schema drift and ensuring that migrations are always verified against the expected state.
- **Kotlin and Android linting (CI)** — ktlint and Android Lint now run on every pull request. ktlint is configured in `.editorconfig` at a 120-column limit, which is the Kotlin/IntelliJ default and what this codebase was already written to. Both Gradle modules scope ktlint to hand-written sources, because KSP registers its output directories into the Kotlin source sets and ktlint would otherwise report tens of thousands of violations against generated Room code.
- **Secret scanning (CI)** — gitleaks now scans the repository's **entire git history, across every branch and tag**, on every pull request and every push to `main` — not just the commits under review, and not just the branch under review. A credential is compromised from the moment it is pushed, and rebasing or squashing does not unpublish it; on a public repository a secret sitting on an unmerged side branch is readable long before that branch is ever reviewed. The existing 116 commits were scanned before the check was added and are clean. Findings are redacted in the logs — this repository is public, so an unredacted scan would republish the very secret it caught — and uploaded as a JSON artifact whose `Fingerprint` field is exactly what `.gitleaksignore` takes. gitleaks is installed from a checksum-pinned release binary rather than a third-party Action, since the job that hunts for credentials is the last place to run unpinned code.
  - The job first **proves the scanner still detects**, by scanning a throwaway fixture containing a known token and asserting it is caught, reported and redacted. Without that, a broken scanner and a clean repository produce the same green tick — the failure AGENTS.md §7 exists to prevent, and one this work hit for real: the first version of the control used a token gitleaks allowlists, so it passed while proving nothing.
  - It also **fails if a credential file has ever been committed** (`*.jks`, `*.keystore`, `*.p12`, `keystore.properties`), checked by name across the whole history. `.gitignore` only stops an accidental `git add` of an untracked file, and gitleaks cannot read an opaque binary keystore, so neither would notice one already tracked.

- **Expanded CI workflow** — `.github/workflows/ci.yml` now includes the enforcement checks for changelog updates, schema integrity and secret scanning, integrated into the standard PR verification suite.
- **Updated `AGENTS.md`** to formalize these automated rules within the project's development guidelines, including a §5 convention for ktlint configuration (and the two ways of "fixing" violations that make things worse) and a §7 lint step alongside the existing test gate. `ROADMAP.md` and `README.md` record the same.

## [0.11.1] - 2026-08-16

Books were quietly losing their authors. Open Library keeps authorship against the book itself
rather than against each individual printing, and a great many printings list no author of their
own — so anything you added by ISBN could arrive with the author field simply blank, with nothing
anywhere to say why. Nothing had failed, so nothing was reported. That is fixed, and the backfill
will repair the books it already happened to.

### Fixed

- **Books added by ISBN now get their author far more often.** Open Library records authorship
  against the book rather than against each individual printing, and a great many printings list no
  author of their own — the 75th Anniversary edition of *The Hobbit* is one. Those books were
  arriving with the author field simply blank, and because nothing had actually failed, nothing was
  logged to say so either. The author is now read from the book when the printing doesn't carry one.

### Changed

- **The cover & author backfill also fills in authors it previously couldn't**, for exactly the
  books described above — run it again if any of your books are missing an author.
- **The backfill now records each book's catalogue link.** Books added before this update don't have
  one and it can only be fetched from Open Library, so the first run may work through your whole
  library even if every cover and author already looks complete. Nothing in the app displays this
  yet; it is what will let a future update tell two printings of the same book apart, for re-read
  history and genre tagging.

## [0.11.0] - 2026-08-11

The log now tells you what the app was doing, not just what broke. Until now nothing was recorded
below the level of a warning, so a healthy app wrote nothing at all and the log screen sat empty —
which looks like a broken feature rather than good news. It now records the ordinary things too:
the app starting, an import or export finishing, a backfill starting and how far it got. When
something does go wrong, the entries before it are the context that explains how you got there.

A batch of failures that used to vanish without trace now explain themselves. If a book arrives with
no author, or no cover, or an import refuses your file, there is now an entry saying why — those
were all previously discarded in silence.

### Added

- **The log records normal activity**, not only failures: app start, import and export completion,
  and backfill start/finish with its counts.
- **Failures that used to disappear now leave a reason behind** — a missing author, a cover that
  could not be saved, a refused import, a provider that could not be reached.

### Changed

- **"Log detail" now starts at Detailed** instead of Warnings, so the log is useful without having
  to turn anything on first.
- **The "Debug" option is gone.** Nothing in the app ever logged at that level, so it behaved
  identically to Detailed and promised detail that did not exist. If you had already selected it,
  your choice is left alone.
- Rejected rows from an import are summarised as a count with their row numbers rather than one
  entry each, so a single malformed file cannot flood the log.

### Fixed

- **Opening Settings could crash the app** if reading your saved backfill progress failed. The
  failure is now recorded and Settings opens normally.

### Note on privacy

Everything above keeps the existing rule: the log records *what failed and why*, never what you are
reading. Book titles, author names and session notes are never written to it, and it stays on your
device.

## [0.10.1] - 2026-08-10

Fixes bulk delete acting on less than you selected. Selecting books and then changing the status
filter made the counter drop, as though the selection were being lost, and deleting would then
remove only the books the filter happened to be showing — leaving the rest selected and invisible
with nothing to explain it. The confirmation now lists every book by name, so what is about to go is
never a surprise.

### Fixed

- **Bulk delete now removes everything you selected**, not just the books the current filter happens
  to be showing. Selecting three books and then switching filters used to make the counter drop, as
  though the selection were being lost, and deleting would then remove only part of it and leave the
  rest selected but invisible.
- **The delete confirmation now lists the books by name**, so you can see exactly what is about to
  go — including any hidden behind the current filter.
- **Deleting a selection no longer does nothing** in the moment after the library screen has been
  away — coming back from another app, or rotating the phone. The delete read a snapshot of the
  selection that stops being kept up to date while nothing is watching it, so it could find nothing
  selected and quietly return, with no books removed and no message.

## [0.10.0] - 2026-08-10

Tidying up a library, and the plumbing to trust that it worked. Deleting books one at a time was
the only option, which made cleaning up after an import a long afternoon; now you can long-press to
select several and remove them together, behind a confirmation. Deleting also finally reclaims the
cover images it downloaded, which it never used to — and does so carefully, since two books with
the same artwork share one file on disk.

The rest is groundwork you will mostly not see. The app module was previously verified only by
whether it compiled, so a button could render perfectly and do nothing; there are now instrumented
tests that catch exactly that. Development builds install as a separate app from real ones, which
they should always have done — as the same app signed with different keys, installing either over
the other silently wiped everything. No schema change: still v5.

### Changed

- **Debug builds now install as a separate app** ("MediaTracker Debug", its own icon). Previously a
  development build and a real build were the same app to Android but signed differently, so
  installing one over the other silently wiped everything — the database and every downloaded
  cover. They now sit side by side with separate data. **If you already have a development build
  installed, it stays as-is and the new one appears alongside it; the old one can be uninstalled.**

### Added

- **A failed bulk delete now says so.** Previously a failure left the books in place, the selection
  intact, and nothing on screen — indistinguishable from the Delete button being ignored. It now
  shows the reason and keeps the selection so the action can be retried.

- **Select several books at once and delete them together (ROADMAP Task 14 Phase B)** — long-press
  any book in the library to start selecting, then tap others to add them. A contextual bar shows
  how many are selected and offers a delete, which asks for confirmation before removing anything.
  - **Deleting books now reclaims their cover images.** Previously a deleted book left its
    downloaded cover on disk forever; a bulk delete would have stranded that many files at once.
    Covers are shared between books with identical artwork, so a cover is only removed once no
    remaining book uses it — deleting one of two books that share a cover leaves the survivor's
    intact.
  - Selecting a book and then narrowing the list by status or search keeps the selection, but
    deleting only ever affects books currently visible, so nothing disappears out of sight.

- **Compose UI test harness** — the Android app module was previously verified only by whether it
  compiled, so a screen could render perfectly and do nothing. 18 instrumented tests now cover the
  log and changelog viewers, including an end-to-end navigation test that catches a control wired
  to a dead callback. No user-visible change; recorded because it changes what "the build passes"
  means.

## [0.9.0] - 2026-08-09

Makes the app able to explain itself. Until now a failure could only say "something went wrong" —
there was no logging anywhere in the shared layer, so the cause was discarded at the catch and lost.
This release adds logging end to end: it records what failed and why, keeps that history on the
device, and lets you read it in the app rather than needing a computer and a debugger attached.
It also adds an in-app "What's new" screen, which is where you are probably reading this.

While scoping the log file's exclusion from backups, a larger problem surfaced: the Android backup
rules had never been filled in, so **your entire library — database and downloaded covers — had
been going to Google Drive** on every eligible device since the first commit. That is fixed here,
and it is the one change in this release with a visible consequence: reinstalling no longer restores
anything automatically. The app's own backup and export remain the way to move your data. No schema
change: still v5.

### Added

- **Logging facility (ROADMAP Task 15 Phase A)** — `shared/` previously had no logging at all (no
  `Logger`, no `Napier`, not even a `println`), which had already forced three separate failure
  paths to discard their cause with nothing recoverable. Added a minimal, hand-rolled KMP `Logger`
  (`core/util/Logger.kt`) — no new dependency (AGENTS.md §5) — with four levels (`DEBUG`/`INFO`/
  `WARN`/`ERROR`), a tag, an optional `Throwable`, and a lazy message lambda so a suppressed call
  costs nothing to build. Platform routing follows the `DatabaseFactory`/`DatabaseFileOps`
  `expect`/`actual` precedent: `android.util.Log` on Android, stdout/stderr on JVM
  (`core/util/PlatformLogger.kt` + platform actuals).
  - **Verbosity is gated centrally, not per platform.** `AppLogger` (the production default every
    adoption site injects) wraps the platform sink with a minimum-level threshold, defaulting to
    `WARN` until configured. `MediaTrackerApplication.onCreate` (the one place `BuildConfig.DEBUG`
    is visible — `shared/` cannot see it) configures `DEBUG` for a debug build or reaffirms `WARN`
    for a release build. **A release build therefore never emits `DEBUG`/`INFO` — only `WARN`/
    `ERROR` ever reach logcat, and the message lambda for a filtered-out call is never even
    evaluated.**
  - **Privacy rule, enforced at every adoption site, not just documented**: log *what failed and
    why*, never *what the user is reading*. A `mediaId` or an ISBN is fine to log (opaque/edition
    identifiers, not personal content); a title, author, or session note never is. No
    crash-reporting service is used or planned — every sink is purely local (logcat/stdout), matching
    the app's local-first, no-cloud premise.
  - **`RecordingLogger`** (`commonTest`) — an in-memory `Logger` so a test can assert *that* a
    failure was logged, at what level, and that the message contains no library content — this is
    what makes the adoption below verifiable rather than eyeballed.
  - **Adopted at the three known gaps**, each proven by a `RecordingLogger` test asserting both that
    the failure is logged and that no book content appears in the message:
    - `OpenLibraryIsbnCoverProbe`'s swallowed network/TLS failure now logs at `WARN` (ISBN + cause)
      before still folding to `NotFound` — the return value is unchanged, only diagnosability
      improved. Its KDoc, which named itself as the first catch block that should adopt logging, is
      updated to match.
    - `BackfillViewModel`'s previously-discarded mid-backfill exception now logs at `ERROR` before
      settling to `Failed` — its KDoc (which explained the omission was forced by the missing
      facility) is updated.
    - The restore/migration paths — `DefaultRestoreDatabaseUseCase` (every `stage`/`commit`
      rejection and the swap's generic exception catch), `validateStagedDatabaseIntegrity`, and
      every registered `Migration` (`Migrations.kt`, via a new `loggedMigration` wrapper that logs
      the failing schema-version transition at `ERROR` and rethrows unchanged) — now all log before
      a failure reaches the user as a message with no recoverable detail.

- **Persistent log store (ROADMAP Task 15 Phase B)** — logging is now always-on and survives the
  process, so a failure is diagnosable without a debugger attached. Phase A's logs only ever
  reached logcat, which a normal user on a release build cannot read; every accepted log call is
  now *additionally* captured to a capped file in app-private storage. The verbosity threshold is
  unchanged (a release build still only emits `WARN`/`ERROR`) — what changed is where those calls
  go, not which ones are made.
  - **A capped pair of files with single rollover** (`log.txt` + `log-previous.txt`, ~1 MB each),
    deliberately **not** a Room table — a log table would bloat the very database that gets
    `.sqlite`-backed-up and CSV-exported.
  - **Buffered, appending writes.** Log calls land in a bounded in-memory buffer and are flushed
    on a size threshold, periodically, and on demand, so a bulk backfill over hundreds of books
    never hits disk per entry. Flushes are true appends, never read-modify-write, which keeps each
    flush proportional to the batch rather than the file and bounds what a crash mid-write can
    damage to the tail.
  - **Sequence numbers derived from the store itself**, scanned from *both* retained files at
    startup rather than persisted separately — a separately-persisted counter could drift from the
    store after a crash and start assigning numbers below entries already on disk.
  - The in-app viewer for these logs is Phase B2, below.

- **In-app log viewer and adjustable detail (ROADMAP Task 15 Phase B2)** — a Diagnostics section in
  Settings. The log is now readable on the device itself, which is the whole point of persisting it:
  a release build's logcat is unreachable to a normal user, so a facility they cannot read does not
  serve an app whose support model is the user themselves.
  - **A snapshot, not a live tail.** The viewer shows entries as of when it was opened; logging
    continues in the background but the view holds still. Refresh pulls in what accumulated since
    and draws a divider marking where it starts. This is deliberate rather than a limitation: a
    live-updating list reflows text mid-drag, so auto-update and genuinely selectable text are
    mutually exclusive, and selectable text is what makes a log worth showing.
  - **Export full log** writes everything retained, including entries older than the on-screen
    window, to a file you pick.
  - **Log detail is now yours to set** (Debug / Info / Warnings / Errors only), defaulting to
    Warnings so the capped log is not filled with routine chatter. Setting it to Debug on a release
    build works — the detail level is a real control, not a debug-build-only affordance. Leaving it
    untouched keeps each build type's own default, so debug builds stay verbose.
  - The privacy rule is unchanged and stated on the screen itself: logs never contain your titles,
    authors, or notes.

- **In-app "What's new" (ROADMAP Task 15 Phase B2b)** — release notes are now readable inside the
  app, from the Diagnostics section in Settings. Opens on the version you are actually running,
  with older releases below it.
  - **Collapsible, because the notes are long and uneven.** `[0.7.0]` runs 335 lines against
    `[0.1.0]`'s 38, and one single entry is 109 lines — flat, that is dozens of screens with no way
    to skim. Each version folds, each version's summary stays visible once opened, and each
    individual entry expands on demand.
  - **`CHANGELOG.md` remains the single source of truth.** The file is copied into the app at build
    time from the repo root; the copy is a gitignored build artifact, so a stale duplicate cannot be
    committed even by accident.
  - Bold and `code` formatting render properly rather than showing their raw markers.

### Changed

- **Android Auto Backup no longer sends your library to Google Drive.** `backup_rules.xml` and
  `data_extraction_rules.xml` had shipped as the untouched Android Studio sample templates, with
  every rule commented out — which, combined with `android:allowBackup="true"`, meant the entire
  app-private directory (the reading database *and* every downloaded cover) was being swept to
  Google Drive on eligible devices. That contradicted the app's local-first, no-cloud premise
  (AGENTS.md §1) and was found while scoping the log file's own required carve-out.
  - **Cloud backup now transfers nothing.** Every domain is excluded in both the API 31+ and the
    legacy rules files - the data directory root included, not just the `files`/`databases`/
    `shared_prefs` subdirectories, so nothing written directly into the data directory slips
    through. **Consequence: reinstalling the app no longer restores
    anything automatically** — the app's own `.sqlite` backup/restore and CSV export (v0.7.0) are
    the restore paths, as was always intended.
  - **Device-to-device transfer carries the covers, deliberately not the database.** Content-
    addressed covers are immutable and safe to byte-copy, and are the expensive half to rebuild
    (re-acquiring them means re-crawling Open Library under its rate limit). The database is
    excluded because device transfer is a raw file copy taken at a moment the app cannot
    checkpoint — precisely the hazard the v0.7.0 backup avoids by using `VACUUM INTO`. Restoring
    a `.sqlite` backup on the new device finds the covers already present.

## [0.8.0] - 2026-08-08

Repairs an imported library. A Goodreads import previously produced books with neither covers
nor authors; this release captures authors on ingestion, adds a bulk backfill that repairs
covers *and* authors in one rate-limited pass (resumable if interrupted), and lets the library
be searched by title or author. Also carries the session-dialog date/time fix that missed
v0.7.0. Ships **Room schema v5** with a tested migration.

Author capture and local library search (ROADMAP Task 9 Phase A). Both providers already resolved
author names during ISBN ingestion — Open Library even makes an extra `/authors/{key}` round-trip
for it — but nothing kept them; this closes that gap and lets the library be searched by title or
author.

### Added

- **Author capture** (Room schema v5, `MIGRATION_4_5`) — `BookDetailsEntity` gained a nullable
  `authors` column: a single denormalized `String`, multiple names joined with `"; "`
  (`BookDetailsEntity.AUTHOR_SEPARATOR`), not a normalized authors table. A comma was ruled out as
  the separator since Goodreads' own `Author l-f` column already writes one name as
  `"Tolkien, J. R. R."` — a comma *inside* a name — which would make a comma-joined multi-author
  field structurally ambiguous. Existing rows land `NULL` (no pre-v5 signal ever recorded an
  author — honest, not fabricated); `AddBookByIsbnUseCase` now persists
  `BookMetadata.authors` on every new ISBN-ingested book instead of discarding it.
- **CSV export/import carries authors** — `library_export.csv` gained an `authors` column
  (`CSV_SCHEMA_VERSION` bumped `1` → `2`); a pre-existing `v1` file (no `authors` column) still
  imports cleanly via a registered legacy-header adapter in `CsvTableReader`, landing `authors`
  `null` for every row exactly as a blank cell in a `v2` file would.
- **Goodreads import now captures authors** — `Author` (primary) plus `Additional Authors`
  (Goodreads' own comma-separated co-author list) are combined and re-joined with this app's `"; "`
  separator. `Author l-f` is intentionally not used (same person as `Author`, just re-formatted).
- **Local library search** — a search field on the Library screen filters the already-loaded
  library by title or author, case-insensitive substring match, entirely in-memory (no schema/index
  work). Composes with the existing reading-status filter chips as an intersection (AND): both
  narrow the same list together, never either-or.
- Author now displays on library list rows (when known) and on the Book Detail screen's metadata
  block, omitted entirely for a book with no author on record rather than showing a placeholder.
- **Bulk cover & author backfill** (ROADMAP Task 14 Phase A) — a new "Cover & author backfill"
  section on the Settings screen repairs the whole library in one pass: any book with an ISBN that
  is missing a cover and/or an author is looked up again, and both fields are written from the
  single resulting lookup rather than two separate crawls. Motivated by a Goodreads import, which
  carries neither covers nor (for pre-Task-9 books) authors, leaving the old one-book-at-a-time
  "re-fetch cover" as the only remedy. Offered from Settings, and as a "Start backfill" action
  directly on the import summary dialog once an import has actually added books.
  - **One shared rate limiter for every ISBN-keyed cover probe.** Open Library's cover lookup quota
    (100 requests/IP/5 minutes) is per device, not per feature, so a new `OpenLibraryCoverRateLimiter`
    now sits inside `OpenLibraryIsbnCoverProbe` itself and is shared — via a single `AppContainer`
    instance — by the bulk backfill, the per-book "re-fetch cover" action, and "add book by ISBN"
    alike. A user tapping "re-fetch cover" mid-backfill draws on the same budget instead of silently
    pushing the combined total over the limit.
  - **A 429/5xx no longer means "no cover."** `OpenLibraryIsbnCoverProbe.probeCoverUrl` now returns
    a `CoverProbeResult` (`Found` / `NotFound` / `RateLimited`) instead of a bare nullable URL, so a
    rate-limit or server-error response is distinguishable from Open Library's real "no cover" 404.
    The bulk backfill pauses and defers a book on `RateLimited` rather than marking it coverless;
    the single-book paths still collapse `RateLimited` into "no cover for this lookup" (identical to
    their pre-existing behavior), since a one-off interactive call has no backfill-style retry loop
    to pause.
  - **Resumable.** Progress (which books are still pending, and running totals) is checkpointed to
    the `app_settings` key-value store after every book — no schema change. A backfill interrupted by
    the quota, cancellation, or the app being killed picks up exactly where it left off on the next
    run rather than restarting or being silently abandoned, and the Settings screen offers "Resume
    backfill (N remaining)" the moment it detects leftover state.
  - Books with no ISBN are reported as skipped (with a note that manual cover entry, still in the
    backlog, is their only route) rather than retried on every run.
  - **Failure-path hardening (PR review).** A book whose cover/authors write fails (e.g. it was
    deleted mid-run) is now correctly left pending for retry instead of being silently counted as
    "updated" and dropped from the queue forever. A database failure mid-backfill (or the backfill
    being cancelled before any book has been checkpointed) now settles the Settings screen's
    progress UI on an accurate stopped state instead of leaving it stuck showing "running" forever;
    a stale progress snapshot read on screen re-open can no longer clobber a backfill that's already
    running.
  - **Failure-path hardening, round 2 (PR review).** A genuine mid-run failure (e.g. a database
    error) now settles the Settings screen on an explicit "something went wrong" message instead of
    the same "stopped" state a clean cancel produces -- previously the two were visually identical,
    so a real failure looked like nothing had gone wrong. The "paused, quota resets in..." message no
    longer rounds a sub-minute wait down to a misleading "about 0 min"; it now rounds up (floored at
    one minute) and pluralizes correctly ("about 1 minute" vs. "about 2 minutes").
  - **Rate-limit hardening (PR review).** A 5xx response now records a server refusal on the shared
    rate limiter too (previously only a 429 did), so the very next probe -- even for a different
    ISBN -- is denied locally instead of hitting a server already known to be refusing. `Retry-After`
    also now understands RFC 7231's HTTP-date form (`Wed, 21 Oct 2015 07:28:00 GMT`), not just
    numeric-seconds, computed against the same clock the rate limiter itself uses so the two can
    never disagree about how long "now" plus the wait actually is.

### Changed

- `library_export.csv`'s format version (`csv_schema_version`) is now `2`. Files this app itself
  produced before this release (`csv_schema_version=1`) remain importable.
- `OpenLibraryIsbnCoverProbe.probeCoverUrl` returns `CoverProbeResult` instead of `String?` (see
  above); `FallbackBookMetadataProvider`/`createDefaultBookMetadataProvider`,
  `createDefaultRefetchCoverUseCase`, and `createDefaultAddBookByIsbnUseCase` all gained an optional
  `coverRateLimiter`/`OpenLibraryCoverRateLimiter` parameter (defaulted, so existing call sites are
  source-compatible) to support the shared-quota wiring above.

## [0.7.0] - 2026-08-03

Data portability. The app's premise is local-first with no cloud, which until now meant there
was no way to get your data out or protect it. This release adds CSV export and import of books
and reading sessions, whole-database `.sqlite` backup and restore, and a Goodreads importer.

Backup uses SQLite's `VACUUM INTO` so a WAL-mode database is captured completely; restore
validates a candidate file (SQLite header, schema version, integrity check, expected tables)
before anything is replaced, and never leaves the live database missing. Import is transactional
and reports every skipped row with a reason. Note the CSV files cover books and reading logs
only — the `.sqlite` backup is the complete one. No schema change: still v4.

### Added

- **CSV export** (ROADMAP Task 8 Phase A) — the app's first data-portability feature: a "Data"
  section on the Settings screen generates `library_export.csv` (every `MediaItemEntity` +
  `BookDetailsEntity` field, plus each book's `ExternalIdentifierEntity` rows packed into one
  `external_identifiers` column) and `reading_logs_export.csv` (every `ReadingSessionEntity`
  field), from one consistent database snapshot, and writes them to two user-picked locations via
  the Storage Access Framework (`ActivityResultContracts.CreateDocument`) — no new permission, and
  this is exactly the SAF plumbing the deferred "manual cover entry" backlog item was waiting on.
  - Hand-rolled RFC 4180 CSV escaping (`shared/.../features/portability/csv/CsvUtil.kt`, no
    third-party CSV dependency per AGENTS.md §5): a field is quoted (with embedded quotes doubled)
    only when it contains a comma, quote, or newline, so free-text titles and session notes
    round-trip unambiguously.
  - **Nullable `durationSeconds` exports as an empty field, never `0`** — the one rule this phase
    treats as load-bearing, since schema v2 made that column nullable specifically so "unknown
    duration" and "a real zero-second session" would never collide; exporting `null` as `0` would
    silently reintroduce that exact collision one layer up.
  - A `csv_schema_version` column (currently `1`) is written on every row of both files rather than
    a dedicated first line, so a Phase B importer can detect a file it doesn't understand while a
    bare `.csv` still opens as an ordinary, uniform table in a spreadsheet. `Instant` fields export
    as ISO-8601 UTC (`kotlin.time.Instant.toString()`), never a locale-dependent format.
  - New `features/portability/` module (justified against AGENTS.md §6's blueprint as a
    cross-cutting concern alongside `features/stats/`, not book-specific): pure Kotlin/KMP-clean
    CSV generation (`csv/`) plus an `ExportDataUseCase`/`ExportUseCase` (`domain/`) wired through a
    new `ExportViewModel` (`Idle`/`Loading`/`Success`/`Error`, mirroring `AddBookViewModel`'s
    existing shape) — the app module owns all file I/O, per AGENTS.md §6.
  - No schema change; Room stays at v4.
- **CSV import** (ROADMAP Task 8 Phase B) — the harder half of data portability: the Settings
  screen's "Data" section gains an "Import library" action alongside export, reading
  `library_export.csv`/`reading_logs_export.csv` back via SAF `ActivityResultContracts.
  OpenDocument` (still no new permission; manifest unchanged) and writing to the real database
  through one all-or-nothing transaction.
  - **A genuine RFC 4180 reader** (`CsvReader`), not `split(",")`: handles quoted commas/quotes and
    embedded newlines (a multi-line session note parses as one field, not extra rows). An
    unterminated quote fails the whole file closed rather than guessing where it ends; a
    completely empty file parses to zero rows with no opinion on headers.
  - **`CsvTableReader`** layers structural validation on top: refuses a file with an unrecognized
    header, a data row whose column count doesn't match the header, or (deliverable: version
    compatibility) a `csv_schema_version` **newer** than this build understands, with a message
    telling the user to update the app rather than mis-parsing an unknown column layout.
  - **`ImportDataUseCase`** with an explicit, user-visible `DuplicatePolicy` (`SKIP`/`REPLACE`/
    **`MERGE`** — merge is a hard requirement per the Goodreads-import groundwork, not just
    skip-or-replace): matches an incoming book by `media_id`, then ISBN, then case-insensitive
    title+release-year as a last resort (documented weakness: no author column exists yet, so two
    same-titled/same-year books by different authors would over-match on this last tier). MERGE
    only backfills fields the existing row left null (releaseYear/purchasePrice/isbn/totalPages,
    plus any external-identifier provider not already on record) and never overwrites a value
    that's already set; REPLACE overwrites everything this importer manages except `createdAt`
    and `coverImageHash` (CSV carries no image bytes, so a foreign hash is never written over an
    existing cover reference).
  - **All-or-nothing**: every resolved insert/update is queued and applied through one new
    `ImportWriteDao.importAtomically` transaction (mirroring `BookWriteDao`'s existing
    `@Transaction` pattern) — a constraint violation partway through rolls back everything already
    applied in that same call, verified by a forced-mid-failure test. Structural file problems
    (bad header, wrong column count, unterminated quote, unsupported version) refuse the entire
    import before any write is attempted; a semantically bad *row* (blank title, out-of-range
    year, a session whose `media_id` isn't a known book) is skipped and reported instead of
    aborting everything else in the file.
  - **Orphan reading-session rows are skipped-with-report, not silently dropped or fatal**: a
    session whose `media_id` matches neither the existing library nor the library file being
    imported alongside it is counted as a rejection with a reason, while every other valid row
    still imports.
  - **Fixed: two rows in the same file sharing a `media_id`/ISBN/title+year no longer collide.**
    Duplicate matching now also resolves each row against every earlier row already added *by
    this same import*, not only against what was already in the database. Previously, two rows
    sharing a `media_id` within one file both inserted as "fresh," which collided on the
    `media_items` primary key and aborted the **entire** atomic import; two rows sharing only an
    ISBN silently created two separate books. In-file duplicates now follow the exact same
    per-`DuplicatePolicy` field rules as a duplicate against an existing book (SKIP: earliest row
    in the file wins; REPLACE: last row wins; MERGE: first row to set a field wins), counted with
    the same imported/skipped/merged/replaced totals already shown.
  - **Fixed: sessions no longer orphaned when their book matched by ISBN or title+year instead of
    `media_id`.** A `reading_logs_export.csv` row references its book by the *file's own*
    `media_id`, but when the matching library row instead landed on an existing (or in-file) book
    via the ISBN or title+year tier, that file `media_id` was never recognized, so the session was
    wrongly rejected as an orphan. Sessions are now also written under the book's actual resolved
    id, so they land on the right book instead of being dropped.
  - **Fixed: a `purchase_price` cell containing `NaN`/`Infinity`/`-Infinity` is now rejected, not
    silently imported.** `String.toDoubleOrNull()` accepts all three per the JLS, and `NaN < 0.0`
    is `false` (IEEE 754), so the existing `>= 0` bounds check could never catch a `NaN` — it would
    have been persisted and poisoned every downstream sum/average/comparison over prices. The
    parser (`parseOptionalDouble`/`parseRequiredDouble`) now rejects any non-finite value up front,
    the same `isFinite`-based fix already applied to reading-session positions. Defence in depth:
    `BookMetadataValidation.validatePurchasePrice` (shared with the manual Edit Book form) now also
    rejects non-finite values directly, since that form's own Save-button gate only checks
    `>= 0.0` — true for a hand-typed `Infinity`.
  - **Fixed: an import update racing a concurrent delete no longer silently over-reports as
    "updated."** `ImportWriteDao`'s book/session update statements now check Room's affected-row
    count and fail the whole import (rolling back the transaction) if a row targeted for update was
    deleted between `ImportDataUseCase`'s duplicate-resolution snapshot and the write — instead of
    Room's `@Update` quietly no-oping while the returned summary still counted it as a success.
  - **Fixed: a leading UTF-8 byte-order mark (BOM) no longer gets a valid export rejected.**
    `CsvReader.parse` now strips a leading `U+FEFF` before tokenizing (`String.trim()` doesn't
    strip it, so it previously became part of the first header cell's text) — a BOM-prefixed
    `library_export.csv` or `goodreads_library_export.csv` (as Excel writes when saving a UTF-8
    CSV) no longer fails the header check and gets rejected as unrecognized. Fixed once at the
    shared tokenizer, so both this app's own CSV import and the Goodreads import below are covered.
  - **The `PROVIDER:id|...` packed-identifier hazard is handled, not assumed away**: each `|`
    segment splits on only its *first* `:`, so a provider id containing `:` round-trips correctly;
    a segment with no `:` at all (the fallout of an id containing a literal `|`, which this
    encoding cannot losslessly represent) is detected and rejects that row with a clear reason
    instead of silently mis-splitting it.
  - Validation reuses the existing rules instead of forking a second copy: extracted
    `BookMetadataValidation` (title/price/pages/year bounds, ex-`BookRepository`) and
    `ReadingSessionValidation` (timestamp/duration/position rules, ex-`ReadingSessionRepository`/
    `LogReadingSessionUseCase`) are now shared by both the manual-edit paths and the importer.
  - The import summary shown to the user always states counts (imported/skipped/merged/replaced,
    per file) and every rejection's reason — never a bare "done."
  - The strongest test added this phase: an export-then-import round trip over a populated
    in-memory database, asserting the freshly-imported data matches the original field-for-field.
  - No schema change; Room stays at v4 (`ImportWriteDao` is a new DAO only, like `StatsDao` before
    it — no `@Entity` changed).
- **`.sqlite` backup and restore** (ROADMAP Task 8 Phase C) — the Settings screen gains a
  "Backup & restore" section, deliberately separate from (and styled at higher visual risk than)
  the CSV export/import section above: a whole-database restore replaces everything, with no
  cloud copy to fall back on (`android:allowBackup="true"` means Android *may* be silently
  snapshotting to Google Drive, but that's invisible, size-capped, and not restorable on demand —
  never the answer this app relies on).
  - **Backup, and the WAL problem it actually has to solve**: `RoomDatabase.Builder` defaults to
    `JournalMode.WRITE_AHEAD_LOGGING` (confirmed against the resolved `room-runtime` 2.8.4
    bytecode) and this app never overrides it with `setJournalMode(TRUNCATE)`, so the live database
    genuinely runs in WAL mode — meaning the most recently committed rows can live only in the
    `-wal` sidecar file until SQLite next checkpoints them into the main `.db` file. A naive
    `File.copyTo(...)` of just the main file can silently produce a backup missing whatever hasn't
    been checkpointed yet. Rejected the "manual `PRAGMA wal_checkpoint(TRUNCATE)` then copy"
    approach too (it would mutate the live database's own WAL state, and `TRUNCATE` can still leave
    the WAL non-empty if a concurrent reader is active, reintroducing the same problem one layer
    down). Instead, backup runs SQLite's own **`VACUUM INTO`** — reading through the normal pager
    (the same path every ordinary query already uses, which transparently merges the main file with
    anything still only in the WAL) and writing a fresh, compacted, single-file, WAL-free snapshot —
    executed via Room KMP's `AppDatabase.useWriterConnection` + `Transactor.usePrepared("VACUUM
    INTO ?")`, binding the destination path as a parameter. `DatabaseBackupUseCaseTest` (`jvmTest`,
    a real file-backed database, not in-memory — in-memory can't prove anything about WAL) confirms
    this concretely: it inserts a row, asserts the live database's own `-wal` file is genuinely
    non-trivial at that moment (proof the row hasn't been checkpointed), then asserts the row is
    present when the backup is opened fresh.
  - **Restore validates before touching anything, in two passes.** Pass 1 parses the candidate
    file's first 100 bytes directly — no SQLite driver, no Room, no database connection at all — for
    the 16-byte `"SQLite format 3\0"` magic string and `PRAGMA user_version` at its fixed header
    offset (new `parseSqliteHeader`, pure Kotlin, `SqliteHeaderTest` in `commonTest` with hand-built
    byte arrays). A non-SQLite file, or a `user_version` newer than `APP_DATABASE_VERSION` (a new
    named constant so the check and the `@Database` annotation can never silently drift), is refused
    with a clear message before Room ever gets near it — exactly the "refuse loudly rather than let
    Room fail obscurely" the task called for. Pass 2, reached only once pass 1 passes, opens the
    candidate **read-only** (`SQLITE_OPEN_READONLY`, `BundledSQLiteDriver`; new
    `validateStagedDatabaseIntegrity`) and runs `PRAGMA integrity_check`, then confirms the tables
    that candidate's own `user_version` says it should have are actually present — a 100-byte header
    alone can't tell a truncated/corrupt file, or a structurally-valid SQLite file belonging to a
    completely different program, apart from a genuinely intact MediaTracker database, and for an
    operation this destructive that bar was too low. Either failure is refused with a clear message
    and the candidate deleted, same as pass 1. An **older** `user_version` that passes both passes is
    legitimate and accepted: the very next time the swapped-in file is opened, it goes through the
    exact same registered migration chain (`MIGRATION_1_2`/`MIGRATION_2_3`/`MIGRATION_3_4`) every
    ordinary app launch already uses, with no separate "restore migration" path — proven, not just
    assumed, by `RestoreDatabaseUseCaseTest`'s round trip: a real v2-schema file is restored, reopened
    through the normal `buildAppDatabase` path, and asserted to land on the current schema with its
    pre-migration data completely intact. Pass 2's required-table set is chosen **from the
    candidate's own reported `user_version`**, not one fixed list for every candidate: a candidate
    older than the version `app_settings` was added at (v4) is only held to the tables present since
    v1, so a legitimate older backup is never itself rejected as "not a MediaTracker library" — but a
    candidate that itself claims `user_version` 4 is held to that later table set too, so a v4 file
    genuinely missing `app_settings` (corrupt, hand-crafted, or a botched edit) is correctly refused
    rather than silently waved through the way excluding it unconditionally would have.
    `RestoreDatabaseUseCaseTest` covers a truncated-but-header-valid file, a structurally valid but
    unrelated SQLite file, and a v3-schema file whose `user_version` was hand-bumped to 4 while
    genuinely missing `app_settings`, all three now refused where the header check (or an
    unconditionally v1-only table check) alone would have accepted them — plus a direct check that
    validating a candidate never needs, and is never granted, write access to it (opening
    read-write, the pre-fix behavior, would let validation silently modify a candidate before the
    user has confirmed anything destructive).
  - **The live database is never deleted until the replacement is staged and validated.** The
    picked SAF document is streamed into a private temp file (new binary-file counterparts to the
    existing CSV `Uri`↔text helpers, `app/.../export/DatabaseFileIo.kt`) and validated *there*,
    before anything destructive happens. The swap itself is same-directory atomic renames
    (`java.nio.file.Files.move` with `ATOMIC_MOVE`, requiring the atomic path rather than falling
    back to a non-atomic copy if the platform provider rejects it — a non-atomic fallback could
    leave a truncated file indistinguishable from a genuine one if a process died mid-copy, which
    would silently defeat the self-heal/rollback guarantees below): the live
    file renames to a fixed-name `.pre-restore-bak` (each restore attempt replaces the previous
    attempt's safety net rather than accumulating one per restore forever), carrying its own
    `-wal`/`-shm` sidecars along with it — but only once the main-file rename itself has already
    succeeded, and only if *both* sidecar renames succeed; if either doesn't (a live rename failure,
    not only a crash — and the two sidecars can disagree, one landing while the other fails), the
    rollback puts back whichever sidecar(s) actually moved *first*, then the main file, before
    refusing the whole attempt — "nothing was changed" is only ever reported once all of that
    actually holds, never assumed from the main file alone — rather than risking the live database's
    most recent commits (still only in its `-wal`) being silently left stranded at the backup path.
    The validated file only then renames into the live path. A failed final rename automatically
    rolls the backup — sidecars first, main file last — back into place before returning an error.
    The one gap this can't make atomic — a process death between the backup and activation renames,
    which would otherwise make Room silently create an empty database on the next launch — is closed
    by a `selfHealDatabaseIfNeeded` check that runs at the very start of every `createAppContainer`,
    before Room ever opens anything: if the live file is missing but the safety-net backup exists,
    its sidecars are moved back *first* and its main file *last*, so the "live file present" sentinel
    this check relies on can never go true before the WAL that belongs next to it has already
    arrived. `RestoreDatabaseUseCaseTest` covers header rejection, the too-new/older version rules,
    the full round trip, a forced mid-swap failure (a `StagedRestoreInfo` pointing at a file that no
    longer exists) leaving the original database completely intact and openable afterward, and — a
    sidecar-rename failure forced via a directory placed at the rename's own target (portable across
    platforms, unlike an open file handle, which only reliably blocks a rename on Windows) during
    both the swap and the self-heal check — proving the live file is never left present without the
    WAL that belongs next to it, and that a sidecar which *did* successfully move before its sibling
    failed is never left stranded at the backup path while the rest of the database looks restored.
  - **`AppContainer.close()` + a full process restart, not an in-place rebuild.** Every
    ViewModel/repository already alive in the process holds references captured from the
    `AppContainer` a restore's confirm handler closes right before the swap; rebuilding a fresh
    container in place and hoping already-created ViewModels/Compose recompositions notice is
    exactly the "half-live container" AGENTS.md §1 warns against. Instead, the app is killed and
    relaunched immediately after the swap — success or failure alike, since the container is
    already closed either way — through the exact same `createAppContainer` cold-start path every
    ordinary launch uses, landing on whatever the swap left at the live path (the restored library
    on success, the untouched/rolled-back original on failure). The outcome is written to a small
    marker file as the swap's last step and consumed exactly once on the next launch
    (`consumeRestoreMarker`), surfaced as a one-time Snackbar on the Settings screen.
  - **Confirmation**: a dedicated modal (`RestoreConfirmationDialog`), reached only after the picked
    file has already passed validation, states plainly what will be lost, and requires an explicit
    checkbox acknowledgement before its destructively-`error`-colored confirm button enables — never
    a single tap next to the export button. The restore button itself is an outlined, `error`-toned
    control, visually distinct from every other action on the screen.
  - No schema change (`AppDatabase` stays at v4); no new dependency; no new permission — reuses the
    SAF `CreateDocument`/`OpenDocument` plumbing Phases A/B already established.
- **Goodreads import** (ROADMAP Task 8 Phase D, completing Task 8) — a distinct "Import from
  Goodreads" action on the Settings screen's "Data" section, separate from the app's own CSV
  import so the two are never confused: its own visible `DuplicatePolicy` picker, its own button,
  and a single-file SAF picker (a Goodreads export has no reading-logs equivalent to ask for
  afterward).
  - New `shared/.../features/portability/goodreads/` mapping layer
    (`GoodreadsCsvTableReader`/`GoodreadsCsvImporter`) on top of the existing `CsvReader` — not a
    new parser. Columns are matched **by header name**, never by position, so a reordered export,
    unknown/extra columns, or missing optional columns all import cleanly; only `Title` is
    required, and its absence refuses the whole file with a clear message.
  - **Excel-armor stripping**: Goodreads writes an ISBN as `="9780593135204"` (including the
    empty-ISBN case `=""`) to stop Excel mangling it — stripped before any ISBN handling, including
    the empty-armor case, which unwraps to blank rather than a stray `=""`.
  - **`Binding` → `BookFormat`**: Hardcover/Library Binding/Board Book/Leather Bound → `HARDCOVER`;
    Paperback/Mass Market Paperback/Trade Paperback/Spiral-bound/Unbound → `PAPERBACK`; Kindle
    Edition/ebook/Nook → `EBOOK`; Audiobook/Audio CD/Audible Audio → `AUDIOBOOK`; anything
    unrecognized (including blank) falls back to `PHYSICAL` — the same "binding unknown" fallback
    ISBN-sourced ingestion already uses.
  - **`Exclusive Shelf` → `ReadingStatus`**: `read` → `FINISHED`, `currently-reading` → `READING`,
    `to-read` → `TO_READ`; blank/unrecognized also falls back to `TO_READ`. Nothing maps to `DNF` —
    Goodreads' exclusive shelf has no such state (a user-tracked DNF lives in the `Bookshelves`
    column this phase drops, not here), so guessing at it was rejected as more likely to mislabel a
    book than help.
  - **`Date Read` → `finishedAt`**, but only when the shelf itself resolved to `FINISHED` — a stray
    `Date Read` value alongside a `currently-reading`/`to-read` shelf is never used, so it can't
    contradict `BookDetailsEntity.finishedAt`'s "when status most recently became FINISHED"
    invariant. `Date Added` → `createdAt`, falling back to import time when blank/unparseable. Both
    dates tolerate a blank or malformed cell as "unknown" (`null`) rather than rejecting the row —
    foreign, Goodreads-controlled formatting isn't held to this app's own stricter timestamp rules.
  - **`Year Published` vs `Original Publication Year` decided**: `releaseYear` prefers `Original
    Publication Year` (the year the *work* first appeared) over `Year Published` (the specific
    *edition/printing* Goodreads happened to catalog, which can be a much later reprint — the
    "2026 anniversary printing masks an original 1926 publication" problem this bullet was written
    to avoid), falling back to `Year Published` only when the original-year column is blank.
  - **`My Rating`, `Bookshelves`, and `Read Count` are dropped, not staged** — the schema has no
    rating/genre/read-through-count column yet (see Tasks 10/12), and per the standing decision no
    staging table or schema bump is added speculatively. Instead, every import summary now carries
    a `notes` list (new field on `ImportSummary`, default-empty for the app's own CSV import) that
    the Goodreads path always populates with an explicit notice: which columns weren't imported,
    why, and that **keeping `goodreads_library_export.csv`** lets a later re-import (once Tasks
    10/12 land) backfill them automatically — `DuplicatePolicy.MERGE` only fills a blank and never
    overwrites, so a repeat import is always safe. The Settings screen's import-summary dialog
    renders every note in full, the same "no silent partial result" treatment rejections already
    got.
  - **`ImportDataUseCase` generalized, not duplicated**: the book-row duplicate-matching/insert/
    update logic `execute()` already had was extracted into a private `resolveBookRows(existing,
    identifiers, parsedRows, duplicatePolicy)` operating on already-parsed rows rather than raw CSV
    text, so a new `executeGoodreads()` (added to the `ImportUseCase` interface) reuses it
    unchanged — same duplicate-matching precedence, same per-`DuplicatePolicy` field rules, same
    `ImportWriteRepository.importAtomically` transaction — fed from the Goodreads mapping layer
    instead of `LibraryCsvImporter`. `ImportViewModel` gained a matching `importGoodreads(...)`
    sharing its existing `Idle`/`Loading`/`Success`/`Error` state machine with `importData(...)`.
  - No schema change (`AppDatabase` stays at v4); no new dependency; no new permission.
- **Fixed: every SAF file read/write on the Settings "Data" screen now runs off the main
  thread.** `readCsvFromUri`/`writeCsvToUri`/`copyFileToUri`/`copyUriToFile` were all being called
  directly inside `rememberLauncherForActivityResult` callbacks (or a `rememberCoroutineScope`
  launch with no dispatcher), which run on the main thread — a large CSV or `.sqlite` file
  (`copyUriToFile` streams the *whole database* during restore) could block the UI long enough to
  ANR. Every call site now wraps the actual stream I/O in `withContext(Dispatchers.IO)`.
- **Fixed: an interrupted `.sqlite` restore could no longer strand the app with a closed database
  in a live process.** The restore-confirm handler's `appContainer.close()` → `commit()` →
  `restartApp()` sequence ran in the `rememberCoroutineScope` scope tied to the Settings screen's
  composition — a cancellation any time after `close()` (e.g. the composable leaving composition)
  skipped `restartApp()` entirely, leaving a running process with a closed `AppContainer` and no
  way back except force-killing the app by hand. The whole sequence, including `restartApp()`
  itself, now runs inside a single `withContext(Dispatchers.IO + NonCancellable)` block, so once
  started it always runs to completion regardless of what happens to the composition.
- **Fixed: a failed reading-logs read during CSV import was silently reported as a successful
  library-only import.** The reading-logs picker's `null` result legitimately means "the user
  skipped this optional file" and correctly proceeds as a library-only import, but a *non-null* uri
  whose `readCsvFromUri` read then failed was folded into that exact same "no reading logs" case —
  the read failure went unreported and `importData` ran anyway. The two are now distinguished:
  a failed read of a file the user actually picked shows the same `importFailureMessage` the
  library-file picker already shows in this situation, and stops before calling `importData`.
- **Fixed: whole-database-sized temp copies could leak on cancellation or failure during `.sqlite`
  backup/restore.** Both the backup-destination launcher's staged snapshot cleanup and the
  restore-file-picker's `incomingFile` cleanup sat as plain statements after a suspension point;
  since both run in the Settings screen's composition-scoped `rememberCoroutineScope`, navigating
  away mid-copy cancelled the launch and skipped that cleanup outright, leaving a full private copy
  of the database behind in `cacheDir`. Both cleanups now run in a `finally` block wrapped in
  `NonCancellable`, so the temp file is always removed once ownership hasn't been handed off
  elsewhere, regardless of how the coroutine exits.
- **Fixed: a book added by ISBN and later re-imported from Goodreads could silently duplicate
  instead of merging, because the two paths disagree about what `release_year` means.** ISBN
  ingestion (`AddBookByIsbnUseCase` via `OpenLibraryClient`'s `/isbn/{isbn}.json` lookup) stores the
  scanned *edition*'s publish year, while `GoodreadsCsvImporter` deliberately prefers the *work*'s
  `Original Publication Year` — so the same book (e.g. a 2026 anniversary printing scanned into the
  app, later shelved on Goodreads under its 1926 original-publication year, possibly under a
  different edition's ISBN) could miss `ImportDataUseCase`'s `media_id` → `isbn` →
  `title`+`release_year` matching tiers entirely and be inserted as a brand-new book rather than
  merged into the existing one. `ImportDataUseCase` now has a fourth, last-resort matching tier:
  case-insensitive **title only**, reached only when the stronger tiers (including exact
  title+release-year) all fail — covering both a genuine year disagreement and either side simply
  missing a year. Because matching on title alone (no year, no author column) carries a real risk
  of conflating two different books that happen to share a title, every match made through this
  tier is additionally surfaced in the import summary's advisory notes (`ImportSummary.notes`),
  naming the row, the matched title, and both release years — so `DuplicatePolicy.MERGE` can
  genuinely backfill the existing record in this scenario, but the lower-confidence match is never
  applied silently. A matching ISBN still resolves at the stronger ISBN tier regardless of
  differing years, with no note. This is a no-schema-change mitigation (`AppDatabase` stays at v4);
  see `ROADMAP.md`'s Task 8 entry for the schema-based follow-up (storing both edition and work
  year) this unblocks later.

## [0.6.0] - 2026-08-02

UI revamp and settings. Every book-facing surface — Details, reading history, Edit Book, and
both session dialogs — now shares one visual language, with reading history rendered as a
timeline. Pages-vs-percent tracking becomes an explicit per-book choice instead of something
inferred from whether a page count happened to be known, and a Settings screen arrives with a
week-start-day preference driving the Stats periods. Ships **Room schema v4** with a tested
migration. Also fixes a ~50% flake rate in the test suite that had been undermining every
verification run.

### Added

- **Explicit per-book tracking mode** (ROADMAP Task 7 Phase A) — replaces the old silent
  `totalPages != null` inference (page-based vs. percent-based progress) with an explicit
  `TrackingMode` (`PAGES`/`PERCENT`) field on `BookDetailsEntity`, editable on the Edit Book screen
  alongside format/status. Ships **Room schema v4** with a tested `MIGRATION_3_4`
  (`shared/.../core/database/Migrations.kt`): an `ALTER TABLE ... ADD COLUMN trackingMode TEXT NOT
  NULL DEFAULT 'PAGES'` followed by `UPDATE ... SET trackingMode = 'PERCENT' WHERE totalPages IS
  NULL`, chosen specifically to reproduce the app's pre-v4 inferred behavior exactly (no existing
  book's mode changes as an observable side effect of upgrading). Ingestion
  (`BookRepository.addBook` / `AddBookByIsbnUseCase`) defaults new books the same way: a known page
  count -> `PAGES`, otherwise `PERCENT`. `BookDetailScreen`'s progress formatting and its
  pending/manual session dialogs (`isPageMode`, the Task 6 Phase B auto-derived `deltaPages`
  behavior) now all read this explicit field instead of re-deriving the mode from `totalPages`,
  closing the "two competing notions of mode" gap the old inference left open.
- **App settings store** (ROADMAP Task 7 Phase A) — a new `app_settings` key-value table (`key TEXT
  PRIMARY KEY, value TEXT NOT NULL`), added in the same schema v4 migration above since it's a
  small, additive, unrelated change bundled into one migration rather than two. A key-value shape
  was chosen over a single-row typed settings table specifically so that adding a future setting
  (e.g. the week-start-day preference ROADMAP Task 7 Phase B will add) needs no further schema
  migration. `AppSettingsDao` plus a typed `SettingsRepository`
  (`shared/.../features/settings/data/`) expose reactive `String`/`Int`/`Boolean` accessors with
  the raw key-value mechanics hidden; no setting has defined semantics yet — Phase B is the first
  consumer.
- **Settings screen + week-start-day preference** (ROADMAP Task 7 Phase B) — the first concrete
  setting to occupy the store Phase A built, and the app's first Settings screen:
  - **`WeekStartDay`** (`MONDAY`/`SUNDAY`, `shared/.../features/settings/data/WeekStartDay.kt`):
    persisted via `SettingsRepository` under a single `week_start_day` key, stored by the enum
    constant's *name* (not ordinal) so a future reordering/insertion can't silently corrupt an
    existing stored value — a deliberate refinement over the ordinal-`Int` approach Phase A's own
    `SettingsRepository` KDoc had speculated a setting like this might use. `observeWeekStartDay`/
    `getWeekStartDay` default to `MONDAY` — ISO-8601, matching the app's pre-Phase-B hardcoded
    behavior exactly — whenever the key is unset *or* holds a value that no longer maps to a
    constant, so nothing changes for a user who never opens Settings.
  - **`StatsRepository.thisWeekBounds`** gains a `weekStartDay: WeekStartDay = WeekStartDay.MONDAY`
    parameter (defaulted, so no existing call site needed updating for the default behavior) and
    now computes `daysSinceStart` as `(today's ISO day number - weekStartDay's ISO day number) mod
    7`, replacing the old Monday-only subtraction. `thisMonthBounds` is unchanged (calendar month,
    per the ROADMAP's decision). **`observeReadingStreak` finding**: checked and confirmed
    unaffected by this preference — it walks backward one calendar day at a time via `computeStreak`
    with no notion of "week" at all, so there is no week boundary for the setting to shift; this is
    documented on the method directly rather than left implicit. New `StatsRepositoryTest` cases
    cover both start days including the awkward boundary cases: a Sunday date under a `MONDAY`
    start maps back to the *preceding* Monday, the same Sunday under a `SUNDAY` start maps to
    *itself*, plus a Wednesday and a Monday under a `SUNDAY` start.
  - **`SettingsViewModel` + `SettingsUiState`** (`shared/.../ui/`): a single `StateFlow` (`map` over
    `SettingsRepository.observeWeekStartDay`, `stateIn`/`WhileSubscribed`, matching
    `LibraryViewModel`/`StatsViewModel`'s convention) exposing the current week-start-day preference
    plus a `setWeekStartDay(...)` action. `AppContainer` now wires a `settingsRepository`
    (previously unexposed since Phase A defined no concrete setting). 4 new `SettingsViewModelTest`
    cases (Room-backed, added to `shared/build.gradle.kts`'s android-unit-test exclusion filter by
    exact class name, mirroring `StatsViewModelTest`/`EditBookViewModelTest`) plus 7 new
    `WeekStartDayTest` cases (default-when-unset, persistence round-trip, malformed-value handling).
  - **`StatsViewModel` reactivity decision**: its "this week" period now **re-buckets immediately**
    when the week-start-day setting changes while the Stats screen is open, via
    `settingsRepository.observeWeekStartDay().flatMapLatest { ... }` feeding the existing week
    `combine` chain — straightforward to add without restructuring the ViewModel's shape, so it was
    done rather than left stale. `"This month"`'s bounds and `today` itself are still resolved once
    at construction (unchanged, already-documented staleness — a real midnight/month rollover still
    needs a fresh ViewModel; only the *setting* is now live). 2 new `StatsViewModelTest` cases prove
    a week-start-day change re-buckets "this week" without recreating the ViewModel, and that "this
    month" stays untouched by it.
  - **Settings screen** (`app/.../ui/screens/SettingsScreen.kt`): a `LazyColumn` of titled,
    card-backed `SettingsSection`s — built this way specifically because the ROADMAP expects more
    settings to land here later, so a future one is a new row/section rather than a screen
    restructure. The week-start-day choice renders as a two-option Material 3
    `SingleChoiceSegmentedButtonRow` (chosen over `EditBookScreen`'s vertical radio rows: segmented
    buttons fit a small, fixed, side-by-side binary choice better than a list built for longer,
    unrelated option sets). `Route.Settings`/`SettingsScreenRoute`/stateless `SettingsScreen` follow
    the established route-wrapper/stateless-screen split; reachable from a new
    `Icons.Filled.Settings` icon in `LibraryScreen`'s TopAppBar, alongside the existing stats icon —
    confirmed present in the curated `material-icons-core` set (unlike the stats/chart icon gap
    noted in `v0.4.0`), so no local vector drawable was needed this time. All new user-visible text
    added via string resources; previews cover both week-start-day selections.

### Changed

- **Details tab and Reading history tab visual revamp** (ROADMAP Task 7 Phase C,
  `app/.../ui/screens/BookDetailScreen.kt`) — both tabs were "functionally complete but visually
  plain" going into this task; this phase is layout/hierarchy only, no new state and no ViewModel
  changes.
  - **Details tab**: the old single stack of prefix-string `Text` rows ("Released: …", "ISBN: …",
    "Format: …", "Progress: …") is replaced with a considered hierarchy: `BookHeader` now renders
    just the cover, a proper heading block (title as `headlineSmall`/bold, release year as a muted
    subtitle via new `detail_published_year`), and the reading-status chip; a new `ProgressSection`
    card is promoted directly below the header with the current progress as a large, primary-colored
    headline plus a `LinearProgressIndicator` whenever a completion fraction is derivable (new
    `progressFraction` helper, mirroring `formatProgress`'s own page/percent-mode precedence, and
    degrading to text-only when tracking by page with no known `totalPages`, or to a muted "No
    progress logged yet" message when nothing has ever been logged); `TimerCard` is restyled with a
    `primaryContainer` background and full-width buttons so it reads as *the* primary action on the
    tab rather than another stacked card of equal weight; a new `MetadataCard` renders ISBN (keeping
    its existing copy `IconButton`)/format/total-pages/tracking-mode as a compact two-column
    key/value grid (`MetadataRow`) instead of concatenated prefix strings. `released_prefix`/
    `isbn_prefix`/`format_prefix`/`total_pages_prefix`/`progress_prefix` are deleted from
    `strings.xml` (superseded by dedicated `detail_*` strings/labels now that each fact has its own
    UI element) rather than left dangling.
  - **Reading history tab**: the flat `LazyColumn` of `SessionRow`s (each concatenating
    `"Duration: 0:31:00  •  42 -> 78"` into one line) is replaced with a **timeline**, built entirely
    from Compose primitives per AGENTS.md §5 (no chart/timeline library added). `buildTimelineEntries`
    flattens the existing most-recent-first session list into date-grouped `TimelineEntry` values (a
    `DateHeader` — "Today"/"Yesterday"/full date — whenever the calendar day changes, since sessions
    are already time-sorted so simple adjacency is sufficient, no re-sort needed); `TimelineRow`
    renders each entry against one continuous rail (a plain `Canvas` line + dot, suppressed only at
    the list's very first/last entry) so the spine runs unbroken through both date separators and
    session cards. Each session's facts render as distinct `StatBadge` chips (`SessionEventCard`) —
    duration, start→end position range, pages read — instead of one run-on string; the unknown-
    duration case (nullable `ReadingSessionEntity.durationSeconds`, schema v2) now shows an explicit
    muted/italic "Duration unknown" badge rather than omitting the segment silently, and still never
    renders a misleading `0:00:00`. `session_duration_positions`/`session_positions`/
    `pages_read_count`/`session_history_title` are deleted (superseded by the new
    `session_stat_*`/`session_position_range`/`session_pages_delta`/`session_duration_unknown`/
    `timeline_*` strings and the timeline structure itself, which makes the redundant "Session
    History" heading unnecessary next to the tab bar's own "Reading history" label). Every existing
    capability is preserved: per-session edit/delete icons and the delete confirmation dialog, the
    "Log session manually" affordance, and the empty state.
  - **State hoisting verified unchanged**: `sessionToDelete`/`showManualEntry`/`sessionToEdit`/
    `selectedTabIndex` stay hoisted in `BookDetailContent` above the `when (selectedTabIndex)` branch
    exactly as Task 6 Phase D established — this phase only changed what renders *inside* each tab's
    composable, not where dialog-triggering state lives. `SelectionContainer`/`DisableSelection`
    scoping and `InteractiveCoverBox`'s tap-to-enlarge/long-press-to-refetch behavior are unchanged
    (now wrapping `BookHeader` and `MetadataCard` as two separate `SelectionContainer`s instead of
    one spanning the old single metadata block, so the timer's live-ticking elapsed time is never
    inside a selectable region).
  - New `@Preview`s cover both tabs in light and dark theme (`MediaTrackerTheme(darkTheme = true,
    dynamicColor = false)`), plus the awkward states: no cover, an unusually long title, unknown
    total pages, no progress logged, an empty session list, and a session with unknown duration.
  - **Deferred to Phase D**: the Edit Book screen's own visual revamp (ROADMAP Task 7's separate
    "Edit screen revamp" bullet) is out of scope for this phase.
- **Edit Book screen visual revamp** (ROADMAP Task 7 Phase D — final phase of Task 7,
  `app/.../ui/screens/EditBookScreen.kt`) — the last screen still presenting as one undifferentiated
  scrolling column of text fields and vertical radio groups; brought in line with the `SettingsScreen`/
  `BookDetailScreen` visual language established in Phases B/C. Layout/control choices only — no
  ViewModel, validation-bound, or `EditBookUiState` shape changes.
  - **Four titled, card-backed sections** replace the flat field list, reusing `SettingsScreen`'s
    "`Text` title above a `Card`" convention verbatim via a new private `FormSection`: **Book
    details** (title, release year), **Physical** (format, total pages, tracking mode), **Status**
    (reading status), **Purchase** (purchase price).
  - **Each enum picker now uses the Material 3 control that fits its option count**, replacing three
    identical vertical radio-button groups: `TrackingMode` (2 values) is a
    `SingleChoiceSegmentedButtonRow`, matching `SettingsScreen`'s week-start-day control exactly;
    `BookFormat` (5 values) is a read-only `ExposedDropdownMenuBox` dropdown, collapsing what used to
    be five stacked radios for an infrequently-changed field into one closed field opened on demand;
    `ReadingStatus` (4 values) is a `FilterChip` row, mirroring `LibraryScreen`'s existing
    `StatusFilterRow` shape for the same enum so it renders consistently wherever it appears in the
    app.
  - **Save/Cancel move to a persistent, non-scrolling bottom action bar** (new `EditBookBottomBar`,
    on an elevated `Surface` at the default `BottomAppBar` tonal elevation) instead of two inline
    buttons at the end of the scrolling content, reading as one committed action pair that's always
    reachable. The Save button shows an inline `CircularProgressIndicator` in place of its label
    while `EditBookUiState.Ready.isSaving` is true, in addition to the existing disabled-fields
    behavior.
  - **Purchase price gets a "$" prefix and total pages a "pages" suffix** (`OutlinedTextField`'s
    `prefix`/`suffix` slots) so each numeric field reads as the unit it represents; keyboard types
    (`Decimal`/`Number`) are unchanged.
  - **Parse-once validation is untouched**: title (non-blank), release year
    (`BookRepository.MIN_RELEASE_YEAR`..`MAX_RELEASE_YEAR`), purchase price (`>= 0`), and total pages
    (`> 0`) are still each parsed exactly once above the fields, with `isError`/`supportingText`
    disambiguating blank/unparseable/out-of-range and gating the bottom bar's Save button — only the
    surrounding layout changed. Two new strings (`edit_purchase_price_prefix`, `edit_total_pages_suffix`)
    plus four new section-title strings (`edit_section_book_details`/`edit_section_physical`/
    `edit_section_status`/`edit_section_purchase`) were added; no existing `EditBookScreen` string was
    orphaned by the revamp. Previews cover light and dark theme, a validation error, a save in
    progress, unknown total pages (with no purchase price on record), and a very long title.
- **Session-logging dialogs visual revamp** (ROADMAP Task 7 Phase E — final phase of Task 7,
  `app/.../ui/screens/BookDetailScreen.kt`) — `ManualSessionDialog`/`PendingSessionDialog` were the
  last surface still in the pre-revamp style: Task 6 Phase B had already made them functionally
  solid (grouped fields, auto-derived pages, full parse-once validation) but never gave them a
  visual pass, and both launch directly from the screens Phases B/C/D just restyled. Layout only —
  every validation rule, precision-preservation behavior, and dismissal rule from Task 6 Phase B is
  unchanged.
  - **Presentation: full-screen `Dialog`, not a bigger `AlertDialog`.** `ManualSessionDialog` alone
    carries date, time, duration, start/end position, optional pages, and notes — enough that the
    old `AlertDialog` already needed an internal scroll on a phone. Material 3's own guidance favors
    a full-screen dialog over `AlertDialog`/`ModalBottomSheet` once a form is this involved on a
    compact screen, so both dialogs now render through a new shared `SessionDialogFrame`: a plain
    `Dialog` (`DialogProperties.usePlatformDefaultWidth = false`, not a nav-graph destination, since
    the dialogs are still opened from hoisted boolean/nullable state in `BookDetailContent` per
    AGENTS.md §5) hosting a `CenterAlignedTopAppBar`, a scrollable body of new card-backed
    `SessionFormSection`s (the same "title `Text` above a `Card`" convention as `EditBookScreen`'s
    `FormSection`/`SettingsScreen`'s `SettingsSection`), and a pinned `SessionDialogBottomBar`
    (elevated `Surface`, two full-width buttons) mirroring `EditBookBottomBar` exactly — the pair now
    reads as one system with the rest of the app rather than a fourth style.
  - **`ManualSessionDialog`**: "When" (date/time buttons, now side by side), "How long" (duration,
    new "min" suffix), "Progress" (start/end position, page-mode auto-derived pages read or
    percent-mode's manual field, now with a "%" suffix on the position fields in percent mode), and
    a new "Notes" section (previously a bare field with no section of its own) — plus a top-bar close
    icon alongside the bottom bar's Cancel, both wired to the same `onDismiss`.
  - **`PendingSessionDialog`**: the finished run's duration is now a small `primaryContainer` stat
    card echoing `TimerCard` (the control that produced the run), above the same "Progress"/"Notes"
    sections. **Still not dismissible by outside tap or back press** — `showCloseIcon = false`,
    `dismissOnBackPress`/`dismissOnClickOutside = false`, `onDismissRequest = {}` — Discard remains
    the only path that abandons a finished timed run, and a failed Save still leaves the dialog open
    with the error shown (now pinned above the bottom bar, outside the scrollable body) and the
    pending session intact for retry.
  - **Every Task 6 Phase B non-negotiable verified unchanged**: parse-once validation
    (blank/unparseable/out-of-range) on every numeric field with `isError`/`supportingText` gating
    Save; a session's original per-second `durationSeconds` is still re-emitted verbatim when its
    duration field is untouched (never recomputed from rounded minutes); `MAX_MANUAL_DURATION_MINUTES`
    still bounds the duration field and a blank duration still saves as a legitimate `null`; page vs.
    percent mode still reads the stored `TrackingMode` rather than re-inferring it from `totalPages`;
    the date/time pickers' Cancel button still reverts to the selection captured when each was
    opened; the start-position field still prefills from `currentProgress` in both dialogs; edit mode
    still prefills every field from the session being edited.
  - Three new strings (`manual_entry_section_notes`, `duration_minutes_suffix`,
    `position_percent_suffix`); `session_duration_label` is deleted (its one call site now composes
    `timer_card_title` + the formatted duration directly inside the new stat card) — the only string
    this revamp orphaned. `duration_minutes_label`'s text was shortened now that a "min" suffix
    carries the unit. New `@Preview`s (light/dark) cover `ManualSessionDialog` in page mode, percent
    mode, edit mode (prefilled from an existing session), a validation error (duration past the max),
    and `PendingSessionDialog`'s ready and failed-save states.

**Task 7 is now complete** — UI revamp & settings work across the Details/Reading-history/Edit
screens, the session-logging dialogs, plus the new Settings screen, explicit tracking mode, and Room
schema v4 all landed above.

## [0.5.0] - 2026-08-02

Books polish: the book domain becomes correctable and navigable rather than just functional.
Book metadata is editable (including new Paperback/Hardcover formats), reading sessions can be
edited instead of only deleted, books carry a reading status, and the Book Detail screen splits
into Details / Reading history tabs. Covers fetch at full size from Google Books and can be
re-fetched per book. Ships **Room schema v3** with a tested migration.

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
  - **Editing preserves a timer-backed session's exact duration unless the duration field is
    itself edited.** `ManualSessionDialog`'s duration field only has minute granularity, and an
    initial version of this edit path converted it back to seconds unconditionally on Save — so
    opening the dialog to fix an unrelated field (a position, a note) on a session with real
    sub-minute precision (e.g. 1,847s = 30m47s) silently rewrote its stored `durationSeconds` to
    1,860s the moment Save was tapped, even though duration was never touched. That is a
    data-integrity defect (AGENTS.md §1: user data safety overrides development shortcuts), not an
    acceptable simplification, and has been fixed before this phase ships: the dialog now captures
    both its prefilled duration text and the session's original `durationSeconds`, and Save
    re-emits that original value verbatim whenever the duration text is unchanged from its prefill
    — only a genuine edit (or a blank field, or create mode) produces a fresh minutes→seconds
    conversion. `timestampStart` derivation follows the same effective-seconds value so
    start/end/duration stay mutually consistent. The dialog's `onSave` contract changed shape
    accordingly (`durationSeconds: Long?` instead of `durationMinutes: Long?`), doing the
    minutes↔seconds conversion inside the dialog itself, where the "was it edited?" knowledge
    lives, rather than in the route lambda that previously blindly reconverted it.
  - 5 new `ReadingSessionRepositoryTest` cases, 6 new `LogReadingSessionUseCaseTest` cases, and 4
    new `BookDetailViewModelTest` cases cover: happy-path field updates, a rejected edit leaving the
    row completely unchanged, editing a nonexistent session id, and — the duration-precision fix
    above — a position-only edit leaving a sub-minute-precision `durationSeconds` byte-identical.

- **Reading status** (ROADMAP Task 6 Phase C) — `TO_READ`/`READING`/`FINISHED`/`DNF`, the missing
  concept that had forced deferring the books-finished stat:
  - `ReadingStatus` (`shared/.../core/database/entities/ReadingStatus.kt`), persisted on
    `BookDetailsEntity` (not `MediaItemEntity`) — the four values are reading-specific (e.g. `DNF`
    has no obvious watch-status analogue), and Task 8 will scope its own movie/TV watch-state model
    rather than inherit this one sight-unseen; see the entity's KDoc for the full justification.
  - **Room schema v3** (`shared/.../core/database/AppDatabase.kt`, version 2 → 3) adds
    `book_details.status` (`TEXT NOT NULL DEFAULT 'TO_READ'`) and `book_details.finishedAt`
    (`INTEGER`, nullable) via `MIGRATION_2_3` (`shared/.../core/database/Migrations.kt`). Simple
    `ALTER TABLE ADD COLUMN` statements, not a table rebuild — unlike `MIGRATION_1_2`, this
    migration only adds columns, and SQLite's `ALTER TABLE ADD COLUMN` already supports both a
    `NOT NULL` addition with a constant default and a nullable addition with no default; verified
    against the newly generated `shared/schemas/.../3.json`. A pre-existing book with at least one
    `reading_sessions` row is derived to `'READING'` (someone has demonstrably started it) via a
    follow-up `UPDATE ... WHERE EXISTS`; every other pre-existing row keeps the blanket `'TO_READ'`
    default. `finishedAt` is `NULL` for every pre-existing row — safe, not lossy, since no row can
    ever be derived to `'FINISHED'` (no pre-v3 signal supports it). 5 new `MigrationTest` cases
    (`shared/src/jvmTest/...`) cover the derivation split, schema validation, and that the new
    columns accept a `'FINISHED'` status with a real `finishedAt` going forward; the derivation
    assertion was manually verified to fail against a deliberately neutered migration before being
    reverted.
  - `BookRepository.updateBookMetadata` gains a required `status` parameter; a new
    `BookRepository.updateReadingStatus(mediaId, status)` supports a quick single-field change
    without the full metadata round-trip. Both share a `resolveFinishedAt` helper: transitioning
    *into* `FINISHED` stamps `finishedAt` to now, staying `FINISHED` on a re-save preserves the
    original `finishedAt` (an unrelated edit must not silently rewrite when a book was actually
    finished), and moving *away from* `FINISHED` clears it. `addBook`/`AddBookByIsbnUseCase` default
    new books to `TO_READ` — nothing has been read the moment a book is added.
  - `EditBookScreen` gains a status radio-button group alongside the existing format picker.
    `BookDetailScreen`'s header gains a quick status `AssistChip` + `DropdownMenu` (all four
    statuses, one tap) for the common "just started"/"just finished" case without a full edit
    round-trip.
  - **Books-finished stat** (`StatsDao`/`StatsRepository`/`StatsViewModel`/`StatsScreen`): a lifetime
    total (`observeBooksFinishedTotal`, exact from the moment schema v3 exists — it counts current
    `status`, no timestamp needed) shown as its own Stats-screen card, *and* a period-scoped count
    (`observeBooksFinishedInRange`, using the new `finishedAt`) folded into the existing "This
    week"/"This month" cards alongside time-read/sessions/pages-read. The period-scoped count is
    honest, not fabricated: every pre-v3 row's `finishedAt` is `NULL` by construction, so it can only
    ever reflect finishes recorded after this phase ships, never retroactive history.
  - **Library status filter** (`LibraryUiState`/`LibraryViewModel`/`LibraryScreen`): a row of
    Material 3 `FilterChip`s ("All" + one per status) above the book list — chosen over a dropdown
    since the option count is small and fixed, so every option stays visible and one-tap-selectable.
    Purely client-side/in-memory (an in-memory `MutableStateFlow<ReadingStatus?>` combined with the
    existing reactive book list); does not re-query the database. `LibraryUiState.books` changed
    shape from `List<MediaItemEntity>` to `List<BookWithDetails>` (via a new
    `BookRepository.observeAllBooksWithDetails()`) since filtering needs each book's status, which a
    bare `MediaItemEntity` can't expose. Sorting is unchanged (still title, from the existing
    underlying query) — no new sort UI was added this phase.

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
- **Book Detail screen tabs** (ROADMAP Task 6 Phase D, `app/.../ui/screens/BookDetailScreen.kt`):
  the single scrolling column is split into a `PrimaryTabRow` with two tabs — **Details** (cover +
  metadata header, reading status, progress, **and the live reading timer**; new private
  `DetailsTab` composable) and **Reading history** (manual-entry affordance, session history; new
  private `ReadingHistoryTab` composable). *(The timer card was originally placed on Reading
  history when this phase first shipped; a books-polish pass — see below — moved it to Details,
  since users expect to start/stop reading from the same screen as the book itself rather than a
  history tab. `DetailsTab`/`ReadingHistoryTab`'s parameter lists reflect that final split
  directly; there was never a released version with the timer on Reading history.)* No Purchase &
  Borrow tab — that data model doesn't exist yet (moved to the backlog below, alongside manual
  cover entry). `sessionToDelete`/`showManualEntry`/`sessionToEdit` (all pre-existing) and the new
  `selectedTabIndex` are all hoisted one level above the tab content in `BookDetailContent`, so
  switching tabs never tears down a dialog opened from either tab (Reading history's manual-entry/
  delete dialogs, or Details' pending-timer-session dialog); `PendingSessionDialog`/
  `ManualSessionDialog`/`DeleteSessionConfirmationDialog` render unconditionally on that same
  state, outside the `when (selectedTabIndex)` branch, so they overlay whichever tab is showing.
  `state.errorMessage`'s banner moved from inside the (now-split) content column to directly below
  the `PrimaryTabRow` — it now surfaces failures from session mutations, book deletion, status
  changes, *and* the cover-refetch action below, so pinning it to one specific tab would have
  hidden it whenever the failing action originated from the other tab. `BookDetailScreen`/
  `BookDetailContent` gain no new required parameters for the tab split itself (tab index is local
  UI state); `@Preview`s updated to cover both tabs (`DetailsTabPreview`, `ReadingHistoryTabPreview`,
  alongside the existing whole-screen previews which default to the Details tab).
- **Selectable/copyable text** (ROADMAP backlog item, addressed alongside Phase D since it touches
  the same file): the Details tab's `BookHeader` metadata block (title/release year/ISBN/format/
  total pages/progress) is wrapped in a `SelectionContainer` so it can be long-press selected and
  copied, applied narrowly per the backlog item's own caveat — the status `AssistChip`, the ISBN
  copy button, and (as of the books-polish pass below) the cover thumbnail's own tap/long-press
  handling are each wrapped in `DisableSelection` so long-press selection never conflicts with
  their tap handling. Session rows and library cards are untouched (no `SelectionContainer` around
  either). Explicit tap-to-copy added on the ISBN value specifically: a small `IconButton` with a
  content description, using `LocalClipboard`/`ClipEntry` — the current, non-deprecated Compose
  clipboard API in this project's
  resolved Compose BOM (verified against the actual resolved `androidx.compose.ui:ui-android:1.11.4`
  artifact: it declares both `LocalClipboardManager` — deprecated — and `LocalClipboard`/
  `ClipEntry`/`Clipboard`, so the latter was used rather than guessed at) rather than the deprecated
  `LocalClipboardManager`. Android 13+ (API 33/`TIRAMISU`) shows its own system "copied" confirmation,
  so an in-app `Snackbar` (via a new `Scaffold(snackbarHost = ...)`) is only shown below API 33 —
  minSdk is 28, so both paths are reachable in practice.
- **Cover improvements** (ROADMAP Task 6 Phase E):
  - **Google Books now selects the largest available cover image**, not just the ~128px thumbnail.
    `GoogleBooksImageLinksDto` (`shared/.../features/books/network/dto/GoogleBooksDto.kt`) now
    declares `small`/`medium`/`large`/`extraLarge` (previously only `thumbnail` was declared, so
    Google's larger links were silently discarded even when a volume provided them) plus
    `smallThumbnail`. A new `GoogleBooksImageLinksDto.largestAvailableUrl()` extension
    (`GoogleBooksClient.kt`) selects `extraLarge > large > medium > small > thumbnail >
    smallThumbnail`, walking the whole chain rather than stopping at the first missing field. This
    matters more than it looks: the field-level cover fallback (Task4 Phase D) consults Google Books
    precisely *when Open Library has no cover*, so books that get a cover from Google were exactly
    the ones stuck with the smallest image. 5 new `GoogleBooksClientTest` cases cover all-sizes-
    present (extraLarge wins), a mid-chain fallback (medium, when only medium-and-smaller are
    present), thumbnail-only, smallThumbnail-only, and all-absent (`null`).
  - **Open Library's own cover sizing was verified and left unchanged** — `OpenLibraryClient`
    already requests `/b/id/{coverId}-L.jpg` (`L` is the largest Open Library offers: S/M/L only)
    keyed by cover id rather than ISBN (ID-keyed cover lookups aren't rate-limited; ISBN/OCLC/LCCN-
    keyed ones are, at 100 requests/IP/5min) — no code change needed here, per the roadmap's own
    finding.
  - **New last-resort `?default=false` ISBN cover probe**: a new `OpenLibraryIsbnCoverProbe`
    (`shared/.../features/books/network/OpenLibraryIsbnCoverProbe.kt`) probes
    `covers.openlibrary.org/b/isbn/{isbn}-L.jpg?default=false`, which returns a real 404 instead of
    Open Library's usual coverless-edition placeholder image — making it safely probeable with just
    a status-code check, unlike the plain ISBN-keyed URL `OpenLibraryClient`'s own KDoc already
    explains it deliberately avoids. Wired as a third fallback step in
    `FallbackBookMetadataProvider.withCoverFallback` (new optional `isbnCoverProbe` constructor
    param, `null` by default so every pre-existing two-arg call site/test keeps its exact prior
    behavior), consulted only when *both* Open Library's own record and the Google Books probe have
    no cover. **This probe is ISBN-keyed and therefore subject to Open Library's
    100-requests-per-IP-per-5-minutes cover rate limit** (unlike the ID-keyed fetches
    `OpenLibraryClient` normally uses) — fine for the one-book-at-a-time re-fetch affordance below,
    but explicitly NOT used in any bulk/loop context in this phase. A new
    `createDefaultBookMetadataProvider(httpClient)` factory (`FallbackBookMetadataProvider.kt`)
    centralizes the standard Open Library → Google Books → ISBN-probe wiring, now shared by both
    `createDefaultAddBookByIsbnUseCase` and the new `createDefaultRefetchCoverUseCase` below so
    ingestion and re-fetch resolve covers identically. 4 new `OpenLibraryIsbnCoverProbeTest` cases
    (200 → URL returned, 404 → `null`, request URL includes `default=false`, network failure →
    `null` rather than throwing) plus 3 new `FallbackBookMetadataProviderTest` cases covering the
    full three-level chain (probe succeeds after both providers are coverless, probe also 404s, and
    the probe is never consulted when the primary already has a cover).
  - **New per-book "re-fetch cover" affordance**, for books added before the field-level cover
    fallback existed (they have no stored cover and previously no way to get one short of deleting
    and re-adding the book). A new `RefetchCoverUseCase` + `createDefaultRefetchCoverUseCase` factory
    (`shared/.../features/books/domain/RefetchCoverUseCase.kt`) re-runs metadata lookup (via the same
    three-level provider chain above) + cover download + content-addressed storage for the book's
    already-recorded ISBN, then updates only `MediaItemEntity.coverImageHash` via two new
    `BookRepository` methods: `getBookWithDetails` (one-shot, non-reactive fetch, unlike
    `observeBookDetail`'s ongoing `Flow`) and `updateCoverImageHash` (deliberately narrower than
    `updateBookMetadata` — touches no other field). Every failure path (no ISBN on record, no
    provider has a cover, download failure, save failure) leaves the book's existing cover completely
    untouched — this only ever *adds* a cover, never removes one. `BookDetailViewModel` gains a new
    required `refetchCoverUseCase` constructor param and a `refetchCover()` method (double-tap-guarded
    via a new `isRefetchingCover` flag on `BookDetailUiState.Ready`, surfacing failures via the
    existing `errorMessage` convention); `AppContainer` wires a new `refetchCoverUseCase`. Surfaced
    on the Book Detail screen's Details tab cover thumbnail's long-press menu (not a TopAppBar/
    overflow icon, chosen because — unlike the always-relevant Edit/Delete icons — this is a rare,
    cover-specific corrective action best discovered right on the cover it affects; see the
    books-polish pass below for why it ended up on the cover's own interactions rather than the
    standalone button this phase first shipped it as) — disabled with an explanatory message when
    the book has no ISBN on record, and showing an in-flight overlay spinner directly on the cover
    while a refetch is running. 7 new `RefetchCoverUseCaseTest` cases (happy path updates the hash
    and writes the file, no ISBN, blank ISBN, provider-coverless leaves the existing hash,
    metadata-lookup failure leaves the existing hash, download failure leaves the existing hash and
    writes no file, unknown media id) plus a `BookDetailViewModelTest` case proving the ViewModel
    plumbs a use-case failure through to `errorMessage` and resets `isRefetchingCover`.
  - **Deliberately NOT implemented this phase** (moved to the backlog below): bulk cover backfill
    across a whole library (the `?default=false` probe's rate limit needs its own throttling for
    that; the per-book affordance above never risks it), and manual cover entry (paste a URL / pick a
    local image — needs a file-picker permission story that belongs in its own phase).
- **Books-polish pass** — user-reported issues in the just-landed Task 6 Phase D/E work above,
  fixed before any of it reaches a tagged release (this bullet amends the two bullets above rather
  than describing a separately-shipped feature):
  - **Cover aspect ratio** (`CoverImage.kt`, `LibraryScreen.kt`, `BookDetailScreen.kt`): `CoverImage`
    hardcoded `ContentScale.Crop` with no say for the caller, and neither call site pinned an aspect
    ratio, so covers rendered at whatever the source image happened to be. On the Library screen the
    cover `Box` constrained width only (`fillMaxWidth(0.2f)`) and let height float free, so a tall
    cover stretched the whole row absurdly tall; on the Detail screen header the `Box` was a fixed
    120dp × 160dp (3:4), narrower/squarer than a typical ~2:3 book cover, so `Crop` sliced the top
    and bottom off nearly every cover. Fixed by giving `CoverImage` a `contentScale: ContentScale =
    ContentScale.Crop` parameter (default preserves prior behavior for any caller that doesn't pass
    one) and adding a single shared `BOOK_COVER_ASPECT_RATIO = 2f / 3f` constant in `CoverImage.kt`,
    applied via `Modifier.aspectRatio(BOOK_COVER_ASPECT_RATIO)` at both call sites so every cover
    gets the same predictable footprint regardless of its actual pixel dimensions. The placeholder
    branch (`CoverPlaceholder`) already rendered whatever `modifier` it was given, so it automatically
    honors the same aspect-ratio footprint as the image branch — no separate fix needed there, and no
    ragged rows when some covers are missing and others aren't. Library keeps `ContentScale.Crop`
    (its `Box` is now `.fillMaxWidth(0.2f).padding(4.dp).aspectRatio(BOOK_COVER_ASPECT_RATIO)`,
    `CoverImage`'s own modifier `Modifier.fillMaxSize()`) since a uniform card-grid look is the goal
    and a small crop is an acceptable trade for every row being the same height; the Detail header's
    cover switches to `ContentScale.Fit` (`Box` is now `.width(120.dp).aspectRatio(
    BOOK_COVER_ASPECT_RATIO)`, i.e. 120dp × 180dp) so the whole cover is visible there instead.
  - **ISBN copy icon** (`BookDetailScreen.kt`): the copy `IconButton` rendered a `"📋"` emoji
    `Text` because `material-icons-core` (this project's curated icon set) has no content-copy
    glyph — the same constraint already documented on the Stats screen's toolbar icon — and adding
    `material-icons-extended` for one icon isn't justified (AGENTS.md §5: no unnecessary
    dependencies). Fixed with a new hand-added vector, `app/src/main/res/drawable/ic_content_copy.xml`
    (the standard Material "content copy" 24dp glyph), rendered via
    `Icon(painter = painterResource(R.drawable.ic_content_copy), ...)` and tinted through the normal
    `LocalContentColor` `Icon` already applies — no dependency added, no behavior change to the
    click/clipboard/description logic below it.
  - **Timer moved from Reading history to the Details tab** (`BookDetailScreen.kt`): described
    inline in the "Book Detail screen tabs" entry above — `TimerCard` (and the pending-session
    dialog's trigger path, i.e. `onStartReading`/`onPauseReading`/`onResumeReading`/`onStopReading`/
    `timerState`/`elapsedSeconds`) moved from `ReadingHistoryTab` to `DetailsTab`; the manual-entry
    affordance and session history list stay on Reading history. `sessionToDelete`/
    `showManualEntry`/`sessionToEdit`/`pendingSession` dialogs remain hoisted in `BookDetailContent`
    above the `when (selectedTabIndex)` branch (pre-existing Phase D structure, verified unchanged),
    so they keep overlaying whichever tab is showing regardless of which tab the timer now lives on.
  - **Cover interactions replace the standalone "Re-fetch cover" button** (`BookDetailScreen.kt`):
    the `OutlinedButton` below `BookHeader` is gone; a new private `InteractiveCoverBox` wraps the
    Detail header's cover in `Modifier.combinedClickable` (`ExperimentalFoundationApi`, opted into
    on that composable) — **tap** opens the cover enlarged in a new `EnlargedCoverDialog`
    (`ContentScale.Fit`, sized to `BOOK_COVER_ASPECT_RATIO` at 90% screen width via
    `DialogProperties(usePlatformDefaultWidth = false)`, dismissible by tapping the image or the
    system back gesture); **long-press** opens a `DropdownMenu` anchored on the cover with a single
    "Re-fetch cover" `DropdownMenuItem` wired to the existing `onRefetchCover` callback. Every prior
    state/behavior of the removed button survives the move: the in-flight `isRefetchingCover` state
    still disables the menu item, and is *additionally* shown as a translucent overlay spinner
    directly on the cover (visible regardless of whether the menu happens to be open, unlike the old
    button's inline spinner which only showed once you'd found the button); the no-ISBN case still
    disables the action, and its explanatory text (`refetch_cover_no_isbn`, unchanged string) is now
    reached by long-pressing the cover and reading the (disabled) menu item's own text, rather than
    a separate line of body text next to a button that no longer exists; error surfacing is
    unchanged (`BookDetailUiState.Ready.errorMessage`'s existing banner above the tab content, not
    duplicated locally). The cover's whole interactive `Box` is wrapped in `DisableSelection`, same
    as the status chip and ISBN copy button, since it sits inside `BookHeader`'s `SelectionContainer`
    (Phase D backlog item) — without it, long-press-to-select would fight long-press-to-open-menu for
    the same gesture; verified the menu still opens correctly with the wrapper in place. Two new
    accessible click/long-click labels (`cover_view_action_label`, `cover_options_action_label`) and
    an in-flight label (`refetch_cover_in_progress`) added as string resources; no new user-visible
    text was hardcoded.

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

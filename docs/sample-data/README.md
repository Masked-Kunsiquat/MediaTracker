# Sample data

`library_sample.csv`, `reading_logs_sample.csv` and `episodes_sample.csv` are development fixtures
for the debug build, which starts with an empty library (debug installs under its own
`applicationId` — see ROADMAP Task 16).

## Getting them onto a device

**The fast way — no file picker:**

```bash
./gradlew :app:seedDebugDevice
```

That installs the debug app and its test APK, then runs `SampleDataSeedTest` directly against them.
`ImportDataUseCase` takes CSV *strings*, so the fixtures are read from the test APK's assets and
imported without any file picker. Re-running is safe — the import uses `SKIP`, so it tops the data
up rather than duplicating it.

The task inspects the instrumentation output rather than trusting the exit code: `am instrument`
reports success even when the test inside it fails, so a silent no-op would otherwise look exactly
like a seeded device.

**Do not use `./gradlew :app:connectedDebugAndroidTest` for this.** It runs the same test but
**uninstalls the app afterwards**, taking the imported data with it. Verified directly: the package
is present before the run and gone after. Driving the test with `am instrument` against
already-installed APKs is what skips that teardown.

**The manual way**, if you want to exercise the real user path: `adb push` the CSVs to
`/sdcard/Download/` (with `MSYS_NO_PATHCONV=1` on Git Bash, or the path gets rewritten) and import
from **Settings → Data → Import from CSV**.

It exists so UI work can be looked at against realistic content rather than an empty screen, and so
manual checks of the library — filtering, searching, multi-select, bulk delete — have something to
act on. Importing it also exercises the real CSV import path, which is a small bonus: that path is
otherwise only run when something has gone wrong.

## Why these particular rows

Every row is chosen to cover the cases that break layouts and filters, not to be realistic.

### Books

The twelve books:

- **Every reading status** (`TO_READ`, `READING`, `FINISHED`, `DNF`) so the status chips all have
  something to match, and so filtering can be seen to actually narrow.
- **Every book format**, including `AUDIOBOOK` with no page count, which is the case that tends to
  render as a blank or a stray "0 pages".
- **Both tracking modes** (`PAGES` and `PERCENT`).
- **Multiple authors on one book** (`Terry Pratchett; Neil Gaiman`) — the `"; "`-joined form the
  library search matches against as a substring.
- **Three books by the same author**, so an author search returns more than one result.
- **A quoted title containing a comma** (`Gödel, Escher, Bach…`) plus a non-ASCII character, which
  is the row that catches naive CSV splitting and encoding mistakes.
- **A row with no authors, no ISBN and no price**, since empty optional fields are where importers
  usually fall over — and a book with no ISBN is also the one the cover backfill must skip rather
  than retry.

### Films

The four movies cover every watch status (`WATCHED`, `WATCHLIST`, `WATCHING`, `ABANDONED`) so the
same filter chips that the books exercise for reading status have something to match for films too:

- **Arrival** — `WATCHED`, and the one film that carries a `watched_at` timestamp, so that column
  is exercised.
- **Dune: Part Two** — `WATCHLIST`.
- **Everything Everywhere All at Once** — `WATCHING`.
- **Solaris** — `ABANDONED`, and deliberately has no `runtime_minutes`. That's the case that
  renders as a blank rather than a stray "0 min" — the film equivalent of the audiobooks' missing
  page count above.

### Shows and episodes

The three shows cover three distinct library states, exercised through `episodes_sample.csv`:

- **Severance** — 2 declared seasons, 19 regular episodes (9 and 10), **every one watched**, plus a
  season 0 holding 3 specials of which 1 is watched. Three cases in one show:
  - It is deliberately the case issue #88 decided. Specials count toward completion, so a show with
    every regular episode watched and a special outstanding reads **In progress**, not Finished.
    Getting this wrong is invisible in any single row, which is why it is worth a fixture.
  - No episode carries a title or an air date, because nothing has backfilled them — that is
    genuinely what a quick-filled show looks like before ROADMAP Task 13 Phase D runs.
  - Its specials season is the only one here that is *partly* ticked, which renders differently
    from an all-ticked or a none-ticked season.
- **Chernobyl** — 1 season, 5 episodes, all watched, and the only episodes carrying real titles and
  original air dates. This is the finished-show case and the "already backfilled" render path. Its
  `runtime_minutes`, `overview` and `community_rating` are left blank along with everything else's
  — see the note on invented data below.
- **The Expanse** — 6 declared seasons but zero episode rows, which is a real state (a show added
  but not yet quick-filled) and exercises the "no episodes" branch that reads "Not started".

**No `cover_image_hash` or `still_image_hash` values.** A hash here would point at an image file
that does not exist on the device, so every book, film or show would show a missing cover, and
every episode a missing still. Left blank deliberately on every library row and every episode row
alike, for the same reason in both cases: the field names a file, not bytes, and this device never
had the file. For books, that also means covers can be fetched afterwards with the bulk cover/
author backfill (Task 14) — which is also the honest way to exercise that feature. Films, shows
and episodes have no such backfill yet (ROADMAP Task 13 Phase D, not shipped), so their blank
hashes stay blank until it lands.

**No invented provider data.** Every value a provider would supply is blank: `external_identifiers`
for films and shows, and `runtime_minutes`, `overview` and `community_rating` on every episode. What
is here instead is only what can be stated without looking anything up — titles, years, season and
episode structure, and Chernobyl's five episode titles with their original air dates.

This is a rule, not an omission. A fixture that carries a *wrong* TMDB id is worse than one carrying
none: the id is blank in one case and confidently points at the wrong show in the other, and only
the second kind of error survives into whatever is built on top of it. Provider ids belong here once
they can be filled in from a real lookup, which is Task 13 Phase D's job.

The happy side effect is that the fixture now models both sides of that phase honestly — Severance
as an un-backfilled show, Chernobyl as a backfilled one — rather than implying metadata arrived from
nowhere.

## Regenerating

If the CSV schema version changes, re-export a real library from the app rather than hand-editing
these files: the exporter is the source of truth for column order and formatting, and a fixture
that no longer imports is worse than none. That now means `library_sample.csv`,
`reading_logs_sample.csv` and `episodes_sample.csv` together — an export taken from one version of
the app produces all three at once, so they should be regenerated as a set rather than patched
individually.

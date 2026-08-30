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

The four shows cover the distinct states a show's progress can be in, exercised through
`episodes_sample.csv`:

- **Severance** — 9 + 10 regular episodes across two seasons, **every one watched**, plus a single
  unwatched special. This is deliberately the case issue #88 decided: specials count toward
  completion, so a show with every regular episode watched and a special outstanding reads
  **In progress**, not Finished. Getting that wrong is invisible in any single row, which is exactly
  why it is worth a fixture rather than a comment.

  No episode here carries a title or an air date, because nothing has backfilled them yet — that is
  genuinely what a quick-filled show looks like before ROADMAP Task 13 Phase D runs.
- **Chernobyl** — 1 season, 5 episodes, all watched, and the only ones carrying titles, air dates,
  runtimes, synopses and ratings. This is the finished-show case *and* the already-backfilled render
  path, so the fixture shows both sides of Phase D rather than implying metadata appeared from
  nowhere. Its synopses are also the longest text in any fixture here, which is the case a list row
  is most likely to mis-truncate.
- **Fleabag** — 2 declared seasons, but only season 1 quick-filled, and only 3 of its 6 episodes
  watched. The only *partly ticked* season in the fixture, which renders differently from an
  all-ticked or a none-ticked one and is the case an episode checklist is most likely to get wrong.
- **The Expanse** — 6 declared seasons but zero episode rows, which is a real state (a show added
  but not yet quick-filled) and exercises the "no episodes" branch that reads "Not started". It
  renders with no progress line at all rather than a stray "0 / 0 episodes".

**No `cover_image_hash` or `still_image_hash` values.** A hash here would point at an image file
that does not exist on the device, so every book, film or show would show a missing cover, and
every episode a missing still. Left blank deliberately on every library row and every episode row
alike, for the same reason in both cases: the field names a file, not bytes, and this device never
had the file. For books, that also means covers can be fetched afterwards with the bulk cover/
author backfill (Task 14) — which is also the honest way to exercise that feature. Films, shows
and episodes have no such backfill yet (ROADMAP Task 13 Phase D, not shipped), so their blank
hashes stay blank until it lands.

**Every provider value here was looked up, not remembered.** The films and shows carry real TMDB
ids, Severance and Fleabag have the season and episode counts TMDB reports, and Chernobyl's titles,
air dates, runtimes, synopses and ratings are that API's values verbatim.

This is a rule rather than a nicety, and it was learned the hard way: an earlier draft of this
fixture carried TMDB ids, episode runtimes and ratings written from memory. Two of the three ids
happened to be right. The runtimes were not, and Severance was given three specials when it has one.

A fixture carrying a *wrong* provider id is worse than one carrying none. A blank is a state the
importer already handles and tests; a wrong id is confidently wrong, gets fetched, and survives into
whatever is built on top of it. So the rule is: look it up, or leave it blank — never split the
difference by guessing. Anything that cannot be verified stays empty, which is a legitimate state
for every one of these columns.

## Regenerating

If the CSV schema version changes, re-export a real library from the app rather than hand-editing
these files: the exporter is the source of truth for column order and formatting, and a fixture
that no longer imports is worse than none. That now means `library_sample.csv`,
`reading_logs_sample.csv` and `episodes_sample.csv` together — an export taken from one version of
the app produces all three at once, so they should be regenerated as a set rather than patched
individually.

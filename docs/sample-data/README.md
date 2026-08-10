# Sample data

`library_sample.csv` and `reading_logs_sample.csv` are development fixtures for the debug build,
which starts with an empty library (debug installs under its own `applicationId` — see ROADMAP
Task 16).

## Getting them onto a device

**The fast way — no file picker:**

```bash
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.github.maskedkunisquat.mediatracker.SampleDataSeedTest   com.github.maskedkunisquat.mediatracker.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`ImportDataUseCase` takes CSV *strings*, so `SampleDataSeedTest` reads these fixtures from the test
APK's assets and imports them directly. Re-running is safe — the import uses `SKIP`, so it tops the
data up rather than duplicating it.

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

The eight books are chosen to cover the cases that break layouts and filters, not to be realistic:

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

**No `cover_image_hash` values.** A hash here would point at an image file that does not exist on
the device, so every book would show a missing cover. Left blank deliberately so covers can be
fetched with the bulk backfill instead — which is also the honest way to exercise that feature.

## Regenerating

If the CSV schema version changes, re-export a real library from the app rather than hand-editing
this file: the exporter is the source of truth for column order and formatting, and a fixture that
no longer imports is worse than none.

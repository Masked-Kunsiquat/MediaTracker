# #208: device-independent screen tests move to the Robolectric lane

**Issue:** [#208](https://github.com/Masked-Kunsiquat/MediaTracker/issues/208). **Graphban:** MT-46.
**Branch:** `test/208-robolectric-migration`, from `main` at `a16903b`.

## Why

**The device run is slow.** `connectedDebugAndroidTest` takes about 5 minutes for 206 tests on the
SM-G975U1: ~3.4 minutes of tests, plus ~1.5 minutes of build, install and uninstall, then ~1.3
minutes of `seedDebugDevice`. It runs before every PR that touches `app/src/`.

**And it gates nothing.** CI runs only the host lanes, so these tests protect only the PRs where
someone remembers to run them.

**Most of them don't need a device.** The bulk of the suite drives *stateless* composables with
fabricated state and fake callbacks, which is exactly what `app/src/test/` already runs under
Robolectric (the occlusion tests, the goldens, `DecimalFieldLocaleTest`).

**Decision (owner, 2026-10-15): move, don't duplicate.** A moved test leaves `androidTest`. Running
the same source in both lanes would keep the device run as slow as it is today.

## What stays on the device

| Class | Why |
| --- | --- |
| `SettingsNavigationTest` | Starts the real `MainActivity`: the only proof a route hands a screen a real callback. |
| `SettingsRouteSafChainsTest` | The system document picker (SAF). |
| `SampleDataSeedTest` | Seeds real data through the app's import path. |
| `DebugApplicationIdTest` | Asserts the installed application id. |
| `LoggingPipelineTest` | The real `MediaTrackerApplication` logging pipeline. Robolectric's lane deliberately uses a bare `Application` (see `robolectric.properties`). |

Plus any individual test that fails under Robolectric for a lane-specific reason; see "The rule".

## What moves (candidates, ~170 tests)

`TVShowDetailScreenTest` (42), `BookDetailScreenTest` (27), `MovieDetailScreenTest` (22),
`AddBookScreenTest` (14), `LibrarySelectionTest` (11), `EditMovieScreenTest` (11),
`ChangelogScreenTest` (8), `LogViewerScreenTest` (8), `AddMovieScreenTest` (7),
`EditTVShowScreenTest` (7), `EditBookScreenTest` (6), `AddTVShowScreenTest` (5),
`UpNextScreenTest` (3), and the three single-test date-picker classes
(`MarkWatchedDialogPickerTest`, `EpisodeSheetEditDateTest`, `MovieHistoryEditDateTest`), which are
attempted last.

## The rule: empirical, one class per commit

For each class, biggest first:

1. `git mv` it from `app/src/androidTest/java/...` to `app/src/test/java/...`, in the same package.
2. Add `@RunWith(RobolectricTestRunner::class)`. Most of these classes have no `@RunWith` today, so
   the device's `AndroidJUnitRunner` applies by default. Add `@GraphicsMode(NATIVE)` only where a test
   needs real drawing, which none of these should.
3. Run the class with `./gradlew :app:testDebugUnitTest --tests '<fqcn>'`.
4. A test that fails **for a lane-specific reason** goes back to `androidTest`, in a class with the
   same name holding only the tests that must stay, and a one-line comment per test saying why.
   Lane-specific reasons are a known Robolectric limit:
   - a text field inside a dialog (AGENTS.md §7: `AppNotIdleException`, or the dialog never composes);
   - a date picker or other component that never goes idle;
   - real-time waits (`waitUntil` with a wall-clock bound that Robolectric's paused clock never
     reaches);
   - real fonts or measurement that an assertion depends on.

   A test that fails because it was **already wrong**, i.e. the device lane was masking a real bug,
   is a finding. It gets reported and fixed in its own commit, never silently sent back to the
   device.
5. **Never weaken an assertion to make it pass under Robolectric.** If an assertion can only hold on
   a device, the test stays on the device.

**Invariant, checked every commit:** the device suite's test count drops by exactly as much as the
Robolectric lane's rises. Counted from the source (`@Test` occurrences per lane) and confirmed by the
host lane's result XML, so no test is lost or duplicated in the move.

## Feasibility first

`TVShowDetailScreenTest` (42 tests, 41 s on the device) goes first. It is the largest, has the most
dialogs (14 dialog references), and already carries a note about a dialog-typing test that had to
stay on the device (#83). If most of it moves cleanly, the rest is mechanical. If it doesn't, the
plan stops and reports before touching the rest.

## Verification

- **Each commit:** the moved class passes under `:app:testDebugUnitTest`, the per-lane counts match
  the invariant, and the full host gate passes.
- **End of branch:** one full `connectedDebugAndroidTest` run, with the remaining classes passing,
  then `seedDebugDevice`. Before and after timings go in the PR (device test time, wall time, and the
  host lane's added time).
- **CI:** the moved tests now run in `Unit tests and debug build` on every PR. That is half the
  point.

## Docs (AGENTS.md §7, same branch)

- "Choosing where a UI test lives": **a screen's behaviour** now defaults to the Robolectric lane in
  `app/src/test/`. `androidTest` is for the five categories above plus documented per-test
  exceptions.
- The device-run rule stays. It now guards a smaller suite: route wiring, the file picker, seeding,
  app identity, the logging pipeline, and the lane exceptions.
- The "Compose screens are covered by instrumented tests" bullet is updated to match.

## Out of scope

- Any change to the tests' assertions beyond what moving them requires.
- New tests.
- Shared fixtures (the classes have none).
- Splitting `TVShowDetailScreen.kt` (#204).
- Animation settings in `testOptions`.

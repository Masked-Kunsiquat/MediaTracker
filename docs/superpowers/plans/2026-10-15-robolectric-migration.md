# #208: Device-independent screen tests move to Robolectric (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every screen-test class that doesn't need a device from `app/src/androidTest/` to `app/src/test/` (the Robolectric lane, which runs in CI), losing no test, so the device run shrinks.

**Architecture:** This is a mechanical move, done class by class. Each class keeps its package, gains `@RunWith(RobolectricTestRunner::class)`, and is run on its own. Tests that fail for a known Robolectric limit return to a slimmer `androidTest` class of the same name, each with a one-line reason.

**Tech Stack:** Robolectric 4.15.1 (SDK 35, see `app/src/test/resources/robolectric.properties`), Compose UI test (`createComposeRule`), JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-15-robolectric-migration-design.md`

## Global Constraints

- **Baseline:** `androidTest` has 206 `@Test`, `test` has 125, total **331**. After every commit the total is still 331. Check with `bash $CLAUDE_JOB_DIR/tmp/lane_counts.sh`.
- **Never weaken an assertion.** A test that can only pass on a device stays on the device. A test that fails because it was already wrong is a finding: report it and fix it in its own commit.
- **Allowed reasons to stay on the device** (one-line comment each): a text field inside a dialog; a picker or other component that never goes idle; a wall-clock `waitUntil`; real fonts or measurement.
- **The five device-only classes are never moved:** `SettingsNavigationTest`, `SettingsRouteSafChainsTest`, `SampleDataSeedTest`, `DebugApplicationIdTest` and `LoggingPipelineTest`.
- **Mechanics:** use `git mv`, so history follows the file. Use package-qualified `--tests` filters. Run `./gradlew ktlintFormat` before each commit.
- **Graphban:** add evidence to MT-46 after each task (moved, stayed, counts).
- **Before push:** `./gradlew ktlintFormat`, then `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`. `app/src/` changes, so a full `connectedDebugAndroidTest` follows, then `seedDebugDevice`.

## Review Focus

1. **No lost or duplicated test:** the per-lane counts sum to 331, and no test name appears in both lanes.
2. **No weakened assertion:** the diff of each moved file is the move plus `@RunWith` and its imports, nothing more, unless a ledgered reason says otherwise.
3. **Robolectric false greens:** a test that passes on the host only because nothing renders, such as a node existing but never laid out. Spot-check that moved tests still fail when their screen is broken; one mutation per task.
4. **Locale or global-state leakage** between moved tests and the goldens in one JVM.
5. **The host lane's time increase** stays reasonable. Measured and reported.

---

## The move procedure (each task applies it to its classes)

For a class `X` at `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/<pkg>/X.kt`:

1. `git mv` it to `app/src/test/java/com/github/maskedkunisquat/mediatracker/<pkg>/X.kt`.
2. If it has no `@RunWith`, add `@RunWith(RobolectricTestRunner::class)` above the class, plus imports `org.junit.runner.RunWith` and `org.robolectric.RobolectricTestRunner`. If it has `@RunWith(AndroidJUnit4::class)`, replace it, because `androidx.test.ext:junit` is not on the host test classpath.
3. Run `./gradlew :app:testDebugUnitTest --tests 'com.github.maskedkunisquat.mediatracker.<pkg>.X'`.
4. **All pass:** commit `Robolectric: move X (n tests) (#208)`.
5. **Some fail:** for each failure, read the message.
   - If it's an allowed reason, create `app/src/androidTest/.../<pkg>/X.kt` again holding only those tests, each with `// Device-only: <reason>`, and delete them from the moved copy.
   - If it isn't, stop and diagnose (superpowers:systematic-debugging), and ledger the result.
6. Check the counts with `lane_counts.sh`: the total is 331. Commit.

---

### Task 1: Feasibility, `TVShowDetailScreenTest` (42)

- [ ] Apply the procedure.
- [ ] **Gate decision:** if fewer than ~75% of its tests move, stop and report to the owner before Task 2. That would be more than ~10 staying for lane reasons.
- [ ] **Mutation** (Review Focus 3): pick one moved test asserting a visible control, such as a status chip or a button. Temporarily break the screen code it covers, for example remove the `Modifier.testTag` or the click handler it uses. The test must fail on the host. Restore it.
- [ ] Commit, then add MT-46 evidence: moved/stayed counts, the mutation result, and the host time for the class (from its result XML).

### Task 2: `BookDetailScreenTest` (27), `MovieDetailScreenTest` (22)

- [ ] Apply the procedure to each, one commit per class.

### Task 3: The add/edit screens

`AddBookScreenTest` (14), `EditMovieScreenTest` (11), `AddMovieScreenTest` (7), `EditTVShowScreenTest` (7), `EditBookScreenTest` (6), `AddTVShowScreenTest` (5).

- [ ] Apply the procedure, one commit per class. `AddBookScreenTest` types into fields (3 text-input calls); check whether any are inside a dialog. `EditBookScreenTest` mentions seeding, so read it first: if it seeds through Room, it stays as device-only (no Room in `:app` host tests).

### Task 4: The rest of the screens

`LibrarySelectionTest` (11), `ChangelogScreenTest` (8), `LogViewerScreenTest` (8), `UpNextScreenTest` (3).

- [ ] Apply the procedure, one commit per class.

### Task 5: The date-picker classes (attempted last)

`MarkWatchedDialogPickerTest`, `EpisodeSheetEditDateTest`, `MovieHistoryEditDateTest` (1 test each).

- [ ] Apply the procedure. These are the likeliest to stay (pickers, sheets). Staying is an acceptable outcome; ledger the reason.

### Task 6: Docs, device run, PR

- [ ] **AGENTS.md §7:**
  - "Choosing where a UI test lives": a screen's behaviour defaults to `app/src/test/` (Robolectric).
  - `androidTest` holds the five device-only categories plus documented per-test exceptions.
  - Update the "Compose screens are covered by instrumented tests" bullet to match.
- [ ] **CHANGELOG** `### Internal`: one entry with the numbers.
- [ ] **Full gate.** Then a full `connectedDebugAndroidTest`, recording the device test count, test time and wall time. Then `seedDebugDevice`.
- [ ] **Fresh Opus review** with Review Focus. **CodeRabbit** (`--committed --base main -c AGENTS.md`).
- [ ] **PR** (`Closes #208`) with a before/after table:

  | | Before | After |
  | --- | --- | --- |
  | Device tests | 206 | |
  | Device time | 4m58s wall / 205 s tests | |
  | Host lane | 125 tests | |

  Then move MT-46 to `review` with the PR URL.
- [ ] **Outcomes table:** the owner's local outcomes folder (`graphban-outcomes/208-robolectric-migration.md`).

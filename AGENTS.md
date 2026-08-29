# AGENTS.md — Local-First Personal Media Hub

This file serves as the strict architectural and coding guideline for AI agents collaborating on this project. All code suggestions, refactoring, and feature additions MUST adhere to the standards outlined below.

**Where planning lives.** `ROADMAP.md` holds *why and what*; the [project board](https://github.com/users/Masked-Kunsiquat/projects/12) holds *when* — `Priority`, `Kind` and `Blocked by` per open issue. Where the two disagree about what to pick up next, the board wins: ordering churns and prose is a poor medium for it. Do not add or maintain "suggested sequencing" tables inside issue bodies now that the board exists.

**Keeping this file true.** `CHANGELOG.md` and `ROADMAP.md` each have an update ritual (§8's changelog discipline; a ROADMAP edit per scheduling decision), and both have stayed accurate because of it. This file had none, and drifted for eight releases — it pointed at an `androidApp/` module that does not exist, mandated a verification command that skips the entire data layer, and recorded one frozen schema version out of five. Documentation that instructs an agent is more dangerous when stale than documentation that merely describes, because the agent obeys it.

So: **update this file in the same commit as the change that invalidates it.** Concretely, that means a new top-level package or module (§6), a change to how tests are located or run (§7), a schema version bump (§8's ledger), a new external API or storage protocol (§4), or an approved new dependency (§5). If you find something here that contradicts the repo, fix it in that commit rather than working around it — a wrong line here misleads every future agent, not just you.

---

## 1. Project Overview & Philosophy

* **Type:** Local-first, privacy-focused media tracking application (Books initially, expanding to Movies & TV Shows).
* **Architecture:** Offline-first, single local SQLite database, zero required external cloud sync or accounts.
* **Tech Stack:** Kotlin Multiplatform (KMP), Jetpack Compose (Android UI), Room KMP (Database), Ktor Client (Networking), Coroutines & Flow (Async/Timers).
* **Core Rule:** User data safety, low resource utilization, and deterministic local storage (content hashing, clean SQL schema) override development shortcuts.

---

## 2. Tech Stack Reference

| Layer | Technology / Library | Guidelines |
| :--- | :--- | :--- |
| **Language** | Kotlin (KMP) | Core business logic MUST reside in the `shared` module. |
| **UI Layer** | Jetpack Compose | Declarative UI, state hoisting, zero legacy XML views. |
| **Database** | Room KMP (SQLite) | Use type-safe DAOs, Flow-based reactive queries, and strict migrations. |
| **Networking** | Ktor Client | Use `kotlinx.serialization` for parsing. Never hardcode API keys. |
| **Concurrency** | Kotlin Coroutines & Flow | Use `StateFlow` and `SharedFlow` for UI state. Avoid raw callbacks. |
| **Asset Storage** | Content-Addressed Local Disk | Compute SHA-256 of raw image bytes for local cover storage. |

---

## 3. Data Model Guidelines

1. **Primary Keys:** ALWAYS use generated `UUID` strings (e.g., `java.util.UUID` or KMP-equivalent string UUIDs) for primary keys. Titles MUST NOT be used as unique identifiers.
2. **Polymorphic Media Schema:**
    * Universal metadata lives in `MediaItems` (id, type, title, release_year, purchase_price, created_at).
    * Domain-specific metadata lives in child tables (`BookDetails`, `MovieDetails`, `TVDetails`) linked by `media_id` FK.
3. **External Identifiers:** Map external API keys (`ISBN`, `TMDB`, `TVDB`) in an `ExternalIdentifiers` table.
4. **Sessions vs. Items:** Keep activity history (`ReadingSessions`, `WatchLogs`) decoupled from `MediaItems` to support re-reading, re-watching, and DNF states cleanly.
5. **Progress Standard:** Store reading session bounds using normalized numeric types to support physical page numbers (`Int`) and e-reader percentages (`Float`).

---

## 4. API & Image Handling Standards

* **Books API:** Primary: Open Library API. Secondary Fallback: Google Books API.
* **Movies & TV API:** Primary: TMDB API (The Movie Database).
* **Works vs. editions — this schema is edition-shaped, and must stay that way.** Open Library
  models a [**work**](https://openlibrary.org/about/work_edition) (the abstract book) separately
  from an **edition** (one printing). An ISBN identifies an *edition*, and every column this app
  stores about a book — `releaseYear`, `totalPages`, `format`, `isbn`, `coverImageHash` — is an
  edition property, because the user owns a specific printing. So **work-level fields must never be
  written into those columns**:
  * `first_publish_year` ✗ → `releaseYear`. The Hobbit's work says 1937; the Mariner printing on
    the shelf says 2012. Mixing them makes one column mean two different things.
  * `number_of_pages_median` ✗ → `totalPages`. It is a median across every edition, so it is wrong
    *by construction* — and re-creates exactly the wrongness Task 6 Phase A's edit screen was built
    to correct.
  * `cover_i` ✗ → the stored cover. It is the work's representative art, not necessarily this
    printing's.
  * **`authors` ✓ is the sole exception**, because authorship genuinely does not vary by printing.
    Open Library hangs it off the work, so `OpenLibraryClient` reads the edition first and falls
    back to the work — many edition records omit `authors` entirely.
* **Capture the work key, don't build a work table.** `AddBookByIsbnUseCase` records the work key as
  `IdentifierProvider.OPEN_LIBRARY_WORK` alongside the edition key. Nothing reads it yet; it is
  recorded because it costs one row now and a full rate-limited re-crawl later, and because
  re-reads across printings (Task 10), genre tagging (Task 12), author navigation and import dedup
  all need it. Same reasoning as the denormalized `authors` column: capture first, normalize only
  when a feature actually demands it.
* **Every outbound request is identified.** `createHttpClient` installs Ktor's `UserAgent` plugin
  with the `USER_AGENT` constant in `core/network/HttpClientFactory.kt`. This is not cosmetic:
  [Open Library's API guidelines](https://openlibrary.org/developers/api) rate-limit **unidentified
  traffic at 1 request/second and identified traffic at 3/second**, so dropping the header
  third-times the budget for every ISBN lookup, cover probe and bulk-backfill crawl at once. The
  contact in it is the **repository URL, never a personal email** — a `User-Agent` is broadcast to
  and logged by every host the app contacts. Their guidelines also say to *cache responses whenever
  possible* and not to *make hundreds of single-book requests*, which is why rate limiting and
  caching live in the client layer rather than being left to callers.
* **Cover/Poster Storage Protocol:**
    1. Download image bytes via Ktor.
    2. Compute SHA-256 hash of the raw `ByteArray`.
    3. Save to app storage as `<hash>.jpg`.
    4. Store relative path or hash string in database (`cover_local_hash`).
    5. If the file `<hash>.jpg` already exists, skip file writing (automatic deduplication).

---

## 5. Coding Standards & Conventions

* **Immutability:** Prefer `val` over `var`. Use Kotlin data classes with `.copy()` for state updates.
* **State Hoisting:** Compose UI components must be stateless where possible, accepting state objects and emitting events up to the ViewModel.
* **Error Handling:** Network calls and database operations MUST be wrapped in custom `Result<T>` or sealed `Resource` classes to prevent UI crashes on offline/error states.
* **No Unnecessary Dependencies:** Do not add third-party libraries without explicit project context approval. Stick to the primary KMP toolchain.
  - **Roborazzi (`:app` test source set only, added in #102).** Approved for screenshot goldens, and chosen over Paparazzi and the Compose Preview screenshot tool for a reason that is not a preference: both render through LayoutLib, which has no real `Window` and no `WindowInsetsCompat` dispatch, so `WindowInsets.safeDrawing` resolves to zero there. They are structurally incapable of rendering the inset bug class this project keeps hitting (#95, #99). Roborazzi runs on Robolectric and therefore shares the lane above. Scoped the same way: not a `:shared` dependency. See §7.
  - **Robolectric (`:app` test source set only, added in #96).** Approved because it is the only way to give `:app` unit tests a real view hierarchy, and therefore the only way layout assertions run in CI at all — `app/src/androidTest/` needs a device and gates nothing. Deliberately scoped: it is **not** a `:shared` dependency and must not become one, because `:shared:jvmTest` already exercises the data layer against a real bundled SQLite driver, which beats a shadowed one. See §7.
* **No credentials in the repository, ever — including in tests and fixtures.** **CI enforces this:** gitleaks scans the *entire* git history — **every branch and tag, not just the one under review** — on every PR and every push to `main`, because a secret is compromised from the moment it is pushed and neither a follow-up commit nor a squash-merge unpublishes it. This repository is public, so a secret on an unmerged side branch is readable long before anyone reviews that branch. It also means a credential anywhere in the repository fails *every* PR until it is dealt with, which is deliberate. If the check ever fires, **rotate the credential first** — removing the file is not a fix, and treating it as one is how a live key ends up sitting in a public history. Only then decide whether the history needs rewriting. False positives go in `.gitleaksignore` by fingerprint, with a comment saying why the match is safe. Credential file types gitleaks cannot detect (keystores and other opaque binaries have no pattern to match) are covered by `.gitignore` instead.
* **Every GitHub Action is pinned to a full commit SHA, never a tag.** `uses:` executes someone else's code inside CI, and a tag is a label its owner can repoint at any commit — every later run would execute the new code with no diff here to show it happened. Adding a job means pinning whatever it uses, in the same shape as the rest: `owner/repo@<40-hex-sha> # vX.Y.Z`, with the version in the trailing comment so the pin stays readable. `.github/dependabot.yml` proposes updates weekly, grouped into one PR; a pin without that never picks up a security fix and nothing complains, which is why the two belong together.
  - **Resolve the SHA by dereferencing the tag, and confirm it is a commit.** Some tags are *annotated*, so the ref points at a tag object rather than a commit and pinning that value yields something that is not a commit at all. `git ls-remote --tags <url> 'refs/tags/vX' 'refs/tags/vX^{}'` — take the `^{}` line when present. Of the five actions here exactly one (`gradle/actions`) is annotated, so the naive lookup works four times out of five and fails on the fifth. A wrong SHA fails only when the workflow runs.
* **Formatting is ktlint's job, and `.editorconfig` is its only configuration.** Run `./gradlew ktlintFormat` before committing; CI runs `ktlintCheck` and fails on any violation. The line limit is **120** — the Kotlin/IntelliJ default, and what this codebase was already written to. Every rule that is off carries a comment in `.editorconfig` saying why. A rule is disabled because the codebase has a considered reason to differ from it, **never because disabling it is the fastest way to make a violation disappear.** Two specific traps, both of which have already cost a session:
  - **Do not widen `max_line_length` to silence violations.** Set it to `off` and ktlint's `function-signature` rule starts *demanding* the opposite of what it demanded before — wrapped expression bodies get collapsed onto single 150-column lines, so the "fix" reformats dozens of files in the wrong direction and undoes the previous formatting commit.
  - **Prefer narrowing a rule to switching it off.** `function-naming` is not disabled; it is scoped with `ktlint_function_naming_ignore_when_annotated_with = Composable`, so PascalCase Composables pass while ordinary functions are still held to camelCase.
* **ktlint must never be pointed at generated code.** KSP registers its output directories into the Kotlin source sets, so both modules filter ktlint in their `build.gradle.kts`. Without the filter ktlint reads `build/generated` and reports over 15,000 violations against Room's DAO and database implementations — machine output nobody can fix by editing. The filter matches on the **absolute path**; an `include("src/**")` / `exclude(...)` pattern pair does **not** work here, because those patterns resolve relative to each source-set root rather than to the project directory, so `src/**` matches nothing and silently filters out either everything or nothing. Verify a filter change by counting violations, not by assuming the pattern took.

---

## 6. Directory Structure Blueprint

Gradle modules are `:shared` and `:app` (see `settings.gradle.kts`). Directories marked *planned*
do not exist yet — do not create them speculatively; they are listed so the intended home for that
work is unambiguous when it is scheduled.

```text
shared/
 ├── src/commonMain/kotlin/com/hub/media/
 │    ├── core/
 │    │    ├── database/      <-- Room Database, DAOs, Entities, Migrations, restore recovery
 │    │    ├── network/       <-- Ktor Client & API definitions
 │    │    ├── storage/       <-- SHA-256 cover storage, disk I/O, persistent log store
 │    │    └── util/          <-- Result wrapper, id generation, Logger facility
 │    ├── features/
 │    │    ├── books/         <-- Timer, Reading Logs, ISBN Fetcher
 │    │    ├── portability/   <-- CSV export/import, .sqlite backup/restore, Goodreads import
 │    │    ├── settings/      <-- Typed access to the app_settings key-value store
 │    │    ├── stats/         <-- Analytics Queries & Aggregate Flow
 │    │    ├── movies/        <-- (planned, Task 13) Movie Logs, TMDB Client
 │    │    └── tv/            <-- (planned, Task 13) Season/Episode Progression
 │    └── ui/                 <-- Shared ViewModels & UI Contracts, AppContainer (manual DI)
 ├── src/androidMain/, src/jvmMain/   <-- expect/actual platform implementations
 └── src/commonTest/, src/jvmTest/, src/androidUnitTest/   <-- see §7 for which goes where
app/                          <-- Android Jetpack Compose Screens & Entry Point
 └── src/main/java/com/github/maskedkunisquat/mediatracker/
      ├── ui/screens/         <-- Compose screens
      ├── ui/navigation/      <-- NavHost + Route definitions
      └── ui/ViewModelFactories.kt   <-- bridges AppContainer to androidx ViewModel factories
```

---
## 7. Testing Standards & Quality Assurance

* **Frameworks:** Use `kotlin.test` for shared KMP unit tests and `kotlinx-coroutines-test` for testing Flows and Coroutines.
* **Coverage Requirements:**
  - Every Repository, UseCase, and Utility function MUST have accompanying unit tests.
  - Test edge cases: 0-page books, 0-second timers, missing API metadata fields, corrupt image byte arrays.
* **Test Location:** Default to `shared/src/commonTest/kotlin/` — tests there run on *every* target. Use `shared/src/jvmTest/kotlin/` when a test needs a JVM-only dependency (Room's `MigrationTestHelper`, real file I/O against a live database file) **or touches Room at all**. `testAppDatabase()` is declared in `jvmTest` and is deliberately not visible from `commonTest`, so a Room-backed test written in the wrong source set is a compile error rather than a test that silently never runs (#81 §3). Before that split there were 19 hand-maintained `excludeTestsMatching(...)` entries doing the same job by memory, and they had drifted: two pure-Kotlin tests were being swept up by package-wide patterns, one of them documenting itself as running on every variant while it did not.
* **Verification Command:** AI agents MUST run:

  ```bash
  ./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug
  ```

  `:app:testDebugUnitTest` is the Robolectric lane described below. It joined this gate in #96 and CI runs the same four tasks — if you add tests there without adding the task to both places, they exist without ever running, which is the shape of the problem #96 was opened about.

  **`:app:verifyRoborazziDebug` is the screenshot-golden gate (#102), named separately on purpose.** It does not run the suite a second time: it *is* `testDebugUnitTest` with Roborazzi's verify property set, so Gradle executes the tests once and compares the goldens in the same pass. Both are named because they are two different gates, and `:app:testDebugUnitTest` **on its own is assertion-only** — the golden tests' paired assertions run, but Roborazzi compares nothing, which looks identical in the log. If you run one task by hand while iterating, run the verify one.

  **Do NOT use `./gradlew test` as the verification command.** It resolves to `:app:testDebugUnitTest` + `:shared:testDebugUnitTest` and does **not** include `:shared:jvmTest` — which is the authoritative run for the entire data layer. Every Room-touching test (all DAO tests, repository tests, `MigrationTest`, backup/restore) lives in `shared/src/jvmTest/` and therefore runs *only* there — those variants execute on the host JVM against Android's stub `android.jar` with no Robolectric, where Room cannot obtain a real `Context`. Running only `./gradlew test` therefore passes while silently skipping the data layer entirely. (Robolectric now exists in `:app` — see the UI-test lanes below — but **not** in `:shared`, and adding it there would not help: `:shared:jvmTest` already runs the data layer against a real bundled SQLite driver, which is a better test than a shadowed one.) Until #81 this was enforced by an exclusion list in `shared/build.gradle.kts` rather than by where the file sits; that list is gone, and the source set is the rule.
* **Also build the app module when touching anything outside Kotlin source** — manifest entries, `res/xml/` rules, resources, Gradle config:

  ```bash
  ./gradlew :app:assembleDebug
  ```

  Unit tests never parse these. A malformed backup-rules XML or a broken manifest merge fails only here.
* **Six CI jobs are required status checks on `main`, matched by name.** `Unit tests and debug build`, `Linting`, `Secret scan`, `Changelog check`, `Room schema check` and `Compose IME insets` must all pass before a PR can merge — the `protect-main` ruleset also requires a PR and forbids force-pushing or deleting `main`, with no bypass for anyone. **Renaming a job's `name:` in `ci.yml` silently blocks every pull request**, because the required check by the old name never arrives and GitHub cannot tell "not applicable" from "not reported yet". Renaming a job therefore means updating the ruleset in the same change. For the same reason, adding a job that only runs conditionally must *not* make it a required check.
  - **`Compose IME insets` was promoted to a required check in #96**, having been advisory since it was added. It greps every screen file holding both a `Scaffold` and a text field for a `contentWindowInsets` assignment or a written exemption, and it is shallow by construction — it cannot tell *which* `Scaffold` in a file received the argument. It is required anyway, because it covers the one thing the Robolectric lane cannot: that lane only guards a screen somebody remembered to add to it, and nobody adds a test for a screen they did not think needed one. The grep fires on any new file by itself. Shallow and automatic beats deep and opt-in for that specific failure.
  - **CodeRabbit and GitGuardian are deliberately not required.** CodeRabbit reports `pass` without having reviewed anything in at least four situations (over 100 changed files, draft PRs, this repository's manual-review setting, and rate limiting), so as a gate it certifies nothing; requiring it would also tie merging to a third-party review quota. GitGuardian is left advisory because gitleaks already covers secrets in-repo more thoroughly, across the whole history and every branch.
* **Lint before pushing.** CI fails on either of these, and both are fast:

  ```bash
  ./gradlew ktlintCheck :app:lintDebug
  ```

  `ktlintCheck` is style only — see §5 for its configuration and the two traps in it. `:app:lintDebug` is Android Lint over the app module. Neither replaces the test gate above, and neither is a substitute for it: a lint-clean tree says nothing about whether the code works.
* **Compose screens are covered by instrumented tests**, in `app/src/androidTest/`:

  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```

  These need a connected device or emulator, so they **cannot** join the verification command above and must be run deliberately. Do not treat their absence from that gate as permission to skip them when changing a screen. They exist because `:app` was otherwise verified only by whether it *compiled*: two bugs shipped past that (a control wired to a no-op lambda; an effect scrolling to an unmeasured extent), and neither is reachable by a ViewModel unit test, because in both cases the ViewModel was correct.
  - Screen-level tests drive a **stateless** composable with fake callbacks — they prove a screen honours the contract it is handed.
  - `SettingsNavigationTest` starts at the real `MainActivity` and taps through, because only that can prove a **route** hands the screen a real callback rather than a stub. That is the failure that actually shipped.
  - Put behaviour that does not need a device in a ViewModel and unit-test it there instead. `ChangelogViewModel` takes its content as a lambda rather than reading `context.assets` specifically so its logic stays in `commonTest`.
* **Compose screens are also covered by a gated Robolectric lane**, in `app/src/test/`, which runs on the host JVM and therefore *is* part of the verification command above:

  ```bash
  # Assertion lane only. Use verifyRoborazziDebug instead if the lane's goldens matter to
  # what you are changing -- this task leaves Roborazzi comparing nothing.
  ./gradlew :app:testDebugUnitTest
  ```

  It exists because `app/src/androidTest/` gates nothing. 95 instrumented tests were green while the Library FAB sat underneath the keyboard (#95), and they were also absent from the run that let it merge. This lane is for assertions that must hold on every PR without a device attached.
  - **It reads geometry, which no other lane here does.** `Occlusion.kt` reports a keyboard-sized IME inset to a screen and fails any interactive node whose bounds land below the keyboard line. Semantics answer *whether* a control exists; only bounds answer *where*, and "where" was the entire bug.
  - **`app/src/test/resources/robolectric.properties` pins the SDK, the screen size and the `Application` class**, and each pin is there because the default failed. Most importantly the real `MediaTrackerApplication` opens Room from `onCreate` and dies on the missing SQLite JNI — *intermittently*, since it races the test. Read that file before adding a test here.
  - **Robolectric is not a licence to test the data layer off-device.** `:shared:jvmTest` remains authoritative for anything touching Room.
  - **Screenshot goldens share this lane's runtime** (`ui/goldens/`, added in #102). They inherit the same `robolectric.properties`, so there is one notion of "the test device" rather than two free to drift apart. Three things about them are not obvious and each was found by a recording that reported success:
    - **`RoborazziOptions.CaptureType.Screenshot()` is set explicitly.** The default renders the *semantics tree* — labelled boxes over node bounds — not pixels, which produced byte-identical files for the light, dark and large-font variants of one screen.
    - **`@GraphicsMode(GraphicsMode.Mode.NATIVE)` is required per golden class.** Robolectric's legacy pipeline returns null from `Bitmap.createBitmap`. It is set per-class so the occlusion lane keeps the cheaper pipeline it never draws in.
    - **`dynamicColor = false`.** On API 31+ the default takes the palette from the wallpaper, making every golden a picture of colours Robolectric supplies rather than the app's own.
  - **Record goldens by hand, never in CI.** `./gradlew :app:recordRoborazziDebug` writes to `app/src/test/screenshots/`; **look at what it wrote**, then commit the PNGs in a commit carrying no assertion changes. CI runs `:app:verifyRoborazziDebug`, which compares and never writes. A job that can regenerate a golden is a golden that silently agrees with whatever the code now does.
* **Choosing where a UI test lives.** Five cases, and picking the wrong one is how tests become slow, flaky, or dependent on a particular phone:
  - **A screen's layout or geometry** — the Robolectric lane in `app/src/test/` (`LibraryScreenOcclusionTest`). Anything expressible as an invariant over bounds belongs here, because here it runs on every PR. Prefer this to a screenshot test whenever the rule can be stated as an assertion: a golden only catches a regression if a human reads the diff, and a golden nobody reads is not evidence.
  - **A screen's *appearance*** — a screenshot golden in `app/src/test/java/.../ui/goldens/` (`LibraryScreenGoldenTest`). Only for what an invariant cannot state: a colour that goes wrong in dark mode, a control that keeps its bounds while its contents overflow, spacing that collapses at a large font scale. Every golden is paired with a non-visual assertion in the same test body — `captureGolden`'s `alsoAssert` has no default, so a test that asserts only its PNG does not compile — and that pairing is falsified when introduced, exactly like any other guard here. Keep the set small: roughly eight canonical surfaces, with dark mode and a large font scale on the library alone. A PR carrying ten image diffs gets reviewed; one carrying forty-five gets waved through, and a golden nobody reads is not evidence.
  - **A screen's behaviour** — drive the *stateless* composable directly with fabricated state and fake callbacks (`LibrarySelectionTest`). It touches no database, so it behaves identically on an empty device, a full one, or a fresh emulator. This should be the default and covers most cases.
  - **A route actually being wired** — start at the real `MainActivity` and tap through (`SettingsNavigationTest`). Only this can prove a route hands a screen a real callback rather than a stub, which is the failure that has shipped here more than once. Slow, so keep it to smoke tests.
  - **A flow needing real data** — seed it in `@Before` from `docs/sample-data` (`SampleDataSeedTest` is the template: read the fixture from test assets, import through `ImportDataUseCase`, which takes CSV strings and so needs no file picker).
* **Prefer a semantic matcher; reach for a `testTag` when one will not do.** A matcher asserts something a user can perceive — a label, a content description, an enabled state — so it fails when the user-visible contract breaks. A tag asserts only that the same string was written in two places, which is why it is second choice rather than the default. Tags live in `ui/TestTags.kt` as constants (never string literals, so a typo is a compile error rather than a matcher that silently finds nothing), and `testTagsAsResourceId` is enabled at the composition root in `MainActivity`, so each one also appears as `resource-id` in a `uiautomator` dump. Three cases justify one:
  - Finding the element semantically would take more than about three matchers.
  - A **device** check needs a handle that outlives a reworded label. Matching on visible text is how the changelog, log viewer and Add Book flows were verified, and it breaks the moment a string changes — which tempts guessing coordinates from a screenshot instead, and that has already produced one mis-tap into the wrong screen.
  - The node **has no perceivable identity at all.** Every scrolling container is in this position, which is why they are tagged despite not being controls: the occlusion lane reads a viewport's bounds, and before the tags existed a stranded list could only be reported as `unlabelled node #91`.

  Put the tag on the node it names, never a wrapper — the occlusion lane measures that node's rectangle, so a tag one level up produces a confidently wrong failure message. Never restructure layout to hang a tag on something; adding a `Box` to hold one changes the very measurement these tests exist to check.

  **Verify a new tag on a device, because no local test can.** Robolectric proves a tag is in the semantics tree; only a phone proves it reaches the `uiautomator` dump. Install, open the screen, and query the id — the dump also reports `[scrollable]` and `[clickable]`, which is how you confirm the tag landed on the node you meant rather than a wrapper one level up. A container tag that comes back without `[scrollable]` is on the wrong node, and the occlusion lane will then be measuring the wrong rectangle while passing.
* **A test must never assume the device already has data.** `connectedDebugAndroidTest` **uninstalls the app when it finishes**, so nothing survives between runs — seeding a device by hand and then writing tests against it produces a suite that passes only on your machine. Tests that need data seed themselves.
* **To put sample data on a device for manual poking:**

  ```bash
  ./gradlew :app:seedDebugDevice
  ```

  Installs the debug app and its test APK, then seeds the sample library. Run it **after** `connectedDebugAndroidTest`, which leaves you with no app — the point at which you most want one, to check by hand what the tests just claimed. Idempotent (the import uses `SKIP`), so re-running tops the library back up rather than duplicating it.
* **Never read `uiState.value` straight after an action in a ViewModel test.** State reaches
  `uiState` through `combine` -> `stateIn`, which needs a dispatch, so `.value` can still hold the
  previous state when the assertion runs. It usually passes on an idle developer machine and fails
  on a loaded CI runner, surfacing as a message-less `AssertionError` at a line number pointing at
  the enclosing function — a symptom that is nearly impossible to diagnose from a CI log. Four
  separate tests in this repository have failed this way. Await the state instead:
  `uiState.first { <the condition you are about to assert> }`, or drain with `runCurrent()` when
  the action is a genuine no-op and there is no new state to wait for. Reading `.value` is only
  safe for the *initial* state, before anything asynchronous has happened.
* **A test about concurrency must be able to *create* the concurrency.** The default test dispatcher runs a `launch` eagerly, so a coroutine can finish inside the call that started it — which means "call this twice before the first completes" is not something the test can guarantee. It passes locally, then fails on a runner where the timing differs, and the failure looks like a broken guard rather than a test that never exercised one. Install a `StandardTestDispatcher` for the window that must stay pending, so the work only *enqueues* until the scheduler is driven, then `runCurrent()`. Verify by removing the guard: if the test still passes, it was never testing it.
* **When editing tests by script, anchor on something unique to the target.** Test bodies in a class share their setup almost verbatim, so a patch matched on `insertBook()` / `newViewModel()` / `first { Ready }` will hit whichever test comes first — silently, and with the edit landing somewhere plausible enough to survive review. This has happened: a dispatcher swap intended for the double-tap guard test went into an unrelated one 150 lines away, the suite still passed locally, and CI kept failing on a test that had never received the fix. Anchor on a literal only the target contains, and assert the match is unique before replacing.
* **A test that cannot fail is worse than no test.** Assert a positive control alongside the negative one — that the thing you expect to be present *is* present, not merely that the forbidden thing is absent. A test asserting "no log data in the export" passes trivially if the export was empty or never ran. This has bitten this project before (PR #16).
---

## 8. Versioning & Release Standards

* **Scheme:** Semantic Versioning `0.y.z` pre-1.0. Minor bump per feature milestone, patch for fixes. `1.0.0` when the app is daily-drivable.
* **Single Source of Truth:** The app version lives ONLY in `[versions] app` in `gradle/libs.versions.toml`. `app/build.gradle.kts` reads `versionName` from it and derives `versionCode` as `major*10000 + minor*100 + patch`. NEVER hand-edit `versionCode` or duplicate the version string elsewhere.
* **Changelog Discipline:** `CHANGELOG.md` follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Every completed task/phase MUST add its user-visible changes to the `[Unreleased]` section in the same commit (or the phase commit immediately following). Agents finishing a phase without touching the changelog have not finished the phase. **CI enforces this:** Pull Requests touching `shared/` or `app/src/main/` will fail if `CHANGELOG.md` is not also modified.
* **`### Internal` is for entries the app's user should not see.** This file is copied into the app's assets at build time and rendered by the in-app "What's new" viewer, so it serves two audiences at once. Anything addressed to whoever maintains the repository — CI jobs, lint configuration, build tooling, refactors with no visible effect — goes under an `### Internal` heading, which `ChangelogParser` omits. Everything else uses the normal Keep a Changelog sections and reaches the screen. Put `### Internal` last, after `Fixed`. The test is simply *who is this sentence written for*: if it names a workflow file or a Gradle plugin, it is internal. Nothing is deleted either way — an internal entry is still in the file for anyone reading the repository, it just does not reach a phone.
* **Release Ritual — a release is its own change, never a passenger on a feature PR.**
  1. Branch from `main` as `release/vX.Y.Z`. Nothing else rides along: the branch contains the two edits below and nothing more.
  2. Move `[Unreleased]` content into a dated `## [x.y.z] - YYYY-MM-DD` section.
  3. Bump `[versions] app` (see Single Source of Truth above — that file only).
  4. Commit as `Release vX.Y.Z`, open a PR, and merge it once CI is green. The squash commit on `main` reads `Release vX.Y.Z (#N)`.
  5. `git tag vX.Y.Z` on that squash commit, push the tag, and publish a GitHub release whose notes are the changelog section from step 2.

  **Why the separate branch, and not a bump folded into the phase PR:** it keeps `main`'s history answering "what shipped in this version?" by inspection instead of by archaeology, and it keeps the release reviewable on its own — the changelog is the one artefact written for the user rather than the reviewer, and it is the last chance to catch a version bump of the wrong size. `v0.11.0` was cut the wrong way (the bump rode along inside the Task 15 Phase C PR, so `main` records `Task 15 Phase C ... (#37)` where it should say `Release v0.11.0`); the tag and release are correct, only the history's shape is wrong. Steps 1 and 4 exist because that happened.

  **The version bump is the release's job, not the feature's.** A phase PR adds to `[Unreleased]` (see Changelog Discipline above) and stops there. It must never touch `[versions] app`.
* **Room Schema Freeze Rule:** Once a release is tagged, the database schema shipped in it is FROZEN. Any later schema change requires incrementing the Room `@Database` version and providing a tested migration (`Migration` object + migration test). In-place edits of the current schema version are permitted ONLY for schema versions that have never been part of a tagged release. **CI enforces this:** existing schema JSONs in `shared/schemas/` must never be modified in a PR, and `APP_DATABASE_VERSION` in `AppDatabase.kt` must always match the highest versioned file in that directory.
* **Frozen schema ledger.** Every version below shipped in a tag and is therefore immutable. **Append a row here in the same commit that bumps `APP_DATABASE_VERSION`** — this table is the rule's only record, and a version missing from it is a version nobody can tell is frozen.

  | Schema | Froze at | Migration into it |
  | :--- | :--- | :--- |
  | v1 | `v0.1.0` | — (initial) |
  | v2 | `v0.4.0` | `MIGRATION_1_2` — nullable `ReadingSessionEntity.durationSeconds` |
  | v3 | `v0.5.0` | `MIGRATION_2_3` |
  | v4 | `v0.6.0` | `MIGRATION_3_4` — adds the `app_settings` key-value table |
  | v5 | `v0.8.0` | `MIGRATION_4_5` — nullable author column on `BookDetailsEntity` |
  | v6 | `v0.15.0` | `MIGRATION_5_6` — `movie_details`, `tv_details`, `episodes`, `watch_logs` |

  Current: `APP_DATABASE_VERSION = 6` (`shared/.../core/database/AppDatabase.kt`). Migrations live in `Migrations.kt`; each is registered through the `loggedMigration` wrapper and covered by `MigrationTest` (`jvmTest`). `v0.2.0`, `v0.3.0` and `v0.7.0` shipped no schema change.

  **Every row above is now frozen.** v6 was the one that was not, and the distinction was the rule rather than an exception to it: the freeze attaches to *shipping in a tag*, not to the version existing. It was held unreleased from PR #76 until Movies and TV actually worked, and that window was spent deliberately rather than allowed to lapse — the release-blocking question (whether TMDB needs a per-episode provider id, which would have meant a new table) was answered *no* against the API docs, and the columns Phase D will fill were added while adding them was still free: `tv_details.airingStatus`/`overview`/`firstAirDate`/`lastAirDate`, `episodes.runtimeMinutes`/`overview`/`stillImageHash`/`communityRating`, and `media_items.communityRating`. See PR #86 and ROADMAP Task 13 Phase D.

  When the next version is added, append its row **unfrozen** in the same commit that bumps `APP_DATABASE_VERSION` — a version absent from this table is a version nobody can tell is frozen, and adding it later is the step that gets forgotten — then **change "not yet frozen" to the tag in the release that ships it.** CI enforces the frozen half by comparing against the newest tag rather than against `main`, so a schema stays editable exactly as long as this table says it is.

# AGENTS.md — Local-First Personal Media Hub

This file serves as the strict architectural and coding guideline for AI agents collaborating on this project. All code suggestions, refactoring, and feature additions MUST adhere to the standards outlined below.

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
* **Test Location:** Default to `shared/src/commonTest/kotlin/` — tests there run on *every* target. Use `shared/src/jvmTest/kotlin/` only when a test genuinely needs a JVM-only dependency (e.g. Room's `MigrationTestHelper`, or real file I/O against a live database file).
* **Verification Command:** AI agents MUST run:

  ```bash
  ./gradlew :shared:jvmTest :shared:testDebugUnitTest
  ```

  **Do NOT use `./gradlew test` as the verification command.** It resolves to `:app:testDebugUnitTest` + `:shared:testDebugUnitTest` and does **not** include `:shared:jvmTest` — which is the authoritative run for the entire data layer. `shared/build.gradle.kts` excludes every Room-touching test (all DAO tests, repository tests, `MigrationTest`, backup/restore) from the `testDebugUnitTest`/`testReleaseUnitTest` variants, because those run on the host JVM against Android's stub `android.jar` with no Robolectric, where Room cannot obtain a real `Context`. Running only `./gradlew test` therefore passes while silently skipping the data layer entirely. Read that file's comment block before adding or moving a test.
* **Also build the app module when touching anything outside Kotlin source** — manifest entries, `res/xml/` rules, resources, Gradle config:

  ```bash
  ./gradlew :app:assembleDebug
  ```

  Unit tests never parse these. A malformed backup-rules XML or a broken manifest merge fails only here.
* **Compose screens are covered by instrumented tests**, in `app/src/androidTest/`:

  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```

  These need a connected device or emulator, so they **cannot** join the two-command gate above and must be run deliberately. Do not treat their absence from that gate as permission to skip them when changing a screen. They exist because `:app` was otherwise verified only by whether it *compiled*: two bugs shipped past that (a control wired to a no-op lambda; an effect scrolling to an unmeasured extent), and neither is reachable by a ViewModel unit test, because in both cases the ViewModel was correct.
  - Screen-level tests drive a **stateless** composable with fake callbacks — they prove a screen honours the contract it is handed.
  - `SettingsNavigationTest` starts at the real `MainActivity` and taps through, because only that can prove a **route** hands the screen a real callback rather than a stub. That is the failure that actually shipped.
  - Put behaviour that does not need a device in a ViewModel and unit-test it there instead. `ChangelogViewModel` takes its content as a lambda rather than reading `context.assets` specifically so its logic stays in `commonTest`.
* **Choosing where a UI test lives.** Three cases, and picking the wrong one is how tests become slow, flaky, or dependent on a particular phone:
  - **A screen's behaviour** — drive the *stateless* composable directly with fabricated state and fake callbacks (`LibrarySelectionTest`). It touches no database, so it behaves identically on an empty device, a full one, or a fresh emulator. This should be the default and covers most cases.
  - **A route actually being wired** — start at the real `MainActivity` and tap through (`SettingsNavigationTest`). Only this can prove a route hands a screen a real callback rather than a stub, which is the failure that has shipped here more than once. Slow, so keep it to smoke tests.
  - **A flow needing real data** — seed it in `@Before` from `docs/sample-data` (`SampleDataSeedTest` is the template: read the fixture from test assets, import through `ImportDataUseCase`, which takes CSV strings and so needs no file picker).
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

  Current: `APP_DATABASE_VERSION = 5` (`shared/.../core/database/AppDatabase.kt`). Migrations live in `Migrations.kt`; each is registered through the `loggedMigration` wrapper and covered by `MigrationTest` (`jvmTest`). `v0.2.0`, `v0.3.0` and `v0.7.0` shipped no schema change.

# Add screens: review form before saving — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every add flow (book, film, show; search or manual) ends on a review form styled like
its edit screen. Nothing is saved until Save.

**Architecture:**

- **Shared form state.** Each type gets one form-state type in `:shared` (`FilmFormState`,
  `ShowFormState`, `BookFormValues`), with its transitions and parsing written once.
- **Shared form composable.** Each type gets one form composable in `app/.../ui/components/`.
  Both its add host and its edit host render it.
- **Shared scaffold.** One `EditFormScaffold` holds the inset, snackbar and pinned-bar layout that
  every form screen shares.
- **Two-step adds.** Each add path becomes lookup → draft → review → save:
  - TMDB drafts are the existing `TmdbMovieMapping` / `TmdbShowMapping`;
  - a book draft is a new `BookDraft` from a split `AddBookByIsbnUseCase`.

**Tech Stack:** Kotlin Multiplatform, Jetpack Compose (Material 3), Room KMP, Ktor, Robolectric and
Roborazzi (`:app` tests), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-23-add-screens-review-design.md`. Read it first; this plan
argues from it.

## Global Constraints

- **AGENTS.md governs.** In particular, §5 (`catchingNonCancellation` / `guardResource`, never a
  bare `catch`) and §7 (test locations, the gate, device lane).
- **Gate:**
  `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`
- **TDD:** each new test is seen failing (or falsified by sabotage) before it is trusted.
- **Test lanes:**
  - Room-touching tests go in `shared/src/jvmTest`;
  - view-model tests await `uiState.first { … }`, never `.value` after an action;
  - stateless screens are tested in `app/src/test` (Robolectric).
- **No schema change; no new dependency.**
- **Nothing written before Save:**
  - no `media_items` row;
  - no cover file in `LocalImageStorageManager`;
  - no `fetchPosterUseCase.enqueue` call.
- **Carried through to the save:**
  - **TMDB:** `externalIdentifiers`, `communityRating`, `synopsis`/`overview`, `airingStatus`,
    `firstAirDate`, `lastAirDate`, `seasons` and `posterPath`;
  - **Books:** the normalized ISBN, the provider id, the work key, `authors` and the cover bytes.
- **Edited values win over the draft:** a cleared field saves `null`.
- **Copy:**
  - "film", not "movie", in user-facing strings on these screens; resource *names* are unchanged;
  - "Add book", not "Add Book";
  - American English;
  - `&amp;` for `&` in XML.
- **Top bar:** left-aligned `TopAppBar` on the add screens, the review forms and Edit book.
- **Navigation after Save is unchanged:** books go to the library; films and shows go to their
  detail screen.
- **Goldens:** recorded by hand (`:app:recordRoborazziDebug`), looked at, and committed in a
  commit with no assertion changes.
- **Commits:**
  - signed (1Password), never bypassed;
  - never stage `gradle.properties`;
  - trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
    `Claude-Session: https://claude.ai/code/session_01X3dhMjqKB1M3voUhWUrqxJ`.
- **Every PR:**
  - an `[Unreleased]` CHANGELOG entry;
  - the device suite (`connectedDebugAndroidTest`) run with a device attached, with its tally in
    the PR;
  - the PR template (`.github/pull_request_template.md`) read before `gh pr create`.
- **PR boundaries:**
  - **PR 1:** Tasks 1–5 (shared scaffold and film), on this branch (`feat/214-add-review-forms`),
    which already holds the spec;
  - **PR 2:** Tasks 6–7 (show);
  - **PR 3:** Tasks 8–11 (book and finishing). Each from `main` after the previous PR merges.
  - Never merge without the owner's go-ahead.

## Review Focus

1. **Back or Cancel while a save is in flight.** The write completes, the user still navigates to
   the new item, and nothing is duplicated. `cancelReview()` is ignored while `isSaving` (Tasks 5,
   7 and 10).
2. **A prefilled value that fails validation.** For example, Open Library's `pageCount = 0`, or a
   TMDB year outside `MIN_RELEASE_YEAR..MAX_RELEASE_YEAR`. The form shows that field's error and
   disables Save, rather than the save failing with a snackbar (Tasks 5 and 10).
3. **Rotating the phone on any review form** keeps the draft and every edit (Tasks 5 and 8).
4. **Keyboard up on a review form.** Save stays above the keyboard; navigation-bar insets are
   checked too (occlusion tests in Tasks 4, 5, 7 and 10).
5. **A second tap while the first lookup is running** (another search result, or the same one) is
   ignored. Only one review opens (Tasks 5 and 10).

---

### Task 1: `EditFormScaffold` — one layout for every form screen

**Files:**
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/EditForm.kt`
- Modify: `app/.../ui/screens/EditMovieScreen.kt`, `EditTVShowScreen.kt`, `EditBookScreen.kt`
- Test (existing guards): `app/src/test/.../ui/insets/Edit*ScreenOcclusionTest.kt`, `ui/goldens/Edit*ScreenGoldenTest.kt`, `ui/screens/Edit*ScreenTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Composable fun EditFormScaffold(
      title: String,
      onNavigateBack: () -> Unit,
      formTag: String,
      isSaving: Boolean,
      canSave: Boolean,
      onCancel: () -> Unit,
      onSave: () -> Unit,
      cancelTag: String,
      saveTag: String,
      snackbarHostState: SnackbarHostState? = null,
      aboveBar: @Composable () -> Unit = {},
      centeredTitle: Boolean = false,   // Edit book only, until Task 11 deletes it
      content: @Composable ColumnScope.() -> Unit,
  )
  ```
  The layout comes from `EditMovieScreen` as it stands:
  - `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` with a left-aligned `TopAppBar`
    and a back arrow;
  - an outer `Column` with `imePadding().consumeWindowInsets(innerPadding)`;
  - a `Box(weight(1f))` holding the scrolling column (top padding from `innerPadding`, horizontal
    bar padding, 16.dp, `testTag(formTag)`, 20.dp spacing), with the snackbar aligned to its
    bottom;
  - then `aboveBar()`, then `EditBottomBar`.
- **Loading and load-failed states:** a host that isn't ready yet renders these itself. The
  scaffold is only for a form.

- [ ] **Step 1: Write the occlusion guard first.** Run `./gradlew :app:verifyRoborazziDebug` on
  the untouched branch and confirm it is green. That run is the baseline every later refactor is
  checked against.
- [ ] **Step 2: Implement `EditFormScaffold`** in `EditForm.kt`. Move the #174 comments explaining
  the snackbar placement and the bar padding into it, since it now owns that layout.
- [ ] **Step 3: Port the three edit screens onto it.**
  - Edit book keeps `CenterAlignedTopAppBar` for now, through a `centeredTitle: Boolean = false`
    parameter that Task 11 removes. Its golden must not change in this task.
  - Edit book's error `Text` moves into `aboveBar`.
- [ ] **Step 4: Run the gate.** Expected: PASS, with no golden diffs. Then sabotage: drop
  `imePadding()` from the scaffold. `EditMovieScreenOcclusionTest` must FAIL. Restore it.
- [ ] **Step 5: Commit** — `Edit screens: one EditFormScaffold (#214)`

### Task 2: `FilmFormState` — the film form's state and rules, written once

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/ui/FilmFormState.kt`
- Create: `shared/src/commonTest/kotlin/com/hub/media/ui/FilmFormStateTest.kt`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/EditMovieViewModel.kt`, `shared/src/jvmTest/kotlin/com/hub/media/ui/EditMovieViewModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public data class FilmFormState(
      val title: String = "",
      val releaseYear: String = "",
      val runtimeMinutes: String = "",
      val purchasePrice: String = "",
      val status: WatchStatus = WatchStatus.WATCHLIST,
      val initialStatus: WatchStatus = status,
      val pendingWatched: Boolean = false,
      val watchedWhen: WatchedWhen? = null,
  ) {
      public fun choosingStatus(value: WatchStatus): FilmFormState   // EditMovieViewModel.onStatusChange's rule
      public fun confirmingWatched(watchedWhen: WatchedWhen): FilmFormState
      public fun dismissingWatched(): FilmFormState
      public fun parsed(): FilmFormParse
  }
  public sealed interface FilmFormParse {
      public data class Valid(val title: String, val releaseYear: Int?, val runtimeMinutes: Int?,
          val purchasePrice: Double?, val status: WatchStatus, val watchedWhen: WatchedWhen?) : FilmFormParse
      public data class Unreadable(val field: String) : FilmFormParse   // "release year" | "runtime" | "purchase price"
  }
  public fun TmdbMovieMapping.toFilmForm(): FilmFormState
  ```
- **`toFilmForm()`** fills title, year and runtime as strings. The price is blank, and the status
  is `WATCHLIST`.
- **`Valid.title`** is trimmed.
- **`EditMovieUiState.Editing`** becomes `Editing(form: FilmFormState, savedTitle, savedYear,
  coverImageHash, isSaving, saveError, saved)`. Its `on*Change` methods delegate to
  `form.copy(...)` and the methods above.

- [ ] **Step 1: Write the failing tests** in `FilmFormStateTest`:
  ```kotlin
  @Test fun choosingWatched_fromWatchlist_asksWhenAndKeepsStatus() {
      val s = FilmFormState().choosingStatus(WatchStatus.WATCHED)
      assertTrue(s.pendingWatched); assertEquals(WatchStatus.WATCHLIST, s.status)
  }
  @Test fun choosingWatched_whenItOpenedWatched_appliesAtOnce() { /* initialStatus = WATCHED -> status WATCHED, pendingWatched false */ }
  @Test fun choosingAnotherStatus_clearsWatchedWhen() { /* watchedWhen = Now, choose WATCHING -> watchedWhen null */ }
  @Test fun parsed_blankNumbers_areNull() { /* "" -> Valid(releaseYear = null, runtimeMinutes = null, purchasePrice = null) */ }
  @Test fun parsed_unreadableRuntime_namesTheField() { assertEquals(FilmFormParse.Unreadable("runtime"), FilmFormState(title = "x", runtimeMinutes = "1.2.3").parsed()) }
  @Test fun toFilmForm_fillsFromTmdbAndLeavesPriceBlank() { /* TmdbMovieMapping("Heat", 1995, 170) -> title "Heat", releaseYear "1995", runtimeMinutes "170", purchasePrice "" */ }
  ```
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests "*FilmFormStateTest"`. Expected: FAIL to
  compile (`FilmFormState` undefined).
- [ ] **Step 3: Implement `FilmFormState.kt`.** `parsed()` uses `parseOptionalNumber` and does no
  range checks: `MovieMetadataValidation` stays authoritative in the repository.
- [ ] **Step 4: Move `EditMovieViewModel` onto `FilmFormState`.** Update `EditMovieViewModelTest`'s
  state reads (`form.title`, etc.) without changing what any test asserts.
- [ ] **Step 5: Run** `./gradlew :shared:jvmTest :shared:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 6: Commit** — `Film form state written once (#214)`

### Task 3: `FilmFormContent` — the film form composable, shared

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/FilmForm.kt`
- Modify: `app/.../ui/screens/EditMovieScreen.kt`

**Interfaces:**
- Consumes: `FilmFormState` (Task 2), `EditFormScaffold` (Task 1).
- Produces:
  ```kotlin
  @Composable fun ColumnScope.FilmFormContent(
      form: FilmFormState,
      header: @Composable () -> Unit,
      enabled: Boolean,
      onTitleChange: (String) -> Unit,
      onReleaseYearChange: (String) -> Unit,
      onRuntimeChange: (String) -> Unit,
      onPurchasePriceChange: (String) -> Unit,
      onStatusChange: (WatchStatus) -> Unit,
  )
  internal fun FilmFormState.canSave(): Boolean   // title not blank && parsed() is Valid
  ```
  It emits the header and the Details, Status and Purchase cards, exactly as `EditMovieScreen`
  draws them now. That includes the filters, supporting-text errors and `StatusDropdownChip`.

- [ ] **Step 1: Move** `EditMovieScreen`'s card bodies into `FilmFormContent`, and its `canSave`
  expression into `FilmFormState.canSave()`.
- [ ] **Step 2: Rewrite `EditMovieScreen`** as `EditFormScaffold { FilmFormContent(...) }`, keeping
  its existing tags.
- [ ] **Step 3: Run the gate.** Expected: PASS, with `EditMovieScreenGoldenTest` unchanged. Then
  sabotage: drop the runtime field's `isError`. `EditMovieScreenTest`'s invalid-runtime test must
  FAIL. Restore it.
- [ ] **Step 4: Commit** — `Edit film renders the shared film form (#214)`

### Task 4: Manual Add film uses the shared form

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/AddMovieViewModel.kt`, `shared/src/jvmTest/.../ui/AddMovieViewModelTest.kt`
- Modify: `app/.../ui/screens/AddMovieScreen.kt`, `app/.../ui/TestTags.kt` (add `AddMovie.CANCEL_BUTTON = "addMovie:cancel"`)
- Modify: `app/src/test/.../ui/screens/AddMovieScreenTest.kt`, `app/src/test/.../ui/insets/AddMovieScreenOcclusionTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (`add_movie_title` → "Add film"; delete `add_movie_save` and `add_movie_status_label` once unused)

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `AddMovieViewModel` holds `val form: StateFlow<FilmFormState>`;
  - it exposes `onTitleChange`, `onReleaseYearChange`, `onRuntimeChange`, `onPurchasePriceChange`,
    `onStatusChange`, `confirmWatched(WatchedWhen)` and `dismissWatched()`;
  - `save()` takes no arguments, and the `Saving`/`Saved` double-tap guard stays.
  - `AddMovieScreen(uiState, form, callbacks…, onSave, onErrorShown, onNavigateBack)`.
  - The route shows `FilmWatchedDialog` from `form.pendingWatched`, as `EditMovieScreenRoute`
    does.
- **What this costs:** typed text now survives rotation through the view model but not process
  death, the same as Add show and Edit film. Say so in the KDoc that currently justifies
  `rememberSaveable`.

- [ ] **Step 1: Write the failing tests:**
  - `AddMovieViewModelTest.save_usesTheFormsValues`: set the fields through `on*Change`, call
    `save()`, await `Saved`, and assert the stored row has title, year, runtime and price.
  - `AddMovieScreenTest.form_isInCards_withSaveAndCancelPinned`: assert the "Details", "Status"
    and "Purchase" texts exist, Save is found by tag `TestTags.AddMovie.SAVE_BUTTON`, and Cancel
    by `TestTags.AddMovie.CANCEL_BUTTON`.
  - `AddMovieScreenOcclusionTest`: add a **navigation-bar** case next to the IME case.
- [ ] **Step 2: Run them.** Expected: FAIL. Then confirm the nav-bar test fails on the old screen:
  temporarily render the old inline Save, see the test fail, and revert.
- [ ] **Step 3: Implement.** The screen becomes
  `EditFormScaffold(title = stringResource(R.string.add_movie_title), …) { FilmFormContent(header = { EditHeader(kind, year = null, title = form.title.ifBlank { <kind> }, artwork = null) }, …) }`.
  Cancel calls `onNavigateBack`.
- [ ] **Step 4: Run the gate.** Expected: PASS.
- [ ] **Step 5: Commit** — `Add film by hand: the edit form's cards and pinned bar (#214)`

### Task 5: Add film from TMDB opens a review form

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/MovieSearchViewModel.kt`, `shared/src/jvmTest/.../ui/MovieSearchViewModelTest.kt`
- Modify: `app/.../ui/screens/MovieSearchScreen.kt`, `app/.../ui/TestTags.kt` (`MovieSearch.REVIEW_FORM`, `REVIEW_SAVE`, `REVIEW_CANCEL`)
- Modify: `app/src/test/.../ui/screens/MovieSearchScreenTest.kt`; Create: `app/src/test/.../ui/insets/MovieSearchReviewOcclusionTest.kt`
- Modify: `app/src/main/res/values/strings.xml` (`movie_search_title` → "Add film"), `CHANGELOG.md`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  ```kotlin
  public data class FilmReview(val mapping: TmdbMovieMapping, val form: FilmFormState,
      val isSaving: Boolean = false, val saveError: String? = null)
  // MovieSearchUiState gains: val review: FilmReview? = null
  // addMovie(tmdbId) is renamed reviewMovie(tmdbId): duplicate check -> movieDetails -> toMovieMapping -> review = FilmReview(mapping, mapping.toFilmForm())
  public fun onReviewTitleChange(value: String) /* …and the other field and status callbacks, mirroring AddMovieViewModel */
  public fun saveReview()     // ignored while isSaving or savedMediaId != null
  public fun cancelReview()   // review = null; ignored while isSaving; results and query kept
  public fun dismissReviewError()
  ```
- **`saveReview()`** writes with `movieRepository.addMovie`:
  - the edited title, year, runtime, price, status and `watchedWhen`;
  - plus the mapping's `externalIdentifiers`, `communityRating` and `synopsis`.
  - On success: `review = null`, `savedMediaId = id`, then `fetchPosterUseCase.enqueue(id, mapping.posterPath)`.
  - On error: `review.saveError`.
- **`addingTmdbId`** still marks the lookup in flight and still blocks a second tap.
- **The screen:** when `review != null`, `MovieSearchScreen` renders `EditFormScaffold` with
  `FilmFormContent` instead of the search UI.
  - The header is `EditHeader(kind, mapping.releaseYear, mapping.title, artwork = null)`.
  - `BackHandler(enabled = review != null)` calls `cancelReview`.
  - The route shows `FilmWatchedDialog` from `review.form.pendingWatched`.

- [ ] **Step 1: Write the failing jvmTests** in `MovieSearchViewModelTest`, with the existing fake
  TMDB client and a real `testAppDatabase()`:
  ```kotlin
  @Test fun reviewMovie_writesNothingAndQueuesNoPoster() { /* await review != null; assertEquals(0, movie row count); assertTrue(fakePosters.enqueued.isEmpty()) */ }
  @Test fun saveReview_storesEditedFieldsAndTmdbExtras() { /* edit title "Heat (1995)", clear runtime; save; row title == "Heat (1995)", runtime == null, TMDB external id present, synopsis == mapping.synopsis; poster enqueued once */ }
  @Test fun cancelReview_keepsResultsAndWritesNothing() { /* results unchanged, query unchanged, 0 rows */ }
  @Test fun cancelReview_whileSaving_isIgnored_andTheSaveLands() { /* StandardTestDispatcher: saveReview(); cancelReview(); runCurrent(); 1 row, savedMediaId set */ }
  @Test fun saveReview_twice_writesOneRow() { /* StandardTestDispatcher window */ }
  @Test fun reviewMovie_secondTapWhileLookingUp_isIgnored() { /* two taps, one movieDetails call */ }
  @Test fun reviewMovie_alreadyInLibrary_isRefusedBeforeLookup() { /* existing test, renamed */ }
  ```
- [ ] **Step 2: Write the failing Robolectric tests** in `MovieSearchScreenTest`:
  - `review_showsPrefilledCardsAndPinnedSave`;
  - `review_yearOutOfRange_showsErrorAndDisablesSave` (form `releaseYear = "1700"`);
  - `review_back_callsCancelReview`.
  Then `MovieSearchReviewOcclusionTest`: IME and nav-bar cases on the review state, with Save found
  by `MovieSearch.REVIEW_SAVE`. Prove the nav-bar case fails by removing `EditBottomBar`'s
  navigation-bar padding, then restore it.
- [ ] **Step 3: Run them.** Expected: FAIL (`review` undefined).
- [ ] **Step 4: Implement the view-model and screen changes above.**
- [ ] **Step 5: Run the gate.** Expected: PASS.
- [ ] **Step 6: Add `CHANGELOG.md` `[Unreleased]` entries:**
  - Changed: "Picking a film from search, or adding one by hand, now opens a form to check and edit
    it before it's saved, laid out like the edit screen."
  - Changed: "'Add movie' is now 'Add film'."
- [ ] **Step 7: Run the device suite.**
  `adb devices`, then `./gradlew :app:connectedDebugAndroidTest`. Record the tally.
- [ ] **Step 8: Check by hand on the phone.** Run `./gradlew :app:seedDebugDevice`. Then:
  - search for a film → review → Cancel → confirm the library is unchanged;
  - search → review → Save → the detail screen opens with the poster;
  - a manual add.
- [ ] **Step 9: Commit and open PR 1** (template first; open as a draft if the body is unsettled).
  Commit message: `Add film from search: review before saving (#214)`.

### Task 6: `ShowFormState` and `ShowFormContent`, shared by Edit show and manual Add show

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/ui/ShowFormState.kt`, `shared/src/commonTest/kotlin/com/hub/media/ui/ShowFormStateTest.kt`
- Create: `app/.../ui/components/ShowForm.kt`
- Modify: `EditTVShowViewModel.kt`, `AddTVShowViewModel.kt` (and their jvmTests), `EditTVShowScreen.kt`, `AddTVShowScreen.kt`, `TestTags.kt` (`AddTVShow.CANCEL_BUTTON`), `AddTVShowScreenTest.kt`, `AddTVShowScreenOcclusionTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public data class ShowFormState(
      val title: String = "", val releaseYear: String = "", val totalSeasons: String = "",
      val purchasePrice: String = "", val seasonRows: List<SeasonRow> = emptyList(),
  ) {
      public fun parsed(): ShowFormParse   // AddTVShowViewModel.save's parsing, including its exact
                                           // row messages ("Season row N: enter a season number")
  }
  public sealed interface ShowFormParse {
      public data class Valid(val title: String, val releaseYear: Int?, val totalSeasons: Int?,
          val purchasePrice: Double?, val seasons: List<SeasonQuickFill>) : ShowFormParse
      public data class Unreadable(val message: String) : ShowFormParse
  }
  public fun TmdbShowMapping.toShowForm(): ShowFormState   // title, year, totalSeasons; price blank; no rows

  @Composable fun ColumnScope.ShowFormContent(
      form: ShowFormState, header: @Composable () -> Unit, enabled: Boolean,
      onTitleChange: (String) -> Unit, onReleaseYearChange: (String) -> Unit,
      onTotalSeasonsChange: (String) -> Unit, onPurchasePriceChange: (String) -> Unit,
      seasons: (@Composable () -> Unit)? = null,   // null: no Seasons card (Edit show)
  )
  ```
  - `AddTVShowUiState` and `EditTVShowUiState.Editing` hold `form: ShowFormState` in place of
    their separate fields.
  - The quick-fill row editor moves out of `AddTVShowScreen` into
    `@Composable fun SeasonQuickFillRows(rows, enabled, onAdd, onRemove, onSeasonNumberChange, onEpisodeCountChange)`
    in `ShowForm.kt`, passed as `seasons`.

- [ ] **Step 1: Write the failing `ShowFormStateTest`.** Port the parsing cases from
  `AddTVShowViewModelTest` (blank → null, unreadable total seasons, row with no episode count →
  `"Season row 1: enter an episode count"`). Add `toShowForm_leavesRowsEmpty`.
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests "*ShowFormStateTest"`. Expected: FAIL to
  compile.
- [ ] **Step 3: Implement** `ShowFormState`. Move both view models onto it, and update their tests'
  state reads only.
- [ ] **Step 4: Implement `ShowFormContent` and `SeasonQuickFillRows`.** Port Edit show onto them
  first: the gate passes with `EditTVShowScreenGoldenTest` unchanged. Then port Add show onto
  `EditFormScaffold`, using the same test-first pattern as Task 4:
  - `AddTVShowScreenTest.form_isInCards_withSaveAndCancelPinned`;
  - a nav-bar occlusion case, proven failing on the old inline Save.
- [ ] **Step 5: Run the gate.** Expected: PASS.
- [ ] **Step 6: Commit** — `Show form written once; Add show by hand gets the edit layout (#214)`

### Task 7: Add show from TMDB opens a review form with a seasons summary

**Files:**
- Modify: `TVShowSearchViewModel.kt`, `TVShowSearchViewModelTest.kt` (jvmTest), `TVShowSearchScreen.kt`, `TVShowSearchScreenTest.kt`, `TestTags.kt` (`TVShowSearch.REVIEW_FORM`, `REVIEW_SAVE`, `REVIEW_CANCEL`)
- Create: `app/src/test/.../ui/insets/TVShowSearchReviewOcclusionTest.kt`
- Modify: `strings.xml`, `CHANGELOG.md`

**Interfaces:**
- Consumes: Task 6, plus `ShowReview` shaped like `FilmReview` (Task 5).
- Produces:
  - `public data class ShowReview(val mapping: TmdbShowMapping, val form: ShowFormState, val isSaving: Boolean = false, val saveError: String? = null)`;
  - `TVShowSearchUiState.review: ShowReview?`;
  - `reviewShow(tmdbId)`, the `onReview*Change` callbacks, `saveReview()`, `cancelReview()` and
    `dismissReviewError()`, with Task 5's guards.
  - `public fun TmdbShowMapping.seasonSummary(): SeasonSummary`, where
    `data class SeasonSummary(val seasons: Int, val episodes: Int)` counts
    `seasons.filterIsInstance<SeasonEpisodes>()` and their `episodes.size`.
- **`saveReview()`** passes the edited title, year, `totalSeasons` and price, plus the mapping's
  `seasons`, `externalIdentifiers`, `airingStatus`, `overview` (as `synopsis`), `firstAirDate`,
  `lastAirDate` and `communityRating`. Then it enqueues the poster.
- **The Seasons card** is the `seasons` slot, rendering the string
  `tv_show_review_seasons_summary`: "%1$d seasons, %2$d episodes from TMDB". Use a plurals
  resource for both counts: `<plurals name="tv_show_review_seasons">` and
  `<plurals name="tv_show_review_episodes">`, joined by the string.

- [ ] **Step 1: Write the failing jvmTests**, mirroring Task 5:
  - `reviewShow_writesNothingAndQueuesNoPoster`;
  - `saveReview_storesEditedFieldsAndEveryTmdbSeasonAndEpisode` (episode rows count == fixture's
    episode count; episode titles present; edited title stored);
  - `cancelReview_keepsResultsAndWritesNothing`;
  - `cancelReview_whileSaving_isIgnored_andTheSaveLands`;
  - `saveReview_twice_writesOneShow`;
  - `seasonSummary_countsSeasonsAndEpisodes` (commonTest): 2 seasons of 3 and 0 episodes →
    `SeasonSummary(2, 3)`.
- [ ] **Step 2: Write the failing Robolectric tests:**
  - `review_showsSeasonsSummaryAndNoQuickFillRows`;
  - `review_back_callsCancelReview`;
  - an occlusion test (IME and nav bar), with the nav-bar case proven failing as in Task 5.
- [ ] **Step 3: Run them.** Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run the gate, the device suite, and the by-hand check** (as Task 5, for shows).
- [ ] **Step 6: CHANGELOG** Changed: "Picking a show from search, or adding one by hand, now opens
  a form to check and edit it before it's saved."
- [ ] **Step 7: Commit and open PR 2** — `Add show from search: review before saving (#214)`

### Task 8: `BookFormContent` — the book form composable, shared, and rotation-safe

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/ui/BookFormValues.kt`
- Create: `app/.../ui/components/BookForm.kt`
- Modify: `app/.../ui/screens/EditBookScreen.kt`, `EditBookViewModel.kt` (`save(values: BookFormValues)`), `EditBookViewModelTest.kt`, `EditBookScreenTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public data class BookFormValues(
      val title: String, val releaseYear: Int?, val purchasePrice: Double?, val totalPages: Int?,
      val format: BookFormat, val status: ReadingStatus, val trackingMode: TrackingMode,
  )
  @Composable fun BookFormContent(
      initial: BookFormValues,
      header: @Composable () -> Unit,
      isReread: Boolean,
      isSaving: Boolean,
      errorMessage: String?,
      title: String,            // top-bar title
      onNavigateBack: () -> Unit,
      formTag: String, saveTag: String, cancelTag: String,
      onSave: (BookFormValues) -> Unit,
  )
  ```
  - It renders a whole `EditFormScaffold`, because its field state is local.
  - It holds `EditBookForm`'s field state, validation and cards, moved here unchanged except for
    `remember` → `rememberSaveable` on every field.
  - `FormatDropdownField`, `TrackingModeRow` and `StatusChipRow` move with it.
  - `EditBookViewModel.save` takes `BookFormValues` instead of seven parameters.

- [ ] **Step 1: Write the failing test.**
  `EditBookScreenTest.editedFields_surviveRecreation`: use `StateRestorationTester`, type
  "Changed" into the title, call `emulateSavedInstanceStateRestore()`, and assert the field still
  holds "Changed". Run it on the untouched screen. Expected: FAIL (`remember` loses it).
- [ ] **Step 2: Move the form into `BookForm.kt`** with `rememberSaveable`, and make
  `EditBookScreen` render `BookFormContent`.
- [ ] **Step 3: Run the gate.** Expected: PASS, with `EditBookScreenGoldenTest` unchanged (still
  centered until Task 11).
- [ ] **Step 4: Commit** — `Book form written once and kept across rotation (#214)`

### Task 9: Book lookup and save become two steps

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/books/domain/AddBookByIsbnUseCase.kt`
- Modify: `shared/src/jvmTest/kotlin/com/hub/media/features/books/domain/AddBookByIsbnUseCaseTest.kt`, `shared/src/commonTest/kotlin/com/hub/media/ui/FakeAddBookByIsbnUseCase.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public class BookDraft(
      public val normalizedIsbn: String,
      public val metadata: BookMetadata,
      public val coverBytes: ByteArray?,   // fetched once at lookup; stored only by save
  ) { public fun initialValues(): BookFormValues }  // title, releaseYear, pageCount, PHYSICAL, TO_READ, PAGES if pageCount != null else PERCENT, price null
  public interface BookIngestionUseCase {
      public suspend fun lookup(isbn: String): Resource<BookDraft>
      public suspend fun save(draft: BookDraft, values: BookFormValues): Resource<String>
  }
  ```
  - `BookDraft` is a plain class, not a data class, because `ByteArray` breaks `equals`.
  - **`lookup`** is today's `execute` up to `addBook`: validate and normalize the ISBN, fetch the
    metadata, and *download* the cover bytes without calling `imageStorage.saveImage`. It keeps
    the shape-only log line.
  - **`save`** stores the cover bytes (`imageStorage.saveImage`; a failure is logged and not
    fatal), then calls `bookRepository.addBook` with:
    - `values`;
    - the draft's `normalizedIsbn` and `buildExternalIdentifiers(metadata, normalizedIsbn)`;
    - `joinAuthors(metadata.authors)`.
  - **`execute(isbn)`** is removed. Port every `AddBookByIsbnUseCaseTest` case to `lookup` + `save`
    with `initialValues()`.

- [ ] **Step 1: Write the failing jvmTests:**
  ```kotlin
  @Test fun lookup_writesNoRowAndNoCoverFile() { /* lookup; 0 media rows; cover dir empty; draft.coverBytes != null */ }
  @Test fun save_storesEditedValuesAndTheLookupsIdentifiers() { /* values.copy(totalPages = 412, format = HARDCOVER, status = READING); row has 412, HARDCOVER, status READING; ISBN + OPEN_LIBRARY + OPEN_LIBRARY_WORK identifiers; authors joined */ }
  @Test fun save_aClearedPageCount_isNull() { /* values.copy(totalPages = null) -> totalPages null */ }
  @Test fun save_storesTheCoverFromTheLookup_withoutASecondDownload() { /* fake downloader call count == 1 after lookup + save; cover file exists */ }
  @Test fun save_aFailedCoverDownloadAtLookup_stillSaves() { /* coverBytes null; row saved; coverImageHash null */ }
  ```
- [ ] **Step 2: Run** `./gradlew :shared:jvmTest --tests "*AddBookByIsbnUseCaseTest"`. Expected:
  FAIL to compile.
- [ ] **Step 3: Implement**, then update `FakeAddBookByIsbnUseCase` to the new interface. It
  records `lookup` and `save` calls and has a scriptable result for each.
- [ ] **Step 4: Run** `./gradlew :shared:jvmTest :shared:testDebugUnitTest`. Expected: PASS.
  `AddBookViewModel` won't compile until Task 10, so do Tasks 9 and 10 in one sitting, and commit
  once both are green.

### Task 10: Add book opens the review form; the confirmation dialog goes

**Files:**
- Modify: `shared/.../ui/AddBookUiState.kt`, `AddBookViewModel.kt`, `shared/src/commonTest/.../ui/AddBookViewModelTest.kt`
- Modify: `app/.../ui/components/EditForm.kt` (`EditHeader` artwork from bytes), `app/.../ui/screens/AddBookScreen.kt`, `TestTags.kt` (`AddBook.REVIEW_FORM`, `REVIEW_SAVE`, `REVIEW_CANCEL`)
- Modify: `app/src/test/.../ui/screens/AddBookScreenTest.kt`, `app/src/test/.../ui/insets/AddBookScreenOcclusionTest.kt`, `app/src/test/.../ui/goldens/AddBookScreenGoldenTest.kt` (only if it rendered the dialog)
- Modify: `strings.xml` (delete the confirmation dialog's strings), `CHANGELOG.md`

**Interfaces:**
- Consumes: Tasks 8 and 9.
- Produces:
  - `AddBookUiState.Reviewing(val draft: BookDraft, val isSaving: Boolean = false, val errorMessage: String? = null)`.
  - `Loading` now means the lookup is in flight.
  - `addBook(isbn)` is renamed `lookUp(isbn)`: `Loading` → `Reviewing`, or `Error` on failure.
  - `saveReview(values: BookFormValues)` is ignored while `isSaving`. It leads to `Success(id)`,
    or to `Reviewing(errorMessage = …)` on failure.
  - `cancelReview()` returns to `Idle`, keeping the query and results. It is ignored while
    `isSaving`.
  - `confirmSelection()` resolves the edition's ISBN, then calls `lookUp`. `selectSearchResult`
    for a result with no work key calls it directly. `confirmationResult` and the dialog are
    removed.
  - `EditHeader` gains `coverBytes: ByteArray? = null`, used when `artwork == null`. Decode it
    once with `remember(coverBytes) { BitmapFactory.decodeByteArray(...)?.asImageBitmap() }`, and
    show nothing if decoding fails.
- **The screen:** when `uiState is Reviewing`, it renders `BookFormContent`:
  - `initial = draft.initialValues()`;
  - the header is `EditHeader(kind, metadata.releaseYear, metadata.title, artwork = null, coverBytes = draft.coverBytes)`;
  - `isReread = false`;
  - top-bar title `add_book_screen_title`;
  - `BackHandler` → `cancelReview`.

- [ ] **Step 1: Write the failing commonTests** in `AddBookViewModelTest` (with the fake):
  - `selectEdition_looksUpAndReviews_withoutSaving` (await `Reviewing`; `fake.saveCalls` empty);
  - `saveReview_savesTheEditedValues` (await `Success`; `fake.saveCalls.single().second.totalPages == 412`);
  - `cancelReview_writesNothingAndKeepsResults`;
  - `cancelReview_whileSaving_isIgnored`;
  - `saveReview_twice_savesOnce`;
  - `resultWithNoWorkKey_goesStraightToLookup_noConfirmation`;
  - `lookUp_secondCallWhileLoading_isIgnored`.
  Delete the confirmation-dialog tests this replaces, and name them in the commit message.
- [ ] **Step 2: Write the failing Robolectric tests:**
  - `review_showsTheLookupsValuesInCards`;
  - `review_zeroPageCount_showsErrorAndDisablesSave` (`pageCount = 0`);
  - `review_coverBytesRenderInTheHeader` (a 1×1 PNG's bytes → the header has an image node);
  - `review_back_callsCancelReview`;
  - occlusion: IME and nav bar on `Reviewing`, with Save found by `AddBook.REVIEW_SAVE`, and the
    nav-bar case proven failing as in Task 5.
- [ ] **Step 3: Run them.** Expected: FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run the gate.** Expected: PASS.
- [ ] **Step 6: CHANGELOG** Changed: "Picking a book's edition, or entering an ISBN, now opens a
  form to check and edit it before it's added, instead of adding it straight away. The 'Add this
  book?' prompt is gone."
- [ ] **Step 7: Commit** — `Add book: review before saving; no more confirmation prompt (#214)`

### Task 11: One top-bar style, the copy fixes, goldens, and PR 3

**Files:**
- Modify: `AddBookScreen.kt`, `EditBookScreen.kt` / `BookForm.kt` (`CenterAlignedTopAppBar` → `TopAppBar`; delete `EditFormScaffold`'s `centeredTitle`)
- Modify: `strings.xml` (`add_book_screen_title` → "Add book"; grep these screens' strings for remaining user-facing "movie" and change to "film")
- Golden PNGs: `app/src/test/screenshots/` (re-record)

**Interfaces:** none new.

- [ ] **Step 1: Make the top-bar and string changes.**
- [ ] **Step 2: Run** `./gradlew :app:verifyRoborazziDebug`. Expected: FAIL only on the goldens
  whose title moved (Add book, Edit book). Any other diff is a bug: stop and find it.
- [ ] **Step 3: Record the goldens.** Run `./gradlew :app:recordRoborazziDebug`, **open each
  changed PNG**, and confirm the title is left-aligned and nothing else moved. If a review form
  earned a golden, add at most one, `AddBookReviewGoldenTest`, paired with an assertion through
  `captureGolden`'s `alsoAssert`.
- [ ] **Step 4: Commit the code** (`One top-bar style on the add screens and Edit book (#214)`), then
  the PNGs in their own commit (`Goldens: left-aligned titles on Add/Edit book (#214)`).
- [ ] **Step 5: Run the gate, the device suite and the by-hand check.** On the phone, run all six
  flows from spec §3, including a cancelled book review, then confirm the library didn't change.
- [ ] **Step 6: Wrap up the PR:**
  - CHANGELOG Changed: "'Add Book' is now 'Add book', and every add screen has its title on the
    left like the rest of the app."
  - Open PR 3 from the template.
  - Write the Graphban outcomes table for the lessons consulted (`| shard id | outcome | reason |`,
    naming MT-A8 (Claude Code)), for the owner to record against MT-48.

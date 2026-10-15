# Edit Screens Revamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Edit Book, Edit Film and Edit TV share the detail screens' look. Each gets a compact
read-only header, titled section cards, the status dropdown chip (Book and Film), and a pinned
Cancel / Save bar.

**Architecture:** Three shared composables go in `ui/components/`. `FormSection` and
`EditBottomBar` move out of `EditBookScreen.kt`; `EditHeader` is new.
`StatusDropdownChip` gains `enabled`. In `shared`, the edit UI states gain the saved title, year
and cover hash that the header shows. Each screen is then rebuilt on those pieces. Behaviour
(validation, saving, Film's "when did you watch it" dialog) is unchanged.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room KMP, Robolectric and Roborazzi
(`app/src/test`), kotlin.test (`shared/src/jvmTest`).

**Spec:** `docs/superpowers/specs/2026-10-15-edit-screens-revamp-design.md`

## Global Constraints

- Room-touching tests go in `shared/src/jvmTest`. View model tests await `uiState.first { ... }`
  and never read `.value` after an action.
- Each screen keeps `contentWindowInsets = WindowInsets.safeDrawing`, which the CI IME check greps for.
- Test tags are constants in `ui/TestTags.kt`, never string literals.
- Goldens are recorded by hand (`:app:recordRoborazziDebug`), looked at, and committed alone.
- A test that types into a text field inside a dialog does not belong in `app/src/test`.
- `CHANGELOG.md` `[Unreleased]` gets the user-visible change.
- The side margin is 16dp. Sections are spaced 20dp apart, and fields inside a card 16dp, as
  `EditBookScreen` already does.
- The gate before a push:
  - `./gradlew ktlintFormat`
  - `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`

## Review Focus

1. **Saving.** On Film and TV, Save sits in a bottom bar next to Cancel, so the old
   "Role.Button without a content description" save matcher now matches both. Tests must find Save
   by tag, or a test can pass by tapping Cancel. This is pinned in Tasks 4 and 5.
2. **Film's status while saving.** The old chips were disabled mid-save. The dropdown chip must be
   disabled too, or a status change can slip in after Save was pressed and be lost. This is pinned
   in Tasks 2 and 4.
3. **The header after typing.** The header must keep the saved title while the title field
   changes. This is pinned in Task 1 (view model) and Task 4 (screen).
4. **A long title in the header.** It must wrap or ellipsize, never push the thumbnail off-screen.
   `EditHeader` caps it at 2 lines with an ellipsis, as pinned in Task 2.
5. **The keyboard.** The new bottom bar on Film and TV must stay above the IME. The existing
   occlusion tests already expect `SAVE_BUTTON`; Tasks 4 and 5 add `CANCEL_BUTTON` to their tag lists.

---

### Task 1: The header data in `shared`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/EditBookUiState.kt` (`Ready`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/EditBookViewModel.kt:126-136`
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/EditMovieViewModel.kt` (`Editing` + `load()`)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/EditTVShowViewModel.kt` (`Editing` + load)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/EditBookViewModelTest.kt`, `EditMovieViewModelTest.kt`, `EditTVShowViewModelTest.kt`

**Interfaces:**
- Produces:
  - `EditBookUiState.Ready.coverImageHash: String? = null`
  - `EditMovieUiState.Editing.savedTitle: String = title`, `savedYear: Int? = null`, `coverImageHash: String? = null`
  - `EditTVShowUiState.Editing` gets the same three fields.

  The defaults keep every existing constructor call compiling. `savedTitle` defaults to `title`,
  so test fixtures that don't care stay short.

- [ ] **Step 1: Write the failing tests**

  In `EditBookViewModelTest`, `insertBook` gains `coverImageHash: String? = null`, passed to
  `sampleMediaItem(coverImageHash = ...)`. Add:

```kotlin
    @Test
    fun uiState_ready_carriesTheCoverHashForTheHeader() =
        runTest {
            insertBook(coverImageHash = "abc123")
            val ready = newViewModel().uiState.first { it is EditBookUiState.Ready } as EditBookUiState.Ready
            assertEquals("abc123", ready.coverImageHash)
        }
```

  In `EditMovieViewModelTest`, add this. The cover is set through the DAO because `addMovie` has no
  cover parameter:

```kotlin
    @Test
    fun editing_snapshotsTheSavedTitleYearAndCover_andTypingLeavesThemAlone() =
        runTest {
            val movieId = insertMovie(title = "Interstellar", releaseYear = 2014)
            db.mediaItemDao().updateCoverImageHash(movieId, "poster1")
            val viewModel = editingViewModel(movieId)

            viewModel.onTitleChange("Interstellarr")

            val editing =
                viewModel.uiState.first { (it as? EditMovieUiState.Editing)?.title == "Interstellarr" }
                    as EditMovieUiState.Editing
            assertEquals("Interstellar", editing.savedTitle)
            assertEquals(2014, editing.savedYear)
            assertEquals("poster1", editing.coverImageHash)
        }
```

  In `EditTVShowViewModelTest`, add the same test using `insertShow(title = "Chernobyl",
  releaseYear = 2019)`, `editing(id)`, `onTitleChange("Chernobil")` and `awaitForm { it.title ==
  "Chernobil" }`. The expected values are `"Chernobyl"`, `2019` and `"poster1"`.

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :shared:jvmTest --tests "com.hub.media.ui.EditBookViewModelTest" --tests "com.hub.media.ui.EditMovieViewModelTest" --tests "com.hub.media.ui.EditTVShowViewModelTest"`

  Expected: compile errors (`Unresolved reference: coverImageHash` / `savedTitle`).

- [ ] **Step 3: Implement**

  Add to `EditBookUiState.Ready`, after `trackingMode`:

```kotlin
        /** The cover shown in the edit screen's header (#174); `null` when the book has none. */
        val coverImageHash: String? = null,
```

  In `EditBookViewModel`'s `Ready(...)` mapping, add `coverImageHash = bookDetail.item.coverImageHash,`.

  Add to `EditMovieUiState.Editing` and `EditTVShowUiState.Editing`, after `purchasePrice`:

```kotlin
        /**
         * The header's title (#174): the title as loaded, so the header keeps saying which item is
         * being edited while [title] changes underneath it. Never written by a field edit.
         */
        val savedTitle: String = title,
        /** The release year as loaded, for the header's kind line. */
        val savedYear: Int? = null,
        /** The cover shown in the header; `null` when the item has none. */
        val coverImageHash: String? = null,
```

  In both `load()` mappings, add:

```kotlin
                        savedTitle = movie.item.title,
                        savedYear = movie.item.releaseYear,
                        coverImageHash = movie.item.coverImageHash,
```

  In the TV mapping these read `show.item.title`, `show.item.releaseYear` and
  `show.item.coverImageHash`.

  Field edits use `.copy(title = ...)`, which leaves `savedTitle` untouched.

- [ ] **Step 4: Run the tests and watch them pass**

  Same command as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

  Message: `Edit states carry the header's saved title, year and cover (#174)`

---

### Task 2: The shared components

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/EditForm.kt`
  (`FormSection`, `EditBottomBar`, `EditHeader`)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/DetailHeader.kt`
  (`StatusDropdownChip` gains `enabled`)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditBookScreen.kt`
  (delete its private `FormSection` and `EditBookBottomBar`, and call the shared ones)
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/components/EditFormTest.kt`

**Interfaces:**
- Produces:
  - `@Composable fun FormSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)`
  - `@Composable fun EditBottomBar(isSaving: Boolean, canSave: Boolean, onCancel: () -> Unit, onSave: () -> Unit, cancelTag: String, saveTag: String)`
  - `@Composable fun EditHeader(kind: String, year: Int?, title: String, artwork: DetailArtwork?, modifier: Modifier = Modifier)`
  - `StatusDropdownChip(..., enabled: Boolean = true)`

- [ ] **Step 1: Write the failing tests**

  `EditFormTest.kt` uses Robolectric and `createComposeRule()` in the same style as
  `DetailTopBarActionsTest`.

```kotlin
@RunWith(RobolectricTestRunner::class)
class EditFormTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun header_showsTheTitleAsAHeading_andTheKindLineWithYear() {
        composeRule.setContent {
            MediaTrackerTheme { EditHeader(kind = "Film", year = 1999, title = "The Matrix", artwork = null) }
        }
        composeRule.onNode(hasText("The Matrix") and isHeading()).assertExists()
        composeRule.onNodeWithText("Film · 1999").assertExists()
    }

    @Test
    fun header_withoutAYear_showsTheKindAlone() {
        composeRule.setContent {
            MediaTrackerTheme { EditHeader(kind = "Film", year = null, title = "X", artwork = null) }
        }
        composeRule.onNodeWithText("Film").assertExists()
        composeRule.onNodeWithText("Film ·", substring = true).assertDoesNotExist()
    }

    @Test
    fun header_longTitle_isCappedAtTwoLines() {
        composeRule.setContent {
            MediaTrackerTheme {
                EditHeader(kind = "Film", year = 2000, title = "Word ".repeat(40).trim(), artwork = null)
            }
        }
        val layout = composeRule.onNode(isHeading()).fetchTextLayoutResult()
        assertEquals(2, layout.lineCount)
    }

    @Test
    fun statusChip_disabled_doesNotOpen() {
        composeRule.setContent {
            MediaTrackerTheme {
                StatusDropdownChip(
                    value = "Watchlist",
                    options = listOf("Watchlist", "Watched"),
                    label = { it },
                    onSelect = {},
                    onClickLabel = "Change status",
                    enabled = false,
                )
            }
        }
        composeRule.onNodeWithText("Watchlist").assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("Watched").assertDoesNotExist()
    }

    @Test
    fun bottomBar_savingShowsProgress_andDisablesBoth() {
        composeRule.setContent {
            MediaTrackerTheme {
                EditBottomBar(isSaving = true, canSave = true, onCancel = {}, onSave = {}, cancelTag = "c", saveTag = "s")
            }
        }
        composeRule.onNodeWithTag("s").assertIsNotEnabled()
        composeRule.onNodeWithTag("c").assertIsNotEnabled()
    }
}
```

  `fetchTextLayoutResult` is a small helper:
  `SemanticsNodeInteraction.fetchTextLayoutResult()` calls the node's `GetTextLayoutResult`
  action. Add it privately in the test. The long-title test needs `@GraphicsMode(NATIVE)` on that
  method.

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditFormTest"`

  Expected: compile errors (`EditHeader`, `EditBottomBar`, `enabled` unresolved).

- [ ] **Step 3: Implement**

  `StatusDropdownChip`:
  - Add `enabled: Boolean = true` after `onClickLabel`.
  - Pass `enabled = enabled` to the `AssistChip`.
  - Guard the semantics `onClick` with `if (enabled) open()`.
  - Add `if (!enabled) disabled()` inside the semantics block.
  - Document it in the KDoc.

  `EditForm.kt` holds three composables:

  1. **`FormSection`** is moved verbatim from `EditBookScreen.kt:492-517` and made public, with a
     `modifier` parameter.
  2. **`EditBottomBar`** is moved verbatim from `EditBookScreen.kt:639-685`. The two `testTag`
     calls take `cancelTag` and `saveTag`.
  3. **`EditHeader`** is new:

```kotlin
/**
 * The edit screens' compact header (#174): which item is being edited, in the detail screen's
 * vocabulary but smaller. A thumbnail when there is a cover, then the kind line ("Film · 1999") and
 * the saved title as a heading. Read-only by design -- the form below it is where things change.
 * With no cover there is no artwork slot at all, matching [DetailHeader].
 */
@Composable
fun EditHeader(
    kind: String,
    year: Int?,
    title: String,
    artwork: DetailArtwork?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artwork != null) {
            Box(modifier = Modifier.size(width = 64.dp, height = 96.dp).clip(RoundedCornerShape(8.dp))) {
                CoverImage(
                    coverDir = artwork.coverStorageDir,
                    coverImageHash = artwork.coverImageHash,
                    mediaType = artwork.mediaType,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (year != null) stringResource(R.string.detail_kind_year_format, kind, year) else kind,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}
```

  In `EditBookScreen.kt`, delete the private `FormSection` and `EditBookBottomBar`. Call
  `EditBottomBar(..., cancelTag = TestTags.EditBook.CANCEL_BUTTON, saveTag = TestTags.EditBook.SAVE_BUTTON)`
  and import the shared `FormSection`.

- [ ] **Step 4: Run the tests and watch them pass**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditFormTest" --tests "*EditBookScreen*"`

  Expected: PASS. Edit Book is unchanged so far, because the move is verbatim.

- [ ] **Step 5: Commit**

  Message: `Shared edit-form pieces: FormSection, EditBottomBar, EditHeader; status chip can be disabled (#174)`

---

### Task 3: Edit Book gets the header and the dropdown

**Files:**
- Modify: `app/.../ui/screens/EditBookScreen.kt`:
  - `EditBookScreenRoute` and `EditBookScreen` gain `coverStorageDir: String`.
  - The header goes at the top of the form.
  - `StatusChipRow` becomes the dropdown.
- Modify: `app/.../ui/navigation/AppNavigation.kt` (pass `coverStorageDir` to `EditBookScreenRoute`)
- Modify: the previews in `EditBookScreen.kt` and `EditBookScreenGoldenTest.kt`/`EditBookScreenOcclusionTest.kt`
  and `EditScreensLoadFailedTest.kt`, which pass `coverStorageDir = "/fake"`.
- Test: `app/src/test/.../ui/screens/EditBookScreenTest.kt`

**Interfaces:**
- Consumes: `EditHeader`, `StatusDropdownChip(enabled)`, `FormSection` (Task 2); `Ready.coverImageHash` (Task 1).

- [ ] **Step 1: Write the failing tests** (in `EditBookScreenTest`, whose `setContent` passes `coverStorageDir = "/fake"`)

```kotlin
    @Test
    fun header_showsTheSavedTitleAndKindLine() {
        setContent(readyState(title = "The Great Gatsby"))
        composeRule.onNode(hasText("The Great Gatsby") and isHeading()).assertExists()
        composeRule.onNodeWithText("Book · 1925").assertExists()
    }

    @Test
    fun status_isChosenFromTheDropdown_andTheChoiceIsSaved() {
        var saved: ReadingStatus? = null
        setContent(readyState(), onSave = { _, _, _, _, _, status, _ -> saved = status })

        composeRule.onNodeWithText(context.getString(R.string.reading_status_to_read)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.reading_status_dnf)).performClick()
        composeRule.onNodeWithTag(TestTags.EditBook.SAVE_BUTTON).performClick()

        assertEquals(ReadingStatus.DNF, saved)
    }
```

  `readyState`'s default `releaseYear` must be 1925. The string resource names are the ones
  `displayLabel()` uses, so confirm them against `ReadingStatus.displayLabel()` before running.

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditBookScreenTest"`

  Expected: there is no heading node, and the DNF option is already on screen as a chip. The
  click lands on the chip and passes, so the dropdown test must be checked against the old chip
  row: with the chip row present, "Did not finish" exists before the dropdown opens. Add
  `composeRule.onNodeWithText(dnf).assertDoesNotExist()` before the first click, which makes it fail.

- [ ] **Step 3: Implement**

  - In `EditBookForm`, put `EditHeader(kind = stringResource(R.string.book_detail_kind), year =
    state.releaseYear, title = state.title, artwork = state.coverImageHash?.let {
    DetailArtwork(coverStorageDir, it, MediaType.BOOK) })` as the first child of the scrolling
    column.
  - Replace `StatusChipRow`'s body. The label `Text` stays, and the `LazyRow` of chips becomes:

```kotlin
        StatusDropdownChip(
            value = status,
            options = ReadingStatus.entries.toList(),
            label = { it.displayLabel() },
            onSelect = onStatusChange,
            onClickLabel = stringResource(R.string.detail_status_change_action_label),
            enabled = enabled,
        )
```

  - Update the KDoc that explains "picking a control per enum": status is now a dropdown, for
    consistency with the detail screens (#174), and because four labels overflowed a row.
  - Thread `coverStorageDir` through `EditBookScreenRoute`, `EditBookScreen` and `EditBookForm`,
    and through `AppNavigation`.

- [ ] **Step 4: Run the tests and watch them pass**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditBook*" --tests "*EditScreensLoadFailedTest" --tests "*DecimalFieldLocaleTest"`

  Expected: PASS. The `edit-book` golden will now differ; it is re-recorded in Task 6.

- [ ] **Step 5: Commit**

  Message: `Edit Book: the header and the status dropdown (#174)`

---

### Task 4: Edit Film gets cards, the dropdown, the bottom bar and the header

**Files:**
- Modify: `app/.../ui/screens/EditMovieScreen.kt`
- Modify: `app/.../ui/TestTags.kt` (add `EditMovie.CANCEL_BUTTON = "editMovie:cancel"`)
- Modify: `app/.../ui/navigation/AppNavigation.kt` (pass `coverStorageDir`)
- Modify: `app/src/main/res/values/strings.xml` (add `edit_section_details` = "Details")
- Test: `app/src/test/.../ui/screens/EditMovieScreenTest.kt`, `ui/insets/EditMovieScreenOcclusionTest.kt`, `DecimalFieldLocaleTest.kt`, `EditScreensLoadFailedTest.kt`

**Interfaces:**
- Consumes: Task 1's `savedTitle`, `savedYear` and `coverImageHash`; Task 2's components.
- Produces: `EditMovieScreen(..., coverStorageDir: String, ...)` and `EditMovieScreenRoute(..., coverStorageDir: String, ...)`

- [ ] **Step 1: Write the failing tests**

  - Swap `saveButtonMatcher` for `hasTestTag(TestTags.EditMovie.SAVE_BUTTON)`. Keep it a `val`, so
    every existing test now finds Save by tag. Review Focus 1: the old matcher would match Cancel too.
  - `whileSaving_theWholeFormIsDisabled` asserts the status chip, not a chip row option:
    `composeRule.onNodeWithText(context.getString(R.string.watch_status_watchlist)).assertIsNotEnabled()`.
  - Add these tests:

```kotlin
    @Test
    fun header_keepsTheSavedTitle_whileTheFieldSaysSomethingElse() {
        setContent(editing(title = "Interstellarr").copy(savedTitle = "Interstellar", savedYear = 2014))
        composeRule.onNode(hasText("Interstellar") and isHeading()).assertExists()
        composeRule.onNodeWithText("Film · 2014").assertExists()
    }

    @Test
    fun status_isChosenFromTheDropdown() {
        var chosen: WatchStatus? = null
        setContent(editing(), onStatusChange = { chosen = it })
        val watched = context.getString(R.string.watch_status_watched)
        composeRule.onNodeWithText(watched).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.watch_status_watchlist)).performClick()
        composeRule.onNodeWithText(watched).performClick()

        assertEquals(WatchStatus.WATCHED, chosen)
    }

    @Test
    fun cancel_isInTheBottomBar_andNavigatesBack() {
        var backs = 0
        setContent(editing(), onNavigateBack = { backs++ })
        composeRule.onNodeWithTag(TestTags.EditMovie.CANCEL_BUTTON).performClick()
        assertEquals(1, backs)
    }
```

  `setContent` gains `onStatusChange` and `onNavigateBack` parameters, and passes
  `coverStorageDir = "/fake"`. Add `TestTags.EditMovie.CANCEL_BUTTON` to the occlusion test's
  `TAGS` (Review Focus 5).

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditMovieScreen*"`

  Expected: compile errors on `CANCEL_BUTTON` and `coverStorageDir`. Stub those two only (add the
  tag constant and an unused parameter) and run again. The expected failures are then: no heading,
  "Watched" present before opening, and no Cancel tag.

- [ ] **Step 3: Implement**

  Restructure the `Scaffold` content to Edit Book's shape:
  - an outer `Column(fillMaxSize, imePadding, consumeWindowInsets)`;
  - a scrolling inner `Column(weight(1f), verticalScroll, padding(top = scaffold top), barPadding(Horizontal), padding(16.dp), testTag(FORM))` with `spacedBy(20.dp)`;
  - then `EditBottomBar`.

  The Loading and LoadFailed branches render as now. They don't show the bar, because `form == null`.

  Inside the scroll:
  - `EditHeader(kind = stringResource(R.string.movie_detail_kind), year = form.savedYear, title = form.savedTitle, artwork = form.coverImageHash?.let { DetailArtwork(coverStorageDir, it, MediaType.MOVIE) })`
  - `FormSection(stringResource(R.string.edit_section_details))` holds the title, year and runtime
    fields, exactly as they are now.
  - `FormSection(stringResource(R.string.edit_section_status))` holds
    `StatusDropdownChip(value = form.status, options = WatchStatus.entries.toList(), label = { it.displayLabel() }, onSelect = onStatusChange, onClickLabel = stringResource(R.string.detail_status_change_action_label), enabled = !form.isSaving)`.
  - `FormSection(stringResource(R.string.edit_section_purchase))` holds the price field.

  The bar is
  `EditBottomBar(isSaving = form.isSaving, canSave = form.title.isNotBlank() && releaseYearIsValid && runtimeIsValid && purchasePriceIsValid, onCancel = onNavigateBack, onSave = onSave, cancelTag = TestTags.EditMovie.CANCEL_BUTTON, saveTag = TestTags.EditMovie.SAVE_BUTTON)`.

  Remove the inline `Button`, the `FilterChip` column and their imports. `edit_movie_save` may then
  be unused, so delete it if lint says so. Thread `coverStorageDir` through the route and
  `AppNavigation`.

- [ ] **Step 4: Run the tests and watch them pass**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditMovie*" --tests "*EditScreensLoadFailedTest" --tests "*DecimalFieldLocaleTest"`

  Expected: PASS.

- [ ] **Step 5: Sabotage check**

  Pass `enabled = true` to the status chip, run the tests, and expect `whileSaving_…` to FAIL.
  Then revert.

- [ ] **Step 6: Commit**

  Message: `Edit Film: header, section cards, status dropdown, Cancel/Save bar (#174)`

---

### Task 5: Edit TV gets cards, the bottom bar and the header

**Files:**
- Modify: `app/.../ui/screens/EditTVShowScreen.kt`
- Modify: `app/.../ui/TestTags.kt` (add `EditTVShow.CANCEL_BUTTON = "editTVShow:cancel"`)
- Modify: `AppNavigation.kt` (pass `coverStorageDir`)
- Test: `EditTVShowScreenTest.kt`, `ui/insets/EditTVShowScreenOcclusionTest.kt`, `EditScreensLoadFailedTest.kt`, `DecimalFieldLocaleTest.kt`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: `EditTVShowScreen(..., coverStorageDir: String, ...)`

- [ ] **Step 1: Write the failing tests**

  - Any test that finds Save by role or text now finds it by `hasTestTag(TestTags.EditTVShow.SAVE_BUTTON)`.
  - Add `CANCEL_BUTTON` to the occlusion test's `TAGS`.
  - Add these tests:

```kotlin
    @Test
    fun header_keepsTheSavedTitle_whileTheFieldSaysSomethingElse() {
        setContent(editing(title = "Chernobil").copy(savedTitle = "Chernobyl", savedYear = 2019))
        composeRule.onNode(hasText("Chernobyl") and isHeading()).assertExists()
        composeRule.onNodeWithText("TV show · 2019").assertExists()
    }

    @Test
    fun cancel_isInTheBottomBar_andNavigatesBack() {
        var backs = 0
        setContent(editing(), onNavigateBack = { backs++ })
        composeRule.onNodeWithTag(TestTags.EditTVShow.CANCEL_BUTTON).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun theFieldsAreGroupedUnderDetailsAndPurchase() {
        setContent(editing())
        composeRule.onNodeWithText(context.getString(R.string.edit_section_details)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.edit_section_purchase)).assertExists()
        // A status section here would contradict the spec: the detail screen owns abandoning.
        composeRule.onNodeWithText(context.getString(R.string.edit_section_status)).assertDoesNotExist()
    }
```

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditTVShowScreen*"`

  Expected: stub the tag constant and parameter first, as in Task 4, then the three new tests FAIL.

- [ ] **Step 3: Implement**

  Use the same shape as Task 4:
  - `EditHeader(kind = stringResource(R.string.tv_show_detail_kind), year = form.savedYear, title = form.savedTitle, artwork = form.coverImageHash?.let { DetailArtwork(coverStorageDir, it, MediaType.TV_SHOW) })`.
    Confirm the `MediaType` constant name for TV before writing it.
  - **Details** holds title, year and seasons; **Purchase** holds price.
  - Add `EditBottomBar` with `canSave = form.title.isNotBlank() && releaseYearIsValid && totalSeasonsIsValid && purchasePriceIsValid`.
  - Remove the inline `Button`.
  - Thread `coverStorageDir` through.

- [ ] **Step 4: Run the tests and watch them pass**

  Run: `./gradlew :app:testDebugUnitTest --tests "*EditTVShow*" --tests "*EditScreensLoadFailedTest" --tests "*DecimalFieldLocaleTest"`

  Expected: PASS.

- [ ] **Step 5: Commit**

  Message: `Edit TV: header, section cards, Cancel/Save bar (#174)`

---

### Task 6: Goldens

**Files:**
- Create: `app/src/test/.../ui/goldens/EditMovieScreenGoldenTest.kt`, `EditTVShowScreenGoldenTest.kt`
- Modify: `EditBookScreenGoldenTest.kt` (pass `coverStorageDir`)
- Re-record: `app/src/test/screenshots/edit-book.png`; new: `edit-film.png`, `edit-tv.png`

- [ ] **Step 1: Write the golden tests**

  Copy `EditBookScreenGoldenTest`'s shape. `alsoAssert` checks that `FORM`, `SAVE_BUTTON` and
  `CANCEL_BUTTON` exist.
  - **Film fixture:** `EditMovieUiState.Editing(title = "Interstellar", releaseYear = "2014", runtimeMinutes = "169", purchasePrice = "14.99", status = WatchStatus.WATCHLIST, savedYear = 2014)`.
  - **TV fixture:** `EditTVShowUiState.Editing(title = "Chernobyl", releaseYear = "2019", totalSeasons = "1", purchasePrice = "19.99", savedYear = 2019)`.
  - Both use `coverStorageDir = "/fake"` with no cover, since a fake path draws nothing.

- [ ] **Step 2: Verify that the goldens fail**

  Run: `./gradlew :app:verifyRoborazziDebug`

  Expected: FAIL. `edit-book` differs, and `edit-film` and `edit-tv` have no reference image.

- [ ] **Step 3: Record and look**

  Run: `./gradlew :app:recordRoborazziDebug`, then open all three PNGs. Check the 16dp margins,
  that the header shows the title, that the status chip is not cut off, and that the bar is pinned.

- [ ] **Step 4: Verify that the goldens pass**

  Run: `./gradlew :app:verifyRoborazziDebug`. Expected: PASS.

- [ ] **Step 5: Commit**

  Commit the test files first. Then commit the PNGs alone as `Goldens: edit screens (#174)`.

---

### Task 7: Changelog, follow-up issue and the full gate

- [ ] **Step 1:** Add to `CHANGELOG.md` `[Unreleased]` under `### Changed`: "The Edit Book, Edit Film and Edit TV show screens now match the detail screens: a header showing what you're editing, fields grouped into cards, status picked from the same dropdown, and Cancel / Save pinned at the bottom. Edit Book's status no longer cuts off 'Did not finish'."
- [ ] **Step 2:** File the follow-up issue "The add screens have not had #141's revamp either", which carries #174's remaining scope (Add Book, Add Film, Add TV). Ask the owner before filing, because it is outward-facing.
- [ ] **Step 3:** Run `./gradlew ktlintFormat`, then the full gate from Global Constraints. Expected: all pass.
- [ ] **Step 4:** Commit `CHANGELOG for #174`.
- [ ] **Step 5:** Device check: `adb devices`, then the full `./gradlew :app:connectedDebugAndroidTest`, then `:app:seedDebugDevice`, then a hand check of the three edit screens (including Film → Watched → dialog, and the keyboard up on each).

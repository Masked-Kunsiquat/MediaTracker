# #172: One top bar for the three detail screens (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Film, TV and Book detail screens share one top bar: ← · Edit · ⋮ ("More options"), with secondary actions and then Delete (last, error colour) in the menu, shown only once the item has loaded.

**Architecture:** A new stateless `DetailTopBarActions` composable in `ui/components/` holds the Edit icon and the ⋮ menu. Each screen's `actions = {}` becomes one gated call to it. No ViewModel changes.

**Tech Stack:** Jetpack Compose (Material 3 `DropdownMenu`), Robolectric UI tests (`app/src/test`), Roborazzi goldens.

**Spec:** `docs/superpowers/specs/2026-10-15-detail-top-bars-design.md`

## Global Constraints

- **No ViewModel or `shared/` change**, and no change to what any action does.
- **Tests:** every test that pins a bar is updated **only** where the spec changes the behaviour: delete via ⋮, TV's loading behaviour, Book's refetch progress. Never loosen any other assertion. Tests live in `app/src/test` (Robolectric).
- **Text:** test matchers use semantic text. The ⋮ is found by its content description "More options", and items by their visible text.
- **Goldens:** re-recorded by hand with `./gradlew :app:recordRoborazziDebug`. Look at every changed PNG, then commit them in their own commit with no assertion changes.
- **Graphban:** add evidence to MT-32 after each task.
- **Before push:** `./gradlew ktlintFormat`, then the full gate (`:shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && :app:assembleDebug`). Then `connectedDebugAndroidTest`, `seedDebugDevice`, and a hand check.

## Review Focus

1. **Delete is still confirmed.** Choosing Delete opens the existing dialog; nothing is deleted until the dialog is confirmed.
2. **No ⋮ while loading or not found**, on all three screens.
3. **The menu closes before the action runs.** No menu is left floating over the confirmation dialog.
4. **Book during a refetch:** ⋮ is present, refetch is disabled with progress shown, and Delete is reachable.
5. **TV refresh while a refresh is running** is disabled. This preserves today's `enabled = !isRefreshingMetadata`.

---

### Task 1: `DetailTopBarActions`

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/components/DetailTopBarActions.kt`
- Modify: `app/src/main/res/values/strings.xml` (add `detail_more_options`)
- Test (create): `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/components/DetailTopBarActionsTest.kt`

**Interfaces:** Produces `data class DetailMenuAction(label: String, onClick: () -> Unit, enabled: Boolean = true, supportingText: String? = null, inProgress: Boolean = false)` and `@Composable fun DetailTopBarActions(editContentDescription: String, onEdit: () -> Unit, secondary: List<DetailMenuAction>, deleteLabel: String, onDelete: () -> Unit)`.

- [ ] **Step 1: Write the failing test.** Robolectric, `createComposeRule()`, hosted in a `Row` under `MediaTrackerTheme(dynamicColor = false)`:
  - `edit_invokesOnEdit`
  - `moreOptions_opensMenu_withSecondaryActionsInOrder_andDeleteLast`: assert the order through each item's top bound, read via `fetchSemanticsNode().boundsInRoot.top`.
  - `choosingDelete_invokesOnDeleteOnce_andClosesTheMenu`: after the click, the item's text no longer exists.
  - `choosingASecondaryAction_invokesIt_andClosesTheMenu`
  - `disabledAction_isNotEnabled_andShowsItsSupportingText`
  - `inProgressAction_isNotEnabled_andShowsProgress`: assert a node with `ProgressBarRangeInfo` exists inside the item.
  - `noSecondaryActions_menuHoldsOnlyDelete`
- [ ] **Step 2: Run to verify it fails.** `./gradlew :app:testDebugUnitTest --tests 'com.github.maskedkunisquat.mediatracker.ui.components.DetailTopBarActionsTest'`. Expected: compile failure on the unresolved names.
- [ ] **Step 3: Implement.** Edit `IconButton` → `Box { IconButton(MoreVert, "More options") ; DropdownMenu { secondary.forEach { DropdownMenuItem(text = label + optional supporting text, leadingIcon = if (inProgress) small CircularProgressIndicator else null, enabled = enabled && !inProgress, onClick = { expanded = false; it.onClick() }) }; if (secondary.isNotEmpty()) HorizontalDivider(); DropdownMenuItem(text = deleteLabel in colorScheme.error, leadingIcon = Delete icon tinted error, onClick = { expanded = false; onDelete() }) } }`. Add `TestTags.DetailTopBar.OVERFLOW` / `.DELETE`. Add the string `<string name="detail_more_options">More options</string>`.
- [ ] **Step 4: Run to verify it passes.** Same command, PASS. **Mutation:** temporarily drop `expanded = false` from Delete's `onClick`; the closes-the-menu test must fail. Restore it.
- [ ] **Step 5: Commit** `DetailTopBarActions: Edit plus a More-options menu with Delete last (#172)`, then add MT-32 evidence.

### Task 2: Film

**Files:** `MovieDetailScreen.kt` (`actions = {` ~line 238), and `MovieDetailScreenTest.kt`'s delete and action tests (~175-265).

- [ ] **Step 1: Update the tests first.** Add a private helper `openMoreOptions()` that clicks the node with content description "More options". In `deleteAction_showsConfirmationDialog_…`, `confirmingTheDeleteDialog_…` and `cancellingTheDeleteDialog_…`, replace the delete-icon click with `openMoreOptions()` followed by a click on the text "Delete movie". `loading_hidesEditAndDeleteActions` and `notFound_hidesEditAndDeleteActions` also assert that "More options" does not exist. Run: the updated tests fail (there's no "More options").
- [ ] **Step 2: Implement.** `actions = { if (uiState is MovieDetailUiState.Ready) DetailTopBarActions(editContentDescription = stringResource(R.string.edit_movie_content_description), onEdit = onNavigateToEditMovie, secondary = emptyList(), deleteLabel = stringResource(R.string.movie_detail_delete), onDelete = { showDeleteConfirmation = true }) }`. Remove unused imports.
- [ ] **Step 3: Run the class**, PASS. **Commit**, then add evidence.

### Task 3: TV

**Files:** `TVShowDetailScreen.kt` (`actions = {` ~line 478), `TVShowDetailScreenTest.kt` (~279-352).

- [ ] **Step 1: Update the tests first.**
  - Rename `deleteAction_isShownEvenDuringLoading_unlikeMovieAndBook` to `loading_hidesOverflowMenu`. It asserts that "More options" and "Delete show" do not exist while loading.
  - The delete tests open ⋮ first.
  - `refreshAction_presentWhenCanRefreshMetadata_…` opens ⋮, then clicks the "Refresh episode details from TMDB" item.
  - `refreshAction_absentWhenCannotRefreshMetadata` opens ⋮ and asserts the item doesn't exist, while "Delete show" does (positive control).
  - New test `refreshAction_disabledWhileRefreshing`.

  Run: the updated tests fail.
- [ ] **Step 2: Implement.** `actions = { if (uiState is TVShowDetailUiState.Ready) DetailTopBarActions(…tv_show_detail_edit, onNavigateToEdit, secondary = if (uiState.canRefreshMetadata) listOf(DetailMenuAction(label = stringResource(R.string.tv_show_detail_refresh_metadata), onClick = onRefreshMetadata, enabled = !uiState.isRefreshingMetadata, inProgress = uiState.isRefreshingMetadata)) else emptyList(), deleteLabel = …tv_show_detail_delete, onDelete = { showDeleteConfirmation = true }) }`. Keep the old comments' reasoning: Edit isn't gated on refresh, and refresh only appears with a TMDB id.
- [ ] **Step 3: Run the class**, PASS. The Task 1 mutation in `TVShowDetailScreenTest` (`refreshAction_absentWhenCannotRefreshMetadata`) still applies: drop the `canRefreshMetadata` condition, see it fail, then restore it. **Commit**, then add evidence.

### Task 4: Book

**Files:** `BookDetailScreen.kt` (`actions = {`), `BookDetailScreenTest.kt` (~160-260, 516-570), and `strings.xml` (remove `book_detail_overflow_menu_content_description` if unused afterwards; grep `app/src` first).

- [ ] **Step 1: Update the tests first.**
  - The delete tests open ⋮ and then click "Delete book".
  - `overflowMenu_refetchItem_*` find ⋮ by "More options".
  - `overflowMenu_refetchItem_disabledAndExplainsWhenNoIsbn`: the item "Re-fetch cover" is disabled, and the no-ISBN explanation is shown.
  - Rewrite `refetchingCover_showsProgressInTopBar_insteadOfOverflowIcon` as `refetchingCover_keepsMoreOptions_withRefetchInProgress_andDeleteReachable`: "More options" exists; the refetch item is disabled and shows progress with "Re-fetching cover…"; clicking "Delete book" opens the dialog.

  Run: the updated tests fail.
- [ ] **Step 2: Implement.** Drop the spinner-replaces-⋮ branch and the local `showOverflowMenu` state. `secondary = listOf(DetailMenuAction(label = if (isRefetchingCover) "Re-fetching cover…" else "Re-fetch cover", onClick = onRefetchCover, enabled = hasIsbn, supportingText = if (!hasIsbn) noIsbnText else null, inProgress = isRefetchingCover))`. Keep the #141 comment explaining why refetch lives here.
- [ ] **Step 3: Run the class**, PASS. **Commit**, then add evidence.

### Task 5: Goldens, docs, gate, device, PR

- [ ] **Goldens:** `./gradlew :app:recordRoborazziDebug`. Read each changed PNG under `app/src/test/screenshots/`: the three detail goldens and MovieDetailHistory. Expect a new ⋮ and the Delete icon gone, with nothing else changed. Commit them alone: `Goldens: detail top bars (#172)`.
- [ ] **CHANGELOG** `### Changed`: "The film, show and book screens now share one top bar: Edit, then a More options menu holding the screen's other actions, with Delete last. Delete is no longer offered before a show has loaded. (#172)"
- [ ] **Full gate**; then `connectedDebugAndroidTest` (39 tests), `seedDebugDevice`, and a hand check on the phone: open each detail screen's ⋮, check the order, and use Delete, then Cancel.
- [ ] **Fresh Opus review** with Review Focus, then **CodeRabbit**, then the **PR** (`Closes #172`). Move MT-32 to `review` with the PR URL, and write the outcomes file.

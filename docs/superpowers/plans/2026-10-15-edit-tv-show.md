# Edit a TV Show (#173) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user correct a TV show's title, release year, total seasons and purchase price from an Edit screen reached from the show's detail top bar.

**Architecture:** The repository write already exists (`TVShowRepository.updateShowMetadata` → `TVWriteDao.updateShowMetadataAtomically`); it is narrowed so it no longer writes `status`. A new `EditTVShowViewModel` (shared) and stateless `EditTVShowScreen` (app) mirror `EditMovie*`. A new `Route.EditTVShow` is reached from an Edit icon placed first in `TVShowDetailScreen`'s top bar, shown only in `Ready`.

**Tech Stack:** Kotlin Multiplatform, Room KMP, Jetpack Compose (Material 3), kotlinx-coroutines-test, Robolectric + Roborazzi (`app/src/test`), Compose instrumented tests (`app/src/androidTest`).

**Spec:** `docs/superpowers/specs/2026-10-15-edit-tv-show-design.md`

## Global Constraints

- Branch: `feat/173-edit-tv-show` (already created; the spec is committed on it).
- Editable fields: **title, release year, total seasons, purchase price**. No status, synopsis, airing status, air dates, rating or cover.
- The edit save must **never write `tv_details.status`**.
- A typed value always survives Refresh (`COALESCE`); a cleared field may be refilled by Refresh. Accepted, documented, not prevented.
- Per-season episode counts stay in the season dialog; `totalSeasons` changes no episode rows.
- Room-touching tests go in `shared/src/jvmTest`. ViewModel tests await with `uiState.first { … }`, never read `.value` after an action.
- Write every test first and watch it fail before implementing.
- Any `Scaffold` holding a text field sets `contentWindowInsets = WindowInsets.safeDrawing` (CI "Compose IME insets" check).
- Escape-sensitive edits: use the Write/Edit tools, not Git Bash heredocs (they mangle backslashes and apostrophes on this machine).
- Gate before every push: `./gradlew ktlintFormat` then
  `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`.
- Never merge, tag or release without the owner's explicit go-ahead.

## Review Focus

1. **A show that was abandoned, then edited** — must stay abandoned (Task 1 test `updateShowMetadata_leavesAnAbandonedShowAbandoned`).
2. **Total seasons set below the number of season rows** — no episode rows may be deleted (Task 1 test `updateShowMetadata_lowerTotalSeasons_deletesNoEpisodes`).
3. **Show deleted while its edit form is open** — save reports an error and the form stays (Task 2 test `save_showDeletedWhileEditing_reportsError`).
4. **Out-of-range values that parse** (total seasons `0`, year `1700`) — the repository refuses and the message reaches the user (Task 2 test `save_zeroTotalSeasons_surfacesTheRepositoryError`).
5. **A whitespace-only or padded title** — padded titles save trimmed; the Save button is disabled for a blank one (Task 2 test `save_trimsTheTitle`, Task 3 test `blankTitle_disablesSave`).

---

## File map

| File | Change |
| --- | --- |
| `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/TVWriteDao.kt` | `updateTVDetailFields` and `updateShowMetadataAtomically` drop `status` |
| `shared/src/commonMain/kotlin/com/hub/media/features/tv/data/TVShowRepository.kt` | `updateShowMetadata` drops `status`; KDoc for the edit-vs-Refresh rule |
| `shared/src/jvmTest/kotlin/com/hub/media/features/tv/data/TVShowRepositoryTest.kt` | update 4 existing tests, add 5 |
| `shared/src/commonMain/kotlin/com/hub/media/ui/EditTVShowViewModel.kt` | **new** |
| `shared/src/jvmTest/kotlin/com/hub/media/ui/EditTVShowViewModelTest.kt` | **new** |
| `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreen.kt` | **new** route + stateless screen |
| `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/TestTags.kt` | add `EditTVShow` |
| `app/src/main/res/values/strings.xml` | add 4 strings |
| `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/insets/EditTVShowScreenOcclusionTest.kt` | **new** |
| `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreenTest.kt` | **new** |
| `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/ViewModelFactories.kt` | add `EditTVShowViewModelFactory` |
| `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/navigation/NavRoutes.kt` | add `Route.EditTVShow` |
| `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/navigation/AppNavigation.kt` | add composable, wire detail → edit |
| `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/TVShowDetailScreen.kt` | `onNavigateToEdit`, Edit action |
| `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/TVShowDetailEditActionTest.kt` | **new** Robolectric |
| `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/screens/TVShowDetailScreenTest.kt` | Edit action tests; fix stale comment |
| `app/src/test/screenshots/tv-show-detail*.png` | re-recorded (4 files) |
| `CHANGELOG.md`, `ROADMAP.md` | entries |

---

### Task 1: Narrow the show-metadata write so it never touches status

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/core/database/dao/TVWriteDao.kt` (around lines 155–240)
- Modify: `shared/src/commonMain/kotlin/com/hub/media/features/tv/data/TVShowRepository.kt` (around lines 767–800)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/features/tv/data/TVShowRepositoryTest.kt` (the `// ---- updateShowMetadata` block, ~line 1361)

**Interfaces:**
- Produces: `TVShowRepository.updateShowMetadata(mediaId: String, title: String, releaseYear: Int? = null, purchasePrice: Double? = null, totalSeasons: Int? = null): Resource<Unit>`
- Produces: `TVWriteDao.updateShowMetadataAtomically(mediaId: String, title: String, releaseYear: Int?, purchasePrice: Double?, totalSeasons: Int?): Int`

- [ ] **Step 1: Update the four existing tests to the new signature and add the new ones**

In the `updateShowMetadata` block, remove every `status = …` argument from `repo.updateShowMetadata(...)` calls. In `updateShowMetadata_happyPath_updatesBothTables` replace
`assertEquals(WatchStatus.WATCHING, details?.status)` with
`assertEquals(WatchStatus.WATCHLIST, details?.status, "an edit leaves status as it was")`.
In `updateShowMetadata_detailsRowMissing_selfHealsRatherThanReportingAHalfWrite` replace
`assertEquals(WatchStatus.WATCHING, details?.status)` with
`assertEquals(WatchStatus.WATCHLIST, details?.status, "the repair invents only the meaningless default")`,
and in its comment change "totalSeasons/status used to go nowhere" to "totalSeasons used to go nowhere".
In `updateShowMetadata_unknownMediaId_returnsErrorAndWritesNothing` the call becomes
`repo.updateShowMetadata(mediaId = unknownId, title = "Any Title")`.

Then add, directly before `// ---- updateWatchStatus`:

```kotlin
    @Test
    fun updateShowMetadata_leavesAnAbandonedShowAbandoned() =
        runTest {
            // The edit form has no status control (#173): abandoning is the detail screen's toggle.
            // So the save must not write the column at all, or an edit would silently un-abandon.
            val mediaId = (repo.addShow(title = "Lost", releaseYear = 2004) as Resource.Success).data
            assertIs<Resource.Success<Unit>>(repo.updateWatchStatus(mediaId, WatchStatus.ABANDONED))

            val result = repo.updateShowMetadata(mediaId = mediaId, title = "LOST", releaseYear = 2004)
            assertIs<Resource.Success<Unit>>(result)

            assertEquals("LOST", db.mediaItemDao().getById(mediaId)?.title, "positive control: the edit landed")
            assertEquals(WatchStatus.ABANDONED, db.tvDetailsDao().getByMediaId(mediaId)?.status)
        }

    @Test
    fun updateShowMetadata_leavesProviderOnlyFieldsUntouched() =
        runTest {
            val firstAired = Instant.fromEpochMilliseconds(1_200_000_000_000L)
            val lastAired = Instant.fromEpochMilliseconds(1_300_000_000_000L)
            val mediaId =
                (
                    repo.addShow(
                        title = "Breaking Bad",
                        releaseYear = 2008,
                        synopsis = "A chemistry teacher.",
                        airingStatus = AiringStatus.ENDED,
                        firstAirDate = firstAired,
                        lastAirDate = lastAired,
                        communityRating = 8.9,
                    ) as Resource.Success
                ).data

            assertIs<Resource.Success<Unit>>(
                repo.updateShowMetadata(mediaId = mediaId, title = "Breaking Bad (2008)", releaseYear = 2008),
            )

            val item = db.mediaItemDao().getById(mediaId)
            assertEquals("Breaking Bad (2008)", item?.title, "positive control: the edit landed")
            assertEquals("A chemistry teacher.", item?.synopsis)
            assertEquals(8.9, item?.communityRating)
            val details = db.tvDetailsDao().getByMediaId(mediaId)
            assertEquals(AiringStatus.ENDED, details?.airingStatus)
            assertEquals(firstAired.toEpochMilliseconds(), details?.firstAirDate)
            assertEquals(lastAired.toEpochMilliseconds(), details?.lastAirDate)
        }

    @Test
    fun updateShowMetadata_editedYearSurvivesALaterRefresh() =
        runTest {
            // "A typed value always wins" (#173's spec): Refresh fills only null columns.
            val mediaId = (repo.addShow(title = "Dark", releaseYear = 2016) as Resource.Success).data
            assertIs<Resource.Success<Unit>>(repo.updateShowMetadata(mediaId = mediaId, title = "Dark", releaseYear = 2017))

            db.tvWriteDao().fillShowMetadata(
                mediaId = mediaId,
                releaseYear = 2016,
                communityRating = null,
                totalSeasons = null,
                airingStatus = null,
                synopsis = null,
                firstAirDate = null,
                lastAirDate = null,
            )

            assertEquals(2017, db.mediaItemDao().getById(mediaId)?.releaseYear)
        }

    @Test
    fun updateShowMetadata_clearedYearIsRefilledByALaterRefresh() =
        runTest {
            // The accepted half of the same rule, pinned so it is documented rather than discovered:
            // blank means "unknown", and Refresh may fill an unknown. Changing this needs a schema change.
            val mediaId = (repo.addShow(title = "Dark", releaseYear = 2016) as Resource.Success).data
            assertIs<Resource.Success<Unit>>(repo.updateShowMetadata(mediaId = mediaId, title = "Dark", releaseYear = null))
            assertNull(db.mediaItemDao().getById(mediaId)?.releaseYear, "precondition: the year was cleared")

            db.tvWriteDao().fillShowMetadata(
                mediaId = mediaId,
                releaseYear = 2016,
                communityRating = null,
                totalSeasons = null,
                airingStatus = null,
                synopsis = null,
                firstAirDate = null,
                lastAirDate = null,
            )

            assertEquals(2016, db.mediaItemDao().getById(mediaId)?.releaseYear)
        }

    @Test
    fun updateShowMetadata_lowerTotalSeasons_deletesNoEpisodes() =
        runTest {
            // totalSeasons is advisory (TVDetailsEntity KDoc); the season dialog owns episode rows.
            val mediaId =
                (
                    repo.addShow(
                        title = "Fargo",
                        totalSeasons = 2,
                        seasons = listOf(SeasonQuickFill(seasonNumber = 1, episodeCount = 3), SeasonQuickFill(seasonNumber = 2, episodeCount = 2)),
                    ) as Resource.Success
                ).data

            assertIs<Resource.Success<Unit>>(repo.updateShowMetadata(mediaId = mediaId, title = "Fargo", totalSeasons = 1))

            assertEquals(1, db.tvDetailsDao().getByMediaId(mediaId)?.totalSeasons)
            assertEquals(5, repo.observeEpisodes(mediaId).first().size)
        }
```

`SeasonQuickFill` is already imported by this test file (used by the `addShow` quick-fill tests).

- [ ] **Step 2: Run the tests and watch them fail**

Run: `./gradlew :shared:jvmTest --tests "com.hub.media.features.tv.data.TVShowRepositoryTest"`
Expected: COMPILATION FAILURE. The updated calls no longer pass the required `status`. That is the red for the signature change. (The abandoned-show test is the behavioural red. Once Step 3 compiles, it fails only if status is still written.)

- [ ] **Step 3: Implement — DAO**

In `TVWriteDao.kt` replace `updateTVDetailFields` with:

```kotlin
    /**
     * The `tv_details` half of [updateShowMetadataAtomically]: `totalSeasons` only.
     *
     * `status` is deliberately absent (#173). The edit form has no status control -- abandoning is
     * the detail screen's toggle via [updateStatusFields] -- so writing it here could only ever put
     * back a value read when the form opened, silently undoing an abandon made since.
     */
    @Query("UPDATE tv_details SET totalSeasons = :totalSeasons WHERE mediaId = :mediaId")
    suspend fun updateTVDetailFields(
        mediaId: String,
        totalSeasons: Int?,
    ): Int
```

and replace the body and signature of `updateShowMetadataAtomically` with:

```kotlin
    @Transaction
    suspend fun updateShowMetadataAtomically(
        mediaId: String,
        title: String,
        releaseYear: Int?,
        purchasePrice: Double?,
        totalSeasons: Int?,
    ): Int {
        val mediaRows = updateMediaItemFields(mediaId, title, releaseYear, purchasePrice)
        if (mediaRows > 0) {
            val detailRows = updateTVDetailFields(mediaId, totalSeasons)
            if (detailRows == 0) {
                // The default WATCHLIST is the one status that means nothing for a show (only
                // ABANDONED is read), so this repair invents no state a user could see.
                insertTVDetails(TVDetailsEntity(mediaId = mediaId, totalSeasons = totalSeasons))
            }
        }
        return mediaRows
    }
```

In that function's KDoc change "`totalSeasons`/`status` would go nowhere" to "`totalSeasons` would go nowhere".

- [ ] **Step 4: Implement — repository**

In `TVShowRepository.kt` replace `updateShowMetadata` with:

```kotlin
    /**
     * Saves the Edit screen's fields (#173): title, release year, purchase price, total seasons.
     *
     * Never writes status -- see [com.hub.media.core.database.dao.TVWriteDao.updateTVDetailFields].
     *
     * ### Against a later Refresh
     * Refresh fills only `null` columns (`COALESCE`, [com.hub.media.core.database.dao.TVWriteDao.fillShowMetadata]),
     * so a value typed here always survives it. A field cleared here is `null` = unknown, and the
     * next Refresh may fill it again. That is accepted rather than prevented: pinning a cleared
     * field would need a schema change for a case nobody has asked for.
     */
    public suspend fun updateShowMetadata(
        mediaId: String,
        title: String,
        releaseYear: Int? = null,
        purchasePrice: Double? = null,
        totalSeasons: Int? = null,
    ): Resource<Unit> {
        TVMetadataValidation.validateTitle(title)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateReleaseYear(releaseYear)?.let { return Resource.Error(it) }
        TVMetadataValidation.validatePurchasePrice(purchasePrice)?.let { return Resource.Error(it) }
        TVMetadataValidation.validateTotalSeasons(totalSeasons)?.let { return Resource.Error(it) }

        return try {
            val rows =
                db.tvWriteDao().updateShowMetadataAtomically(
                    mediaId = mediaId,
                    title = title,
                    releaseYear = releaseYear,
                    purchasePrice = purchasePrice,
                    totalSeasons = totalSeasons,
                )
            if (rows == 0) Resource.Error("TV show with id=$mediaId not found") else Resource.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(TAG, e) { "Failed to update metadata for show: id=$mediaId" }
            Resource.Error("Failed to update show metadata: ${e.message ?: "Unknown error"}", cause = e)
        }
    }
```

In `updateWatchStatus`'s KDoc, change "re-sending title/year/price/totalSeasons to change one column" to "re-sending title/year/price/totalSeasons to change status". Leave the rest.

- [ ] **Step 5: Run and watch it pass**

Run: `./gradlew :shared:jvmTest --tests "com.hub.media.features.tv.data.TVShowRepositoryTest"`
Expected: PASS.

Then **falsify the abandoned test**. Temporarily add `, status = 'WATCHLIST'` to `updateTVDetailFields`'s SQL SET list and re-run. `updateShowMetadata_leavesAnAbandonedShowAbandoned` must FAIL. Revert the change and re-run until it passes.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hub/media/core/database/dao/TVWriteDao.kt shared/src/commonMain/kotlin/com/hub/media/features/tv/data/TVShowRepository.kt shared/src/jvmTest/kotlin/com/hub/media/features/tv/data/TVShowRepositoryTest.kt
git commit -m "Show edit save no longer writes status (#173)"
```

---

### Task 2: EditTVShowViewModel

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/ui/EditTVShowViewModel.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/EditTVShowViewModelTest.kt`

**Interfaces:**
- Consumes: `TVShowRepository.updateShowMetadata(mediaId, title, releaseYear, purchasePrice, totalSeasons): Resource<Unit>` (Task 1); `TVShowRepository.observeShowDetail(id): Flow<MediaWithDetails.TVShow?>`; `parseOptionalNumber` / `ParsedNumber.value` from `com.hub.media.ui.NumericFormField`.
- Produces:
  ```kotlin
  sealed class EditTVShowUiState {
      data object Loading; data object NotFound
      data class Editing(title: String, releaseYear: String, totalSeasons: String, purchasePrice: String,
                         isSaving: Boolean = false, saveError: String? = null, saved: Boolean = false)
  }
  class EditTVShowViewModel(showId: String, tvShowRepository: TVShowRepository) : ViewModel {
      val uiState: StateFlow<EditTVShowUiState>
      fun onTitleChange(String); fun onReleaseYearChange(String); fun onTotalSeasonsChange(String)
      fun onPurchasePriceChange(String); fun save(); fun consumeSaveError()
  }
  ```

- [ ] **Step 1: Write the failing tests**

Create `EditTVShowViewModelTest.kt`:

```kotlin
package com.hub.media.ui

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.testAppDatabase
import com.hub.media.core.util.Resource
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [EditTVShowViewModel] against a real in-memory [AppDatabase] (#173), in the shape of
 * [EditMovieViewModelTest]. Room-backed, so `jvmTest`. Every assertion after an action awaits the
 * state it expects with `uiState.first { }` rather than reading `.value` (AGENTS.md section 7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditTVShowViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TVShowRepository
    private val viewModels = ViewModelRegistry()

    @BeforeTest
    fun setUp() {
        viewModels.installMain()
        db = testAppDatabase()
        repo = TVShowRepository(db)
    }

    @AfterTest
    fun tearDown() {
        viewModels.clearAll()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun insertShow(
        title: String = "Chernobyl",
        releaseYear: Int? = 2019,
        totalSeasons: Int? = 1,
        purchasePrice: Double? = 19.99,
    ): String {
        val result = repo.addShow(title = title, releaseYear = releaseYear, totalSeasons = totalSeasons, purchasePrice = purchasePrice)
        assertIs<Resource.Success<String>>(result)
        return result.data
    }

    private suspend fun editing(showId: String): EditTVShowViewModel {
        val vm = viewModels.track(EditTVShowViewModel(showId, repo))
        vm.uiState.first { it is EditTVShowUiState.Editing }
        return vm
    }

    private suspend fun EditTVShowViewModel.awaitForm(predicate: (EditTVShowUiState.Editing) -> Boolean) =
        uiState.first { it is EditTVShowUiState.Editing && predicate(it) } as EditTVShowUiState.Editing

    @Test
    fun load_prefillsTheShowsCurrentValues() =
        runTest {
            val id = insertShow(title = "Chernobyl", releaseYear = 2019, totalSeasons = 1, purchasePrice = 19.99)

            val form = editing(id).awaitForm { true }

            assertEquals("Chernobyl", form.title)
            assertEquals("2019", form.releaseYear)
            assertEquals("1", form.totalSeasons)
            assertEquals("19.99", form.purchasePrice)
        }

    @Test
    fun load_unknownNumbersAreBlankNotNullOrZero() =
        runTest {
            val id = insertShow(releaseYear = null, totalSeasons = null, purchasePrice = null)

            val form = editing(id).awaitForm { true }

            assertEquals("", form.releaseYear)
            assertEquals("", form.totalSeasons)
            assertEquals("", form.purchasePrice)
        }

    @Test
    fun load_unknownShow_isNotFound() =
        runTest {
            val vm = viewModels.track(EditTVShowViewModel("no-such-show", repo))

            assertIs<EditTVShowUiState.NotFound>(vm.uiState.first { it !is EditTVShowUiState.Loading })
        }

    @Test
    fun save_happyPath_persistsAndMarksSaved() =
        runTest {
            val id = insertShow(title = "Chernobil", releaseYear = 2019, totalSeasons = 1)
            val vm = editing(id)

            vm.onTitleChange("Chernobyl")
            vm.onTotalSeasonsChange("2")
            vm.onPurchasePriceChange("9.99")
            vm.save()
            vm.awaitForm { it.saved }

            val item = db.mediaItemDao().getById(id)
            assertEquals("Chernobyl", item?.title)
            assertEquals(9.99, item?.purchasePrice)
            assertEquals(2, db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_trimsTheTitle() =
        runTest {
            val id = insertShow()
            val vm = editing(id)

            vm.onTitleChange("  Chernobyl  ")
            vm.save()
            vm.awaitForm { it.saved }

            assertEquals("Chernobyl", db.mediaItemDao().getById(id)?.title)
        }

    @Test
    fun save_clearedNumbers_saveAsUnknown() =
        runTest {
            val id = insertShow(releaseYear = 2019, totalSeasons = 1, purchasePrice = 19.99)
            val vm = editing(id)

            vm.onReleaseYearChange("")
            vm.onTotalSeasonsChange("")
            vm.onPurchasePriceChange("")
            vm.save()
            vm.awaitForm { it.saved }

            val item = db.mediaItemDao().getById(id)
            assertNull(item?.releaseYear)
            assertNull(item?.purchasePrice)
            assertNull(db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_unreadableTotalSeasons_isRefusedAndTheStoredValueKept() =
        runTest {
            // Digits too long for Int parse to nothing; sending that as null would erase the count.
            val id = insertShow(totalSeasons = 3)
            val vm = editing(id)

            vm.onTotalSeasonsChange("99999999999")
            vm.save()
            val form = vm.awaitForm { it.saveError != null }

            assertEquals("Enter a valid total seasons, or clear the field", form.saveError)
            assertEquals(false, form.saved)
            assertEquals(3, db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_unreadableYear_isRefused() =
        runTest {
            val id = insertShow(releaseYear = 2019)
            val vm = editing(id)

            vm.onReleaseYearChange("19999999999")
            vm.save()
            val form = vm.awaitForm { it.saveError != null }

            assertEquals("Enter a valid release year, or clear the field", form.saveError)
            assertEquals(2019, db.mediaItemDao().getById(id)?.releaseYear)
        }

    @Test
    fun save_zeroTotalSeasons_surfacesTheRepositoryError() =
        runTest {
            // Parses fine, so the ViewModel sends it; TVMetadataValidation is what refuses it.
            val id = insertShow(totalSeasons = 2)
            val vm = editing(id)

            vm.onTotalSeasonsChange("0")
            vm.save()
            val form = vm.awaitForm { it.saveError != null && !it.isSaving }

            assertEquals(false, form.saved)
            assertEquals("0", form.totalSeasons, "the form keeps what was typed")
            assertEquals(2, db.tvDetailsDao().getByMediaId(id)?.totalSeasons)
        }

    @Test
    fun save_showDeletedWhileEditing_reportsError() =
        runTest {
            val id = insertShow()
            val vm = editing(id)
            db.mediaItemDao().deleteById(id)

            vm.onTitleChange("Gone")
            vm.save()
            val form = vm.awaitForm { it.saveError != null && !it.isSaving }

            assertEquals(false, form.saved)
            assertNotNull(form.saveError)
        }

    @Test
    fun consumeSaveError_clearsIt() =
        runTest {
            val id = insertShow()
            val vm = editing(id)
            vm.onReleaseYearChange("19999999999")
            vm.save()
            vm.awaitForm { it.saveError != null }

            vm.consumeSaveError()

            vm.awaitForm { it.saveError == null }
        }

    @Test
    fun save_secondTapWhileTheFirstIsInFlight_isIgnored() =
        runTest {
            val id = insertShow(title = "Original")
            val vm = editing(id)
            // StandardTestDispatcher so the first save's launch only enqueues: the second tap is
            // then guaranteed to land while the first is in flight (AGENTS.md section 7).
            viewModels.installMain(StandardTestDispatcher(testScheduler))

            vm.onTitleChange("First")
            vm.save()
            vm.onTitleChange("Second")
            vm.save()
            runCurrent()
            vm.awaitForm { it.saved }

            assertEquals("First", db.mediaItemDao().getById(id)?.title)
        }
}
```

`MediaItemDao.deleteById(id)` exists; deleting the `media_items` row cascades to `tv_details`.

Note on the double-save test: `onTitleChange("Second")` is ignored while `isSaving`, because the screen disables the fields. That is why the guard is checked against the stored title. If `updateForm` does not refuse edits while saving, the test still holds, because only one write runs.

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew :shared:jvmTest --tests "com.hub.media.ui.EditTVShowViewModelTest"`
Expected: COMPILATION FAILURE — `EditTVShowViewModel` / `EditTVShowUiState` unresolved.

- [ ] **Step 3: Implement**

Create `shared/src/commonMain/kotlin/com/hub/media/ui/EditTVShowViewModel.kt`:

```kotlin
package com.hub.media.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hub.media.core.util.Resource
import com.hub.media.features.tv.data.TVShowRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** State of the edit-show form (#173). */
public sealed class EditTVShowUiState {
    /** The show's current values are still being read. */
    public data object Loading : EditTVShowUiState()

    /** No show with this id -- deleted, or never a show. */
    public data object NotFound : EditTVShowUiState()

    /**
     * The form, pre-filled with the show's current values. Numbers are the strings being edited,
     * for the reason [EditMovieUiState.Editing] gives: blank ("unknown") and an in-progress number
     * must both survive the round trip.
     *
     * There is no status here: abandoning is the detail screen's toggle, and the save never writes
     * status (see [TVShowRepository.updateShowMetadata]).
     */
    public data class Editing(
        val title: String,
        val releaseYear: String,
        val totalSeasons: String,
        val purchasePrice: String,
        val isSaving: Boolean = false,
        val saveError: String? = null,
        val saved: Boolean = false,
    ) : EditTVShowUiState()
}

/**
 * Drives correcting a TV show's title, year, total seasons and price (#173) -- the show counterpart
 * of [EditMovieViewModel]. A hand-entered show has no provider to correct a typo from, so this is
 * the only way to fix one.
 *
 * Values are loaded once, not observed: a form that re-read the database on every emission would
 * overwrite what the user is typing whenever anything else touched the row.
 */
public class EditTVShowViewModel(
    private val showId: String,
    private val tvShowRepository: TVShowRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<EditTVShowUiState>(EditTVShowUiState.Loading)
    public val uiState: StateFlow<EditTVShowUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val show = tvShowRepository.observeShowDetail(showId).first()
            _uiState.value =
                if (show == null) {
                    EditTVShowUiState.NotFound
                } else {
                    EditTVShowUiState.Editing(
                        title = show.item.title,
                        releaseYear = show.item.releaseYear?.toString().orEmpty(),
                        totalSeasons = show.details?.totalSeasons?.toString().orEmpty(),
                        purchasePrice = show.item.purchasePrice?.toString().orEmpty(),
                    )
                }
        }
    }

    public fun onTitleChange(value: String): Unit = updateForm { it.copy(title = value) }

    public fun onReleaseYearChange(value: String): Unit = updateForm { it.copy(releaseYear = value) }

    public fun onTotalSeasonsChange(value: String): Unit = updateForm { it.copy(totalSeasons = value) }

    public fun onPurchasePriceChange(value: String): Unit = updateForm { it.copy(purchasePrice = value) }

    /**
     * Writes the edited values. Blank numbers go as `null` ("unknown"); text that does not parse is
     * refused rather than sent as `null`, which would erase the value the user came to correct --
     * [EditMovieViewModel.save] explains the distinction. Range rules stay in
     * [com.hub.media.features.tv.data.TVMetadataValidation].
     */
    public fun save() {
        val form = _uiState.value as? EditTVShowUiState.Editing ?: return
        if (form.isSaving) return

        val releaseYear = parseOptionalNumber(form.releaseYear, String::toIntOrNull)
        val totalSeasons = parseOptionalNumber(form.totalSeasons, String::toIntOrNull)
        val purchasePrice = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull)
        if (releaseYear == null || totalSeasons == null || purchasePrice == null) {
            val unreadableField =
                when {
                    releaseYear == null -> "release year"
                    totalSeasons == null -> "total seasons"
                    else -> "purchase price"
                }
            _uiState.value = form.copy(saveError = "Enter a valid $unreadableField, or clear the field")
            return
        }

        _uiState.value = form.copy(isSaving = true, saveError = null)
        viewModelScope.launch {
            val result =
                tvShowRepository.updateShowMetadata(
                    mediaId = showId,
                    title = form.title.trim(),
                    releaseYear = releaseYear.value,
                    purchasePrice = purchasePrice.value,
                    totalSeasons = totalSeasons.value,
                )
            val current = _uiState.value as? EditTVShowUiState.Editing ?: return@launch
            _uiState.value =
                when (result) {
                    is Resource.Success -> current.copy(isSaving = false, saved = true)
                    is Resource.Error -> current.copy(isSaving = false, saveError = result.message)
                }
        }
    }

    /** Acknowledges a save error once shown, so it is not repeated on the next recomposition. */
    public fun consumeSaveError(): Unit = updateForm { it.copy(saveError = null) }

    private inline fun updateForm(transform: (EditTVShowUiState.Editing) -> EditTVShowUiState.Editing) {
        val current = _uiState.value as? EditTVShowUiState.Editing ?: return
        // Refused while saving: the save already captured the form, so a change now would be shown
        // as saved without having been.
        if (current.isSaving) return
        _uiState.value = transform(current)
    }
}
```

Caution: the `isSaving` guard in `updateForm` would also block `consumeSaveError`, but an error only exists once saving has finished, so that is fine.

- [ ] **Step 4: Run and watch it pass**

Run: `./gradlew :shared:jvmTest --tests "com.hub.media.ui.EditTVShowViewModelTest"`
Expected: PASS.

Then falsify the double-save test. Comment out `if (form.isSaving) return` in `save()` and re-run: `save_secondTapWhileTheFirstIsInFlight_isIgnored` must FAIL. Restore the line.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/hub/media/ui/EditTVShowViewModel.kt shared/src/jvmTest/kotlin/com/hub/media/ui/EditTVShowViewModelTest.kt
git commit -m "EditTVShowViewModel: load once, save title/year/seasons/price (#173)"
```

---

### Task 3: EditTVShowScreen (stateless) with occlusion and instrumented tests

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreen.kt` (stateless screen only in this task; the route is added in Task 4)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/TestTags.kt` (after `object EditMovie`, line ~60)
- Modify: `app/src/main/res/values/strings.xml` (after `edit_movie_content_description`, line ~132)
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/insets/EditTVShowScreenOcclusionTest.kt`
- Test: `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreenTest.kt`

**Interfaces:**
- Consumes: `EditTVShowUiState` (Task 2).
- Produces:
  ```kotlin
  @Composable fun EditTVShowScreen(uiState: EditTVShowUiState, onTitleChange: (String) -> Unit,
      onReleaseYearChange: (String) -> Unit, onTotalSeasonsChange: (String) -> Unit,
      onPurchasePriceChange: (String) -> Unit, onSave: () -> Unit, onErrorShown: () -> Unit,
      onNavigateBack: () -> Unit)
  TestTags.EditTVShow.FORM = "editTVShow:form"; TestTags.EditTVShow.SAVE_BUTTON = "editTVShow:save"
  R.string.edit_tv_show_title = "Edit show"; R.string.edit_tv_show_save = "Save changes"
  R.string.tv_show_detail_edit = "Edit show"
  ```

- [ ] **Step 1: Add the tags and strings (no behaviour; needed to compile the tests)**

`TestTags.kt`, after the `EditMovie` object:

```kotlin
    object EditTVShow {
        const val FORM = "editTVShow:form"
        const val SAVE_BUTTON = "editTVShow:save"
    }
```

`strings.xml`, after `edit_movie_content_description`:

```xml
    <string name="edit_tv_show_title">Edit show</string>
    <string name="edit_tv_show_save">Save changes</string>
    <string name="tv_show_detail_edit">Edit show</string>
```

- [ ] **Step 2: Write the failing occlusion test**

Create `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/insets/EditTVShowScreenOcclusionTest.kt`:

```kotlin
package com.github.maskedkunisquat.mediatracker.ui.insets

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.maskedkunisquat.mediatracker.ui.TestTags
import com.github.maskedkunisquat.mediatracker.ui.screens.EditTVShowScreen
import com.hub.media.ui.EditTVShowUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Occlusion guard for the Edit Show form (#173), shaped like [EditMovieScreenOcclusionTest] and
 * for the same reasons: [EditTVShowUiState.Editing] with every field filled (Loading renders no
 * fields and would pass regardless), measured at the end of the one scroll container, and no
 * navigation-bar test because this short form never reaches the bar.
 */
@RunWith(RobolectricTestRunner::class)
class EditTVShowScreenOcclusionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun withTheKeyboardUp_theFormStaysReachable() {
        composeRule.assertNoInteractiveNodeIsBehindTheKeyboard(expectedTags = TAGS) { Fixture() }
    }

    @Composable
    private fun Fixture() {
        EditTVShowScreen(
            uiState =
                EditTVShowUiState.Editing(
                    title = "Chernobyl",
                    releaseYear = "2019",
                    totalSeasons = "1",
                    purchasePrice = "19.99",
                ),
            onTitleChange = {},
            onReleaseYearChange = {},
            onTotalSeasonsChange = {},
            onPurchasePriceChange = {},
            onSave = {},
            onErrorShown = {},
            onNavigateBack = {},
        )
    }

    private companion object {
        val TAGS = listOf(TestTags.EditTVShow.FORM, TestTags.EditTVShow.SAVE_BUTTON)
    }
}
```

- [ ] **Step 3: Write the failing instrumented test**

Create `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreenTest.kt`:

```kotlin
package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.mediatracker.R
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.ui.EditTVShowUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Behavioural tests for the Edit Show screen (#173), driving the stateless [EditTVShowScreen] the
 * way [EditMovieScreenTest] drives the film form. Both halves of the save control are asserted:
 * a form that never enabled Save would pass a disabled-only check while being useless.
 */
class EditTVShowScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The Save button: a Button with no content description, unlike the top bar's back icon. */
    private val saveButtonMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    private fun editing(
        title: String = "Chernobyl",
        releaseYear: String = "2019",
        totalSeasons: String = "1",
        purchasePrice: String = "19.99",
        saveError: String? = null,
    ) = EditTVShowUiState.Editing(
        title = title,
        releaseYear = releaseYear,
        totalSeasons = totalSeasons,
        purchasePrice = purchasePrice,
        saveError = saveError,
    )

    private fun setContent(
        uiState: EditTVShowUiState,
        onSave: () -> Unit = {},
        onErrorShown: () -> Unit = {},
        onTotalSeasonsChange: (String) -> Unit = {},
        onPurchasePriceChange: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaTrackerTheme {
                EditTVShowScreen(
                    uiState = uiState,
                    onTitleChange = {},
                    onReleaseYearChange = {},
                    onTotalSeasonsChange = onTotalSeasonsChange,
                    onPurchasePriceChange = onPurchasePriceChange,
                    onSave = onSave,
                    onErrorShown = onErrorShown,
                    onNavigateBack = {},
                )
            }
        }
    }

    @Test
    fun aValidForm_canBeSaved_andTappingSaveInvokesOnSave() {
        var saves = 0
        setContent(editing(), onSave = { saves++ })

        composeRule.onNode(saveButtonMatcher).assertIsEnabled().performClick()

        assertEquals(1, saves)
    }

    @Test
    fun blankTitle_disablesSave() {
        setContent(editing(title = "   "))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
    }

    @Test
    fun clearedNumbers_canStillBeSaved() {
        setContent(editing(releaseYear = "", totalSeasons = "", purchasePrice = ""))

        composeRule.onNode(saveButtonMatcher).assertIsEnabled()
    }

    @Test
    fun unreadableTotalSeasons_disablesSave_andSaysWhy() {
        setContent(editing(totalSeasons = "99999999999"))

        composeRule.onNode(saveButtonMatcher).assertIsNotEnabled()
        composeRule
            .onNodeWithText(context.getString(R.string.add_tv_show_total_seasons_invalid_error))
            .assertExists()
    }

    @Test
    fun typingIntoTotalSeasons_forwardsDigitsOnly() {
        var captured: String? = null
        setContent(editing(totalSeasons = ""), onTotalSeasonsChange = { captured = it })

        val label = context.getString(R.string.add_tv_show_field_total_seasons)
        composeRule.onNode(hasText(label) and hasSetTextAction()).performTextInput("3a")

        assertEquals("3", captured)
    }

    @Test
    fun typingIntoThePriceField_forwardsFilteredText() {
        var captured: String? = null
        setContent(editing(purchasePrice = ""), onPurchasePriceChange = { captured = it })

        val label = context.getString(R.string.add_tv_show_field_price)
        composeRule.onNode(hasText(label) and hasSetTextAction()).performTextInput("1,499.50")

        assertEquals("1499.50", captured)
    }

    @Test
    fun saveError_isShownInASnackbar_andAcknowledged() {
        var acknowledged = 0
        setContent(editing(saveError = "Seasons must be positive"), onErrorShown = { acknowledged++ })

        composeRule.onNodeWithText("Seasons must be positive").assertExists()
        composeRule.waitUntil(timeoutMillis = 10_000) { acknowledged == 1 }
    }
}
```

Before running, check how `EditMovieScreenTest` asserts its snackbar test. If it uses a different wait idiom, copy that one instead of `waitUntil`.

- [ ] **Step 4: Run the occlusion test and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*EditTVShowScreenOcclusionTest"`
Expected: COMPILATION FAILURE — `EditTVShowScreen` unresolved.

- [ ] **Step 5: Implement the stateless screen**

Create `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreen.kt`. Use `EditMovieScreen.kt`'s imports, minus `FilterChip`, `MaterialTheme`, `FilmWatchedDialog`, `WatchStatus`, `MovieMetadataValidation`, `EditMovie*` and the route-only `viewModel`/`collectAsStateWithLifecycle`/`AppContainer`/`EditMovieViewModelFactory`/`getValue`. Add `com.hub.media.features.tv.data.TVMetadataValidation` and `com.hub.media.ui.EditTVShowUiState`:

```kotlin
/**
 * Stateless edit-show form (#173): title, release year, total seasons, purchase price.
 *
 * No status control -- abandoning is the detail screen's toggle -- and no per-season episode
 * counts, which stay in the season dialog. Shaped like [EditMovieScreen] so #174's restyle finds
 * the same structure on every edit screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTVShowScreen(
    uiState: EditTVShowUiState,
    onTitleChange: (String) -> Unit,
    onReleaseYearChange: (String) -> Unit,
    onTotalSeasonsChange: (String) -> Unit,
    onPurchasePriceChange: (String) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val form = uiState as? EditTVShowUiState.Editing

    LaunchedEffect(form?.saveError) {
        val message = form?.saveError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onErrorShown()
        }
    }

    Scaffold(
        // Scaffold's default insets don't cover the IME; without safeDrawing the keyboard would sit
        // over these fields instead of the layout resizing (same as EditMovieScreen).
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_tv_show_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(scrollingContentPadding(innerPadding, PaddingValues(16.dp)))
                    .testTag(TestTags.EditTVShow.FORM),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (form == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            // Blank = "unknown" and saves; unparseable blocks Save (EditTVShowViewModel refuses it
            // too -- this is what says which field). Range rules stay in TVMetadataValidation.
            val releaseYearIsValid = parseOptionalNumber(form.releaseYear, String::toIntOrNull) != null
            val totalSeasonsIsValid = parseOptionalNumber(form.totalSeasons, String::toIntOrNull) != null
            val purchasePriceIsValid = parseOptionalNumber(form.purchasePrice, String::toDoubleOrNull) != null

            OutlinedTextField(
                value = form.title,
                onValueChange = onTitleChange,
                label = { Text(stringResource(R.string.add_tv_show_field_title)) },
                singleLine = true,
                isError = form.title.isBlank(),
                enabled = !form.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.releaseYear,
                onValueChange = { onReleaseYearChange(it.filterIntegerInput()) },
                label = { Text(stringResource(R.string.add_tv_show_field_year)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !releaseYearIsValid,
                supportingText =
                    if (!releaseYearIsValid) {
                        {
                            Text(
                                stringResource(
                                    R.string.edit_release_year_invalid_error,
                                    TVMetadataValidation.MIN_RELEASE_YEAR,
                                    TVMetadataValidation.MAX_RELEASE_YEAR,
                                ),
                            )
                        }
                    } else {
                        null
                    },
                enabled = !form.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.totalSeasons,
                onValueChange = { onTotalSeasonsChange(it.filterIntegerInput()) },
                label = { Text(stringResource(R.string.add_tv_show_field_total_seasons)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = !totalSeasonsIsValid,
                supportingText =
                    if (!totalSeasonsIsValid) {
                        { Text(stringResource(R.string.add_tv_show_total_seasons_invalid_error)) }
                    } else {
                        null
                    },
                enabled = !form.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.purchasePrice,
                onValueChange = { onPurchasePriceChange(it.filterDecimalInput()) },
                label = { Text(stringResource(R.string.add_tv_show_field_price)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = !purchasePriceIsValid,
                supportingText =
                    if (!purchasePriceIsValid) {
                        { Text(stringResource(R.string.edit_purchase_price_invalid_error)) }
                    } else {
                        null
                    },
                enabled = !form.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSave,
                enabled =
                    form.title.isNotBlank() &&
                        releaseYearIsValid &&
                        totalSeasonsIsValid &&
                        purchasePriceIsValid &&
                        !form.isSaving,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.EditTVShow.SAVE_BUTTON),
            ) {
                if (form.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                } else {
                    Text(stringResource(R.string.edit_tv_show_save))
                }
            }
        }
    }
}
```

Check `TVMetadataValidation.MIN_RELEASE_YEAR`/`MAX_RELEASE_YEAR` exist under those names (`AddTVShowScreen.kt:212` uses them).

- [ ] **Step 6: Run the occlusion test and watch it pass; then falsify**

Run: `./gradlew :app:testDebugUnitTest --tests "*EditTVShowScreenOcclusionTest"`
Expected: PASS. Then delete the `contentWindowInsets = WindowInsets.safeDrawing,` line, re-run, and confirm it FAILS. Restore the line.

- [ ] **Step 7: Compile the instrumented test**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL. That task actually runs on a device in Task 5.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreen.kt app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/TestTags.kt app/src/main/res/values/strings.xml app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/insets/EditTVShowScreenOcclusionTest.kt app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreenTest.kt
git commit -m "EditTVShowScreen: stateless form with occlusion and instrumented tests (#173)"
```

---

### Task 4: Route, factory, navigation and the Edit action on the detail screen

**Files:**
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/EditTVShowScreen.kt` (add route wrapper at top)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/ViewModelFactories.kt` (after `EditMovieViewModelFactory`, ~line 362)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/navigation/NavRoutes.kt` (after `TVShowDetail`, ~line 76)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/navigation/AppNavigation.kt` (TVShowDetail composable, ~line 191–208)
- Modify: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/TVShowDetailScreen.kt` (route ~line 103, screen signature ~line 206, top bar actions ~line 474)
- Test: `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/TVShowDetailEditActionTest.kt` (new, Robolectric)
- Test: `app/src/androidTest/java/com/github/maskedkunisquat/mediatracker/ui/screens/TVShowDetailScreenTest.kt`
- Goldens: `app/src/test/screenshots/tv-show-detail*.png`

**Interfaces:**
- Consumes: `EditTVShowScreen(...)` (Task 3), `EditTVShowViewModel(showId, tvShowRepository)` (Task 2), `appContainer.tvShowRepository`.
- Produces: `Route.EditTVShow` with `ARG_SHOW_ID = "showId"`, `createRoute(showId)`; `TVShowDetailScreen(..., onNavigateToEdit: () -> Unit = {})`; `TVShowDetailScreenRoute(..., onNavigateToEdit: () -> Unit)`.

- [ ] **Step 1: Write the failing Robolectric test**

Create `app/src/test/java/com/github/maskedkunisquat/mediatracker/ui/screens/TVShowDetailEditActionTest.kt`. Copy the imports, the `show()`/`episode()` helpers and the `NO_COVERS` companion from `TVShowDetailRefreshActionTest.kt`, plus `assertIsEnabled`/`performClick`/`assertDoesNotExist`:

```kotlin
/**
 * The Edit action on the show detail screen (#173). Robolectric, so it runs in the required
 * `:app:testDebugUnitTest` gate. Edit is gated on Ready like films' (there is nothing to edit until
 * the show has loaded), unlike Delete, which stays reachable while loading.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class TVShowDetailEditActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aLoadedShowOffersEdit_andTappingItNavigates() {
        var edits = 0
        setContent(ready = true, onEdit = { edits++ })

        composeRule.onNodeWithContentDescription("Edit show").assertIsEnabled().performClick()

        assertEquals(1, edits)
    }

    @Test
    fun aHandEnteredShowOffersEditToo() {
        // The case #173 exists for: no TMDB record, so no Refresh -- Edit must not share its guard.
        setContent(ready = true, canRefresh = false)

        composeRule.onNodeWithContentDescription("Edit show").assertIsEnabled()
    }

    @Test
    fun whileLoading_editIsAbsent_butDeleteIsNot() {
        setContent(ready = false)

        composeRule.onNodeWithContentDescription("Edit show").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Delete show").assertExists()
    }

    private fun setContent(
        ready: Boolean,
        canRefresh: Boolean = true,
        onEdit: () -> Unit = {},
    ) {
        composeRule.setContent {
            TVShowDetailScreen(
                coverStorageDir = NO_COVERS,
                uiState =
                    if (ready) {
                        TVShowDetailUiState.Ready(
                            show = show(),
                            seasons = listOf(SeasonGroup(1, listOf(episode(1, 1)), watchedCount = 0)),
                            watchedEpisodes = 0,
                            totalEpisodes = 1,
                            isAbandoned = false,
                            canRefreshMetadata = canRefresh,
                        )
                    } else {
                        TVShowDetailUiState.Loading
                    },
                onEpisodeWatchedChange = { _, _ -> },
                onSeasonWatchedChange = { _, _ -> },
                onSetSeasonLength = { _, _ -> },
                onRemoveSeason = {},
                onAbandonedChange = {},
                onRefreshMetadata = {},
                onAddMissingEpisodes = {},
                onAddAllMissingEpisodes = {},
                onDelete = {},
                onErrorShown = {},
                onUndoUntick = {},
                onUntickShown = {},
                onEpisodeOpen = {},
                onNavigateBack = {},
                onNavigateToEdit = onEdit,
            )
        }
    }

    // show(), episode() and the NO_COVERS companion: copied verbatim from TVShowDetailRefreshActionTest.
}
```

Replace the trailing comment with the actual copied helpers. A plan comment is not code.

- [ ] **Step 2: Run and watch it fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*TVShowDetailEditActionTest"`
Expected: COMPILATION FAILURE — no parameter `onNavigateToEdit`.

- [ ] **Step 3: Implement the Edit action**

In `TVShowDetailScreen.kt`:

1. `TVShowDetailScreen` signature: add `onNavigateToEdit: () -> Unit = {},` as the last parameter, after `onNavigateBack`. This mirrors `MovieDetailScreen`'s `onNavigateToEditMovie: () -> Unit = {}`. The default keeps the six existing test call sites compiling, and the route below passes the real callback.
2. `TVShowDetailScreenRoute` signature: add `onNavigateToEdit: () -> Unit,` after `onNavigateBack` (no default), and pass `onNavigateToEdit = onNavigateToEdit,` in its `TVShowDetailScreen(...)` call.
3. In `actions = { … }`, insert **before** the Refresh guard:

```kotlin
                    // First, as on the film and book screens (#173). Gated on Ready like theirs:
                    // there is nothing to edit until the show has loaded. Not inside the refresh
                    // guard -- a hand-entered show, which cannot refresh, is the one that most
                    // needs editing.
                    if (uiState is TVShowDetailUiState.Ready) {
                        IconButton(onClick = onNavigateToEdit) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.tv_show_detail_edit),
                            )
                        }
                    }
```

Add `import androidx.compose.material.icons.filled.Edit` (not imported there yet).

- [ ] **Step 4: Run and watch it pass; then falsify**

Run: `./gradlew :app:testDebugUnitTest --tests "*TVShowDetailEditActionTest"`
Expected: PASS. Temporarily move the Edit `IconButton` inside the `canRefreshMetadata` guard: `aHandEnteredShowOffersEditToo` must FAIL. Revert.

- [ ] **Step 5: Add the route wrapper, factory, route and navigation**

`EditTVShowScreen.kt`, above the stateless screen (add imports `androidx.compose.runtime.getValue`, `androidx.lifecycle.compose.collectAsStateWithLifecycle`, `androidx.lifecycle.viewmodel.compose.viewModel`, `com.github.maskedkunisquat.mediatracker.ui.EditTVShowViewModelFactory`, `com.hub.media.ui.AppContainer`, `com.hub.media.ui.EditTVShowViewModel`):

```kotlin
/** Route wrapper: owns the [EditTVShowViewModel] and leaves once the save lands or the show is gone. */
@Composable
fun EditTVShowScreenRoute(
    appContainer: AppContainer,
    showId: String,
    onNavigateBack: () -> Unit,
) {
    val viewModel: EditTVShowViewModel =
        viewModel(factory = EditTVShowViewModelFactory(appContainer, showId))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val saved = (uiState as? EditTVShowUiState.Editing)?.saved == true
    LaunchedEffect(saved) {
        if (saved) onNavigateBack()
    }
    LaunchedEffect(uiState) {
        if (uiState is EditTVShowUiState.NotFound) onNavigateBack()
    }

    EditTVShowScreen(
        uiState = uiState,
        onTitleChange = viewModel::onTitleChange,
        onReleaseYearChange = viewModel::onReleaseYearChange,
        onTotalSeasonsChange = viewModel::onTotalSeasonsChange,
        onPurchasePriceChange = viewModel::onPurchasePriceChange,
        onSave = viewModel::save,
        onErrorShown = viewModel::consumeSaveError,
        onNavigateBack = onNavigateBack,
    )
}
```

`ViewModelFactories.kt`, after `EditMovieViewModelFactory` (import `com.hub.media.ui.EditTVShowViewModel`):

```kotlin
/** Factory for [EditTVShowViewModel] with its [showId] (#173). */
class EditTVShowViewModelFactory(
    appContainer: AppContainer,
    showId: String,
) : AppViewModelFactory<EditTVShowViewModel>(
        EditTVShowViewModel::class.java,
        {
            EditTVShowViewModel(
                showId = showId,
                tvShowRepository = appContainer.tvShowRepository,
            )
        },
    )
```

`NavRoutes.kt`, after `TVShowDetail`:

```kotlin
    /**
     * Edit an existing show's title, year, total seasons and price (#173). A hand-entered show has
     * no provider to correct a typo from, so this is the only path that can fix one.
     */
    data object EditTVShow : Route {
        const val ARG_SHOW_ID: String = "showId"

        private const val PATH: String = "edit_tv_show"

        override val route: String = "$PATH/{$ARG_SHOW_ID}"

        fun createRoute(showId: String): String = "$PATH/$showId"
    }
```

`AppNavigation.kt`: import `EditTVShowScreenRoute`. In the `TVShowDetail` composable add
`onNavigateToEdit = { navController.navigate(Route.EditTVShow.createRoute(showId)) },` to the `TVShowDetailScreenRoute(...)` call. Directly after that composable block, add:

```kotlin
        composable(
            route = Route.EditTVShow.route,
            arguments =
                listOf(
                    navArgument(Route.EditTVShow.ARG_SHOW_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val showId =
                requireNotNull(backStackEntry.arguments?.getString(Route.EditTVShow.ARG_SHOW_ID)) {
                    "Missing required argument: ${Route.EditTVShow.ARG_SHOW_ID}"
                }
            EditTVShowScreenRoute(
                appContainer = appContainer,
                showId = showId,
                onNavigateBack = { navController.navigateUp() },
            )
        }
```

Add a line for `Route.EditTVShow` to the KDoc route list at the top of `AppNavigation.kt` if that list names `EditMovie`.

- [ ] **Step 6: Update the instrumented detail test**

In `TVShowDetailScreenTest.kt`:
- add `onNavigateToEdit: () -> Unit = {},` to `setContent(...)` and pass `onNavigateToEdit = onNavigateToEdit,` to `TVShowDetailScreen(...)`;
- in `refreshAction_presentWhenCanRefreshMetadata_andInvokesOnRefreshMetadata`, replace the stale first comment line "TV has no Edit action at all (see TVShowDetailScreen's topBar actions) -- refresh is its Ready-gated action, and" with "Refresh is Ready-gated like Edit, and";
- in `deleteAction_isShownEvenDuringLoading_unlikeMovieAndBook`, append `composeRule.onNodeWithContentDescription(context.getString(R.string.tv_show_detail_edit)).assertDoesNotExist()` (Edit, unlike Delete, waits for Ready);
- add after `refreshAction_absentWhenCannotRefreshMetadata`:

```kotlin
    @Test
    fun editAction_presentWhenReady_evenWithoutTmdb_andInvokesOnNavigateToEdit() {
        var edits = 0
        setContent(readyState(episodeCount = 3, canRefreshMetadata = false), onNavigateToEdit = { edits++ })

        composeRule.onNodeWithContentDescription(context.getString(R.string.tv_show_detail_edit)).performClick()

        assertEquals(1, edits)
    }
```

Run: `./gradlew :app:compileDebugAndroidTestKotlin` — expected BUILD SUCCESSFUL.

- [ ] **Step 7: Run the app unit lane, then commit code (goldens separately)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. Then run `./gradlew :app:verifyRoborazziDebug`. Expected: FAIL on the four `tv-show-detail*` goldens, and only those, because the Edit icon was added. Any other failing golden is a real regression; stop and investigate.

```bash
git add app/src/main app/src/test/java app/src/androidTest
git commit -m "Edit action on the show detail screen opens EditTVShowScreen (#173)"
```

- [ ] **Step 8: Re-record and inspect the goldens**

Run: `./gradlew :app:recordRoborazziDebug`
Open each changed PNG (`git status app/src/test/screenshots`) with the Read tool. For every one, check that:
- an Edit (pencil) icon sits left of Refresh (when shown) and Delete;
- nothing else moved;
- the font-scale-2 variant does not truncate the header or push Delete off-screen.

Only the four `tv-show-detail*` files should change. Then:

```bash
git add app/src/test/screenshots/tv-show-detail*.png
git commit -m "Re-record TV detail goldens for the Edit action (#173)"
```

Run `./gradlew :app:verifyRoborazziDebug`. Expected: PASS.

---

### Task 5: Docs, gate, device run, review, PR

**Files:**
- Modify: `CHANGELOG.md` (`[Unreleased]` → `### Added`)
- Modify: `ROADMAP.md` (line ~81, the #173 sentence)

- [ ] **Step 1: CHANGELOG**

Under `## [Unreleased]` → `### Added`, append:

```markdown
- TV shows can now be edited: the show's page has an Edit button for its title, release year,
  total seasons and purchase price. A value you type is kept when the show's details are refreshed
  from TMDB; a field you clear may be filled in again by the next refresh.
```

- [ ] **Step 2: ROADMAP**

In the "Next, in order" sentence (~line 79–82), change "[#173](…), an edit screen for TV shows; then" to "[#173](…), an edit screen for TV shows (in progress on `feat/173-edit-tv-show`); then". Do not add a sequencing table.

- [ ] **Step 3: Commit docs**

```bash
git add CHANGELOG.md ROADMAP.md
git commit -m "Changelog and roadmap for the TV edit screen (#173)"
```

- [ ] **Step 4: Full gate**

```bash
./gradlew ktlintFormat
./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL for both. If `ktlintFormat` changed files, commit them as "ktlint format (#173)".

- [ ] **Step 5: Device run (if `adb devices` lists one)**

1. Check the keyguard: `adb shell dumpsys window | grep -q "isKeyguardShowing=false" || { echo LOCKED; exit 1; }`. If it's locked, ask the owner to unlock.
2. Offer the owner a `.sqlite` backup of the device library first (the run uninstalls the app).
3. `./gradlew :app:connectedDebugAndroidTest` (full suite, no `leaveApksInstalledAfterRun`). Record the tally. Check any failure against `main` before blaming this branch.
4. `./gradlew :app:seedDebugDevice`.
5. By hand: open a seeded show → tap Edit → change title and total seasons → Save → the detail screen shows the new title. Abandon a show, edit it, and confirm it is still abandoned. Confirm tap targets with a screenshot, because uiautomator bounds on this SM-G975U1 can be offset.

If no device is attached, the PR must say so in words.

- [ ] **Step 6: Whole-branch review**

Run a fresh whole-branch review on Opus against `main`. Re-grade each finding by what a user would actually get, not by its label. Fix the ones that matter, with a test first where it's behavioural. Then run:

`coderabbit review --agent --committed --base main -c AGENTS.md`

Triage it the same way. Re-run the gate after any fix.

- [ ] **Step 7: Push and open the PR**

```bash
git push -u origin feat/173-edit-tv-show
```

Open a PR titled "Edit a TV show (#173)" with "Closes #173". The body covers:
- the decisions table from the spec;
- the device-run tally (or why it wasn't run);
- the four re-recorded goldens and what was checked in them;
- the memory-outcomes table (`| shard id | outcome | reason |`) for snapshot lessons actually consulted.

End the body with the session attribution lines. Do not merge.

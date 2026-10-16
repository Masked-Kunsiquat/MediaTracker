# Domain Hub Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app opens on a hub of Books / Films / Shows cards, each with a live number. Each card
opens a library scoped to that kind, and "All media" is today's library, demoted.

**Architecture:**
- `HubViewModel` (new, in `shared/ui`) derives three counts from flows the app already observes.
- `LibraryViewModel` gains an optional `domain: MediaType?` that narrows `media` before anything
  else sees it.
- `LibraryScreen` gains `domain` and `onNavigateBack`, which change its title, + button, top-bar
  actions and search placeholder.
- `HubScreen` (new) and `Route.Hub` become the start destination, and `Route.Library` gains an
  optional `domain` argument.
- No schema changes, and Stats is untouched.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Navigation Compose, Room KMP, Robolectric and
Roborazzi, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-16-domain-hub-design.md`

## Global Constraints

- Room-touching tests go in `shared/src/jvmTest`. View model tests await `uiState.first { ... }`
  and never read `.value` after an action.
- Do not fork or modify `StatsRepository`. Stats stays books-only.
- Catch around suspending code through `catchingNonCancellation`, never a bare `catch`.
- Test tags are constants in `ui/TestTags.kt`.
- The library keeps `contentWindowInsets = WindowInsets.safeDrawing`.
- Goldens are recorded by hand, looked at, and committed alone.
- `CHANGELOG.md` `[Unreleased]` gets the user-visible change.
- The gate:
  - `./gradlew ktlintFormat`
  - `./gradlew :shared:jvmTest :shared:testDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug ktlintCheck :app:lintDebug && ./gradlew :app:assembleDebug`

## Review Focus

1. **The Shows count matches the Up next screen.** Both read `observeUpNext(includeSpecials)` with
   the same setting, and toggling "include specials" moves both. This is pinned in Task 1.
2. **A domain library's empty state.** An empty Films library, with books present, shows the empty
   state rather than a blank list. The scope must apply to `media` itself, before `isEmpty` is
   computed. This is pinned in Task 2.
3. **Bulk delete within a domain.** Selected ids are intersected with the scoped `media`, so a
   selection can never include rows the user cannot see. This is pinned in Task 2.
4. **Large font on the hub.** Card text wraps and nothing clips or overlaps at font scale 2. This is
   pinned by the golden and an occlusion test in Task 6.
5. **Back.** Back from any library returns to the hub, and from the hub it leaves the app. All media
   gets a back arrow too. This is pinned by the screen test (Task 4) and the hand check.

---

### Task 1: `HubViewModel` and its counts

**Files:**
- Create: `shared/src/commonMain/kotlin/com/hub/media/ui/HubViewModel.kt` (`HubUiState` and `HubViewModel`)
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/HubViewModelTest.kt`

**Interfaces:**
- Produces:
  - `public data class HubUiState(val isLoading: Boolean = true, val loadFailed: Boolean = false, val readingBooks: Int = 0, val watchlistFilms: Int = 0, val showsUpNext: Int = 0)`
  - `public class HubViewModel(mediaRepository: MediaRepository, tvShowRepository: TVShowRepository, settingsRepository: SettingsRepository) : ViewModel` with `val uiState: StateFlow<HubUiState>`

- [ ] **Step 1: Write the failing tests**

  Use the same setup as `UpNextViewModelTest`: `testAppDatabase()`, `ViewModelRegistry`,
  `installMain()`, and the repositories. The helpers are:
  - `BookRepository(db).addBook(title, format = BookFormat.PHYSICAL)` plus `updateReadingStatus`;
  - `MovieRepository(db).addMovie(title = ..., status = ...)`;
  - `startedShow(regular)`, copied from `UpNextViewModelTest`.

```kotlin
    @Test
    fun counts_readingBooks_watchlistFilms_andShowsWithAnEpisodeUpNext() =
        runTest {
            book("Dune", ReadingStatus.READING)
            book("Piranesi", ReadingStatus.READING)
            book("Emma", ReadingStatus.FINISHED)
            film("Solaris", WatchStatus.WATCHLIST)
            film("Alien", WatchStatus.WATCHED)
            startedShow(regular = 3)

            val state = newViewModel().uiState.first { !it.isLoading && it.showsUpNext == 1 }

            assertEquals(2, state.readingBooks)
            assertEquals(1, state.watchlistFilms)
            assertEquals(1, state.showsUpNext)
        }

    @Test
    fun anEmptyLibrary_isZeroEverywhere_notLoading() =
        runTest {
            val state = newViewModel().uiState.first { !it.isLoading }
            assertEquals(HubUiState(isLoading = false), state)
        }

    @Test
    fun aStatusChange_movesTheCount() =
        runTest {
            val id = book("Dune", ReadingStatus.TO_READ)
            val vm = newViewModel()
            vm.uiState.first { !it.isLoading && it.readingBooks == 0 }

            BookRepository(db).updateReadingStatus(id, ReadingStatus.READING)

            assertEquals(1, vm.uiState.first { it.readingBooks == 1 }.readingBooks)
        }

    @Test
    fun showsUpNext_followsIncludeSpecials_likeTheUpNextScreen() =
        runTest {
            // A show whose only unwatched episode is the special: up next only with specials on.
            val added =
                tv.addShow(
                    title = "Specials only",
                    seasons = listOf(SeasonQuickFill(0, episodeCount = 1), SeasonQuickFill(1, episodeCount = 1)),
                )
            assertIs<Resource.Success<String>>(added)
            tv.setEpisodeWatched(db.episodeDao().getByMediaIdAndSeason(added.data, 1).first().id, watched = true)
            val vm = newViewModel()
            val off = vm.uiState.first { !it.isLoading }.showsUpNext

            settings.setIncludeSpecials(!settings.observeIncludeSpecials().first())

            val on = vm.uiState.first { it.showsUpNext != off }.showsUpNext
            assertEquals(setOf(0, 1), setOf(off, on))
        }
```

  `setIncludeSpecials` is an extension in `features/settings/data/IncludeSpecials.kt`; import it.

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :shared:jvmTest --tests "com.hub.media.ui.HubViewModelTest"`

  Expected: compile errors (`HubViewModel` / `HubUiState` unresolved).

- [ ] **Step 3: Implement**

```kotlin
/**
 * The hub's three live numbers (#126): one per domain card, so the hub is somewhere you open the
 * app to look at rather than a menu to tap through. Each is derived from a flow another screen
 * already uses -- in particular [TVShowRepository.observeUpNext] with the same "include specials"
 * setting the Up next screen reads, so the Shows card and that screen can never disagree.
 */
public data class HubUiState(
    val isLoading: Boolean = true,
    /** A read failed; the cards show no number rather than a wrong one. */
    val loadFailed: Boolean = false,
    val readingBooks: Int = 0,
    val watchlistFilms: Int = 0,
    val showsUpNext: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
public class HubViewModel(
    mediaRepository: MediaRepository,
    tvShowRepository: TVShowRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    public val uiState: StateFlow<HubUiState> =
        combine(
            mediaRepository.observeAllMediaWithDetails(),
            settingsRepository.observeIncludeSpecials().flatMapLatest(tvShowRepository::observeUpNext),
        ) { media, upNext ->
            HubUiState(
                isLoading = false,
                readingBooks =
                    media.count { it is MediaWithDetails.Book && it.details?.status == ReadingStatus.READING },
                watchlistFilms =
                    media.count { it is MediaWithDetails.Movie && it.details?.status == WatchStatus.WATCHLIST },
                showsUpNext = upNext.size,
            )
        }.catch { emit(HubUiState(isLoading = false, loadFailed = true)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), HubUiState())
}
```

  `catch` on a Flow does not swallow cancellation, which is why `LibraryViewModel`'s neighbours use
  it. That makes it the right tool here; `catchingNonCancellation` is for suspending blocks.

  `EpisodeDao.observeUpNext` returns one row per started, non-abandoned show (its KDoc,
  "Which shows appear"), so `upNext.size` is the number of shows with an episode up next.

- [ ] **Step 4: Add a failure test, then run everything**

  Add `aFailedRead_isLoadFailed_notACrash`, using a `MediaRepository` whose database is closed
  before the view model subscribes. Close `db` after building the repositories, and assert
  `loadFailed`. It must fail first: temporarily remove `.catch`, watch the test fail, then restore it.

  Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

  Message: `HubViewModel: reading books, watchlist films, shows up next (#126)`

---

### Task 2: `LibraryViewModel` scoped to a domain

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/hub/media/ui/LibraryViewModel.kt`
- Test: `shared/src/jvmTest/kotlin/com/hub/media/ui/LibraryViewModelTest.kt`

**Interfaces:**
- Produces: `LibraryViewModel(mediaRepository, deleteMediaUseCase, settingsRepository, domain: MediaType? = null)`

- [ ] **Step 1: Write the failing tests** (add a `scoped(domain)` helper that builds a second
  tracked view model with the same repositories)

```kotlin
    @Test
    fun aDomain_showsOnlyItsOwnType() =
        runTest {
            insertBook("Dune")
            insertMovie("Solaris", WatchStatus.WATCHLIST)

            val films = scoped(MediaType.MOVIE).uiState.first { it.media.isNotEmpty() }

            assertEquals(listOf("Solaris"), films.media.map { it.item.title })
        }

    @Test
    fun anEmptyDomain_isEmpty_whileOtherKindsExist() =
        runTest {
            insertBook("Dune")
            val vm = scoped(MediaType.TV_SHOW)
            // Positive control: the book is in the database (the unscoped library sees it).
            viewModel.uiState.first { it.media.isNotEmpty() }

            assertTrue(vm.uiState.first { !it.isLoadingOrInitial() }.isEmpty)
        }

    @Test
    fun searchWithinADomain_matchesOnlyThatDomain() =
        runTest {
            insertBook("Solaris")
            insertMovie("Solaris", WatchStatus.WATCHLIST)
            val vm = scoped(MediaType.BOOK)
            vm.setSearchQuery("Solaris")

            val state = vm.uiState.first { it.searchQuery == "Solaris" && it.media.isNotEmpty() }

            assertEquals(listOf(MediaType.BOOK), state.filteredMedia.map { it.item.type })
        }

    @Test
    fun aSelectionInADomain_neverHoldsAnotherDomainsRow() =
        runTest {
            val book = insertBook("Dune")
            val film = insertMovie("Solaris", WatchStatus.WATCHLIST)
            val vm = scoped(MediaType.BOOK)
            vm.uiState.first { it.media.isNotEmpty() }

            vm.toggleSelection(book)
            vm.toggleSelection(film)

            assertEquals(setOf(book), vm.uiState.first { it.selectedIds.isNotEmpty() }.selectedIds)
        }
```

  Read `LibraryUiState` for how an "initial" state is told apart from an empty loaded one, and use
  that predicate instead of the placeholder `isLoadingOrInitial()`. If there is none, await the
  unscoped view model first, then `scoped(...).uiState.drop(1).first()`, and say so in a comment.
  The search setter is `setSearchQuery`, and the filtered list is `LibraryUiState.filteredMedia`.

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :shared:jvmTest --tests "com.hub.media.ui.LibraryViewModelTest"`

  Expected: compile error on the `domain` parameter.

- [ ] **Step 3: Implement**

  Add the parameter last, `domain: MediaType? = null`, and document it. In `mediaWithProgress`,
  narrow `media` before pairing:

```kotlin
            val scoped = if (domain == null) media else media.filter { it.item.type == domain }
            scoped to progress.mapValues { (_, counts) -> counts.counted(includeSpecials) }
```

  Everything downstream (search, filter, `isEmpty`, the selection intersect) already works on
  `media`, so it inherits the scope.

- [ ] **Step 4: Run the tests and watch them pass**

  Same command as Step 2. Expected: PASS, including every existing test, because `null` keeps
  today's behaviour.

- [ ] **Step 5: Commit**

  Message: `LibraryViewModel can be scoped to one domain (#126)`

---

### Task 3: `HubScreen`

**Files:**
- Create: `app/src/main/java/com/github/maskedkunisquat/mediatracker/ui/screens/HubScreen.kt` (`HubScreenRoute` and the stateless `HubScreen`)
- Modify: `ui/TestTags.kt` (add `object Hub { BOOKS, FILMS, SHOWS, ALL_MEDIA, STATS, LIST }`)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `ui/ViewModelFactories.kt` (add `HubViewModelFactory`)
- Test: `app/src/test/.../ui/screens/HubScreenTest.kt`

**Interfaces:**
- Produces: `HubScreen(uiState: HubUiState, onOpenDomain: (MediaType) -> Unit, onOpenAllMedia: () -> Unit, onOpenStats: () -> Unit, onOpenSettings: () -> Unit)`
- Produces: `HubScreenRoute(appContainer, onOpenDomain, onOpenAllMedia, onOpenStats, onOpenSettings)`

- [ ] **Step 1: Strings**

  Add these, in the same commit as the code that uses them:

```xml
    <string name="hub_title">Media Tracker</string>
    <string name="hub_books">Books</string>
    <string name="hub_films">Films</string>
    <string name="hub_shows">Shows</string>
    <plurals name="hub_books_count"><item quantity="one">%d reading</item><item quantity="other">%d reading</item></plurals>
    <plurals name="hub_films_count"><item quantity="one">%d on watchlist</item><item quantity="other">%d on watchlist</item></plurals>
    <plurals name="hub_shows_count"><item quantity="one">%d up next</item><item quantity="other">%d up next</item></plurals>
    <string name="hub_all_media">All media</string>
```

  Stats reuses `stats_title`, and the gear reuses `settings_content_description` ("Open settings"),
  so `SettingsNavigationTest`'s matcher keeps working.

- [ ] **Step 2: Write the failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class HubScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val opened = mutableListOf<String>()

    private fun setContent(state: HubUiState = HubUiState(isLoading = false, readingBooks = 3, watchlistFilms = 5, showsUpNext = 1)) {
        composeRule.setContent {
            MediaTrackerTheme(dynamicColor = false) {
                HubScreen(
                    uiState = state,
                    onOpenDomain = { opened += it.name },
                    onOpenAllMedia = { opened += "all" },
                    onOpenStats = { opened += "stats" },
                    onOpenSettings = { opened += "settings" },
                )
            }
        }
    }

    @Test
    fun theCards_showTheirNumbers() {
        setContent()
        composeRule.onNodeWithText("3 reading").assertExists()
        composeRule.onNodeWithText("5 on watchlist").assertExists()
        composeRule.onNodeWithText("1 up next").assertExists()
    }

    @Test
    fun zero_isShown_notHidden() {
        setContent(HubUiState(isLoading = false))
        composeRule.onNodeWithText("0 reading").assertExists()
    }

    @Test
    fun whileLoading_theNumbersAreBlank_notZero() {
        setContent(HubUiState())
        composeRule.onNodeWithText("0 reading").assertDoesNotExist()
        // Positive control: the card itself is there.
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).assertExists()
    }

    @Test
    fun everyTarget_isWired() {
        setContent()
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).performClick()
        composeRule.onNodeWithTag(TestTags.Hub.FILMS).performClick()
        composeRule.onNodeWithTag(TestTags.Hub.SHOWS).performClick()
        composeRule.onNodeWithTag(TestTags.Hub.ALL_MEDIA).performClick()
        composeRule.onNodeWithTag(TestTags.Hub.STATS).performClick()
        composeRule.onNodeWithContentDescription("Open settings").performClick()
        assertEquals(listOf("BOOK", "MOVIE", "TV_SHOW", "all", "stats", "settings"), opened)
    }

    @Test
    fun aCard_readsAsOneStop_withItsNumber() {
        setContent()
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).assertContentDescriptionContains("Books, 3 reading")
    }
}
```

  `assertContentDescriptionContains` is a small private helper over
  `SemanticsProperties.ContentDescription`. Alternatively, use a merged node:
  `onNode(hasText("Books") and hasText("3 reading"), useUnmergedTree = false)`. Pick one and keep it.

- [ ] **Step 3: Run them and watch them fail**

  Run: `./gradlew :app:testDebugUnitTest --tests "*HubScreenTest"`

  Expected: compile errors.

- [ ] **Step 4: Implement**

  A `Scaffold` with a `CenterAlignedTopAppBar` whose title is `hub_title` and whose action is
  Settings: the same `Icons.Filled.Settings` and content description the library uses. The body is
  a `verticalScroll` `Column` tagged `Hub.LIST`, padded 16dp, with `spacedBy(12.dp)`:
  - three `DomainCard`s;
  - a `HorizontalDivider`;
  - two `ListItem` rows ("All media", "Stats"), each with a trailing chevron icon
    (`Icons.AutoMirrored.Filled.KeyboardArrowRight`), tagged and `clickable`.

  A `DomainCard` is a `Card(onClick = ...)` with `Modifier.fillMaxWidth().testTag(tag)` and
  `semantics(mergeDescendants = true) {}`. Inside, a `Row` holds:
  - the same emoji the library rows use (📖 🎬 📺), as a `Text` at `headlineMedium`, cleared from
    semantics;
  - a `Column` with the name (`titleLarge`) and the number line (`bodyMedium`, `onSurfaceVariant`).

  The number line is `pluralStringResource(..., n, n)` when `!isLoading && !loadFailed`, otherwise
  empty. Use `heightIn(min = 20.dp)` on the line so the card doesn't change height.

  The scaffold uses `contentWindowInsets = WindowInsets.safeDrawing`, the same as other screens,
  and the content gets `scrollingContentPadding(innerPadding, PaddingValues(16.dp))`.

  `HubScreenRoute` uses `HubViewModelFactory(appContainer)`, which builds
  `HubViewModel(appContainer.mediaRepository, appContainer.tvShowRepository, appContainer.settingsRepository)`.

- [ ] **Step 5: Run the tests and watch them pass**

  Same command as Step 3. Expected: PASS.

- [ ] **Step 6: Commit**

  Message: `HubScreen: domain cards with live numbers, All media, Stats, Settings (#126)`

---

### Task 4: `LibraryScreen` scoped to a domain

**Files:**
- Modify: `app/.../ui/screens/LibraryScreen.kt`:
  - `LibraryScreenRoute` gains `domain: MediaType?` and `onNavigateBack: () -> Unit`;
  - `LibraryScreen` gains `domain: MediaType? = null` and `onNavigateBack: (() -> Unit)? = null`;
  - the `onNavigateToSettings` parameter is removed.
- Modify: `ui/ViewModelFactories.kt` (`LibraryViewModelFactory(appContainer, domain: MediaType? = null)`)
- Modify: `strings.xml`:
  - `library_title_all` "All media";
  - `library_title_books` "Books";
  - `library_title_films` "Films";
  - `library_title_shows` "Shows";
  - `library_search_placeholder_books` "Search books by title or author";
  - `library_search_placeholder_films` "Search films";
  - `library_search_placeholder_shows` "Search shows".
- Modify: the call sites in `LibrarySelectionTest`, `LibraryScreenGoldenTest`,
  `LibraryScreenOcclusionTest` and the previews (drop `onNavigateToSettings`).
- Test: `app/src/test/.../ui/screens/LibraryDomainTest.kt`

- [ ] **Step 1: Write the failing tests**

  The tests use `LibraryScreen` with a small `LibraryUiState`, recording callbacks, and one
  `setContent(domain)` helper.

```kotlin
    @Test
    fun aDomain_isTitled_andItsAddButton_addsThatKindDirectly() {
        setContent(domain = MediaType.MOVIE)
        composeRule.onNodeWithText("Films").assertExists()
        composeRule.onNodeWithTag(TestTags.Library.ADD_BUTTON).performClick()
        assertEquals(listOf("addMovie"), calls)
        // No "what do you want to add?" menu in a domain.
        composeRule.onNodeWithText(context.getString(R.string.add_book_menu_item)).assertDoesNotExist()
    }

    @Test
    fun allMedia_keepsTheAddMenu() {
        setContent(domain = null)
        composeRule.onNodeWithText("All media").assertExists()
        composeRule.onNodeWithTag(TestTags.Library.ADD_BUTTON).performClick()
        composeRule.onNodeWithText(context.getString(R.string.add_book_menu_item)).assertExists()
    }

    @Test
    fun topBarActions_perDomain() {
        // Books: Stats only. Shows: Up next only. Films: neither. All media: both. Settings: nowhere.
        for ((domain, stats, upNext) in listOf(
            Triple(MediaType.BOOK, true, false),
            Triple(MediaType.TV_SHOW, false, true),
            Triple(MediaType.MOVIE, false, false),
            Triple(null, true, true),
        )) { /* one test per row, using composeRule.setContent per test -- split into four @Tests */ }
    }

    @Test
    fun back_returnsToTheHub() {
        setContent(domain = MediaType.BOOK)
        composeRule.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun theSearchPlaceholder_namesTheDomain() {
        setContent(domain = MediaType.BOOK)
        composeRule.onNodeWithText("Search books by title or author").assertExists()
    }
```

  Write `topBarActions_perDomain` as four separate tests: `books_offerStats_notUpNext`,
  `shows_offerUpNext_notStats`, `films_offerNeither`, and `allMedia_offersBoth_andNoneOfferSettings`.
  Each asserts present and absent by content description, so every absence has a presence beside
  it. (The `for` above shows the table only. A `setContent` loop is not allowed in one test.)

- [ ] **Step 2: Run them and watch them fail**

  Run: `./gradlew :app:testDebugUnitTest --tests "*LibraryDomainTest"`

  Expected: compile errors.

- [ ] **Step 3: Implement**

  - **Title:** `when (domain) { BOOK -> library_title_books; MOVIE -> films; TV_SHOW -> shows; null -> library_title_all }`.
    This replaces the hard-coded `"Library"`.
  - **Back arrow:** a `navigationIcon` when `onNavigateBack != null`.
  - **Actions:**
    - Up next shows when `domain == null || domain == TV_SHOW`.
    - Stats shows when `domain == null || domain == BOOK`.
    - Remove Settings.
  - **The + button:** with `domain != null`, `onClick` calls the matching
    `onNavigateToAdd{Book,Movie,TVShow}` and the `DropdownMenu` is not composed. With `null`, keep
    today's menu.
  - **Search placeholder:** chosen by domain.
  - **The route** builds `LibraryViewModelFactory(appContainer, domain)` and passes the `viewModel`
    `key = "library-${domain ?: "all"}"`, so each domain gets its own view model instance.

  Update the KDoc for the FAB ("the FAB now picks a media type") and the top-bar comment.

- [ ] **Step 4: Run the tests and watch them pass**

  Run: `./gradlew :app:testDebugUnitTest --tests "*Library*"`

  Expected: PASS. The existing library goldens will differ; they are re-recorded in Task 6.

- [ ] **Step 5: Commit**

  Message: `LibraryScreen scoped by domain: title, back, direct add, per-domain actions (#126)`

---

### Task 5: Navigation

**Files:**
- Modify: `app/.../ui/navigation/NavRoutes.kt`:
  - add `Route.Hub` (`"hub"`);
  - `Route.Library` takes `route = "library?domain={domain}"`, `ARG_DOMAIN = "domain"` and
    `fun createRoute(domain: MediaType?)`.
- Modify: `app/.../ui/navigation/AppNavigation.kt`:
  - the start destination becomes `Route.Hub.route`;
  - a `composable(Route.Hub.route)` is added;
  - the library composable reads the optional argument.
- Modify: `app/src/androidTest/.../ui/SettingsNavigationTest.kt` (rename
  `settings_isReachableFromTheLibraryScreen` to `settings_isReachableFromTheHub`; the matcher
  is unchanged)
- Create: `app/src/androidTest/.../ui/HubNavigationTest.kt`
- Test: a Robolectric `RoutesTest` in `app/src/test` (if one exists, extend it) for
  `Route.Library.createRoute`.

- [ ] **Step 1: Write the failing tests**

  Route tests:
  - `createRoute(null) == "library"`;
  - `createRoute(MediaType.BOOK) == "library?domain=BOOK"`;
  - the hub route is `"hub"`.

  Device smoke test (`HubNavigationTest`, same rule and setup as `SettingsNavigationTest`):

```kotlin
    @Test
    fun theAppOpensOnTheHub_andBooksOpensTheBooksLibrary() {
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.Hub.BOOKS).performClick()
        composeRule.onNodeWithText(string(R.string.library_title_books)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.library_search_placeholder_books)).assertIsDisplayed()
    }
```

  This lives in `androidTest` because only a test starting at the real `MainActivity` proves the
  route is wired (AGENTS.md §7). Say so in its KDoc.

- [ ] **Step 2: Run the route test and watch it fail**

  Run: `./gradlew :app:testDebugUnitTest --tests "*Route*"`

  Expected: compile error.

- [ ] **Step 3: Implement**

  The library composable:

```kotlin
        composable(
            route = Route.Library.route,
            arguments = listOf(navArgument(Route.Library.ARG_DOMAIN) { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            val domain = backStackEntry.arguments?.getString(Route.Library.ARG_DOMAIN)?.let(MediaType::valueOf)
            LibraryScreenRoute(appContainer = appContainer, coverStorageDir = coverStorageDir, domain = domain,
                onNavigateBack = { navController.navigateUp() }, /* existing add/detail/stats/upNext lambdas */)
        }
```

  The hub composable wires:
  - `onOpenDomain = { navController.navigate(Route.Library.createRoute(it)) }`;
  - `onOpenAllMedia = { navController.navigate(Route.Library.createRoute(null)) }`;
  - `onOpenStats` and `onOpenSettings`, as the library's were.

  Remove the library's `onNavigateToSettings` wiring, and update `AppNavigation`'s KDoc route list.

- [ ] **Step 4: Run the tests, then the device tests**

  Run `./gradlew :app:testDebugUnitTest --tests "*Route*"`. Expected: PASS.

  Device: `./gradlew :app:connectedDebugAndroidTest`. Expected: every test passes, including the
  renamed Settings test and the new Hub test.

- [ ] **Step 5: Commit**

  Message: `Navigation: the hub is the start destination; Library takes an optional domain (#126)`

---

### Task 6: Goldens and the hub occlusion test

**Files:**
- Create: `app/src/test/.../ui/goldens/HubScreenGoldenTest.kt` (`hub` and `hub-large-font`)
- Create: `app/src/test/.../ui/insets/HubScreenOcclusionTest.kt`
- Re-record: `library.png`, `library-dark.png`, `library-large-font.png`

- [ ] **Step 1: Write the tests**
  - **Golden:** copy `LibraryScreenGoldenTest`'s shape and its large-font setup. The fixture is
    `HubUiState(isLoading = false, readingBooks = 3, watchlistFilms = 5, showsUpNext = 2)`.
    `alsoAssert` checks that the five hub tags exist.
  - **Occlusion:** `assertNoInteractiveNodeIsBehindTheNavigationBar(expectedTags = listOf(Hub.LIST, Hub.BOOKS, Hub.STATS))`
    at font scale 2, the only size at which the list reaches the bottom.
    - Falsify it by removing `scrollingContentPadding`'s bottom inset, and expect a FAIL.
    - If it passes with that removed, the hub never reaches the bar: delete the test and say why
      in the golden's KDoc, as the edit screens' tests once did.

- [ ] **Step 2: Verify that the goldens fail**

  Run: `./gradlew :app:verifyRoborazziDebug --tests "*Hub*GoldenTest" --tests "*LibraryScreenGoldenTest"`

  Expected: FAIL. The hub images are missing, and the library images changed.

- [ ] **Step 3: Record and look**

  Run `./gradlew :app:recordRoborazziDebug` for the same tests, then open every PNG. Check:
  - the hub cards and numbers;
  - at 2× font, card text wraps rather than clipping;
  - the library shows its "All media" title and back arrow, and has no Settings icon.

- [ ] **Step 4: Verify that they pass, and commit**

  Run the verify again. Expected: PASS. Commit the tests first, then the PNGs alone as
  `Goldens: hub, and the library's new bar (#126)`.

---

### Task 7: Changelog, follow-up issue, gate and device

- [ ] **Step 1:** Add to `CHANGELOG.md` `[Unreleased]` under `### Changed`: "The app now opens on a hub with a card for Books, Films and Shows, each showing a live number (books you're reading, films on your watchlist, shows with an episode up next). A card opens a library of just that kind, where search covers only that kind and + adds it directly. Everything together is still there as All media, and Settings has moved to the hub. (#126)"
- [ ] **Step 2:** Ask the owner, then file "Stats for films and shows". Body: domain stats are scoped reads over the one stats pipeline, never a fork of `StatsRepository` (#126's constraint), and #91 applies.
- [ ] **Step 3:** Run `ktlintFormat`, then the full gate. Expected: all pass.
- [ ] **Step 4:** Commit `CHANGELOG for #126`.
- [ ] **Step 5:** Device: the full `connectedDebugAndroidTest`, then `seedDebugDevice`, then a hand check:
  - each card's number against the seeded library;
  - each domain's + and search;
  - back from every library returns to the hub;
  - All media;
  - Settings from the ⚙;
  - Stats from both the hub and Books.

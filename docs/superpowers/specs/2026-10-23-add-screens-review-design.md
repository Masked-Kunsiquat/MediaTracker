# Add screens: a review form before anything is saved (#214)

#213 (for #141) revamped the **edit** screens: a compact header, titled section cards, the status
dropdown chip and a pinned Cancel/Save bar. The **add** screens were left as they were, and two
of them save on a single tap. This design brings all three add flows into one shape.

## 0. Decisions (owner, 2026-10-22/23)

- **A review step before saving, for books.** Picking an edition no longer adds the book. It opens
  a form filled in from the lookup, and nothing is in the library until Save.
- **Every field the edit screen has is editable on that form**, the same as Edit book.
- **All three types work the same way.** A film or show picked from TMDB search opens the same
  kind of review form, filled in from TMDB, instead of saving on the tap.
- **Share, don't duplicate.** Where the add and edit forms show the same fields, they use the same
  code. The owner does not want a second copy of each form.

## 1. Why

- **A wrong tap costs a cleanup.** Today a mis-tapped search result is a library entry the user
  then has to find and delete. This happened during brainstorming: Piranesi was added by tapping an
  edition to see what would happen.
- **Lookups are often slightly wrong.** Open Library's page count, year or format is the most
  common thing users fix in Edit book. Fixing it before the save is cheaper than fixing it after.
- **The screens look like two apps.** The add forms are a flat column of outlined fields, a stack
  of four FilterChips for film status, and an inline full-width Save that appears only once you
  scroll to it. The edit forms use cards, the dropdown chip and a pinned bar.

## 2. Goals, non-goals, anti-goals

**Goals**

1. Every way of adding a book, film or show ends on a review form styled like its edit screen:
   - `EditHeader`;
   - `FormSection` cards;
   - `StatusDropdownChip` where the type has a status;
   - `EditBottomBar` pinned to the bottom.
2. Nothing is written to the database, and no cover or poster is written to disk, until Save.
3. One form per type, used by both its add and its edit screen.
4. Small consistency fixes across the add screens:
   - one top-bar style;
   - "film" rather than "movie" in user-facing strings;
   - "Add book" in sentence case, like "Add TV show".

**Non-goals**

- **No manual entry for books.** Films and shows have "Enter manually instead"; books do not, and
  this design does not add it. It is a reasonable follow-up issue.
- **The search and edition-picker steps keep their behavior.** They get the same top bar, and
  their wording follows the "film" rule. Their layout and the edition dialog are otherwise
  unchanged.
- **No new editable fields.** For example, Edit book has no author field, so the review form has
  none either: the author is saved as looked up. Adding author editing is a separate change to both
  screens.
- **No schema change.** Every repository `add*` already accepts every value the forms produce.

**Anti-goals** (failure even if it technically works)

- **A second copy of a form.** For example, an `AddBookForm` that re-declares Edit book's fields,
  validation and filters. Two copies drift; #213 exists because they already had.
- **A Cancel that leaves something behind:**
  - a library row;
  - a stored cover file;
  - a queued poster fetch.
- **Losing what the lookup knew.** Today's direct saves pass TMDB's or Open Library's external ids,
  synopsis, community rating, airing status, air dates, seasons with episode titles, work key and
  ISBN. The review step must carry all of them through to the save, not just the visible fields.
- **A review form that can't be finished.** For example, Save hidden behind the keyboard or the
  navigation bar, or a snackbar covering Save (#174's review, #213).

## 3. The flows

| Type | How you get to the form | Prefilled from |
| :--- | :--- | :--- |
| Book | Search → pick a result → pick an edition | that edition's ISBN lookup |
| Book | Search → pick a result with no work key | that result's resolved ISBN lookup |
| Book | ISBN tab → type an ISBN → Add | that ISBN's lookup |
| Film | Search TMDB → tap a result | `tmdbClient.movieDetails` |
| Film | "Enter manually instead" | nothing (empty form) |
| Show | Search TMDB → tap a result | `tmdbClient.showWithSeasons` |
| Show | "Enter manually instead" | nothing (empty form, with season rows) |

**The "Add this book?" dialog goes** (MT-A8's call). A search result with no work key opens a
confirmation dialog today, and `confirmSelection` then resolves an ISBN and adds. With a review
form after it, that dialog would be a second confirmation. `confirmSelection` now resolves the ISBN
and opens the review form directly, and the dialog and its strings are removed.

**Where the form lives** (MT-A8's call):

- **From TMDB search:** the review form is a state of the search route, hosted by the search view
  model (`MovieSearchViewModel`, `TVShowSearchViewModel`), which holds the draft. No new route,
  and no hand-off of a draft between view models; a show's draft holds every season and episode,
  which no nav argument can carry. Cancel returns to the search state with the results still there.
- **Manual entry:** stays its own route (`AddMovieManual`, `AddTVShowManual`) with its own view
  model. It hosts the same form composable, empty.
- **Books:** everything stays on the one `AddBook` route under `AddBookViewModel`; the review form
  is a state of it.
- Two hosts render one form. That is the intended sharing: the form composable is shared, and so is
  any parse-and-validate helper.

**After the tap:**

- The duplicate check stays where it is, *before* the lookup: a film or show already in the library
  is refused on the search screen, as today.
- While the lookup runs, the user sees a loading state. A failed lookup is reported where the tap
  happened, as today, and the user stays on search or ISBN.

**On the form:**

- **Cancel and back** both return to where the user came from (search results, the ISBN tab, or
  the search screen for a manual form). They never save.
- **Save:**
  - writes once, and a double tap still writes once (the existing `Saving`/`Saved` guards);
  - then navigates where that flow navigates today.
  - Builder's call: whether every flow should instead land on the new item's detail screen. Today
    books go to the library and films and shows to their detail screen. Say which in the PR.

## 4. What each form holds

**The same fields as the type's edit screen**, in the same cards and order.

**Book** (Edit book's form):

- Book details card: title, release year.
- Physical card: format, total pages, tracking mode.
- Status card: reading status.
  - Every value is offered: a new book is never a re-read.
  - It starts at the repository default (`TO_READ`).
- Purchase card: purchase price.

**Film** (Edit film's form):

- Details card: title, release year, runtime.
- Status card: status. It starts at `WATCHLIST`, and choosing Watched still asks when
  (`FilmWatchedDialog`), as on both forms today.
- Purchase card: purchase price.

**Show** (Edit show's form, plus one card):

- Edit show's fields: title, release year, total seasons, purchase price.
- **Seasons card, for a manual show:** today's season quick-fill rows.
- **Seasons card, for a show from TMDB:** a read-only summary, such as "5 seasons, 62 episodes from
  TMDB". It replaces the quick-fill rows, because the TMDB seasons carry episode titles and
  runtimes the rows cannot hold. Editing those is not in scope.
- The total-seasons field still prefills from TMDB and stays editable, as on Edit show.
- Edit show has no status, so neither does this form.

**Header:**

- `EditHeader`, with the kind and year.
- The title is the one the form opened with, not the field being retyped (#174's rule).
- **Artwork:** see §5.

**Validation:**

- Exactly what the edit form does: the same filters, the same parse-once checks and the same
  supporting-text errors.
- The repositories stay authoritative (`*MetadataValidation`).

## 5. Lookup and save become two steps

Today each search pick does lookup + save in one call:

- `AddBookByIsbnUseCase.execute(isbn)`;
- `MovieSearchViewModel.addMovie(tmdbId)`;
- `TVShowSearchViewModel.addShow(tmdbId)`.

Each needs splitting into:

1. **Lookup → a draft.** A plain value holding everything the save needs:
   - the visible fields, prefilled;
   - the hidden ones: external ids, synopsis, rating, air data, TMDB seasons, poster path, cover
     URL, normalized ISBN, authors.
   - For books this is the metadata half of `execute` without `downloadAndStoreCover` and without
     `addBook`.
   - For films and shows it is the existing `toMovieMapping()` / `toShowMapping()` result, which is
     already that draft.
2. **Save(draft, edited fields).** It calls the existing `addBook` / `addMovie` / `addShow` with
   the edited visible values and the draft's hidden ones. Then it does what happens after the save
   today:
   - **Books:** the cover bytes fetched during the lookup (see the artwork bullet below) are
     stored as part of the save, before `addBook`, so a cancelled review stores nothing. A failed or
     missing cover is still not a failed save.
   - **Films and shows:** `fetchPosterUseCase.enqueue` after the save, as now.

**Edited values win over the draft.** If the user clears the page count, `null` is saved, not
Open Library's number.

**Artwork in the review header:**

- **Book** (MT-A8's call): the header shows **the cover Save will store**, which is
  `BookMetadata.coverImageUrl` from the provider chain. It is not the picker's
  `coverThumbnailUrl`, which can be a different image and doesn't exist on the ISBN tab.
  - The lookup downloads those bytes **once** and keeps them in the draft, in memory.
  - The header renders them, and Save stores the same bytes. This means no second request against
    the rate-limited cover quota, and nothing on disk until Save.
  - `EditHeader` currently takes a local hash only, so it needs a second artwork source (in-memory
    bytes). Keep that change inside `EditHeader`.
  - A cover download that fails during lookup means no header art and no stored cover, as a failed
    cover means today.
- **Film and show:** no artwork in the review header, since the poster is fetched after the save,
  as today. Builder's call whether to show the TMDB poster remotely, the way books are shown.
  - **The constraint either way:** the poster must not be stored before Save.

**`BookIngestionUseCase`** (the interface the add-book view model fakes) changes shape to match.
The other callers of `AddBookByIsbnUseCase.execute` (check with a search) keep working, either by
leaving `execute` as lookup-then-save or by moving to the two steps.

**Where the draft lives:**

- in the hosting view model named in §3, so it survives rotation;
- process death may drop it, as Edit film's form state can today.

**Edits survive rotation** (MT-A8's call):

- Film and show edits already live in view-model state.
- Book edits live in the composable. Edit book uses `remember`, and the manifest has no
  `configChanges`, so today a rotation drops in-progress edits on Edit book too.
- The shared book form switches those fields to `rememberSaveable`. Its values are strings and
  enums, so this is a small change, and it fixes Edit book at the same time.
- Moving Edit book onto view-model state is therefore not required.

## 6. Sharing

**One form composable per type**, used by its add screen and its edit screen:

- It takes the field values, the callbacks, the header inputs and the Save/Cancel wiring.
- The edit screens become thin callers of it.
- It lives in `ui/components/`, or beside the edit screen as `internal`. Builder's call; §6 of
  AGENTS.md says to widen only what the compiler forces.

**The form state's home is already split between screens:**

- Edit book keeps its field state in the composable (`remember`).
- Edit film and Edit show keep it in the view model (`EditMovieUiState.Editing`,
  `EditTVShowUiState.Editing`).
- The add form for each type follows its edit form's choice, so one composable serves both.
- Builder's call whether to unify Edit book onto the view-model shape while extracting it. If so,
  say so in the PR, because it touches a screen with goldens.

**One scaffold for every form screen.** All six screens are the same shape: Scaffold with
`safeDrawing`, `imePadding`, a scrolling column, a snackbar above the bar, then `EditBottomBar`.
Extract that into `EditForm.kt` beside `EditHeader`, `FormSection` and `EditBottomBar`, so the
snackbar and inset handling (#174, #213) live in one place.

**Shared view-model logic** (`parseOptionalNumber`, `*MetadataValidation`) stays shared. The add
view models must not grow copies of the edit view models' parse-and-validate code. Extract a shared
helper if the two would otherwise be written twice.

**Search screens:**

- `MovieSearchScreen` and `TVShowSearchScreen` stay separate files: their KDoc gives the reason,
  and it still holds.
- Only their "tap a result" wiring changes.

## 7. Small consistency fixes

- **One top-bar style** across the three add screens, their search steps and the review forms.
  Builder's call: left-aligned `TopAppBar`, as Edit film and Edit show use. Edit book's
  `CenterAlignedTopAppBar` may follow in the same PR if its golden is re-recorded.
- **"film", not "movie",** in user-facing strings on these screens, to match the hub. For example,
  `movie_search_title` "Add movie" → "Add film". Resource *names* stay as they are.
- **Sentence case:** "Add Book" → "Add book".
- **American English**, as everywhere.

## 8. Testing

Follow AGENTS.md §7. Each new test is seen failing (or falsified by sabotage) before it is trusted.

**`:shared:jvmTest`** (anything touching Room):

- Lookup produces a draft and writes nothing (row count unchanged; no cover file).
- Save with edited fields stores the edited values *and* the draft's hidden ones (external ids,
  work key, synopsis, seasons).
- A cleared field saves `null`.
- A double save writes one row.

**`commonTest`** (view models with fakes):

- Review → Cancel saves nothing.
- A lookup failure leaves the user on search with the message.
- Await `uiState.first { … }`; never read `.value` after an action.

**Robolectric lane `app/src/test/`** (stateless screens with fabricated state):

- Each review form renders its cards and the prefilled values.
- Save is disabled on an invalid field.
- Cancel calls back.
- **A navigation-bar occlusion test per review screen,** proven to fail (#213's lesson). Find Save
  by its tag.

**Goldens:**

- Re-record any golden whose screen changed, and look at what was written.
- Commit the PNGs separately from assertion changes.
- Keep the set small; at most one new golden (a review form).

**Device lane:**

- Run `connectedDebugAndroidTest` before the PR, since `app/src` changes. Report the tally.
- Don't add a screen-behavior test there unless it hits a documented Robolectric limit. Typing into
  the review form is not in a dialog, so it can stay in Robolectric.

**By hand on the phone** (it holds dummy data):

- Each of the six flows in §3, including a cancelled one, then confirm the library didn't change.

## 9. Size and order

**Size:** a project. Builder's call on PR boundaries. A natural order is one PR per type:

1. **Film, with the shared scaffold and the film form extracted.** It is the smallest form, and it
   proves the shared pieces.
2. **Show,** adding the TMDB seasons summary.
3. **Book,** the use-case split and the remote cover in the header.
4. **The §7 fixes,** in whichever PR touches each screen.

Each PR adds its user-visible change to `CHANGELOG.md` `[Unreleased]`.

# #172: one top bar for the three detail screens

**Issue:** [#172](https://github.com/Masked-Kunsiquat/MediaTracker/issues/172). **Graphban:** MT-32.
**Branch:** `feat/172-detail-top-bars` (from `main` at `aa6e3ec`).

## Today (measured on main)

| Screen | Top-bar actions |
| --- | --- |
| Film | Edit, Delete. Both appear only once the film has loaded. |
| TV show | Edit (once loaded); Refresh (once loaded *and* the show has a TMDB id); **Delete always, even while loading** |
| Book | Edit, Delete, ⋮ ("Re-fetch cover", disabled with an explanation when there's no ISBN). All appear once loaded. **While a refetch runs, a spinner replaces the ⋮ icon.** |

Edit already matches across all three, since #173 gave TV an edit screen. The differences left
are where Delete lives, where the secondary actions live, and TV's Delete during loading.

## Decisions (owner, 2026-10-15)

1. **Delete moves behind ⋮** on all three screens, as the menu's **last** item, in the theme's
   error colour. It keeps its existing confirmation dialog. Every bar reads **← · Edit · ⋮**.
2. **No ⋮ until the item has loaded**, on all three screens. TV no longer offers Delete while
   loading, so a delete always acts on a show the user has seen.
3. **During a Book cover refetch, the ⋮ stays.** The "Re-fetch cover" item shows its in-progress
   state (disabled, with a small spinner and the existing "Re-fetching cover…" text). Delete stays
   reachable.
4. **One shared component** builds the actions, so the three bars can't drift apart again.
5. **The ⋮ button's accessibility label is "More options"** on every screen.

## Design

### `DetailTopBarActions`, in `app/.../ui/components/`

```kotlin
/** One secondary action in a detail screen's ⋮ menu (#172). */
data class DetailMenuAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** Shown under the label when present, e.g. why a disabled action is unavailable. */
    val supportingText: String? = null,
    /** Disabled, with a small progress indicator in place of its leading slot. */
    val inProgress: Boolean = false,
)

/**
 * A detail screen's top-bar actions: Edit as an icon, then ⋮ holding [secondary] actions and,
 * always last and in the error colour, Delete. The caller shows these only once the item has
 * loaded.
 */
@Composable
fun DetailTopBarActions(
    editContentDescription: String,
    onEdit: () -> Unit,
    secondary: List<DetailMenuAction>,
    deleteLabel: String,
    onDelete: () -> Unit,
)
```

- **⋮** uses `Icons.Filled.MoreVert` with the content description `R.string.detail_more_options`
  ("More options"), and opens a `DropdownMenu`.
- **Menu order:** the `secondary` actions in the order given, then a `HorizontalDivider` if there
  are any, then **Delete**. Delete's text and leading icon use `MaterialTheme.colorScheme.error`.
- **Choosing an item** closes the menu, then calls its `onClick`. Delete's `onClick` is the screen's
  existing "show the confirmation dialog", unchanged.
- **Test tags** (`TestTags.DetailTopBar.OVERFLOW`, `.DELETE`), for device checks only. The tests
  prefer semantic matchers per AGENTS.md §7: content description for ⋮, text for the items.

### Per screen

| Screen | `secondary` |
| --- | --- |
| Film | none (⋮ holds only Delete) |
| TV show | "Refresh episode details from TMDB", only when `canRefreshMetadata` |
| Book | "Re-fetch cover": `enabled = hasIsbn && !isRefetchingCover`; `supportingText` = the no-ISBN explanation when there is no ISBN; `inProgress = isRefetchingCover` with the label "Re-fetching cover…" |

Each screen's `actions = { if (uiState is Ready) DetailTopBarActions(…) }`. TV's ungated Delete
goes away.

**Strings.** New: `detail_more_options` ("More options"). Delete items reuse the existing
`movie_detail_delete` / `tv_show_detail_delete` / `delete_book_content_description` ("Delete
movie" / "Delete show" / "Delete book") as their visible text. Book's
`book_detail_overflow_menu_content_description` is removed, if nothing else uses it.

## Testing (TDD, in the Robolectric lane; red first)

- **New `DetailTopBarActionsTest`** (`app/src/test/.../ui/components/`):
  - ⋮ is found by "More options" and opens the menu;
  - the secondary actions appear in order, with Delete last;
  - choosing Delete calls `onDelete` exactly once and closes the menu;
  - a disabled action is not clickable and shows its supporting text;
  - an in-progress action is disabled and shows its progress indicator;
  - Edit calls `onEdit`.
- **Existing screen tests, updated where the behaviour deliberately changes:**
  - **Delete tests (all three):** open ⋮ first, then choose Delete. The dialog and confirm/cancel
    assertions are unchanged.
  - `TVShowDetailScreenTest.deleteAction_isShownEvenDuringLoading_unlikeMovieAndBook` becomes
    `loading_hidesOverflowMenu`, matching the Film and Book loading tests. The behaviour change is
    deliberate; the test is renamed, not weakened.
  - `BookDetailScreenTest.refetchingCover_showsProgressInTopBar_insteadOfOverflowIcon` becomes:
    during a refetch ⋮ is still present, the refetch item is disabled and shows progress, and Delete
    is reachable.
  - `BookDetailScreenTest.overflowMenu_refetchItem_*`: same assertions, found through "More options".
- **Goldens:** the four detail goldens (`MovieDetailScreen`, `MovieDetailHistory`, `TVShowDetailScreen`,
  `BookDetailScreen`) change, because the bar's icons change. Re-record them by hand, look at every
  image, and commit the PNGs in their own commit, with no assertion changes (AGENTS.md §7).
- **Device:** `app/src/` changes, so run the full `connectedDebugAndroidTest` (39 tests, about 2.5
  minutes), then `seedDebugDevice`, then a hand check on the phone: open each detail screen's ⋮, and
  delete through it with Cancel.

## Out of scope

The add/edit screens (#174). The library's top bar. What any action does. Changing the delete
confirmation dialog.

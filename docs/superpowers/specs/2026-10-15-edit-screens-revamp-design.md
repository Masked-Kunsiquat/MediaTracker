# Edit screens get the detail screens' look (#174, first pass)

**Status:** approved in brainstorming 2026-10-15; this file is the written spec.
**Branch:** `feat/174-add-edit-revamp`, stacked on `feat/172-detail-top-bars` (PR #212).

## Why

#141 redesigned the three detail screens with a header, a status chip, grouped cards and 16dp
margins. The edit screens never got the same pass, and they don't even match each other:

- **Edit Book** groups its fields into titled cards and has a pinned Cancel / Save bar. Its status
  is a horizontally scrolling chip row that cuts "Did not finish" off (see the `edit-book` golden).
- **Edit Film** is one flat column. Status is four stacked `FilterChip`s, and Save is a button at
  the end of the form.
- **Edit TV** is one flat column with Save at the end. It has no status control by design: the
  detail screen owns abandoning a show.

## Scope

**In scope:** Edit Book, Edit Film, Edit TV.

**Out of scope:** the three Add screens. They are search-first flows with a different shape, so
they get a follow-up issue filed by this branch. There are no behaviour changes: what is saved,
validation, and the "when did you watch it" dialog all stay exactly as they are.

## Decisions (the owner's, from brainstorming)

| Question | Decision |
| --- | --- |
| Scope | Edit screens first; Add screens follow up |
| Header | A compact, read-only header on every edit screen |
| Header title | The **saved** title (what the screen opened with), not the live field |
| Status control | The detail screens' dropdown chip (`StatusDropdownChip`) |
| Grouping | Titled section cards on all three screens, with 16dp side margins |
| Save | A pinned Cancel / Save bottom bar on all three screens (Book's existing bar) |

## Design

### Shared components (`app/.../ui/components/`)

1. **`EditHeader(kind, year, title, artwork)`**: a compact header row with a small cover thumbnail
   when there is one, the title as a heading, and the "Film · 1999" kind line underneath. It has no
   rating, status or synopsis. The thumbnail reuses `DetailArtwork` and the same cover-loading
   path, at a smaller size. With no cover it draws no artwork slot, as `DetailHeader` does.
2. **`FormSection(title, content)`**: moved out of `EditBookScreen.kt` (where it is `private`)
   unchanged. It is a `titleMedium` heading over a `Card` whose column is spaced 16dp.
3. **`EditBottomBar(isSaving, canSave, onCancel, onSave, cancelTag, saveTag)`**: moved out of
   `EditBookScreen.kt` (`EditBookBottomBar`) unchanged, except that the test tags become
   parameters so each screen keeps its own `TestTags.EditX.*` constants.

### Per screen

- **Edit Book**
  - The header goes above the existing sections.
  - In the **Status** section, `StatusChipRow` becomes a `StatusDropdownChip` over `ReadingStatus`,
    with the same "Reading status" label above it.
  - Everything else stays as it is.
- **Edit Film**
  - Header, then the cards:
    - **Details:** title, year, runtime.
    - **Status:** the dropdown chip over `WatchStatus`. Choosing it still calls `onStatusChange`, so
      picking Watched still opens `FilmWatchedDialog`.
    - **Purchase:** price.
  - Save moves into `EditBottomBar`, with Cancel calling `onNavigateBack`. The enable rule is the
    current one (a non-blank title, every numeric field readable, not saving).
- **Edit TV**
  - Header, then the cards:
    - **Details:** title, year, seasons.
    - **Purchase:** price.
  - It gets `EditBottomBar` the same way. It still has no status control.

All three keep their `contentWindowInsets` / IME handling and their Loading, LoadFailed and NotFound
branches. The header shows only in the editable state.

### Data (`shared`)

The header needs the saved title, year and cover hash.

- **Book:** `EditBookUiState.Ready` is re-derived from the database on every emission, so its
  `title` and `releaseYear` are already the saved values. It gains `coverImageHash: String?`.
- **Film / TV:** the `Editing` states hold live form text, so they gain a separate snapshot taken at
  load: `savedTitle: String`, `savedYear: Int?` and `coverImageHash: String?`, all from
  `MediaItemEntity`. Editing the fields never changes them.

Nothing about saving changes.

### Strings

New section titles are added only where Film and TV need ones Book lacks. The existing
`edit_section_*` strings are reused where the meaning matches. Kind labels reuse the strings the
detail headers already use.

## Testing

TDD throughout, with each test red first.

- **Shared (`shared/src/jvmTest/kotlin/`, since these tests touch Room through `testAppDatabase()`):**
  the Film and TV view models expose the saved title, year and cover
  hash. Editing the title field leaves `savedTitle` unchanged. Book's `Ready` carries the cover hash.
- **Robolectric screen tests (`app/src/test`):**
  - The header shows the saved title and kind line.
  - Status is chosen through the dropdown on Book and Film; on Film, Watched still raises the dialog.
  - Save and Cancel live in the bottom bar on Film and TV, and Save's enable rule is unchanged.
  - Existing tests that tapped a status `FilterChip` or the inline Save are updated to the new
    controls.
  - The occlusion lane covers the new bottom bar on Film and TV: it must clear the keyboard and the
    navigation bar.
- **Goldens:** re-record `edit-book`, and add `edit-film` and `edit-tv` (each paired with an
  assertion). All are viewed before they are committed, and committed alone.
- **Device:** a full `connectedDebugAndroidTest`, then `seedDebugDevice`, then a hand check of all
  three screens, including Film → Watched → dialog.

## Risks

- **Instrumented tests** that tap the old status chips or the inline Save will break at compile or
  run time. The device run is what catches that; grep `androidTest` for them up front.
- **The IME insets CI check:** the files keep their `contentWindowInsets` assignment. Moving the
  Save button into a `bottomBar` must not drop it.

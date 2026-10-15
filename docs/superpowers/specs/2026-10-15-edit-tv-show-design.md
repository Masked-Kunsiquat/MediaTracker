# Edit a TV show (#173) — design

Status: approved in chat 2026-10-15. Issue: [#173](https://github.com/Masked-Kunsiquat/MediaTracker/issues/173).
Neighbours deliberately out of scope: [#172](https://github.com/Masked-Kunsiquat/MediaTracker/issues/172)
(top-bar consistency) and [#174](https://github.com/Masked-Kunsiquat/MediaTracker/issues/174) (add/edit
screen revamp).

## Problem

A TV show cannot be edited. Films and books have an Edit screen reached from the detail screen's top
bar; a show has none, so a title, year or price typed in by hand (or imported with a typo) can never
be corrected. A show not on TMDB gets nothing from Refresh either.

## What already exists

- `TVShowRepository.updateShowMetadata` → `TVWriteDao.updateShowMetadataAtomically` writes title,
  year, price (scoped `type = 'TV_SHOW'`), `totalSeasons` and `status`, and self-heals a missing
  `tv_details` row. It is covered by `TVShowRepositoryTest` and **called by no production code**.
- Refresh (`BulkTmdbBackfillUseCase` → `TVWriteDao.fillShowMetadata`) wraps every column in
  `COALESCE(column, :value)` and never writes `title`.
- `tv_details.status` is an abandonment flag: only `ABANDONED` is read; progress is derived from
  episodes. The detail screen's Abandon toggle writes it via `updateWatchStatus`.

## Decisions

| Question | Decision |
| --- | --- |
| Editable fields | **Title, release year, total seasons, purchase price** — the Add form's fields, and what the repository already writes. No synopsis, airing status or air dates (films' Edit has none of these either, so #174 inherits one shape). |
| Edit vs. a later Refresh | **A typed value always wins**, already guaranteed by `COALESCE`. A field the user **clears** is `null` = unknown, and the next Refresh may fill it again. Accepted and documented; no schema change. |
| Status | **Not on the form.** Abandon stays the detail screen's toggle. The edit save stops writing `status` at all, so an edit can never undo an abandon. |
| Seasons | Per-season episode counts stay in the existing season dialog. Only the advisory `totalSeasons` is on the form; it changes no episode rows. |
| Shape | A dedicated `EditTVShowViewModel` + `EditTVShowScreen` mirroring `EditMovie*`, not a generalised media form (that is #174's call) and not an edit mode of `AddTVShowScreen` (which carries season rows). |

## Design

### Data (`:shared`)

- `TVWriteDao.updateTVDetailFields` sets `totalSeasons` only; its `status` parameter is removed.
- `TVWriteDao.updateShowMetadataAtomically` drops `status`. When the `tv_details` row is missing it
  inserts `TVDetailsEntity(mediaId, totalSeasons)`; the default `WATCHLIST` carries no meaning
  (only `ABANDONED` is read), so the repair invents nothing a user would see.
- `TVShowRepository.updateShowMetadata` drops `status`; validation via `TVMetadataValidation` and the
  `Resource` contract are unchanged. KDoc on both records the edit-vs-Refresh rule above.
- Existing `updateShowMetadata` tests are updated for the new signature.

### ViewModel (`shared/.../ui/EditTVShowViewModel.kt`)

`EditTVShowUiState`: `Loading`, `NotFound`, `Editing(title, releaseYear, totalSeasons, purchasePrice,
isSaving, saveError, saved)`.

- Loads **once** from `observeShowDetail(showId).first()` (a live form would overwrite typing).
  Unknown numbers load as blank strings.
- `save()`: blank → `null`; text that does not parse is refused with
  "Enter a valid <field>, or clear the field" rather than sent as `null`; title trimmed; range rules
  left to `TVMetadataValidation`. A save while `isSaving` is ignored. Success sets `saved`; failure
  sets `saveError` and keeps the form. `consumeSaveError()` clears it.

### App (`:app`)

- `ui/screens/EditTVShowScreen.kt`: `EditTVShowScreenRoute` (ViewModel, navigates back on `saved`)
  and a stateless `EditTVShowScreen(uiState, callbacks…)`. `Scaffold(contentWindowInsets =
  WindowInsets.safeDrawing)`. Field labels reuse `add_tv_show_field_*`; numeric input goes through
  the existing `ui/text/` filtering, as `EditMovieScreen` does. `NotFound` navigates back, as in `EditMovieScreenRoute`; save
  errors show in a snackbar.
- `Route.EditTVShow` (arg `showId`), a `NavHost` entry, `EditTVShowViewModelFactory`.
- `TVShowDetailScreen` gains `onNavigateToEdit: () -> Unit`. Edit is the **first** top-bar action
  (Edit, Refresh, Delete), shown only in `Ready`, like films. This is #172's proposed end state for
  Edit, so folding Refresh/Delete into ⋮ later does not move it.

### Errors

| Case | Behaviour |
| --- | --- |
| Show deleted before/while opening | `NotFound` → route navigates back |
| Unparseable number | Save refused, message names the field, form kept |
| Validation or DB failure | `Resource.Error` → snackbar, form kept |
| Show deleted between load and save | Repository returns "not found" → snackbar |

## Testing

Each test is written first and seen to fail.

- **jvmTest, repository/DAO:** an `ABANDONED` show stays `ABANDONED` after an edit; an edit leaves
  synopsis, airing status, air dates and rating untouched; an edited year survives a later
  `fillShowMetadata`; a cleared year *is* refilled by `fillShowMetadata` (pins the accepted rule);
  the missing-row self-heal still lands `totalSeasons`.
- **jvmTest, ViewModel:** load (found / not found / nulls as blank), save success, unparseable field
  refused, repository error surfaced, double-save ignored. Await with `uiState.first { }`, never
  `.value` after an action.
- **Robolectric (`app/src/test`):** `EditTVShowScreenOcclusionTest` (no control under the IME);
  a detail top-bar test: Edit present in `Ready` and calls `onNavigateToEdit`, absent while loading.
- **androidTest:** `EditTVShowScreenTest` (types into the fields, saves); `TVShowDetailScreenTest`
  updated for the new parameter and the Edit action, so the source set keeps compiling.
- **Goldens:** no new golden (#174 will restyle this screen). The TV detail golden gains the Edit
  icon: re-record, look at the PNG, commit it alone.
- **CI rule:** the new screen has a `Scaffold` and text fields, so `contentWindowInsets` is required.

## Docs

`CHANGELOG.md` `[Unreleased]` → `Added`: TV shows can be edited. `ROADMAP.md`'s #173 line updated on
this branch.

## Non-goals

Editing synopsis, airing status, air dates, rating or cover; pinning cleared fields against Refresh;
status on the form; moving Refresh/Delete into ⋮ (#172); restyling add/edit screens (#174).

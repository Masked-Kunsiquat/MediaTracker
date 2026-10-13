# Film watch history — design (#192)

Follow-up to #153. Episodes gained a "when?" prompt on tick (#131) and a viewing history (#90);
films get the same, so both ship watch history in one release. Decided with the owner on
2026-10-13.

## Decisions

| Question | Decision |
| :--- | :--- |
| Leaving Watched (to Watching, Watchlist, …) | **History stays.** Status is "where am I now"; viewings are "what happened". A snackbar offers Undo for the status change. |
| Ask when on becoming Watched | **Yes, everywhere status can become Watched**: the detail screen, Add and Edit. |
| Prompt options | **Now / Pick a date.** Films store only `releaseYear`, so there is no release *date* to offer (unlike an episode's air date). |
| Deleting a Watched film's last viewing | The film moves to **Watchlist**; Undo restores the viewing and the status. Mirrors an episode unticking when its last viewing goes. |
| Stats | Unchanged: they read `watchedAt`. A film partway through a rewatch (Watching) is not counted as watched, the same as today. |

## Model

No schema change. `watch_logs.episodeId` is already nullable for film rows, and `mediaId` has a
foreign key to `media_items` with cascade delete.

**Invariant (films):**
- If `movie_details.status == WATCHED`, then `watchedAt == MAX(watch_logs.watchedAt WHERE mediaId = film AND episodeId IS NULL)`.
- Otherwise `watchedAt == null`.

Viewings exist independently of status. It is enforced inside each `@Transaction` write, through one
derivation, `recomputeFilmWatchedAt(mediaId)`:

```sql
UPDATE movie_details SET watchedAt = CASE WHEN status = 'WATCHED'
  THEN (SELECT MAX(watchedAt) FROM watch_logs WHERE mediaId = :id AND episodeId IS NULL)
  ELSE NULL END WHERE mediaId = :id
```

A Watched film with no viewings is not a state any write produces. The one legacy case (Watched
with `watchedAt` set and no viewing) is closed by the backfill below.

**Transitions.** Old status → new status:

| From → to | Write |
| :--- | :--- |
| not Watched → Watched, with `WatchedWhen` | Set status; insert a viewing at the resolved instant; recompute. |
| Watched → Watched (re-save) | Nothing changes for viewings. |
| Watched → other | Set status; recompute (→ null). Viewings stay. |
| other → other | Set status only. |

**Resolving `WatchedWhen` for films.**
- `Now` gives now.
- `On(date)` gives noon local, clamped to now, through the existing `resolveWatchedAt(watchedWhen, airDate = null, now, zone)`.
- `OnAirDate` is never offered for films.

## Data layer (`shared`)

- **`MovieWriteDao`** (all `@Transaction`), mirroring `TVWriteDao`:
  - `setStatusWatchedAt(mediaId, status, at: Instant?)`: the transition table above. `at` is required exactly when the move is into Watched.
  - `addFilmViewing(mediaId, at)`: Watched again. Precondition: status is Watched.
  - `deleteFilmViewing(id): WatchLogEntity?`. If it was the last viewing and the film is Watched, set the status to Watchlist. Returns the log and the previous status for Undo.
  - `updateFilmViewingDate(id, at)`.
  - `restoreFilmViewing(log, previousStatus)`. FK-safe: a no-op if the film is gone.
  - `restoreFilmStatus(mediaId, status)`: Undo for leaving Watched; it recomputes.
  - `recomputeFilmWatchedAt(mediaId)`.
- **`WatchLogDao`:** `observeForFilm(mediaId)`; `observeAll` already includes film rows.
- **`MovieRepository`:**
  - `updateWatchStatus(mediaId, status, watchedWhen: WatchedWhen? = null)` returns `Resource<StatusChange>`, carrying the previous status for Undo.
  - `updateMovieMetadata(..., watchedWhen)` and `addMovie(..., watchedWhen)`: replace the `clock.now()` stamping in `resolveWatchedAt` (the companion function) with the transition table.
  - `observeViewings`, `addViewing`, `deleteViewing`, `restoreViewing`, `editViewingDate`, `restoreStatus`.
- **`EnsureViewingsUseCase`** also gives each Watched film that has `watchedAt` but no viewing exactly one viewing at `watchedAt`.

## UI

- **`ui/components/FilmWatchedDialog.kt`:** title "Watched *Title*", with **Now** and **Pick a date**. Pick a date opens `WatchedDatePickerDialog`. It reports `WatchedWhen`.
- **`MovieDetailViewModel`:**
  - `requestStatus(status)`: becoming Watched sets `pendingWatched`; anything else writes immediately and, when leaving Watched, emits `statusUndo`.
  - `confirmWatched(when)` and `dismissWatched()`.
  - `requestWatchedAgain()` and `confirmWatchedAgain(when)`.
  - `deleteViewing(log)`, which merges Undo the way `ViewingUndo` does; `undoViewing()`; `editViewingDate(log, picked)`.
  - `Ready` gains `viewings`, `pendingWatched`, `pendingWatchedAgain`, `viewingUndo` and `statusUndo`.
- **`MovieDetailScreen`:**
  - A **History** section under the details, with one row per viewing ("Watched Mar 3, 2024", Edit date, Delete), newest first.
  - **Watched again** shows when the film is Watched.
  - The status dropdown routes through `requestStatus`.
  - Snackbars: "Moved to Watching" with Undo, and "Viewing deleted" with Undo.
  - The row layout is extracted from `EpisodeSheet`'s viewing row into a shared `ui/components/ViewingRow.kt`, so episodes and films render a viewing identically.
- **`EditMovieViewModel` / `AddMovieViewModel`:** choosing Watched from another status opens `FilmWatchedDialog`.
  - Confirming stores `watchedWhen` in the form state, and Save passes it to the repository.
  - Dismissing reverts the dropdown.
  - Re-selecting Watched on an already-Watched film (Edit) does not prompt.

## CSV

- **`WatchLogCsvExporter`:** film rows export with an empty `episode_id`. The CSV schema stays **v6**: the column and header are unchanged, and v6 importers already read the column.
- **`WatchLogCsvImporter`:** a blank `episode_id` is a film viewing. The "film viewings are not imported yet" rejection is removed.
- **`ImportDataUseCase`:** the same rules as episodes, keyed on the film.
  - Resolve `media_id` through `mediaResolution.resolvedMediaId`.
  - Accept only a MOVIE.
  - Dedup on (film, watchedAt).
  - REPLACE drops the stored film viewings for every film this import replaced; MERGE and SKIP take the union.
  - Count into `viewingsImported` / `viewingsSkipped`.
- **`ImportWriteDao.importAtomically`:** normalise every touched film (inserted, updated, or given viewings).
  - If the film is Watched with `watchedAt` and has no viewing at that instant, insert one.
  - Then `recomputeFilmWatchedAt`.
  - This is the same never-move-backwards rule as episodes (a56ac63).
- **`docs/sample-data/watch_logs_sample.csv`:** add one film with two viewings. Update `CSV_FORMAT.md`.

## Testing

- **jvmTest, DAO:** the transition table row by row; the invariant after every write; deleting the last viewing moves the film to Watchlist; restoring after the film is deleted is a no-op; restoring status recomputes.
- **jvmTest, `MovieRepositoryTest`:** Add/Edit with `watchedWhen`; a re-save adds no viewing.
- **jvmTest, `EnsureViewingsUseCaseTest`:** a film case.
- **jvmTest, `MovieDetailViewModelTest` (new):**
  - Becoming Watched prompts, and confirming adds a viewing.
  - Leaving Watched keeps viewings, and Undo restores.
  - Watched again works.
  - Delete with merged Undo; editing a date recomputes.
  - Uses `uiState.first {}`, never `.value`.
- **jvmTest, `WatchLogImportExportTest`:** film round trip; a re-import creates nothing; REPLACE means the file wins; a film viewing pointing at a show is rejected; the never-backwards rule.
- **Robolectric:** the stateless History section's callbacks; one golden (`movie-detail-history.png`).
- **androidTest:** editing a date on a film viewing opens on that viewing's day (the dialog holds a date input).
- **Device:** run the full suite, then reseed and drive the flow manually.

## Out of scope

- A release *date* for films (a TMDB field the app does not store). It would enable "On release date".
- Stats counting rewatches.
- Bulk status changes from the library (no such path exists today).

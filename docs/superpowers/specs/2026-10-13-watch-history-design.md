# Watch history: viewings, Rewatched, and correcting a date — design (#90, part B of #153)

Status: design approved in conversation 2026-10-13. This document is the written spec for review.

## Why

An episode has one `watchedAt`. Watching it again overwrites the first date, so the history that
`watch_logs` (shipped empty in schema v6) exists to hold is destroyed at the moment it is
created. #131 (part A, v0.23.0) made ticks ask *when* and gave unticking an exact-date Undo. This
part makes every viewing a row, so the app can:
- record a re-watch;
- show an episode's viewing history;
- correct the date of something already watched — the gap #131 left.

## Decisions (owner, 2026-10-12 and 2026-10-13)

- **Untick and Rewatched are separate actions**, as in TV Time (#153).
- **Viewings are the truth.** Unticking means "I haven't watched this": it removes the episode's
  viewings, and Undo restores them all. To drop a single re-watch, use the history.
- **Episodes only.** Films (`watch_logs.episodeId` is nullable for them) are a follow-up issue,
  reusing this design.
- **Viewing history is in the CSV export and import.** Otherwise it would survive only a
  `.sqlite` backup, which is a data-safety gap under AGENTS.md §1.
- **There are no users yet.** Upgrade handling is good practice, not a constraint: a cheap,
  idempotent backfill rather than a migration. Future agents should not treat migration
  compatibility as a blocker until the app ships publicly.

## The model

**Invariant:** for every episode, `episodes.watchedAt` is the date of its **latest** viewing, or
`null` when it has none.

Every write below runs in **one transaction** and leaves the invariant true. Because of this,
progress counts, Up next (#89), the library and Include specials (#88) keep reading `watchedAt`
unchanged. The checklist is a cached view of the logs.

| Operation | Viewings | `watchedAt` |
|---|---|---|
| **Tick** (the #131 prompt, one, catch-up or season) | +1 per episode written | set (only if unwatched, as today) |
| **Watched again** | +1 | `max(current, new)` |
| **Untick** / **Clear season** | all of that episode's deleted | `null` |
| **Undo an untick** | the deleted viewings restored | restored to their max |
| **Delete one viewing** | −1 | recomputed: max of the rest, or `null` |
| **Edit a viewing's date** | updated | recomputed |

- **Undo stays conditional.** It restores an episode only if that episode is still unwatched and
  has no viewings. A re-tick or re-watch made before Undo wins, as in #185.
- **Deleting the last viewing un-ticks the episode.** It offers the same Undo snackbar as an
  untick.
- **"Watched again" never stamps the future.** It uses `WatchedWhen` / `resolveWatchedAt` from
  #131: noon local, clamped to now, air dates read in UTC.

## Interaction (episodes)

- **The circle keeps its job:** tick (opens the #131 prompt) and untick (instant, with Undo).
- **Tapping an episode row's text** opens an **episode sheet** (`ModalBottomSheet`) containing:
  - the title, or "Episode N";
  - the air date;
  - **Viewings**, newest first: each row shows the date, with **Edit date** (opens the date picker
    from `MarkWatchedDialog`'s picker) and **Delete** (Undo snackbar);
  - **Watched again**, which opens `MarkWatchedDialog` titled "Watched "<title>" again?" with Now /
    On air date / Pick a date… and **no catch-up**.
- **An unwatched episode's sheet** shows "Not watched yet". Its primary action is the normal tick.
- **The row label:** when an episode has 2 or more viewings, its date line reads "Watched 3×".
  Air date stays first, for example "May 13, 2019 · Watched 3×".

## Data and domain

- **`WatchLogDao`** (new, in `core/database/dao/`). Read-only queries:
  - `observeForEpisode(episodeId): Flow<List<WatchLogEntity>>`;
  - `viewingCounts(mediaId): Flow<Map<episodeId, Int>>` (one `GROUP BY` for the row labels).
- **Writes** go in `TVWriteDao`, which already owns multi-row TV writes:
  - The tick path (`markEpisodesWatchedAt`) also inserts one viewing per episode it writes — only
    for rows the `IS NULL` guard actually changed.
  - `addViewing(episodeId, at)`.
  - `deleteViewing(logId)` and `updateViewingDate(logId, at)`, each followed by
    `recomputeWatchedAt(episodeId)` in the same transaction.
  - `clearEpisodeReturningPrevious` / `clearSeasonReturningPrevious` now also delete and return
    the episode's viewings.
  - `restoreAll` restores viewings and `watchedAt` together, under the conditional rule above.
- **The Undo payload changes.** `UntickEvent.previous` becomes the removed viewings,
  `List<WatchLogEntity>`. `watchedAt` is re-derived from them on restore, so the two can't
  disagree. Merging two unticks (#185) concatenates the lists.
- **Use cases:**
  - `MarkEpisodesWatchedUseCase` is unchanged in shape; the DAO it calls now also writes viewings.
  - `RecordRewatchUseCase(episodeId, watchedWhen)` writes one viewing, resolving the time through
    `resolveWatchedAt`.
  - Viewing edits and deletes go through `TVShowRepository`.
- **Backfill:** `EnsureViewingsUseCase` runs once per app start from `AppContainer`, off the main
  thread. For every episode with `watchedAt` set and **no** viewing, it inserts one viewing at
  that date. It is idempotent by construction (a "no viewing" filter), with no flag and no
  migration.
- **CSV:**
  - A new `watch_logs_export.csv` file, with columns `csv_schema_version`, `log_id`, `media_id`,
    `episode_id`, `watched_at`, `duration_seconds`.
  - `WatchLogCsvExporter` / `WatchLogCsvImporter` follow the `EpisodeCsvExporter` /
    `EpisodeCsvImporter` pattern, and `CSV_SCHEMA_VERSION` gets bumped according to that file's
    own rules.
  - Import **skips a viewing that already exists** (same `episode_id` and `watched_at`). Dedup
    lives in the importer, not in a unique index: #153 rejected one, because date-only precision
    can make two genuine viewings collide.
  - Importing an episode row with `watched_at` and no matching viewing creates one, preserving
    the invariant.
  - `docs/sample-data/CSV_FORMAT.md` documents the new file. The sample data gains a re-watched
    episode, so the device shows "Watched 2×".
- **No schema change.** `watch_logs` exists in v7 already.

## Testing

- **jvmTest:**
  - **Invariant.** After each of tick (single, catch-up, season), watched again (including a
    back-dated one), untick + Undo, clear season + Undo, delete one, delete last + Undo, and edit
    date: `watchedAt == max(viewings)`.
  - Undo after a re-tick or re-watch writes nothing.
  - The backfill is idempotent: two runs create exactly one viewing per watched episode.
  - CSV: an export → import round-trip is lossless, re-import creates no duplicates, and an
    episode imported as watched gets a viewing.
  - View models, using `first { }` and never `.value`:
    - "Watched again" adds a viewing;
    - a double confirm writes once (`StandardTestDispatcher`);
    - deleting the last viewing un-ticks with Undo.
- **Robolectric:** the episode sheet's actions and empty state, and the "Watched N×" label.
- **androidTest:** editing a viewing's date through the picker (device-only, AGENTS.md §7).
- **Golden:** one for the sheet with three viewings, paired with a non-visual assertion.
- **Falsify each guard:** the recompute after delete and after edit; the conditional restore;
  viewings written only for rows the tick guard changed; importer dedup.

## Out of scope

- Films (follow-up issue).
- Stats that count viewings, such as "hours watched" or "re-watched the most". This data makes
  them possible; building them is Task 11.
- Durations: `durationSeconds` stays null.

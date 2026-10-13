# Tick with a date, untick with Undo — design (#131, part A of #153)

Status: approved in conversation 2026-10-12. Revised the same day after an independent review
(future-date clamp, UTC air dates, conditional Undo, catch-up and season edge cases, test lanes).

## Why

Ticking an episode records **now**, instantly, with no confirmation (#131). That causes two problems:

1. **A mis-tap writes history.** The episode list can run to hundreds of rows, so a stray tap is
   ordinary rather than rare.
2. **"Now" is often wrong.** If you catalogue a show you finished last year, every episode is
   stamped with today's date. `watchedAt` *is* the watch history, so this is a data-fidelity gap,
   not a cosmetic one.

Unticking has the opposite problem. It is instant, but it **loses** the old date for good:
re-ticking stamps today.

#153 groups this with re-watches (#90). The owner settled the model: there are **two separate
actions**, as in TV Time. *Untick* corrects a mistake; *Rewatched* logs another viewing. This spec
is **part A**, the tick and untick interaction. Part B (#90) is out of scope and listed at the end.

## Decisions (owner, 2026-10-12)

- **Every tick asks first.** The app's most frequent action now costs one more tap. The tap is
  doing work: it asks *when*, not "are you sure".
- **The prompt offers Now / On air date / Pick a date… / Cancel.** On air date appears only when it
  can be honoured (see the rules below).
- **A catch-up checkbox:** "Also mark the N earlier episodes". It appears when earlier episodes are
  unwatched and starts **unchecked**. This is TV Time's "started tracking mid-show" case.
- **Unticking is instant, with an Undo snackbar** that restores the exact previous timestamp. A
  dialog would tax every deliberate untick and still couldn't give the date back after a reflexive
  confirm.
- **One interaction everywhere:** the show screen's episode rows, its "Mark season watched", and Up
  next's check button (#89) all use it.

## Interaction

### Ticking one episode

```
Mark "Please Remain Calm" watched?
    [ ] Also mark the 3 earlier episodes          <- only when N >= 1
    [ Now ]
    [ On air date ]
    [ Pick a date… ]
    [ Cancel ]
```

- **Title.** Uses the episode title, falling back to "Episode 4" for a quick-filled episode with no
  title. That is the same string resource Up next already uses (`up_next_untitled_format`), so there
  is one fallback rather than two.
- **Layout.** The four choices are **stacked full-width buttons**, not a row. Four buttons plus a
  checkbox do not fit side by side in an `AlertDialog` at a large font scale.
- **Earlier episodes.** "Before" is defined by Up next's ordering key,
  `(seasonNumber = 0), seasonNumber, episodeNumber`: regular seasons ascending, then specials. So if
  the target is itself a special, "earlier" means every regular episode plus the specials that come
  before it.
  - Candidates are unwatched, aired, and allowed: specials only while Include specials (#88) is on.
  - "Aired" means `airDate IS NULL OR airDate <= now`, with `now` taken from the repository's
    injected clock, so tests are deterministic.
  - Whatever date the user picks is applied to **all** N earlier episodes as well as the target.
    That is intended: catch-up means "I watched these too, around then".
- **An unaired or excluded target.** The show screen can still tick an episode that has not aired,
  or a special while specials are excluded. The dialog allows it, without catch-up. On air date is
  hidden for an unaired target because that date is in the future.
- **Now** stamps the current time.
- **On air date** gives each affected episode **its own** air date. It is shown only if *every*
  affected episode has an `airDate` that is **not in the future**; ticking the checkbox can therefore
  hide it. The app never guesses a date for an episode that doesn't have one. Catch-up can give an
  earlier episode a later date than the target (a late-aired special, for example). That is correct:
  each episode gets its own date.
- **Pick a date…** opens a Material date picker; dates after today are disabled. It opens on the
  target's air date when there is one, and on today otherwise. The chosen date is stored as
  **12:00 local time** on that day, so a time-zone shift can never move it to the neighbouring day.
  The time is then **clamped to `now`**: picking *today* before noon must not store a future
  `watchedAt`, because Up next sorts by `MAX(watchedAt)` and Stats counts watch dates. The same date
  is applied to every affected episode.
- **Cancel** writes nothing.

### Marking a whole season

The same dialog, worded "Mark the 12 unwatched episodes of Season 4 watched?". The count is exactly
what will be written. It has no catch-up checkbox: the whole season already is the bulk action.

- **Only unwatched, aired episodes are written.** An episode that is already watched keeps its date
  (the `watchedAt IS NULL` rule). An unaired episode is left alone, so neither Now nor On air date
  can stamp an episode that hasn't aired. If every unwatched episode in the season is unaired, the
  season control is not offered.
- **On air date** follows the same rule as above: shown only if every episode to be written has a
  past `airDate`.
- **The use case replaces `TVShowRepository.setSeasonWatched(..., watched = true)`.** The
  `watched = false` direction stays, as Clear season. `TVWriteDao.markSeasonWatched` is removed once
  nothing calls it, so there are not two ways to mark a season.

### Unticking

There is no dialog. The episode is unwatched at once, and a snackbar says
`Unmarked "Please Remain Calm".  [UNDO]`. Undo writes back the **exact** previous `watchedAt`.
"Clear season" behaves the same way: Undo restores every cleared episode's own previous timestamp.

- **Read and clear happen in one `@Transaction`**, so the value Undo carries is exactly the one
  removed.
- **Restore is conditional:** `WHERE id = :id AND watchedAt IS NULL`. If the episode was re-ticked
  (or re-marked by a season action) before Undo, the newer value wins and Undo writes nothing. An
  episode deleted in the meantime matches no row. Restoring a season is one transaction over its
  rows, under the same per-row condition.
- **Only the latest Undo is live.** A new untick replaces the pending event, and the snackbar for
  the previous one is dismissed.

### Up next

The check button opens the same dialog for the offered episode. **This deliberately removes** what
#89 shipped a few hours earlier: Up next's instant tick and its "Marked … watched / Undo" snackbar.
That means `MarkedWatchedEvent`, `UpNextViewModel.markWatched`/`undo`/`consumeMarked`, and the tests
covering them. They are replaced by the dialog and its tests rather than kept alongside.

## Data and domain

- **No schema change.** `episodes.watchedAt` already exists and is nullable.
- **`WatchedWhen`** (shared, `features/tv/domain`) is a sealed type: `Now`, `OnAirDate`, or
  `On(date: LocalDate)`.
- **`MarkEpisodesWatchedUseCase.execute(episodeIds, watchedWhen): Resource<Unit>`** marks a set of
  episodes watched. (Not `when`: that is a Kotlin keyword.) It takes an injectable `Clock` and
  `TimeZone`, so the noon rule and the clamp are testable in a non-UTC zone.
  1. It resolves one `Instant` per episode:
     - `Now` → the clock's current time.
     - `OnAirDate` → that episode's air date, converted to a `LocalDate` **in UTC** (TMDB dates are
       stored as midnight UTC by `TmdbDates.toInstantOrNull`), then to noon local, then clamped to
       `now`. Writing the raw midnight-UTC `Instant` would show as the previous day west of UTC,
       which is the very bug the noon rule exists to prevent. It is an error if any affected
       episode lacks an air date, or has one in the future.
     - `On(date)` → noon local on `date`, clamped to `now`.
  2. It writes them in one transaction, only to episodes that are still unwatched.

  This makes it the single path for every tick, whether one episode, catch-up, or a season.

  **Failure rolls everything back** and returns `Resource.Error`. Nothing is partially marked, and
  the view model emits no event. **Double confirm:** the view model clears `pendingTick` before
  launching, and the use case takes its episode ids from that captured `pendingTick`, never from the
  live list. A second confirm finds nothing pending, and any duplicate write is a no-op under the
  `IS NULL` guard.
- **`TVWriteDao.restoreWatchedAt(id, at: Instant)`** is a new write used only by Undo. It is
  **conditional on `watchedAt IS NULL`** (see Unticking). The existing
  `COALESCE(watchedAt, :watchedAt)` write stays: its refusal to overwrite a date is load-bearing.
- **Untick reads and clears in one transaction,** so the Undo event can carry the old value(s).
- **Catch-up candidates** come from one query: unwatched, aired, allowed episodes of the show that
  sort before the target. "Allowed" uses the same predicate as `observeUpNext`, so Up next and the
  dialog agree on what "earlier" means.

## View models and UI

- `TVShowDetailViewModel` and `UpNextViewModel` each expose:
  - `pendingTick: PendingTick?`, which holds the target(s), the title, N earlier episodes, and
    whether On air date is available;
  - an untick-undo event, with `id`, `previous: Map<episodeId, Instant>`, and a label.

  Opening the dialog fills `pendingTick`; confirming calls the use case with the checkbox state and
  the chosen `WatchedWhen`; dismissing clears it.
- **One shared, stateless `MarkWatchedDialog`** composable in `app/.../ui/components/`. The date
  picker is shown from inside it. The routes own the snackbars, as in the other screens.

## Testing

- **jvmTest:**
  - `MarkEpisodesWatchedUseCase`:
    - each `WatchedWhen`, including noon-local for `On(date)`;
    - `OnAirDate` across several episodes giving each its own date;
    - `OnAirDate` refused when any episode lacks an air date or has one in the future;
    - an already-watched episode never overwritten.
  - The use case in a non-UTC `TimeZone`: an `OnAirDate` episode keeps its calendar date, and a
    pick of *today* before noon is clamped to `now`.
  - `restoreWatchedAt`:
    - restores an exact value;
    - does **not** overwrite an episode that was re-ticked before Undo.
  - The catch-up query:
    - ordering, including a special as the target;
    - unaired episodes excluded;
    - specials included or excluded by the setting.
  - The season path: unaired episodes untouched; the count matches what is written.
  - View models, using `first { }` and never `.value`:
    - prompt → confirm → watched with the chosen date;
    - catch-up marks the earlier episodes too;
    - untick → Undo restores the exact timestamp, including season clear;
    - a double confirm writes once (`StandardTestDispatcher` holds the first confirm pending, per
      AGENTS.md §7);
    - a failed write emits no event and marks nothing.
- **Robolectric lane (`app/src/test`, runs on every PR):** `MarkWatchedDialog` driven statelessly.
  It contains no text field, so it can be tested here, and should be, since this lane gates every
  PR:
  - each button calls back with the right `WatchedWhen`;
  - the checkbox toggles;
  - On air date is hidden when unavailable;
  - an occlusion and layout check at the large font scale: every stacked button is on screen.
- **androidTest:**
  - Only the **date picker** flow runs here, because a dialog containing an input does not go idle
    under Robolectric (AGENTS.md §7).
  - Existing instrumented tests that tick with a plain tap are updated to confirm the dialog.
- **Golden:** one new golden for the dialog in its fullest state (checkbox plus all four choices),
  paired in the same test body with a non-visual assertion through `captureGolden`'s `alsoAssert`.
- **Changelog:** an `[Unreleased]` entry under Added/Changed. The Changelog check requires it.
- **Falsify each guard:** remove each of the following and confirm a test fails each time:
  - the noon rule;
  - the clamp to `now`;
  - the UTC conversion of the air date;
  - the air-date availability rule;
  - the `IS NULL` guards on the write and on restore.

## Out of scope — part B (#90)

- A "Rewatched" action on a watched episode.
- Writing `watch_logs` on every tick.
- A per-episode viewing-history list.
- Editing the date of an episode that is already watched.

The "Mark … watched?" dialog built here is what B's Rewatched action will reuse.

## Risks

- **One extra tap on the most frequent action.** Accepted by the owner. If it proves too much, the
  fallback is to keep the prompt for out-of-order and season ticks and make the next-episode tick
  instant. That would be a change to this spec, not something to drift into.
- **Some instrumented tests tick with a plain tap.** They will fail until they confirm the dialog.
  The device run before the PR is what catches this.
- **The date picker can't be tested under Robolectric.** It only runs on a device, so it gates
  nothing in CI. The pure resolution logic (`WatchedWhen` → `Instant`) lives in the use case's
  jvmTest, so what the picker *does* is still gated; only the picker's own UI is device-only.

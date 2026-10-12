# Tick with a date, untick with Undo — design (#131, part A of #153)

Status: approved in conversation 2026-10-12; this document is the written spec for review.

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
    [ Now ]  [ On air date ]  [ Pick a date… ]  [ Cancel ]
```

- **Title.** Uses the episode title, falling back to "S1 · E4" for a quick-filled episode with no
  title. The same fallback appears in Up next.
- **Earlier episodes.** These are the show's unwatched episodes that sort before this one, in the
  same order Up next uses: regular seasons ascending, specials last. Specials count only while
  Include specials (#88) is on. Unaired episodes (`airDate` in the future) are never counted.
- **Now** stamps the current time.
- **On air date** gives each affected episode **its own** `airDate`. It is shown only if *every*
  affected episode has an `airDate`; ticking the checkbox can therefore hide it. The app never
  guesses a date for an episode that doesn't have one.
- **Pick a date…** opens a Material date picker; dates after today are disabled. The chosen date is
  stored as **12:00 local time** on that day, so a time-zone shift can never move it to the
  neighbouring day. The same date is applied to every affected episode.
- **Cancel** writes nothing.

### Marking a whole season

The same dialog, worded "Mark all 82 episodes of Season 4 watched?". It has no catch-up checkbox:
the whole season already is the bulk action. Only *unwatched* episodes are written, so an episode
that is already watched keeps its date (the existing `watchedAt IS NULL` rule). On air date follows
the same every-episode-has-one rule.

### Unticking

There is no dialog. The episode is unwatched at once, and a snackbar says
`Unmarked "Please Remain Calm".  [UNDO]`. Undo writes back the **exact** previous `watchedAt`.
"Clear season" behaves the same way: Undo restores every cleared episode's own previous timestamp.

### Up next

The check button opens the same dialog for the offered episode. Up next's current instant tick and
"Marked … watched / Undo" snackbar (#89) go away; the dialog replaces them.

## Data and domain

- **No schema change.** `episodes.watchedAt` already exists and is nullable.
- **`WatchedWhen`** (shared, `features/tv/domain`) is a sealed type: `Now`, `OnAirDate`, or
  `On(date: LocalDate)`.
- **`MarkEpisodesWatchedUseCase.execute(episodeIds, when): Resource<Unit>`** marks a set of episodes
  watched:
  1. It resolves one `Instant` per episode:
     - `Now` → the clock's current time.
     - `OnAirDate` → that episode's `airDate`; it is an error if any affected episode lacks one.
     - `On(date)` → noon local on `date`.
  2. It writes them in one transaction, only to episodes that are still unwatched.

  This makes it the single path for every tick, whether one episode, catch-up, or a season.
- **`TVWriteDao.restoreWatchedAt(id, at: Instant?)`** is a new, unconditional write used only by
  Undo. The existing `COALESCE(watchedAt, :watchedAt)` write and the season write's
  `watchedAt IS NULL` predicate both stay. Their refusal to overwrite a date is load-bearing for
  bulk marking. Restoring is a different operation, so it gets its own query rather than a changed
  one.
- **Untick reads before it writes,** so the Undo event can carry the old value(s).
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
    - `OnAirDate` refused when any episode lacks an air date;
    - an already-watched episode never overwritten.
  - `restoreWatchedAt`:
    - restores an exact value;
    - restores `null`.
  - The catch-up query:
    - ordering;
    - unaired episodes excluded;
    - specials included or excluded by the setting.
  - View models, using `first { }` and never `.value`:
    - prompt → confirm → watched with the chosen date;
    - catch-up marks the earlier episodes too;
    - untick → Undo restores the exact timestamp, including season clear.
- **androidTest:**
  - `MarkWatchedDialog` driven statelessly:
    - each button calls back with the right `WatchedWhen`;
    - the checkbox toggles;
    - On air date is hidden when unavailable.
  - The date picker is exercised here rather than in Robolectric, because a dialog containing an
    input does not go idle there (AGENTS.md §7).
  - Existing instrumented tests that tick with a plain tap are updated to confirm the dialog.
- **Golden:** one new golden for the dialog in its fullest state (checkbox plus all four choices).
- **Falsify each guard:** remove the noon rule, the air-date availability rule, and the exact-restore
  write, and confirm a test fails each time.

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

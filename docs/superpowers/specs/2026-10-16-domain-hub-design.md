# A hub of domain tiles as the home screen (#126, first version)

**Status:** approved in brainstorming 2026-10-16; this file is the written spec.
**Branch:** `feat/126-domain-hub`, from `main` at `f40f2a1` (v0.26.0).

## Why

#126 records the owner's original model for the app. The home screen is a hub with one tile per
media domain (Books, Films, Shows), each domain has its own library, and the hub holds whatever
is global. Today the app opens on one Library screen covering every kind of item.

The issue's decided constraints still hold, and this spec keeps all four:

- **Settings stays one sectioned screen.** It was already split by domain in #81; this change only
  alters where it is opened from.
- **There is one stats pipeline.** `StatsRepository` is not forked. Stats are still books-only and
  are not changed here.
- **The app-wide library is kept and demoted.** It becomes "All media", reached from the hub.
- **Each tile carries one live number**, so the hub is not a tap tax.

## Scope

**In scope:**
- A new hub screen as the start destination.
- A domain library per kind, built by scoping the existing Library screen and view model.
- The tile numbers.
- Navigation changes.

**Out of scope:**
- Film and show stats. A follow-up issue will be filed.
- Any change to Stats, Settings, Up next or the detail, add and edit screens beyond how they are
  reached.
- Search filters (#66). Domain-scoped search narrows what #66 will filter within, and #66 should
  read this spec before it is built.
- Any database change.

## Decisions (the owner's, from brainstorming)

| Question | Decision |
| --- | --- |
| First version | Hub plus domain libraries. Books keeps its stats; film and show stats come later |
| Tile numbers | Books: currently reading. Films: on the watchlist. Shows: up next |
| Global things | Settings is a ⚙ in the hub's top bar. Under the tiles: "All media" and "Stats" rows |
| Inside a domain | Search and the status filter are scoped to the domain. + adds that kind directly. Books' bar has Stats, Shows' bar has Up next |
| Hub layout | Three stacked, full-width cards, then the two rows |

## Design

### The hub (`HubScreen`, new)

The top bar has the app name and a Settings ⚙. The body scrolls and holds:

1. Three full-width cards in the order Books, Films, Shows. Each card has the domain's icon, its
   name, and its number line:
   - Books: "N reading"
   - Films: "N on watchlist"
   - Shows: "N up next"

   Each line uses Android plurals. The number is shown even when it is 0 ("0 reading"), so a card
   never changes shape. While the numbers are loading, the line is left blank rather than showing 0.
2. Two plain rows with a chevron: **All media** and **Stats**.

The whole card is one tap target, with a semantic label such as "Books, 3 reading". Text wraps
rather than clipping at large font scales.

### Tile numbers (`HubViewModel`, new, in `shared/ui`)

`HubUiState(isLoading, readingBooks: Int, watchlistFilms: Int, showsUpNext: Int)`, derived
reactively from data the app already observes:

- **readingBooks:** books whose `BookDetails.status` is `READING`, from
  `MediaRepository.observeAllMediaWithDetails()`.
- **watchlistFilms:** films whose `MovieDetails.status` is `WATCHLIST`, from the same flow.
- **showsUpNext:** the size of `TVShowRepository.observeUpNext(includeSpecials)`, which is the same
  queue and the same "include specials" setting the Up next screen uses. The two can never
  disagree.

A failed read leaves the numbers blank. It is caught through `catchingNonCancellation`, as the
other view models do, and never crashes the app.

### Domain libraries (the existing `LibraryViewModel` and `LibraryScreen`, scoped)

- **`LibraryViewModel(domain: MediaType? = null, ...)`.** When `domain` is set, `uiState.media`
  holds only that type. The scope applies before search, the status filter and the empty check, so
  an empty Films library shows the empty state rather than a list of nothing. `null` means All
  media, which is exactly today's behaviour.
- **`LibraryScreen(domain: MediaType?, onNavigateBack: (() -> Unit)?, ...)`:**
  - **Title:** "Books", "Films", "Shows", or "All media".
  - **Back:** a back arrow to the hub, on every library including All media.
  - **The + button:** in a domain it calls that domain's add action directly. In All media it keeps
    today's "what do you want to add?" menu.
  - **Top bar:**
    - Books: Stats.
    - Shows: Up next.
    - Films: neither.
    - All media: Up next and Stats, as today.
  - **Settings** is no longer in any library's top bar; it lives on the hub.
  - **Search placeholder:** domain-specific ("Search books by title or author", "Search films",
    "Search shows"). All media keeps its current text.

### Navigation

- **`Route.Hub`** (`"hub"`) becomes the start destination. Its targets are Books, Films, Shows,
  All media, Stats and Settings.
- **`Route.Library`** gains an optional `domain` argument (`library?domain=BOOK`). With no argument
  it is All media.
- **Add flows** come back the way they do now (`popBackStack`), so adding a book from the Books
  library returns there.
- **Back from a library** returns to the hub. Back from the hub leaves the app.

### Strings

New strings: the hub's title, the three tile names, their plural number lines, and the tile
content descriptions; "All media"; the domain library titles; and the search placeholders.
Existing strings are reused where they already fit: Stats, Settings, Up next.

## Testing

TDD throughout, each test red first.

- **`HubViewModelTest` (`shared/src/jvmTest`, because it touches Room):**
  - The counts with mixed seeded data.
  - Zero counts.
  - Each count moves when a status changes, awaited with `uiState.first { }`.
  - Up next follows the include-specials setting.
  - A failed read gives blank numbers and no crash.
- **`LibraryViewModelTest` additions (jvmTest):**
  - A domain shows only its type.
  - Search and the status filter work within a domain.
  - An empty domain is `isEmpty` while other types exist.
  - `null` still shows everything.
- **Robolectric (`app/src/test`):**
  - `HubScreenTest`: the three cards with their numbers and labels, the tap callbacks for every
    target, and the loading state.
  - `LibraryScreen` domain tests:
    - the title;
    - + calls the domain's add action directly, with no menu;
    - the right top-bar icons per domain;
    - All media keeps the add menu;
    - the back arrow.
  - Occlusion tests for the hub at the default size, and at a large font scale if the content
    reaches the bottom.
- **Goldens:**
  - New: `hub` and `hub-large-font`.
  - Re-recorded: `library` and its dark and large-font variants. The title and back arrow change,
    and Settings leaves the bar.
  - All are viewed and committed alone.
- **Device:** `SettingsNavigationTest` starts at `MainActivity`, so its path changes: it now taps
  the hub's ⚙. A new smoke test taps hub → Books, to prove the route wiring.
- **Device, by hand:** the full `connectedDebugAndroidTest`, then `seedDebugDevice`, then a hand
  check:
  - each tile's number against the seeded data;
  - each domain's + and search;
  - back behaviour;
  - All media.

## Risks

- **Every device test that starts at `MainActivity`** expects to land on the library. Grep
  `androidTest` for them up front.
- **Saved navigation state:** the start destination changes, so a process restored on the old
  graph lands on the hub. That is acceptable.
- **The IME check:** the library keeps its `contentWindowInsets`. The hub has no text field, so the
  check does not apply to it.

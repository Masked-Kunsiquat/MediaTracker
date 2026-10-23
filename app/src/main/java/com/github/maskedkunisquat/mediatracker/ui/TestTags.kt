package com.github.maskedkunisquat.mediatracker.ui

/**
 * Stable handles for the controls that tests and device checks actually drive.
 *
 * Two problems, one fix.
 *
 * **On a device**, the only way to drive a control today is to dump the view hierarchy and match on
 * visible text. That works — it is how the changelog, log viewer and Add Book flows were verified —
 * but it breaks the moment a label is reworded, and it tempts guessing coordinates from a
 * screenshot instead, which has already produced one mis-tap into the wrong screen. With
 * `testTagsAsResourceId` enabled in `MainActivity`, every tag here surfaces as `resource-id` in a
 * `uiautomator` dump, which survives both a rewording and a layout change.
 *
 * **In the occlusion lane** (`ImeOcclusion.kt`), a failure has to name the control it found
 * stranded. Without a tag the best available name is the visible text, and a scrolling container
 * has none at all — so failures read `unlabelled node #91` and leave the reader to work out which
 * list on the screen that was.
 *
 * ## What gets a tag
 *
 * Not everything. AGENTS.md section 7 prefers a semantic matcher, because a matcher asserts
 * something a user can perceive while a tag asserts only that a developer wrote the same string
 * twice. A tag is warranted when:
 * - finding the element semantically would take more than about three matchers, or
 * - a device check needs a handle that outlives a reworded label, or
 * - the node carries no perceivable identity of its own — which is every scrolling container, and
 *   is why they are tagged here despite not being controls.
 *
 * Constants rather than string literals at both ends, so a typo is a compile error rather than a
 * test that silently matches nothing, and every use of a tag is greppable from its declaration.
 */
object TestTags {
    /** Book detail (#230). */
    object BookDetail {
        /** The screen's scrolling list. */
        const val LIST = "book_detail_list"

        /** The stars in "Rate this read?". */
        const val RATE_STARS = "book_detail_rate_stars"
    }

    /** The shared detail-screen top bar (#172). */

    /** The show detail screen (#231). */
    object TVShowDetail {
        /** The scrolling list, so a test can reach a card below the fold. */
        const val LIST = "tv_show_detail_list"

        /** The current watch-through's card. */
        const val LATEST_RUN = "tv_show_detail_latest_run"

        /** The stars in "Rate this watch-through?". */
        const val RATE_STARS = "tv_show_detail_rate_stars"

        /** An earlier watch-through's folded card, by run id. */
        fun runCard(runId: String): String = "tv_show_detail_run:$runId"
    }

    /** The film detail screen (#231). */
    object MovieDetail {
        /** The stars in the "Watched" dialog. */
        const val WATCHED_STARS = "movie_detail_watched_stars"

        /** One History row's stars, by viewing id. */
        fun viewingStars(viewingId: String): String = "movie_detail_viewing_stars:$viewingId"
    }

    object DetailTopBar {
        const val OVERFLOW = "detail-top-bar:overflow"
        const val DELETE = "detail-top-bar:delete"
    }

    /** The hub (#126): each card and row is one tap target with no other stable handle. */
    object Hub {
        const val LIST = "hub:list"
        const val BOOKS = "hub:books"
        const val FILMS = "hub:films"
        const val SHOWS = "hub:shows"
        const val ALL_MEDIA = "hub:allMedia"
        const val STATS = "hub:stats"
        const val YEAR_REVIEW = "hub:yearReview"
    }

    object Stats {
        const val LIST = "stats:list"
        const val PERIODS = "stats:periods"
        const val GOAL_CARD = "stats:goal"
        const val MONTH_CHART = "stats:monthChart"
        const val CHARTS_CARD = "stats:charts"
        const val DOMAINS = "stats:domains"
        const val TOP_SHOWS_CARD = "stats:topShows"
        const val YEAR_REVIEW_CARD = "stats:yearReview"
        const val DAYPART_CHART = "stats:daypartChart"
        const val WEEKDAY_CHART = "stats:weekdayChart"
        const val AUTHORS_CARD = "stats:authors"
        const val FORMATS_CARD = "stats:formats"
        const val RATINGS_CARD = "stats:ratings"
        const val RATING_SPREAD_CHART = "stats:ratingSpread"
    }

    object YearReview {
        const val PAGER = "yearReview:pager"
        const val CLOSE = "yearReview:close"

        /** A highlight's cover (#229): an image with no text of its own, being decoration. */
        const val COVER = "yearReview:cover"

        /** One page's root, by its kind (YEAR, TOTALS, LONGEST_BOOK, … SUMMARY). */
        fun page(kind: String): String = "yearReview:page:$kind"
    }

    object Library {
        const val ADD_BUTTON = "library:add"
        const val SEARCH_FIELD = "library:search"
        const val MEDIA_LIST = "library:list"
    }

    object AddBook {
        const val SEARCH_FIELD = "addBook:search"
        const val ISBN_FIELD = "addBook:isbn"
        const val RESULTS = "addBook:results"
    }

    object EditBook {
        const val FORM = "editBook:form"
        const val SAVE_BUTTON = "editBook:save"
        const val CANCEL_BUTTON = "editBook:cancel"
    }

    object AddMovie {
        const val FORM = "addMovie:form"
        const val SAVE_BUTTON = "addMovie:save"
        const val CANCEL_BUTTON = "addMovie:cancel"
    }

    object EditMovie {
        const val FORM = "editMovie:form"
        const val SAVE_BUTTON = "editMovie:save"
        const val CANCEL_BUTTON = "editMovie:cancel"
    }

    object EditTVShow {
        const val FORM = "editTVShow:form"
        const val SAVE_BUTTON = "editTVShow:save"
        const val CANCEL_BUTTON = "editTVShow:cancel"
    }

    object AddTVShow {
        const val FORM = "addTVShow:form"
        const val SAVE_BUTTON = "addTVShow:save"
    }

    object MovieSearch {
        const val RESULTS = "movieSearch:results"
        const val QUERY_FIELD = "movieSearch:query"
        const val SEARCH_BUTTON = "movieSearch:search"
        const val MANUAL_ENTRY = "movieSearch:manualEntry"
        const val REVIEW_FORM = "movieSearch:reviewForm"
        const val REVIEW_SAVE = "movieSearch:reviewSave"
        const val REVIEW_CANCEL = "movieSearch:reviewCancel"
    }

    object TVShowSearch {
        /** The results list. Tagged because a scrolling container has no perceivable identity of
         * its own, and the occlusion lane measures this node's rectangle. */
        const val RESULTS = "tvShowSearch:results"
        const val QUERY_FIELD = "tvShowSearch:query"
        const val SEARCH_BUTTON = "tvShowSearch:search"
        const val MANUAL_ENTRY = "tvShowSearch:manualEntry"
    }

    object Settings {
        const val LIST = "settings:list"
        const val API_KEY_FIELD = "settings:apiKey"
        const val TMDB_KEY_FIELD = "settings:tmdbKey"
    }

    object About {
        /** The credits list. Tagged under the scrolling-container rule above, not as a control. */
        const val LIST = "about:list"
    }

    object MismatchReview {
        /** The findings list. Tagged under the scrolling-container rule above, not as a control. */
        const val LIST = "mismatchReview:list"
    }

    object UpNext {
        /** The queue. Tagged under the scrolling-container rule above, not as a control. */
        const val LIST = "upNext:list"
    }
}

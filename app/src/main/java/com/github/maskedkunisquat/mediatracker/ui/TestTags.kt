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
    }

    object EditMovie {
        const val FORM = "editMovie:form"
        const val SAVE_BUTTON = "editMovie:save"
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
}

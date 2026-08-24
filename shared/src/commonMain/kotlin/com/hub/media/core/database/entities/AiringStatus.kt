package com.hub.media.core.database.entities

/**
 * Whether a show is still being made — a fact about the show, not about the user (schema v6,
 * ROADMAP Task 13 Phase A; column added before the v6 freeze).
 *
 * ### Why this is not [WatchStatus]
 * [WatchStatus] answers "where is *this viewer* in this show". This answers "is the show over".
 * They are independent: a viewer can be up to date on a [CONTINUING] show and permanently done
 * with an [ENDED] one, and no value of either implies anything about the other.
 *
 * The distinction is load-bearing rather than decorative. `LibraryStatusFilter.ofShow` derives
 * "finished" from `watched == total`, where "total" means *episode rows that exist* — so a running
 * show whose aired episodes are all watched is indistinguishable from a show that is genuinely
 * over. Those are different states to a viewer: **up to date** versus **completed**. Without this
 * column the library shows the same chip for both, and the running show quietly stops being
 * "finished" the moment someone quick-fills its next season.
 *
 * ### Persisted by name
 * Stored as its `name` string like every other enum here (see
 * [com.hub.media.core.database.converters.Converters]), so entries may be **added** freely but
 * never renamed or reordered — a rename is a data migration over every row already written. `null`
 * in the column means "unknown", which is every row today: nothing writes this yet. TMDB supplies
 * it in Phase D, where its `status`/`in_production` fields map onto these.
 */
public enum class AiringStatus {
    /** Still in production; more episodes are expected. TMDB's "Returning Series". */
    CONTINUING,

    /** Concluded as intended — the story is over and no more episodes are coming. */
    ENDED,

    /**
     * Stopped before its intended end. Kept distinct from [ENDED] because it means something
     * different to a viewer deciding whether to start: an unresolved ending is a reason not to.
     */
    CANCELLED,
}

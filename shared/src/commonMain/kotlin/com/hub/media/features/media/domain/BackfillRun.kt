package com.hub.media.features.media.domain

/**
 * A resumable, cancellable pass over the library that reports progress as it goes.
 *
 * ### Why this interface exists
 * There are two such passes — [com.hub.media.features.books.domain.BulkBackfillUseCase] over Open
 * Library and [BulkTmdbBackfillUseCase] over TMDB — and #140 decided they stay two, because the
 * Settings screen #126 settled has a home for each and none for a merged one.
 *
 * Two *actions* is not two of everything. [com.hub.media.ui.BackfillViewModel]'s lifecycle handling
 * is the part with scar tissue on it: an unguarded suspend read in `init` that crashed Settings, a
 * `CancellationException` catch that has to precede the broad one, a failure state kept distinct from
 * a clean stop so the two do not look alike. Copying that for a second pass would mean two places to
 * relearn each of those, which is what #126's "do not fork `StatsRepository` per domain" constraint
 * warns against and what #141 documents happening to three detail screens. So the ViewModel is
 * written once against this interface and instantiated twice.
 *
 * It is deliberately the smallest surface that makes that possible: peek, and run. Everything the two
 * passes genuinely disagree about — what a candidate is, which provider answers, what stops a run and
 * what the user should do about it — stays behind [P], which each pass defines for itself.
 *
 * @param P That pass's own progress snapshot, surfaced verbatim to the UI.
 */
public interface BackfillRun<P : Any> {
    /**
     * Whatever a previous, unfinished run left behind, or `null` if there is nothing to resume.
     *
     * Must not touch the network: this is called on construction so a Settings screen can offer
     * "Resume (168 remaining)" the moment it opens, and a screen that spent a request to render a
     * button would be paying for it on every visit.
     */
    public suspend fun peek(): P?

    /**
     * Runs or resumes one pass, returning where it ended up.
     *
     * @param onProgress Invoked after every item this run touches, with state as just persisted —
     *   never a stale or duplicate snapshot.
     */
    public suspend fun run(onProgress: suspend (P) -> Unit): P
}

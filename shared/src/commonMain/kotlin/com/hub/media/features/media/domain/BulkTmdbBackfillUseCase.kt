package com.hub.media.features.media.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.entities.EpisodeEntity
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaItemEntity
import com.hub.media.core.database.entities.MediaType
import com.hub.media.core.database.entities.MovieDetailsEntity
import com.hub.media.core.database.entities.TVDetailsEntity
import com.hub.media.core.network.RequestPacer
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.features.movies.domain.toMovieMapping
import com.hub.media.features.settings.data.SettingsRepository
import com.hub.media.features.settings.data.ShowSeasonMismatch
import com.hub.media.features.settings.data.TmdbBackfillState
import com.hub.media.features.settings.data.clearTmdbBackfillMismatches
import com.hub.media.features.settings.data.clearTmdbBackfillState
import com.hub.media.features.settings.data.getTmdbBackfillMismatches
import com.hub.media.features.settings.data.getTmdbBackfillState
import com.hub.media.features.settings.data.saveTmdbBackfillMismatches
import com.hub.media.features.settings.data.saveTmdbBackfillState
import com.hub.media.features.tv.domain.BackfillShowEpisodesUseCase
import com.hub.media.features.tv.domain.FetchPosterUseCase
import com.hub.media.features.tv.domain.toShowMapping
import com.hub.media.features.tv.network.TmdbClient
import com.hub.media.features.tv.network.TmdbShowWithSeasons
import com.hub.media.features.tv.network.dto.TmdbSeasonDetailsDto
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** Log tag for this use case's lifecycle tracing (ROADMAP Task 15 Phase C). */
private const val TAG = "BulkTmdbBackfill"

/**
 * Progress/result snapshot for one [BulkTmdbBackfillUseCase.execute] run (#140).
 *
 * The film-and-show counterpart of
 * [com.hub.media.features.books.domain.BulkBackfillProgress], reporting the same way — honestly and
 * partially, never all-or-nothing. [processed] + [remaining] always equals [totalCandidates].
 *
 * ### What it does not carry, and why
 * There is no `isPaused`/`retryAfter` pair here. Those describe a **quota**: Open Library's cover
 * probe can be genuinely exhausted, and the only truthful thing to tell a user is "come back in a few
 * minutes". TMDB imposes a **rate**, which [RequestPacer] answers by waiting rather than by giving
 * up, so this pass has no state in which it is stopped-but-not-finished for a reason that time alone
 * fixes. Carrying the fields anyway would mean two of them permanently false and null, which reads to
 * the next person as a case nobody got round to implementing.
 *
 * What can stop it is [blockedMessage], and that is a different thing entirely: not "wait", but "go
 * and fix something in Settings".
 *
 * @property totalCandidates Films and shows that needed something and had a TMDB id, fixed at the
 *   moment this run was first seeded.
 * @property processed `totalCandidates - remaining`. **Not** always `updated + nothingToFill`: a
 *   title deleted since it was queued, or holding a TMDB id that is not a number, leaves the queue
 *   without being either. Those are rare and are not counted anywhere — the same shape
 *   [com.hub.media.features.books.domain.BulkBackfillProgress] documents, spelled out here because
 *   the arithmetic looking like it should balance is exactly what makes the gap confusing.
 * @property updated Cumulative count of titles something was actually written onto.
 * @property nothingToFill Cumulative count of titles fully resolved with nothing to write.
 * @property noTmdbIdSkipped Titles that can never be filled from a provider because they were never
 *   added from one — fixed at seed time, never retried.
 * @property remaining Titles still needing a future run.
 * @property mismatchedShows Distinct shows this run found disagreeing with TMDB about a season's
 *   episode count (#123) — shows, not seasons, because one show can disagree about several. The
 *   detail behind the number is read separately via [BulkTmdbBackfillUseCase.mismatches], since it
 *   outlives the run this progress object describes.
 * @property blockedMessage TMDB's own reason for refusing the credential, or `null` if the run was
 *   not blocked. Deliberately the provider's user-facing sentence rather than a code: both of the
 *   sentences [TmdbClient] produces here already say what the user must do, and re-deriving them into
 *   an enum only to spell them out again downstream would give the same words two owners.
 */
public data class TmdbBackfillProgress(
    public val totalCandidates: Int,
    public val processed: Int,
    public val updated: Int,
    public val nothingToFill: Int,
    public val noTmdbIdSkipped: Int,
    public val remaining: Int,
    public val blockedMessage: String? = null,
    public val mismatchedShows: Int = 0,
) {
    /** `true` once nothing is left to fill — [remaining] is 0. */
    public val isComplete: Boolean get() = remaining == 0

    /** `true` when the run stopped because TMDB would not accept the stored credential. */
    public val isBlocked: Boolean get() = blockedMessage != null
}

/**
 * Fills missing artwork and metadata across every film and show in the library (#140) — the TMDB
 * counterpart of [com.hub.media.features.books.domain.BulkBackfillUseCase].
 *
 * ### Why this is a second action rather than books' pass grown to cover three types
 * Settled on #140 against #126, which decided that Settings is **one sectioned screen** grouped
 * `Books / Films & TV / Data / Diagnostics`. A book repair belongs to the first section and this one
 * belongs to the second; a single merged "fill in everything" control belongs to neither, and would
 * have to be filed under Data next to export and backup — away from both domains it serves. That is
 * the same "duplicate it or assign it arbitrarily" problem #126 used to rule out per-domain settings
 * *screens*, arrived at from the other end.
 *
 * The two passes also stop for genuinely different reasons — see [TmdbBackfillProgress]'s note on the
 * absent `isPaused`.
 *
 * What is **not** duplicated is the machinery, because #126's other standing constraint (do not fork
 * `StatsRepository` per domain) is about exactly that: [com.hub.media.ui.BackfillViewModel] runs both
 * passes from one implementation, [RequestPacer] paces both, and the per-show filling below is
 * [BackfillShowEpisodesUseCase]'s, called rather than copied.
 *
 * ### One request per title, filling everything that request can answer
 * A film costs one `/movie/{id}`; a show costs one `/tv/{id}` with its seasons appended. Each response
 * already carries the poster path, the title-level metadata *and* (for a show) every episode — so a
 * title is asked about once and everything blank about it is filled from that one answer. This is why
 * [BackfillShowEpisodesUseCase.applyFetched] exists: routing through its [BackfillShowEpisodesUseCase.execute]
 * would spend a second request for a payload already in hand.
 *
 * The one exception is a show longer than
 * [com.hub.media.features.tv.network.MAX_APPENDED_SEASONS], whose remaining seasons cannot ride in
 * that response at all. Those cost one request each, and only for seasons this library actually
 * holds fillable episodes for — see [withHeldSeasons].
 *
 * ### It enriches; it never creates, deletes or re-dates
 * Inherited wholesale from #75 and not reopened. Every write goes through a `COALESCE` statement that
 * cannot touch a column already holding a value and does not mention watch state at all — see
 * [com.hub.media.core.database.dao.TVWriteDao.fillEpisodeMetadata], whose guarantee is the statement
 * rather than this class's care. A show's episode *count* is likewise never changed: a season the
 * provider says is longer is reported by [BackfillShowEpisodesUseCase], not filled in.
 *
 * ### Two pacers, because there are two hosts
 * Metadata goes to `api.themoviedb.org` through a [TmdbClient] built with
 * [com.hub.media.core.network.tmdbPacer]; posters go to `image.tmdb.org` through [posterPacer]. #140
 * required the split before either existed: a single interval covering both would be wrong in
 * whichever direction the shared number was set. Pacing lives here rather than inside
 * [BackfillShowEpisodesUseCase] or [FetchPosterUseCase] precisely because both of those are also
 * reached interactively, where paying an interval would be latency for nothing — the rule
 * [BackfillShowEpisodesUseCase]'s own KDoc states.
 *
 * ### The credential is checked once, up front
 * A run that cannot authenticate would otherwise spend one failing request per title and finish
 * reporting several hundred deferrals, which describes neither the cause nor the remedy. One
 * [TmdbClient.verifyCredential] before the loop converts that into an immediate, accurate sentence.
 * It is checked on a resume too, not only on a fresh seed: the credential can have been cleared
 * between the two, and a restore is one of the ways that happens (backups have credentials scrubbed
 * out of them). A credential revoked *mid*-run is not guarded against — every title after it defers
 * and is retried next time, costing requests but losing nothing.
 *
 * ### Resumability
 * [execute] checkpoints [TmdbBackfillState] after **every title**, so quota-free though this pass is,
 * a cancellation or process death still resumes from the next unprocessed title rather than
 * restarting. That matters more here than for books, not less: a show is one request but hundreds of
 * episode rows.
 *
 * @param db Source of the library scan, every per-title re-read, and every fill.
 * @param tmdbClient **Must be the paced client**, exclusive to this pass. Sharing the interactive one
 *   would leave this crawl unpaced; sharing this one with an interactive path would land its sleeps on
 *   a request someone is waiting for.
 * @param showEpisodes The per-show episode filler, built over the same paced [tmdbClient].
 * @param fetchPoster Downloads and content-addresses one title's poster.
 * @param posterPacer Holds poster downloads to [com.hub.media.core.network.TMDB_IMAGE_REQUESTS_PER_SECOND].
 * @param settingsRepository Backing store for [TmdbBackfillState].
 */
public class BulkTmdbBackfillUseCase(
    private val db: AppDatabase,
    private val tmdbClient: TmdbClient,
    private val showEpisodes: BackfillShowEpisodesUseCase,
    private val fetchPoster: FetchPosterUseCase,
    private val posterPacer: RequestPacer,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger = AppLogger,
) : BackfillRun<TmdbBackfillProgress> {
    /** [BackfillRun]'s spelling of [peekProgress]; see the book pass's adapter on why both exist. */
    override suspend fun peek(): TmdbBackfillProgress? = peekProgress()

    /** [BackfillRun]'s spelling of [execute]. */
    override suspend fun run(onProgress: suspend (TmdbBackfillProgress) -> Unit): TmdbBackfillProgress =
        execute(onProgress)

    /**
     * Runs (or resumes) one pass. Processes [TmdbBackfillState.pendingMediaIds] in order,
     * checkpointing after every title, until everything is resolved or the caller's coroutine is
     * cancelled.
     *
     * @param onProgress Invoked with the current [TmdbBackfillProgress] after every title this run
     *   touches — the hook a live progress bar reads. Each call reflects state exactly as just
     *   persisted.
     */
    public suspend fun execute(onProgress: (suspend (TmdbBackfillProgress) -> Unit)? = null): TmdbBackfillProgress {
        val resumable = settingsRepository.getTmdbBackfillState()
        var state = resumable ?: seedState()

        if (state.pendingMediaIds.isEmpty()) {
            // Logged for the reason the book pass logs the same case: without an entry, a user who
            // presses the button and sees nothing cannot tell "nothing to do" from "the button is
            // broken".
            logger.info(TAG) { "TMDB backfill run: nothing pending, no titles to update" }
            settingsRepository.clearTmdbBackfillState()
            return state.toProgress(mismatchedShows = mismatches().countShows())
        }

        // Deliberately after the empty check: a library with nothing to fill should not spend a
        // request discovering that its credential is fine.
        val credentialCheck = tmdbClient.verifyCredential()
        if (credentialCheck is Resource.Error) {
            logger.info(TAG) { "TMDB backfill run blocked before starting: credential not accepted" }
            return state.toProgress(
                blockedMessage = credentialCheck.message,
                mismatchedShows = mismatches().countShows(),
            )
        }

        // A fresh run forgets the previous one's findings, because it is about to re-derive whatever
        // still holds. Deliberately *here* rather than in seedState: both early returns above leave
        // without visiting a single show, so clearing at seed time meant that pressing the button on
        // an already-complete library destroyed the list and re-derived nothing -- the review list
        // disappearing because someone tapped Start. A resumed run keeps the earlier leg's findings
        // and appends to them.
        if (resumable == null) settingsRepository.clearTmdbBackfillMismatches()

        val toProcess = state.pendingMediaIds
        logger.info(TAG) { "TMDB backfill run starting: ${toProcess.size} title(s) pending" }
        val stillPending = mutableListOf<String>()
        var updated = state.updated
        var nothingToFill = state.nothingToFill
        val foundMismatches = settingsRepository.getTmdbBackfillMismatches().toMutableList()
        // Only written when it actually changes. The resume queue changes every title and must be
        // saved every title; this list does not, and most libraries produce none at all -- so
        // persisting unconditionally meant a DELETE per title, for every title, to store nothing.
        // Compared by content rather than size, because `record` can replace an entry in place.
        var persistedMismatches = foundMismatches.toList()

        try {
            for (index in toProcess.indices) {
                // Cooperative cancellation between titles, never mid-title: state is checkpointed
                // after every *completed* title, so whatever was last saved is the correct resume
                // point either way.
                coroutineContext.ensureActive()
                val mediaId = toProcess[index]

                when (val outcome = processOne(mediaId, foundMismatches)) {
                    StepOutcome.Removed -> Unit
                    is StepOutcome.Done -> if (outcome.wroteAnything) updated++ else nothingToFill++
                    StepOutcome.DeferredTransient -> stillPending += mediaId
                }

                state =
                    state.copy(
                        pendingMediaIds = stillPending + toProcess.subList(index + 1, toProcess.size),
                        updated = updated,
                        nothingToFill = nothingToFill,
                    )
                // Mismatches are written **before** the resume queue, and the order is the point.
                // Saving the queue first removes this title from it, so a process death in the gap
                // between the two writes would lose the finding for good: nothing would revisit the
                // show to re-derive it. This way the gap can only ever cause the *opposite* — a
                // title still queued whose finding is already stored — which the next run resolves
                // by re-processing it, and `record` above keeps that from duplicating the row.
                //
                // A transaction spanning both would be stronger, and was not taken: SettingsRepository
                // exposes one key at a time, and saveTmdbBackfillState is itself five separate writes,
                // so making only these two atomic would buy a guarantee the surrounding code does not
                // have. Ordering costs nothing and removes the damaging direction.
                if (foundMismatches != persistedMismatches) {
                    settingsRepository.saveTmdbBackfillMismatches(foundMismatches)
                    persistedMismatches = foundMismatches.toList()
                }
                settingsRepository.saveTmdbBackfillState(state)
                onProgress?.invoke(state.toProgress(mismatchedShows = foundMismatches.countShows()))
            }
        } catch (e: CancellationException) {
            // Cancelling is normal -- Settings offers it -- but without this the run logs "starting,
            // 40 pending" and then nothing, which reads as a hang. Read from `state` (the last
            // persisted checkpoint) rather than the loop-local values, for the reason the book pass
            // spells out: `stillPending` alone is not the resume set.
            logger.info(TAG) {
                "TMDB backfill run cancelled: ${state.updated} updated, " +
                    "${state.pendingMediaIds.size} left for the next run"
            }
            throw e
        }

        if (state.pendingMediaIds.isEmpty()) {
            // The resume queue goes; the mismatches deliberately stay. A finished run is exactly
            // when its findings become worth reading -- see TmdbBackfillMismatches' KDoc.
            settingsRepository.clearTmdbBackfillState()
        }
        logger.info(TAG) {
            "TMDB backfill run finished: $updated updated, $nothingToFill with nothing to fill, " +
                "${state.pendingMediaIds.size} still pending, " +
                "${foundMismatches.countShows()} show(s) disagreeing about episode counts"
        }
        return state.toProgress(mismatchedShows = foundMismatches.countShows())
    }

    /**
     * Every season disagreement the most recent run recorded (#123).
     *
     * Read directly rather than through [peekProgress], which returns `null` once a run completes —
     * and a completed run is the normal case in which someone goes looking at these.
     */
    public suspend fun mismatches(): List<ShowSeasonMismatch> = settingsRepository.getTmdbBackfillMismatches()

    /**
     * One-shot peek at whether a run is resumable, for UI offering "Resume (40 remaining)" without
     * starting one. Never touches the network, so [TmdbBackfillProgress.blockedMessage] is always
     * `null` here — being blocked is a property of a *run*, not of the stored state.
     */
    public suspend fun peekProgress(): TmdbBackfillProgress? =
        settingsRepository.getTmdbBackfillState()?.toProgress(mismatchedShows = mismatches().countShows())

    /**
     * Scans every film and show for gaps, persists the freshly-seeded state, and returns it. Only
     * called when nothing is resumable — a resumed run reuses the already-seeded queue so that
     * [TmdbBackfillState.totalCandidates] stays fixed and "312 of 480" keeps meaning something.
     *
     * Titles with no TMDB mapping are split into [TmdbBackfillState.noTmdbIdSkipped] and never
     * queued: there is nothing to ask about a film someone typed in by hand, and retrying it forever
     * would not make one appear in the catalogue.
     */
    private suspend fun seedState(): TmdbBackfillState {
        val haveTmdbId = db.externalIdentifierDao().getMediaIdsForProvider(IdentifierProvider.TMDB).toSet()
        val incompleteEpisodes = db.episodeDao().mediaIdsWithIncompleteEpisodes().toSet()
        val anyEpisodes = db.episodeDao().mediaIdsWithAnyEpisodes().toSet()

        val movieDetails = db.movieDetailsDao().getAll().associateBy { it.mediaId }
        val films =
            db
                .mediaItemDao()
                .getAllByType(MediaType.MOVIE)
                .filter { filmNeedsFilling(it, movieDetails[it.id]) }

        val showDetails = db.tvDetailsDao().getAll().associateBy { it.mediaId }
        val shows =
            db
                .mediaItemDao()
                .getAllByType(MediaType.TV_SHOW)
                .filter {
                    showNeedsFilling(
                        item = it,
                        details = showDetails[it.id],
                        hasIncompleteEpisodes = it.id in incompleteEpisodes,
                        hasAnyEpisodes = it.id in anyEpisodes,
                    )
                }

        // Films before shows, each title-ordered, because that is the order getAllByType returns and
        // a stable queue is the only ordering property a resume actually needs.
        val (withTmdbId, withoutTmdbId) = (films + shows).partition { it.id in haveTmdbId }

        val state =
            TmdbBackfillState(
                pendingMediaIds = withTmdbId.map { it.id },
                totalCandidates = withTmdbId.size,
                noTmdbIdSkipped = withoutTmdbId.size,
                updated = 0,
                nothingToFill = 0,
            )
        settingsRepository.saveTmdbBackfillState(state)
        return state
    }

    /**
     * Resolves whatever [mediaId] is still missing, from its **current** database state — re-read
     * here rather than trusted from the seed scan, because a title can have been fixed by hand or
     * deleted since the queue was written.
     *
     * @param foundMismatches Collected into, not returned. A season disagreement is not an outcome
     *   of the title — a show can disagree about a season *and* be filled, deferred, or have nothing
     *   to do — so folding it into [StepOutcome] would mean every branch carrying a field only one
     *   of them ever populates. See [execute] for what happens to the collected list.
     */
    private suspend fun processOne(
        mediaId: String,
        foundMismatches: MutableList<ShowSeasonMismatch>,
    ): StepOutcome {
        val item = db.mediaItemDao().getById(mediaId) ?: return StepOutcome.Removed
        val storedId =
            db
                .externalIdentifierDao()
                .getByKey(mediaId, IdentifierProvider.TMDB)
                ?.externalId
                ?: return StepOutcome.Removed // guarded at seed time; the mapping was deleted since
        // A stored id that is not a number cannot address anything at TMDB. Dropped rather than
        // coerced, for the reason BackfillShowEpisodesUseCase refuses it: the CSV importer accepts
        // any non-blank string as an external id, and coercing would spend a request on /tv/-1 and
        // report a 404 that describes neither the cause nor the remedy.
        val tmdbId = storedId.toIntOrNull() ?: return StepOutcome.Removed

        return when (item.type) {
            MediaType.MOVIE -> processFilm(item, tmdbId)
            MediaType.TV_SHOW -> processShow(item, tmdbId, foundMismatches)
            // A book carrying a TMDB mapping is not a thing this app creates; nothing here could
            // fill it if it existed.
            else -> StepOutcome.Removed
        }
    }

    private suspend fun processFilm(
        item: MediaItemEntity,
        tmdbId: Int,
    ): StepOutcome {
        val details = db.movieDetailsDao().getByMediaId(item.id)
        val needsPoster = item.coverImageHash == null
        val needsYear = item.releaseYear == null
        val needsRating = item.communityRating == null
        // On the item, not on `details`: since v7 the synopsis lives on media_items, which always
        // exists for a row being processed.
        val needsSynopsis = item.synopsis == null
        // Gated on the details row existing, not merely on the column being null. A film whose
        // movie_details half is missing (the integrity edge MediaWithDetails.Movie documents) has no
        // row for a runtime to land in, and fillMovieMetadata deliberately does not create one --
        // counting it as filled would report a write that could not have happened.
        val needsRuntime = details != null && details.runtimeMinutes == null
        if (!needsPoster && !needsYear && !needsRating && !needsSynopsis && !needsRuntime) {
            return StepOutcome.Removed
        }

        val mapping =
            when (val result = tmdbClient.movieDetails(tmdbId)) {
                is Resource.Error -> return StepOutcome.DeferredTransient
                is Resource.Success -> result.data.toMovieMapping() ?: return StepOutcome.Done(wroteAnything = false)
            }

        var wrote = false
        val year = if (needsYear) mapping.releaseYear else null
        val rating = if (needsRating) mapping.communityRating else null
        val synopsis = if (needsSynopsis) mapping.synopsis else null
        val runtime = if (needsRuntime) mapping.runtimeMinutes else null
        if (year != null || rating != null || synopsis != null || runtime != null) {
            val rows =
                db.movieWriteDao().fillMovieMetadata(
                    mediaId = item.id,
                    releaseYear = year,
                    communityRating = rating,
                    synopsis = synopsis,
                    runtimeMinutes = runtime,
                )
            wrote = rows > 0
        }

        return finishWithPoster(item.id, mapping.posterPath, needsPoster, wrote)
    }

    private suspend fun processShow(
        item: MediaItemEntity,
        tmdbId: Int,
        foundMismatches: MutableList<ShowSeasonMismatch>,
    ): StepOutcome {
        val details = db.tvDetailsDao().getByMediaId(item.id)
        val needsPoster = item.coverImageHash == null
        val needsYear = item.releaseYear == null
        val needsRating = item.communityRating == null
        val detailGaps = details != null && details.hasGap()
        // Its own flag, not part of detailGaps: since v7 the synopsis is on media_items, and a
        // show whose only gap is that would otherwise be queued by the seed and dropped here.
        val needsSynopsis = item.synopsis == null
        // Re-derived from the current rows rather than reused from the seed scan's set, for the same
        // reason every other gap here is: an episode could have been filled by the per-show refresh
        // (#136) between the two. The condition is the same five columns
        // EpisodeDao.mediaIdsWithIncompleteEpisodes tests, and has to stay that way -- a re-check
        // narrower than the seed would silently drop shows the scan had queued for a real gap.
        // Read once and reused by withHeldSeasons below. A daily series is 458 rows, and this used to
        // load all of them twice on the way to one boolean and one set of season numbers.
        val localEpisodes = db.episodeDao().getByMediaId(item.id).filter { it.seasonNumber >= 1 }
        val needsEpisodes = localEpisodes.any { it.hasGap() }
        // #123: a show that is set up is compared even with nothing left to fill. Counting is a
        // different question from filling and does not converge -- an ended show can still disagree
        // (Fleabag did, 0 against 6) and an airing one gains episodes after any number of clean runs.
        // A show with no episode rows is excluded: every season would read "you have 0, TMDB has N",
        // which is a show waiting to be quick-filled (#74) rather than a disagreement to reconcile.
        val worthComparing = localEpisodes.isNotEmpty()
        if (!needsPoster &&
            !needsYear &&
            !needsRating &&
            !needsSynopsis &&
            !detailGaps &&
            !needsEpisodes &&
            !worthComparing
        ) {
            return StepOutcome.Removed
        }

        val fetched =
            when (val result = tmdbClient.showWithSeasons(tmdbId)) {
                is Resource.Error -> return StepOutcome.DeferredTransient
                is Resource.Success -> result.data
            }
        // Only the scalar fields of this mapping are used. Its `seasons` list is creation data for
        // addShow, and creating episode rows is precisely what enrichment must not do (#75, #123).
        val mapping = fetched.toShowMapping() ?: return StepOutcome.Done(wroteAnything = false)

        var wrote = false
        val year = if (needsYear) mapping.releaseYear else null
        val rating = if (needsRating) mapping.communityRating else null
        // Each gated on the details row existing, not merely on the column being null -- the same
        // reasoning processFilm gives for runtimeMinutes: a show whose tv_details half is missing has
        // no row for these to land in, and fillShowMetadata deliberately does not create one.
        val totalSeasons = if (details != null && details.totalSeasons == null) mapping.totalSeasons else null
        val airingStatus = if (details != null && details.airingStatus == null) mapping.airingStatus else null
        // Gated on the item, not on `details`: since v7 the synopsis lives on `media_items`, which
        // always exists for a row being processed. Keeping the `details != null` guard would refuse
        // to fill the synopsis of a show whose tv_details half is missing, for no reason.
        val synopsis = if (item.synopsis == null) mapping.overview?.takeIf { it.isNotBlank() } else null
        val firstAirDate =
            if (details != null && details.firstAirDate == null) mapping.firstAirDate?.toEpochMilliseconds() else null
        val lastAirDate =
            if (details != null && details.lastAirDate == null) mapping.lastAirDate?.toEpochMilliseconds() else null

        // Entered on the *values*, not on detailGaps. A show can have blank columns that TMDB also
        // has nothing for -- an unrated show has no communityRating at either end -- and entering on
        // the gap alone then reading the affected-row count would report every such show as
        // "updated", because the count is 1 whenever the media_items row exists rather than whenever
        // a column changed. That is the mistake BackfillShowEpisodesUseCase already paid for once
        // with "Updated 5 episodes" on a show where nothing changed; the fix is the same one.
        val fillsSomething =
            listOfNotNull(year, rating, totalSeasons, airingStatus, synopsis, firstAirDate, lastAirDate)
                .isNotEmpty()
        if (fillsSomething) {
            wrote =
                db.tvWriteDao().fillShowMetadata(
                    mediaId = item.id,
                    releaseYear = year,
                    communityRating = rating,
                    totalSeasons = totalSeasons,
                    airingStatus = airingStatus,
                    synopsis = synopsis,
                    firstAirDate = firstAirDate,
                    lastAirDate = lastAirDate,
                ) > 0
        }

        if (needsEpisodes || worthComparing) {
            // applyFetched both fills and compares, and it is safe to call when there is nothing to
            // fill: every write it makes is a COALESCE that cannot touch a column already holding a
            // value, so a show with complete episodes comes back reporting 0 filled and whatever it
            // disagrees about. That is what lets one call answer both questions.
            val complete =
                withHeldSeasons(tmdbId, localEpisodes, fetched) ?: return StepOutcome.DeferredTransient
            val report = showEpisodes.applyFetched(item.id, complete)
            if (report.episodesFilled > 0) wrote = true
            // #123: this report was computed and thrown away until now, which made the one pass that
            // visits every show the only place that could answer "which of my shows disagree with
            // the catalogue?" and also the only place that binned the answer.
            //
            // seasonsNotFetched is deliberately *not* collected. Since withHeldSeasons above, a
            // season still absent is one the provider declares and this library holds no fillable
            // episodes for -- which is a statement about seasons the user does not track, closer to
            // #122's specials question than to a count disagreement, and reporting it here would
            // dilute a list whose whole value is that every row is actionable.
            foundMismatches.replaceShow(
                item.id,
                report.mismatches.map {
                    ShowSeasonMismatch(
                        mediaId = item.id,
                        seasonNumber = it.seasonNumber,
                        localEpisodes = it.localEpisodes,
                        providerEpisodes = it.providerEpisodes,
                    )
                },
            )
        }

        return finishWithPoster(item.id, mapping.posterPath, needsPoster, wrote)
    }

    /**
     * [fetched] topped up with any season it did not carry that this library actually holds fillable
     * episodes for, or `null` if one of those follow-up requests failed.
     *
     * ### Why a library-wide pass chases these and the per-show refresh does not
     * `append_to_response` carries at most
     * [com.hub.media.features.tv.network.MAX_APPENDED_SEASONS] seasons, so a longer show comes back
     * with the rest absent — reported by [TmdbShowWithSeasons.missingSeasonNumbers] rather than
     * silently dropped. [BackfillShowEpisodesUseCase] deliberately leaves fetching them to its
     * caller, and for the interactive refresh that is right: one extra round trip per season is
     * latency someone is waiting on.
     *
     * For this pass it is not right. Nobody is waiting, and leaving them would mean a 22-season show
     * reporting itself complete every single run while its last two seasons stay blank forever — the
     * silent partial result this codebase treats as its recurring failure mode, not a saving.
     *
     * ### Only seasons this library holds episodes for
     * A season the user does not track is not fetched. The point is to fill rows that exist, and a
     * 30-season catalogue where someone tracks season 1 would otherwise spend ten requests learning
     * about episodes it must not create anyway (#75, #123).
     *
     * @param localEpisodes This show's `seasonNumber >= 1` rows, already read by the caller — passed
     *   rather than re-read, because the caller needed them a moment ago for the same decision and a
     *   daily series is several hundred rows.
     *
     * ### A failed season defers the whole title
     * Returning `null` here sends the title back to the pending queue rather than letting it be
     * reported [StepOutcome.Done] with episodes still unfilled. Whatever already landed stays
     * landed; enrichment is idempotent, so the retry simply finds fewer gaps.
     */
    private suspend fun withHeldSeasons(
        tmdbId: Int,
        localEpisodes: List<EpisodeEntity>,
        fetched: TmdbShowWithSeasons,
    ): TmdbShowWithSeasons? {
        val missing = fetched.missingSeasonNumbers
        if (missing.isEmpty()) return fetched

        val held = localEpisodes.filter { it.hasGap() }.mapTo(mutableSetOf()) { it.seasonNumber }
        val wanted = missing.filter { it in held }
        if (wanted.isEmpty()) return fetched

        val extra = mutableMapOf<Int, TmdbSeasonDetailsDto>()
        for (seasonNumber in wanted) {
            when (val result = tmdbClient.seasonDetails(tmdbId, seasonNumber)) {
                is Resource.Error -> return null
                is Resource.Success -> extra[seasonNumber] = result.data
            }
        }
        return fetched.copy(seasons = fetched.seasons + extra)
    }

    /**
     * Downloads [posterPath] if one is wanted and available, then classifies the whole title.
     *
     * A failed download defers the *title*, so the next run retries it. Everything already written
     * above stays written: enrichment is idempotent, and the re-read at the top of the next attempt
     * simply finds fewer gaps.
     *
     * ### This is the one write here the SQL does not protect
     * Every other column this pass touches goes through a `COALESCE` statement that *cannot*
     * overwrite, which the class KDoc rightly calls a stronger guarantee than a careful caller. The
     * poster does not: [com.hub.media.core.database.MediaRepository.updateCoverImageHash] is an
     * unconditional `UPDATE`, and the only thing stopping this from replacing existing artwork is
     * [needsPoster] being computed from `coverImageHash == null` a few lines up.
     *
     * That asymmetry is deliberate rather than missed. The same statement backs the per-book
     * "re-fetch cover" action, whose entire purpose is to replace a cover the user already has — a
     * `COALESCE` there would make the button silently do nothing. So the overwrite-ability is a
     * requirement of the shared write, and this caller takes on the check instead. Worth knowing when
     * editing either end: the guarantee here really is this class's care, and is the one place in the
     * pass where that sentence is true.
     */
    private suspend fun finishWithPoster(
        mediaId: String,
        posterPath: String?,
        needsPoster: Boolean,
        wroteMetadata: Boolean,
    ): StepOutcome {
        if (!needsPoster || posterPath.isNullOrBlank()) return StepOutcome.Done(wroteMetadata)

        // The image CDN's own budget, not the API's -- see this class's KDoc on the two pacers.
        posterPacer.acquire()
        // A Resource.Error here is a genuine download or storage failure: "there was no poster to
        // fetch", the other thing FetchPosterUseCase reports as an error, is excluded by the blank
        // check above.
        return when (fetchPoster.execute(mediaId, posterPath)) {
            is Resource.Success -> StepOutcome.Done(wroteAnything = true)
            is Resource.Error -> StepOutcome.DeferredTransient
        }
    }

    /** Outcome of resolving a single pending title, driving [execute]'s bookkeeping for it. */
    private sealed class StepOutcome {
        /** Deleted since being queued, already complete, or holding an id nothing can be asked about. */
        data object Removed : StepOutcome()

        /** Fully resolved this run — removed from the pending list. */
        data class Done(
            val wroteAnything: Boolean,
        ) : StepOutcome()

        /** A transient failure (lookup, download, or image save) — retried on a future run. */
        data object DeferredTransient : StepOutcome()
    }
}

/** Whether a film has any column this pass could fill. Mirrors [BulkTmdbBackfillUseCase]'s re-check. */
private fun filmNeedsFilling(
    item: MediaItemEntity,
    details: MovieDetailsEntity?,
): Boolean =
    item.coverImageHash == null ||
        item.releaseYear == null ||
        item.communityRating == null ||
        // On the item since v7, exactly as the show side has it: a synopsis-only gap must queue.
        item.synopsis == null ||
        (details != null && details.runtimeMinutes == null)

/** Whether a show has any column, or any episode row, this pass could fill. */
private fun showNeedsFilling(
    item: MediaItemEntity,
    details: TVDetailsEntity?,
    hasIncompleteEpisodes: Boolean,
    hasAnyEpisodes: Boolean,
): Boolean =
    item.coverImageHash == null ||
        item.releaseYear == null ||
        item.communityRating == null ||
        // On the item since v7: a synopsis-only gap would never be queued if this still asked
        // tv_details, which no longer holds one.
        item.synopsis == null ||
        (details != null && details.hasGap()) ||
        hasIncompleteEpisodes ||
        // #123: a show that is set up is always worth asking about, even with nothing left to fill.
        // Counting is not filling and does not converge -- see EpisodeDao.mediaIdsWithAnyEpisodes.
        hasAnyEpisodes

/**
 * Whether any of the episode columns TMDB can answer is still empty.
 *
 * The Kotlin form of [com.hub.media.core.database.dao.EpisodeDao.mediaIdsWithIncompleteEpisodes]'s
 * predicate, and the two are a pair: that query decides which shows are worth queueing and this
 * decides whether a queued show is still worth a request. They must test the same columns.
 */
private fun EpisodeEntity.hasGap(): Boolean =
    title == null ||
        airDate == null ||
        runtimeMinutes == null ||
        overview == null ||
        communityRating == null

/**
 * Whether any of the show-level columns TMDB can answer is still empty.
 *
 * `status` is not among them and must never be: it is the abandonment flag, which is a decision only
 * the user makes.
 */
private fun TVDetailsEntity.hasGap(): Boolean =
    totalSeasons == null ||
        airingStatus == null ||
        firstAirDate == null ||
        lastAirDate == null

/** Distinct shows represented, not seasons — one show can disagree about several of its seasons. */
private fun List<ShowSeasonMismatch>.countShows(): Int = distinctBy { it.mediaId }.size

/**
 * Makes [fresh] the complete set of disagreements recorded for [mediaId], dropping whatever was
 * there before.
 *
 * **Replacing the show's whole set, rather than merging into it, is what keeps the list true.** Two
 * things go wrong with per-season adding:
 *
 * - **Duplicates.** A season can legitimately be visited more than once across a resume chain — a
 *   show whose poster download fails is deferred *after* its episodes were filled and its
 *   disagreement noted, so the next run reports the same season again. Appending onto the list
 *   loaded from storage gave that show one copy per attempt.
 * - **Stale rows, which is worse.** A season the user has since reconciled simply stops appearing in
 *   the report, and an add-or-replace scheme has no way to notice an absence — so the entry would
 *   outlive the problem and go on claiming a disagreement that no longer exists. Replacement treats
 *   "reported nothing for this show" as the meaningful answer it is.
 *
 * Note the bound on that guarantee: it holds for shows this pass actually *visits*. A show with no
 * remaining gaps is not a candidate, so it is never re-examined and its recorded rows persist until
 * a later run clears them. Removing an entry the moment its season is reconciled belongs to whatever
 * does the reconciling (#123's screen), not here.
 */
private fun MutableList<ShowSeasonMismatch>.replaceShow(
    mediaId: String,
    fresh: List<ShowSeasonMismatch>,
) {
    removeAll { it.mediaId == mediaId }
    addAll(fresh)
}

/**
 * [mismatchedShows] has no default on purpose. It defaulted to 0, and every call site that forgot it
 * — the empty-state return, the credential-blocked return, and [BulkTmdbBackfillUseCase.peekProgress]
 * — reported "no shows disagree" while the stored list said otherwise. Two sources of truth for one
 * number, disagreeing in exactly the states a user is most likely to be looking at: after a run has
 * finished, or on reopening Settings. Required, so forgetting it does not compile.
 */
private fun TmdbBackfillState.toProgress(
    mismatchedShows: Int,
    blockedMessage: String? = null,
): TmdbBackfillProgress =
    TmdbBackfillProgress(
        totalCandidates = totalCandidates,
        processed = totalCandidates - pendingMediaIds.size,
        updated = updated,
        nothingToFill = nothingToFill,
        noTmdbIdSkipped = noTmdbIdSkipped,
        remaining = pendingMediaIds.size,
        blockedMessage = blockedMessage,
        mismatchedShows = mismatchedShows,
    )

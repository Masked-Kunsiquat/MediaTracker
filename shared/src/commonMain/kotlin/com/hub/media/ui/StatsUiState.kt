package com.hub.media.ui

/**
 * UI state for the stats screen (ROADMAP Task 5; the screen itself lands in Phase C — this state
 * shape is consumed by it).
 *
 * @property isLoading True until the first emission from every combined
 *   [com.hub.media.features.stats.data.StatsRepository] flow has arrived. `true` initially
 *   (before any DB read completes), matching the "Loading initial state" requirement without a
 *   separate sealed `Loading` state — every field already has a sensible zero/null default, so a
 *   single flag is enough for the screen to show a loading affordance without needing a distinct
 *   state type to destructure.
 * @property week Aggregate stats for the current calendar week (Monday start — see
 *   [com.hub.media.features.stats.data.StatsRepository.thisWeekBounds]).
 * @property month Aggregate stats for the current calendar month (1st of month start — see
 *   [com.hub.media.features.stats.data.StatsRepository.thisMonthBounds]).
 * @property currentStreakDays Current consecutive-reading-day streak — see
 *   [com.hub.media.features.stats.data.StatsRepository.observeReadingStreak] for the exact rule
 *   (today-without-a-session-yet does not break yesterday's streak).
 * @property lifetimeBooksFinished Total count of books currently
 *   [com.hub.media.core.database.entities.ReadingStatus.FINISHED] (ROADMAP Task 6 Phase C), across
 *   all time — see [com.hub.media.features.stats.data.StatsRepository]'s "Books-finished stat" KDoc
 *   for why this lifetime total is shown alongside, not instead of, [week]/[month]'s period-scoped
 *   [Period.booksFinished].
 */
public data class StatsUiState(
    val isLoading: Boolean = true,
    val week: Period = Period(),
    val month: Period = Period(),
    val currentStreakDays: Int = 0,
    val lifetimeBooksFinished: Int = 0,
) {

    /**
     * One period's (week or month) aggregate stats. Reused for both [week] and [month] rather
     * than duplicating three near-identical properties twice.
     *
     * @property timeReadSeconds Sum of known session durations in the period, or `null` if no
     *   session in the period has a known duration (schema v2 — "unknown", not "zero"; see
     *   [com.hub.media.features.stats.data.StatsRepository] KDoc point 1). Never coerced to `0`
     *   here — the screen decides how to render "unknown" (e.g. "--" vs "0m").
     * @property sessionCount Count of ALL sessions in the period, null-duration sessions
     *   included. Always a real, non-negative count.
     * @property pagesRead Sum of known per-session `deltaPages` in the period, or `null` if no
     *   session in the period has a known `deltaPages`. Never inferred from position continuity
     *   between sessions ("no gap reconciliation" — see
     *   [com.hub.media.features.stats.data.StatsRepository] KDoc point 2).
     * @property booksFinished Count of books whose
     *   [com.hub.media.core.database.entities.BookDetailsEntity.finishedAt] falls in this period
     *   (ROADMAP Task 6 Phase C). Always a real, non-negative count (never `null`) — an empty
     *   period genuinely has `0`, same as [sessionCount] — but see
     *   [com.hub.media.features.stats.data.StatsRepository]'s "Books-finished stat" KDoc for why
     *   this specific count necessarily undercounts anything finished before schema v3 shipped.
     */
    public data class Period(
        val timeReadSeconds: Long? = null,
        val sessionCount: Int = 0,
        val pagesRead: Int? = null,
        val booksFinished: Int = 0,
    )
}

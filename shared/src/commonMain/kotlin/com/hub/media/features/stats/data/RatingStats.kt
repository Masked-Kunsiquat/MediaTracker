package com.hub.media.features.stats.data

import com.hub.media.core.database.dao.RatedItemRow
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * One title's score for a period (#232): the mean of its rated items in it, in stars.
 *
 * @property latestAt Its most recent dated rated item in the period, for breaking ties; `null` when
 *   none is dated (All time only).
 * @property rated How many of its items are rated in the period.
 */
public data class RatedTitle(
    val mediaId: String,
    val title: String,
    val stars: Double,
    val latestAt: Instant?,
    val rated: Int,
) {
    /** The score as the stars draw it: rounded to the nearest half, in halves. */
    val roundedHalves: Int get() = (stars * 2).roundToInt().coerceIn(1, 10)

    /** Whether the stars cannot show the score exactly, so the row also prints it ("4.3"). */
    val showsExactScore: Boolean get() = (stars * 2) != (stars * 2).roundToInt().toDouble()
}

/**
 * A period's ratings for one medium (#232): each title once, at its score.
 *
 * @property averageStars The mean of the titles' scores.
 * @property titles How many titles have a score.
 * @property top The highest scores first; ties go to the most recent, dateless last, then A to Z.
 * @property spread Ten counts of titles, index 0 = half a star … index 9 = five stars.
 */
public data class RatingStats(
    val averageStars: Double,
    val titles: Int,
    val top: List<RatedTitle>,
    val spread: List<Int>,
) {
    /** The fullest bar, in halves; a tie picks the higher score. */
    val mostCommonHalves: Int get() = spread.indices.maxWith(compareBy({ spread[it] }, { it })) + 1
}

/** [rows] as scores, a top [top], the average and the spread; `null` when nothing is rated. */
public fun ratingStats(
    rows: List<RatedItemRow>,
    top: Int = 5,
): RatingStats? {
    if (rows.isEmpty()) return null
    val titles =
        rows.groupBy { it.mediaId }.map { (id, items) ->
            RatedTitle(
                mediaId = id,
                title = items.first().title,
                stars = items.sumOf { it.rating } / 2.0 / items.size,
                latestAt = items.mapNotNull { it.at }.maxOrNull(),
                rated = items.size,
            )
        }
    val ranked =
        titles.sortedWith(
            compareByDescending<RatedTitle> { it.stars }
                .thenByDescending { it.latestAt }
                .thenBy { it.title.lowercase() },
        )
    val spread = IntArray(10)
    titles.forEach { spread[it.roundedHalves - 1]++ }
    return RatingStats(
        averageStars = titles.sumOf { it.stars } / titles.size,
        titles = titles.size,
        top = ranked.take(top),
        spread = spread.toList(),
    )
}

/** A year's top-rated book, film and show (#232); a medium with nothing rated that year is `null`. */
public data class Favorites(
    val book: RatedTitle?,
    val film: RatedTitle?,
    val show: RatedTitle?,
)

/** Each medium's best of [books], [films] and [shows] by [ratingStats]'s order; `null` when none is rated. */
public fun favorites(
    books: List<RatedItemRow>,
    films: List<RatedItemRow>,
    shows: List<RatedItemRow>,
): Favorites? {
    val f =
        Favorites(
            book = ratingStats(books, top = 1)?.top?.firstOrNull(),
            film = ratingStats(films, top = 1)?.top?.firstOrNull(),
            show = ratingStats(shows, top = 1)?.top?.firstOrNull(),
        )
    return f.takeIf { it.book != null || it.film != null || it.show != null }
}

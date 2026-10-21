package com.hub.media.features.books.data

import com.hub.media.core.database.entities.ReadThroughEntity
import kotlin.math.abs
import kotlin.math.roundToInt

/** The smallest rating, in half-steps (#230): half a star. */
public const val MIN_RATING: Int = 1

/** The largest rating, in half-steps (#230): five stars. */
public const val MAX_RATING: Int = 10

public fun isValidRating(halves: Int): Boolean = halves in MIN_RATING..MAX_RATING

/** Half-steps as stars: 9 is 4.5. */
public fun Int.toStars(): Double = this / 2.0

/** [stars] as half-steps, or `null` when it is not on the half-star grid in 0.5..5 (an import's `4.3`). */
public fun starsToHalves(stars: Double): Int? {
    // NaN cannot be rounded at all (it throws), and infinity is never a rating.
    if (!stars.isFinite()) return null
    val halves = (stars * 2).roundToInt()
    return if (abs(stars * 2 - halves) < 1e-9 && isValidRating(halves)) halves else null
}

/**
 * A title's rating from its rated reads (#230): the average and the latest, in stars.
 *
 * @property ratedReads How many reads carry a rating; unrated reads are ignored.
 */
public data class TitleRating(
    val averageStars: Double,
    val latestStars: Double,
    val ratedReads: Int,
)

/** [reads]' rating, or `null` when none is rated. "Latest" is the highest-numbered rated read. */
public fun titleRating(reads: List<ReadThroughEntity>): TitleRating? {
    val rated = reads.filter { it.rating != null }.sortedBy { it.number }
    if (rated.isEmpty()) return null
    return TitleRating(
        averageStars = rated.map { it.rating!! }.average() / 2.0,
        latestStars = rated.last().rating!!.toStars(),
        ratedReads = rated.size,
    )
}

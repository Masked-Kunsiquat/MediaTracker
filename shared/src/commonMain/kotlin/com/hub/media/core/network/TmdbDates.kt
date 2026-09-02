package com.hub.media.core.network

import kotlin.time.Instant

/**
 * TMDB's `YYYY-MM-DD` as an [Instant] at midnight UTC, or `null`.
 *
 * A date with no time and no zone has to be given one to become an instant, and UTC midnight is the
 * convention used for provider dates throughout this app. The alternative -- the device's zone --
 * would make the same TMDB response produce different stored values on two phones, and make a CSV
 * round-trip between them lossy.
 *
 * Anything unparseable yields `null` rather than throwing. TMDB sends `""` for unknown often enough
 * that it is ordinary, not exceptional, and one bad date must not cost the whole record.
 *
 * Lives in `core.network` rather than beside either mapper because both the show and the film
 * translation need it, and a second copy of a date rule is a second thing to get subtly wrong.
 */
internal fun String?.toInstantOrNull(): Instant? {
    val raw = this?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return runCatching { Instant.parse("${raw}T00:00:00Z") }.getOrNull()
}

/** The four-digit year of a TMDB `YYYY-MM-DD` date, or `null`. */
internal fun String?.toYearOrNull(): Int? = this?.trim()?.take(4)?.toIntOrNull()

/**
 * A provider score, or `null` when nothing has actually been rated.
 *
 * TMDB answers an unrated title with `vote_average: 0.0`, not `null` -- the mean of an empty set.
 * Passing that through would record "everybody scored this zero" for every obscure title, which is
 * worse than recording nothing: it is a number, so it survives into averages and comparisons looking
 * like data. [voteCount] is the only thing that distinguishes the two, which is why the DTOs model a
 * field nothing else reads. Verified against live TMDB on #128.
 *
 * The range is *not* clamped. A value outside 0-10 means TMDB changed its scale, and
 * [com.hub.media.features.media.domain.MediaMetadataValidation.validateCommunityRating] refusing the
 * write is the correct outcome -- silently clamping would store a wrong number rather than surface a
 * broken assumption.
 */
internal fun tmdbRatingOf(
    voteAverage: Double?,
    voteCount: Int?,
): Double? = voteAverage?.takeIf { (voteCount ?: 0) > 0 }

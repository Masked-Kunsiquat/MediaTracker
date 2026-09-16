package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.github.maskedkunisquat.mediatracker.ui.theme.MediaTrackerTheme
import com.hub.media.core.database.entities.MediaType

/** Statuses used only by these previews -- [StatusDropdownChip] is generic over any enum-like list. */
private val PREVIEW_STATUSES = listOf("Watchlist", "Watching", "Watched", "Abandoned")

/** Preview of [DetailHeader] with artwork, a subline, a rating and a status note (Option A, #141). */
@Preview(showBackground = true)
@Composable
private fun DetailHeaderReadyPreview() {
    MediaTrackerTheme {
        DetailHeader(
            kind = "Film",
            year = 2014,
            title = "Interstellar",
            subline = "2h 49m",
            rating = 8.6,
            artwork =
                DetailArtwork(
                    coverStorageDir = "/fake/path",
                    coverImageHash = "fake.jpg",
                    mediaType = MediaType.MOVIE,
                ),
            statusNote = "Watched Jan 3, 2024",
            statusControl = {
                StatusDropdownChip(
                    value = "Watched",
                    options = PREVIEW_STATUSES,
                    label = { it },
                    onSelect = {},
                    onClickLabel = "Change status",
                )
            },
        )
    }
}

/** No-artwork preview (#141's "never a permanent placeholder" rule) -- the text column takes the full width. */
@Preview(showBackground = true)
@Composable
private fun DetailHeaderNoArtworkPreview() {
    MediaTrackerTheme {
        DetailHeader(
            kind = "Film",
            year = null,
            title = "Primer",
            subline = "Runtime unknown",
            rating = null,
            artwork = null,
            statusNote = null,
            statusControl = {
                StatusDropdownChip(
                    value = "Watchlist",
                    options = PREVIEW_STATUSES,
                    label = { it },
                    onSelect = {},
                    onClickLabel = "Change status",
                )
            },
        )
    }
}

/** Dark-theme counterpart of [DetailHeaderReadyPreview], same data. */
@Preview(showBackground = true)
@Composable
private fun DetailHeaderReadyDarkPreview() {
    MediaTrackerTheme(darkTheme = true, dynamicColor = false) {
        DetailHeader(
            kind = "Film",
            year = 2014,
            title = "Interstellar",
            subline = "2h 49m",
            rating = 8.6,
            artwork =
                DetailArtwork(
                    coverStorageDir = "/fake/path",
                    coverImageHash = "fake.jpg",
                    mediaType = MediaType.MOVIE,
                ),
            statusNote = "Watched Jan 3, 2024",
            statusControl = {
                StatusDropdownChip(
                    value = "Watched",
                    options = PREVIEW_STATUSES,
                    label = { it },
                    onSelect = {},
                    onClickLabel = "Change status",
                )
            },
        )
    }
}

/** Preview of [DetailSynopsis] with text short enough that it never overflows -- no "More" button. */
@Preview(showBackground = true)
@Composable
private fun DetailSynopsisShortPreview() {
    MediaTrackerTheme {
        DetailSynopsis(text = "A stranded astronaut must find a way to survive on a hostile planet.")
    }
}

/** Preview of [DetailSynopsis] with text long enough to clamp at 4 lines and show "More". */
@Preview(showBackground = true)
@Composable
private fun DetailSynopsisLongPreview() {
    MediaTrackerTheme {
        DetailSynopsis(
            text =
                "A team of explorers travel through a wormhole in space in an attempt to ensure " +
                    "humanity's survival. With Earth becoming increasingly uninhabitable, a former " +
                    "NASA pilot leads a crew through interstellar space to search for a new home " +
                    "among the stars, racing against time and the limits of relativity itself.",
        )
    }
}

/** Preview of [DetailProgressCard] partway through (#141 step 2). */
@Preview(showBackground = true)
@Composable
private fun DetailProgressCardPreview() {
    MediaTrackerTheme {
        DetailProgressCard(value = "12 / 19 episodes", completed = 12, total = 19)
    }
}

/** Preview of [DetailFacts], including a dropped pair -- one fewer cell than the label list below. */
@Preview(showBackground = true)
@Composable
private fun DetailFactsPreview() {
    MediaTrackerTheme {
        DetailFacts(
            facts =
                listOf(
                    DetailFact("First aired", "May 26, 2019"),
                    DetailFact("Airing", "Ended"),
                    DetailFact("Seasons", "1"),
                    DetailFact("Episodes", null),
                ),
        )
    }
}

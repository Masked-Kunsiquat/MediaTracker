package com.github.maskedkunisquat.mediatracker.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.mediatracker.R
import kotlin.math.roundToInt

/**
 * Five stars for a rating in half-steps (#230): 1 is half a star, 10 is five. `null` is unrated.
 *
 * They are drawn with Google's Material Symbols star icons (`ic_star_*`); the half star mirrors in
 * right-to-left languages. With [onRate], a tap on a star's left half rates a half and on its right
 * half a whole, and a tap on the current value clears it (`null`). Without it they only show a value.
 *
 * TalkBack reads one node, "Your rating, 4.5 of 5" or "Not rated". An editable one is a progress node
 * TalkBack steps in half stars (swipe up/down), so a rating never depends on hitting half a star.
 */
@Composable
fun RatingStars(
    halves: Int?,
    modifier: Modifier = Modifier,
    starSize: Dp = 20.dp,
    onRate: ((Int?) -> Unit)? = null,
) {
    val value = halves ?: 0
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val description =
        if (halves == null) {
            stringResource(R.string.rating_none)
        } else {
            stringResource(R.string.rating_value, formatStars(halves))
        }
    val input =
        if (onRate == null) {
            Modifier
        } else {
            Modifier
                // Material's minimum touch target, whatever the star size.
                .heightIn(min = 48.dp)
                .pointerInput(halves, rtl) {
                    detectTapGestures { offset ->
                        // Star 1 sits at the start edge: the right one in a right-to-left layout.
                        val fromStart = if (rtl) size.width - offset.x else offset.x
                        val tapped = (fromStart / size.width * 10).toInt().coerceIn(0, 9) + 1
                        onRate(if (tapped == halves) null else tapped)
                    }
                }
        }
    Row(
        modifier =
            modifier.then(input).clearAndSetSemantics {
                contentDescription = description
                if (onRate != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(value / 2f, 0f..5f, steps = 9)
                    setProgress { target ->
                        onRate((target * 2).roundToInt().coerceIn(0, 10).takeIf { it > 0 })
                        true
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(starSize / 8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(5) { i ->
            val filled = (value - i * 2).coerceIn(0, 2)
            Icon(
                painter =
                    painterResource(
                        when (filled) {
                            2 -> R.drawable.ic_star_filled
                            1 -> R.drawable.ic_star_half
                            else -> R.drawable.ic_star_outline
                        },
                    ),
                contentDescription = null,
                tint = if (filled > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(starSize),
            )
        }
    }
}

/** A rating in stars as the viewer's locale writes it: "4.5", or "4" for a whole star. */
@Composable
fun formatStars(halves: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (halves % 2 == 0) (halves / 2).toString() else String.format(locale, "%.1f", halves / 2.0)
}

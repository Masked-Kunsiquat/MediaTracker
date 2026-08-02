package com.github.maskedkunisquat.mediatracker.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.github.maskedkunisquat.mediatracker.R
import com.hub.media.features.portability.domain.DuplicatePolicy

/**
 * Maps [DuplicatePolicy] to its human-readable display label (ROADMAP Task 8 Phase B). Mirrors
 * [WeekStartDay.displayLabel][com.github.maskedkunisquat.mediatracker.ui.screens.displayLabel]'s
 * exhaustive-`when` pattern exactly, so a future [DuplicatePolicy] constant is a compile error
 * here until a label is added.
 */
@Composable
internal fun DuplicatePolicy.displayLabel(): String = when (this) {
    DuplicatePolicy.SKIP -> stringResource(R.string.duplicate_policy_skip)
    DuplicatePolicy.REPLACE -> stringResource(R.string.duplicate_policy_replace)
    DuplicatePolicy.MERGE -> stringResource(R.string.duplicate_policy_merge)
}

package com.github.maskedkunisquat.mediatracker

import android.content.Context
import android.content.Intent

/**
 * Kills and relaunches the whole app process (ROADMAP Task 8 Phase C, `.sqlite` restore) --
 * see [com.hub.media.features.portability.domain.DefaultRestoreDatabaseUseCase]'s class KDoc,
 * "Why a full process restart follows every commit() call," for the full justification: every
 * ViewModel/repository already alive in this process holds references captured from an
 * [com.hub.media.ui.AppContainer] that a restore's caller has just closed, and Room is not designed
 * to be re-pointed at a swapped-in file after construction. A fresh process, launched through the
 * exact same [com.hub.media.ui.createAppContainer] cold-start path every ordinary launch already
 * uses, is the only way back to a fully working state that doesn't risk a half-live container.
 *
 * Relaunches [MainActivity] with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` (a fresh
 * task, discarding the current back stack entirely) and then forcibly terminates this process via
 * [Runtime.exit] -- deliberately not [android.app.Activity.finish]/[System.exit] alone, since
 * lingering singletons (this app's own now-closed [com.hub.media.ui.AppContainer], any coroutine
 * scope tied to it) must not survive into what should be a clean process.
 */
internal fun restartApp(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launchIntent)
    }
    Runtime.getRuntime().exit(0)
}

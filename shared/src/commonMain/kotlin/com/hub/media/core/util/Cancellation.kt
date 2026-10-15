package com.hub.media.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block], handing any [Exception] to [onException] -- except [CancellationException], which is
 * always rethrown untouched.
 *
 * ### Why the rethrow exists
 * On Kotlin/JVM `CancellationException` *is* an `Exception`, so a bare `catch (e: Exception)` around
 * suspending code also catches coroutine cancellation. Swallowing it breaks structured concurrency:
 * a coroutine whose screen was closed carries on as though nothing happened, and the cancellation is
 * reported -- and logged -- as a failure. Every catch around suspending code in `shared/` used to
 * spell this out by hand (72 sites when #81 item 6 replaced them); this function is that clause,
 * written once.
 *
 * ### What it does not change
 * Only [Exception] is caught: an [Error] (out-of-memory, a failed assertion) still propagates.
 * `TimeoutCancellationException` is a `CancellationException`, so it is rethrown too.
 *
 * Both lambdas are inlined, so [block] may suspend, and either may `return` from the enclosing
 * function, `return@launch`, or `continue` an enclosing loop.
 *
 * ### When not to use it
 * When the cancellation branch itself has work to do -- log a resume point, delete a partial file
 * under `NonCancellable`, settle UI state -- keep a hand-written `catch (e: CancellationException)`
 * that does that work and then rethrows (`BulkBackfillUseCase`, `DatabaseBackupUseCase`,
 * `BackfillViewModel.start`). Use [guardResource] for the standard repository failure.
 */
public inline fun <T> catchingNonCancellation(
    block: () -> T,
    onException: (Exception) -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onException(e)
    }

/**
 * The standard repository / use-case failure, on top of [catchingNonCancellation]: logs at ERROR
 * under [tag] with the exception attached and [logMessage] as the text, and returns
 * `Resource.Error("<failure>: <exception message, or "Unknown error">", cause = e)`.
 *
 * A [Resource] that [block] returns itself -- including its own `Resource.Error` for "not found" --
 * passes through unlogged. Cancellation is rethrown and logged nowhere.
 *
 * Only for sites whose failure is exactly that shape. A site that warns instead, omits the cause, or
 * must keep the exception's message out of user-facing text (a URL carrying a credential) uses
 * [catchingNonCancellation] with its own handler.
 *
 * @param failure The user-facing prefix, e.g. `"Failed to update season"`.
 * @param logMessage The log text; identifiers only, never library content (see [Logger]).
 */
public inline fun <T> Logger.guardResource(
    tag: String,
    failure: String,
    noinline logMessage: () -> String,
    block: () -> Resource<T>,
): Resource<T> =
    catchingNonCancellation(block) { e ->
        this.error(tag, e, logMessage)
        Resource.Error("$failure: ${e.message ?: "Unknown error"}", cause = e)
    }

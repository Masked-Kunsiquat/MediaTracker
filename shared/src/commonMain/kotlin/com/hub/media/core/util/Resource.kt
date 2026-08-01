package com.hub.media.core.util

/**
 * A sealed result wrapper that encapsulates the outcome of an operation that may fail.
 * Per AGENTS.md §5, network calls and database operations MUST be wrapped to prevent
 * UI crashes on offline/error states.
 *
 * @param T The type of the successful result.
 */
public sealed class Resource<out T> {

    /**
     * Represents a successful result.
     *
     * @property data The result data.
     */
    public data class Success<T>(val data: T) : Resource<T>()

    /**
     * Represents a failed result.
     *
     * @property message A user-friendly or diagnostic error message.
     * @property cause The underlying exception, if any.
     */
    public data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : Resource<Nothing>()
}

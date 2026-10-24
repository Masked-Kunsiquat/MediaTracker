package com.hub.media.ui

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.util.Resource
import com.hub.media.features.books.domain.BookDraft
import com.hub.media.features.books.domain.BookFormValues
import com.hub.media.features.books.domain.BookIngestionUseCase
import com.hub.media.features.books.network.BookMetadata
import kotlinx.coroutines.CompletableDeferred

/**
 * Hand-rolled fake [BookIngestionUseCase] for [AddBookViewModel] tests (AGENTS.md §5 "No
 * Unnecessary Dependencies" — no mocking library).
 *
 * [result] scripts the whole add as it used to be one call (#214 split it into lookup and save): an
 * [Resource.Error] fails the **lookup** with it, as an unknown or invalid ISBN does; a
 * [Resource.Success] lets the lookup return a draft of "Fake Book" and makes **save** return it.
 *
 * Records every [lookup] and [save]. [awaitGate] holds lookups and [awaitSaveGate] holds saves until
 * [release] / [releaseSave], so tests can observe the in-flight states deterministically.
 */
internal class FakeAddBookByIsbnUseCase(
    private val result: Resource<String> = Resource.Success("fake-media-id"),
    private val pageCount: Int? = 300,
) : BookIngestionUseCase {
    /** The ISBNs [lookup] was called with, in order. */
    val lookups: MutableList<String> = mutableListOf()

    /** The values [save] was called with, in order. */
    val saves: MutableList<BookFormValues> = mutableListOf()

    /** Number of lookups -- the one network-spending call a tap makes. */
    val callCount: Int get() = lookups.size

    /** When true, [lookup] suspends until [release] is called. */
    var awaitGate: Boolean = false

    /** When true, [save] suspends until [releaseSave] is called. */
    var awaitSaveGate: Boolean = false

    private val gate = CompletableDeferred<Unit>()
    private val saveGate = CompletableDeferred<Unit>()

    override suspend fun lookup(isbn: String): Resource<BookDraft> {
        lookups += isbn
        if (awaitGate) gate.await()
        return when (result) {
            is Resource.Error -> result
            is Resource.Success ->
                Resource.Success(
                    BookDraft(
                        normalizedIsbn = isbn,
                        metadata =
                            BookMetadata(
                                title = "Fake Book",
                                pageCount = pageCount,
                                provider = IdentifierProvider.OPEN_LIBRARY,
                            ),
                        coverBytes = null,
                    ),
                )
        }
    }

    override suspend fun save(
        draft: BookDraft,
        values: BookFormValues,
    ): Resource<String> {
        saves += values
        if (awaitSaveGate) saveGate.await()
        return result
    }

    /** Releases a pending [lookup] started while [awaitGate] was true. */
    fun release() {
        gate.complete(Unit)
    }

    /** Releases a pending [save] started while [awaitSaveGate] was true. */
    fun releaseSave() {
        saveGate.complete(Unit)
    }
}

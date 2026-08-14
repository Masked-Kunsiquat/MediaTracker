package com.hub.media.features.books.network

import com.hub.media.core.database.entities.IdentifierProvider

/**
 * One provider-agnostic search hit from [BookSearchProvider.searchByTitleOrAuthor]
 * (ROADMAP Task 9 Phase B1).
 *
 * Deliberately **not** [BookMetadata]. That type is the result of resolving one specific edition
 * by ISBN and treats [BookMetadata.title] as the only guaranteed field; a search hit is a
 * different thing — a *candidate* at work granularity, carrying whatever the index happened to
 * have, and identified by a work key rather than an ISBN. Reusing `BookMetadata` here would have
 * meant an `isbn` that is always null and a `pageCount` that means "median across editions", both
 * of which quietly lie to every existing caller.
 *
 * @property title Work title — the one field a hit is dropped for lacking, since a result the user
 *   cannot read is not worth showing.
 * @property authors Author display names as the index holds them, best-effort and possibly empty.
 *   No secondary `/authors/{key}` fetch happens here: search already returns names inline, which
 *   is precisely why it is affordable per keystroke when the ISBN path is not.
 * @property firstPublishYear First publication year of the *work*, which is what disambiguates
 *   two same-titled books in a dropdown. Note this is not the year of any edition the user may own.
 * @property coverThumbnailUrl A remote cover URL sized for a list row, if the index has a cover.
 * @property editionCount How many editions the work has, when known. Useful as a rough popularity
 *   signal for ordering or display; not authoritative.
 * @property medianPageCount Median page count across editions, when known. An estimate by
 *   construction — editions genuinely differ — so it must never be written to a book as fact
 *   without the user seeing it first.
 * @property provider Which catalog this hit came from.
 * @property workKey The provider-native work identifier (e.g. `/works/OL27482W`).
 * @property coverEditionKey The provider's chosen representative edition (e.g. `OL51711263M`),
 *   when it names one. This is the handle a selection can resolve to a concrete ISBN, which is
 *   what [com.hub.media.features.books.domain.AddBookByIsbnUseCase] needs — a search hit alone
 *   cannot be added, and wiring that resolution up is Phase B2's job, not this one's.
 */
public data class BookSearchResult(
    val title: String,
    val authors: List<String> = emptyList(),
    val firstPublishYear: Int? = null,
    val coverThumbnailUrl: String? = null,
    val editionCount: Int? = null,
    val medianPageCount: Int? = null,
    val provider: IdentifierProvider,
    val workKey: String? = null,
    val coverEditionKey: String? = null,
)

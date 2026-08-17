package com.hub.media.features.books.domain

import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.books.network.OpenLibraryCoverRateLimiter
import com.hub.media.features.books.network.createDefaultBookMetadataProvider
import io.ktor.client.HttpClient

/**
 * Re-runs cover metadata lookup + download + content-addressed storage for an existing book's
 * already-recorded ISBN, updating [com.hub.media.core.database.entities.MediaItemEntity.coverImageHash]
 * in place (ROADMAP Task 6 Phase E). This exists for books added before the field-level cover
 * fallback / Google Books largest-image-size fix landed: they have no stored cover and, until now,
 * no way to get one short of deleting and re-adding the book.
 *
 * Deliberately **per-book**, not a bulk backfill across the whole library — the roadmap explicitly
 * defers bulk backfill as optional. A bulk pass would need its own throttling: the last-resort
 * `?default=false` ISBN cover probe this use case's [metadataProvider] chain can reach (see
 * [com.hub.media.features.books.network.OpenLibraryIsbnCoverProbe]) is ISBN-keyed and therefore
 * subject to Open Library's 100-requests-per-IP-per-5-minutes cover rate limit, unlike the
 * ID-keyed cover fetches [com.hub.media.features.books.network.OpenLibraryClient] normally uses. A
 * one-book-at-a-time affordance never risks that limit; a bulk pass over a large library could.
 *
 * Every failure path leaves the book's existing cover completely untouched — this use case only
 * ever *adds* a cover, never removes or blanks one that was already there, per AGENTS.md §1 (user
 * data safety over shortcuts).
 *
 * @param metadataProvider The same Open Library -> Google Books -> ISBN-probe fallback chain
 *   [AddBookByIsbnUseCase] uses (see [createDefaultRefetchCoverUseCase] for the standard wiring),
 *   but here only the returned [com.hub.media.features.books.network.BookMetadata.coverImageUrl]
 *   is used — title/authors/page count/etc. are intentionally discarded, since correcting a
 *   book's other metadata is [BookRepository.updateBookMetadata]'s job, not this one's.
 * @param coverDownloader Downloads the raw cover image bytes.
 * @param imageStorage Content-addressed local disk store for cover images (AGENTS.md §4).
 * @param bookRepository Source of the book's recorded ISBN ([BookRepository.getBookWithDetails])
 *   and target of the cover update ([BookRepository.updateCoverImageHash]).
 */
public class RefetchCoverUseCase(
    private val metadataProvider: BookMetadataProvider,
    private val coverDownloader: CoverImageDownloader,
    private val imageStorage: LocalImageStorageManager,
    private val bookRepository: BookRepository,
) {
    /**
     * Runs the re-fetch flow for [mediaId]:
     * 1. Looks up the book's recorded ISBN via [BookRepository.getBookWithDetails]. No book, or a
     *    book with no ISBN on record, short-circuits before any network call.
     * 2. Fetches metadata via [metadataProvider] for that ISBN. A lookup failure on every provider
     *    is propagated as-is; the existing cover (if any) is untouched.
     * 3. If the metadata carries no [com.hub.media.features.books.network.BookMetadata.coverImageUrl]
     *    at all (neither provider, nor the last-resort ISBN probe, has a cover for this ISBN),
     *    returns [Resource.Error] with a message clear enough to surface directly in the UI. The
     *    existing cover is left exactly as it was.
     * 4. Downloads and content-addresses the cover image. A download or storage failure returns
     *    [Resource.Error]; again, the existing cover is untouched — nothing is written until the
     *    new image is fully downloaded and hashed.
     * 5. Persists the new hash via [BookRepository.updateCoverImageHash].
     *
     * @param mediaId The book to refetch a cover for.
     * @return [Resource.Success] with the new `<sha256>.jpg` cover filename on success, or
     *   [Resource.Error] describing why — [mediaId] doesn't resolve to a book, the book has no
     *   ISBN on record, no provider (including the last-resort probe) has a cover for it, the
     *   download failed, or the image couldn't be saved to disk. Never throws.
     */
    public suspend fun execute(mediaId: String): Resource<String> {
        val bookWithDetails =
            bookRepository.getBookWithDetails(mediaId)
                ?: return Resource.Error("Book with id=$mediaId not found")
        val isbn = bookWithDetails.details?.isbn
        if (isbn.isNullOrBlank()) {
            return Resource.Error("This book has no ISBN on record, so a cover can't be looked up")
        }

        val metadataResult = metadataProvider.fetchByIsbn(isbn)
        if (metadataResult !is Resource.Success) {
            return Resource.Error(
                "Cover lookup failed: ${(metadataResult as Resource.Error).message}",
            )
        }

        val coverUrl =
            metadataResult.data.coverImageUrl
                ?: return Resource.Error("No cover image is available for this book from any provider")

        val downloadResult = coverDownloader.download(coverUrl)
        if (downloadResult !is Resource.Success) {
            return Resource.Error(
                "Cover image download failed: ${(downloadResult as Resource.Error).message}",
            )
        }

        val saveResult = imageStorage.saveImage(downloadResult.data)
        val hash =
            saveResult.getOrNull()
                ?: return Resource.Error(
                    "Failed to save the downloaded cover image: " +
                        "${saveResult.exceptionOrNull()?.message ?: "Unknown error"}",
                )

        return when (val updateResult = bookRepository.updateCoverImageHash(mediaId, hash)) {
            is Resource.Success -> Resource.Success(hash)
            is Resource.Error -> updateResult
        }
    }
}

/**
 * Convenience factory mirroring [createDefaultAddBookByIsbnUseCase]: assembles the standard
 * Open Library -> Google Books -> ISBN-probe cover fallback chain
 * ([com.hub.media.features.books.network.createDefaultBookMetadataProvider]) and a matching
 * [CoverImageDownloader] from a single shared [HttpClient], wired into a ready-to-use
 * [RefetchCoverUseCase].
 *
 * @param httpClient Shared Ktor client (see [com.hub.media.core.network.createHttpClient]) used
 *   for both metadata providers, the ISBN cover probe, and the cover downloader.
 * @param imageStorage Content-addressed local disk store for cover images.
 * @param bookRepository Source of the book's ISBN and target of the cover update.
 * @param coverRateLimiter Shared ISBN-cover-probe quota tracker (ROADMAP Task 14 Phase A) --
 *   forwarded to [createDefaultBookMetadataProvider]. Production wiring (`AppContainer`) passes
 *   the same instance handed to [com.hub.media.features.books.domain.BulkBackfillUseCase] and
 *   [createDefaultAddBookByIsbnUseCase], so a bulk backfill and this per-book re-fetch draw on one
 *   shared budget instead of each silently pushing the combined total over Open Library's limit --
 *   see [com.hub.media.features.books.network.OpenLibraryCoverRateLimiter]'s KDoc.
 * @param googleBooksApiKeyProvider Suspending source of the user-supplied Google Books API key --
 *   forwarded to [createDefaultBookMetadataProvider]; defaults to no key, which is the keyless
 *   behavior every caller had before this parameter existed.
 */
public fun createDefaultRefetchCoverUseCase(
    httpClient: HttpClient,
    imageStorage: LocalImageStorageManager,
    bookRepository: BookRepository,
    coverRateLimiter: OpenLibraryCoverRateLimiter = OpenLibraryCoverRateLimiter(),
    googleBooksApiKeyProvider: suspend () -> String? = { null },
): RefetchCoverUseCase =
    RefetchCoverUseCase(
        metadataProvider =
            createDefaultBookMetadataProvider(
                httpClient = httpClient,
                coverRateLimiter = coverRateLimiter,
                googleBooksApiKeyProvider = googleBooksApiKeyProvider,
            ),
        coverDownloader = CoverImageDownloader(httpClient),
        imageStorage = imageStorage,
        bookRepository = bookRepository,
    )

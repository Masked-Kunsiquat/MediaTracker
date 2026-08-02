package com.hub.media.features.books.domain

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.Resource
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.books.network.createDefaultBookMetadataProvider
import io.ktor.client.HttpClient

private val ISBN_10_REGEX = Regex("^\\d{9}[\\dX]$")
private val ISBN_13_REGEX = Regex("^\\d{13}$")

/**
 * Abstraction over "ingest a book by ISBN" so callers (namely [com.hub.media.ui.AddBookViewModel])
 * can depend on a narrow contract instead of the concrete [AddBookByIsbnUseCase]. This exists
 * purely for testability without a mocking library (AGENTS.md §5 "No Unnecessary Dependencies"):
 * shared/commonTest can hand-roll a fake implementation with no Ktor engine, no Room database,
 * and no disk I/O.
 */
public interface BookIngestionUseCase {
    /** See [AddBookByIsbnUseCase.execute]. */
    public suspend fun execute(isbn: String): Resource<String>
}

/**
 * End-to-end "add a book by ISBN" workflow: looks up metadata, best-effort downloads and
 * content-addresses the cover image, and atomically persists the result.
 *
 * @param metadataProvider Source of [BookMetadata] for a given ISBN. Callers are expected to
 *   hand in the [com.hub.media.features.books.network.createDefaultBookMetadataProvider] chain
 *   (Open Library -> Google Books -> ISBN cover probe, per AGENTS.md §4 and ROADMAP Task 6 Phase E
 *   — see [createDefaultAddBookByIsbnUseCase] for the standard wiring), but any implementation
 *   works.
 * @param coverDownloader Downloads raw cover image bytes from [BookMetadata.coverImageUrl].
 * @param imageStorage Content-addressed local disk store for cover images (AGENTS.md §4).
 * @param bookRepository Persists the media item, book details, and external identifiers
 *   atomically.
 */
public class AddBookByIsbnUseCase(
    private val metadataProvider: BookMetadataProvider,
    private val coverDownloader: CoverImageDownloader,
    private val imageStorage: LocalImageStorageManager,
    private val bookRepository: BookRepository,
) : BookIngestionUseCase {

    /**
     * Runs the full ingestion flow for [isbn]:
     * 1. Normalizes and validates [isbn] (strips hyphens/spaces; requires 10 or 13 digits, with
     *    an optional trailing `X` check digit for ISBN-10). Invalid input short-circuits before
     *    any network call.
     * 2. Fetches metadata via [metadataProvider]. A lookup failure (not found, network error,
     *    malformed response) is propagated as-is and nothing is written to the database.
     * 3. If the metadata carries a [BookMetadata.coverImageUrl], downloads and saves it via
     *    [coverDownloader] / [imageStorage]. **By design, cover failures never fail ingestion**:
     *    a 404/timeout/corrupt-image response simply leaves the book's `coverImageHash` null so
     *    the user still gets their book row instead of a spurious top-level error (AGENTS.md §5 —
     *    prefer degraded success over a crash/failure for a non-essential asset). No cover URL at
     *    all means no download is attempted.
     * 4. Persists the media item, book details, and external identifiers atomically via
     *    [BookRepository.addBook]. Two identifier rows are recorded when available: the
     *    provider-native id (`metadata.provider` -> `metadata.externalId`) and the normalized
     *    ISBN under [IdentifierProvider.ISBN]. If the metadata's own provider *is* ISBN, only one
     *    row is written to avoid a duplicate (mediaId, provider) composite key.
     *
     * Format is not knowable from ISBN metadata alone, so newly ingested books default to
     * [BookFormat.PHYSICAL] (an ISBN identifies a specific print/audio/ebook edition, but the
     * providers used here don't reliably expose which); users can correct this after the fact.
     *
     * @param isbn Raw ISBN-10 or ISBN-13, with or without hyphens/spaces.
     * @return [Resource.Success] with the new media ID, or [Resource.Error] describing why
     *   ingestion failed. Never throws.
     */
    public override suspend fun execute(isbn: String): Resource<String> {
        val normalizedIsbn = normalizeIsbn(isbn)
        if (!isValidIsbn(normalizedIsbn)) {
            return Resource.Error("Invalid ISBN: '$isbn'")
        }

        val metadataResult = metadataProvider.fetchByIsbn(normalizedIsbn)
        if (metadataResult !is Resource.Success) {
            return metadataResult as Resource.Error
        }
        val metadata = metadataResult.data

        val coverImageHash = metadata.coverImageUrl?.let { url -> downloadAndStoreCover(url) }

        return bookRepository.addBook(
            title = metadata.title,
            releaseYear = metadata.releaseYear,
            purchasePrice = null,
            format = BookFormat.PHYSICAL,
            totalPages = metadata.pageCount,
            isbn = normalizedIsbn,
            coverImageHash = coverImageHash,
            externalIdentifiers = buildExternalIdentifiers(metadata, normalizedIsbn),
        )
    }

    /**
     * Downloads the cover at [url] and saves it to content-addressed storage. Returns null (not
     * an error) on any failure — see [execute]'s KDoc for why cover failures don't fail
     * ingestion.
     */
    private suspend fun downloadAndStoreCover(url: String): String? {
        val downloadResult = coverDownloader.download(url)
        if (downloadResult !is Resource.Success) return null
        return imageStorage.saveImage(downloadResult.data).getOrNull()
    }

    /**
     * Builds the external identifier rows for [metadata]: the provider-native id, plus the
     * normalized ISBN — deduped to a single row if [BookMetadata.provider] is already
     * [IdentifierProvider.ISBN].
     */
    private fun buildExternalIdentifiers(
        metadata: BookMetadata,
        normalizedIsbn: String,
    ): List<Pair<IdentifierProvider, String>> = buildList {
        if (metadata.provider != IdentifierProvider.ISBN && metadata.externalId != null) {
            add(metadata.provider to metadata.externalId)
        }
        add(IdentifierProvider.ISBN to normalizedIsbn)
    }
}

/** Strips hyphens and whitespace and upper-cases any trailing ISBN-10 check digit. */
internal fun normalizeIsbn(rawIsbn: String): String =
    rawIsbn.filterNot { it == '-' || it.isWhitespace() }.uppercase()

/** True if [normalizedIsbn] is a well-formed 10- or 13-digit ISBN (post [normalizeIsbn]). */
internal fun isValidIsbn(normalizedIsbn: String): Boolean =
    normalizedIsbn.isNotBlank() &&
        (ISBN_10_REGEX.matches(normalizedIsbn) || ISBN_13_REGEX.matches(normalizedIsbn))

/**
 * Convenience factory assembling the standard Open Library -> Google Books -> ISBN-probe cover
 * fallback chain (AGENTS.md §4, ROADMAP Task 6 Phase E — see
 * [com.hub.media.features.books.network.createDefaultBookMetadataProvider]) and a matching
 * [CoverImageDownloader] from a single shared [HttpClient], wired into a ready-to-use
 * [AddBookByIsbnUseCase].
 *
 * @param httpClient Shared Ktor client (see [com.hub.media.core.network.createHttpClient]) used
 *   for both metadata providers and the cover downloader.
 * @param imageStorage Content-addressed local disk store for cover images.
 * @param bookRepository Persists the ingested book.
 */
public fun createDefaultAddBookByIsbnUseCase(
    httpClient: HttpClient,
    imageStorage: LocalImageStorageManager,
    bookRepository: BookRepository,
): AddBookByIsbnUseCase = AddBookByIsbnUseCase(
    metadataProvider = createDefaultBookMetadataProvider(httpClient),
    coverDownloader = CoverImageDownloader(httpClient),
    imageStorage = imageStorage,
    bookRepository = bookRepository,
)

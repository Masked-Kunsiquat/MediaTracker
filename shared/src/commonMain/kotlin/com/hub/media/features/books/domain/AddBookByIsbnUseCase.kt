package com.hub.media.features.books.domain

import com.hub.media.core.database.entities.BookFormat
import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.ReadingStatus
import com.hub.media.core.database.entities.TrackingMode
import com.hub.media.core.database.entities.joinAuthors
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import com.hub.media.core.util.Resource
import com.hub.media.core.util.info
import com.hub.media.core.util.warn
import com.hub.media.features.books.data.BookRepository
import com.hub.media.features.books.network.BookMetadata
import com.hub.media.features.books.network.BookMetadataProvider
import com.hub.media.features.books.network.CoverImageDownloader
import com.hub.media.features.books.network.OpenLibraryCoverRateLimiter
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
    /** See [AddBookByIsbnUseCase.lookup]. */
    public suspend fun lookup(isbn: String): Resource<BookDraft>

    /** See [AddBookByIsbnUseCase.save]. */
    public suspend fun save(
        draft: BookDraft,
        values: BookFormValues,
    ): Resource<String>
}

/**
 * A book looked up and waiting on the user's review (#214): everything the save needs that the
 * form does not show -- the normalized ISBN, the provider and work keys, the authors -- plus the
 * cover's bytes.
 *
 * The cover is downloaded **once**, at lookup, and held here in memory: the review header shows it,
 * and the save stores these same bytes. Downloading it again at Save would spend a second request
 * against Open Library's rate limit on every add; storing it at lookup would leave a file behind for
 * every review the user cancels. `null` when there is no cover or it could not be fetched -- a book
 * is complete without one.
 *
 * A plain class rather than a data class, because a [ByteArray] property breaks a data class's
 * `equals`.
 */
public class BookDraft(
    public val normalizedIsbn: String,
    public val metadata: BookMetadata,
    public val coverBytes: ByteArray?,
) {
    /**
     * The form's starting values: what the lookup found, To read, and page tracking when the page
     * count is known (percent otherwise -- the signal [BookRepository.addBook]'s own default uses).
     *
     * Format is not knowable from ISBN metadata alone -- an ISBN identifies a print, audio or ebook
     * edition, but the providers used here don't reliably say which -- so it starts at
     * [BookFormat.PHYSICAL], which the review now lets the user correct before the book is saved.
     */
    public fun initialValues(): BookFormValues =
        BookFormValues(
            title = metadata.title,
            releaseYear = metadata.releaseYear,
            purchasePrice = null,
            totalPages = metadata.pageCount,
            format = BookFormat.PHYSICAL,
            status = ReadingStatus.TO_READ,
            trackingMode = if (metadata.pageCount != null) TrackingMode.PAGES else TrackingMode.PERCENT,
        )
}

/** Log tag for this use case's adoption sites (ROADMAP Task 15 Phase C). */
private const val TAG = "AddBookByIsbnUseCase"

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
    private val logger: Logger = AppLogger,
) : BookIngestionUseCase {
    /**
     * Looks [isbn] up and returns it as a [BookDraft] for the user to review (#214). Writes nothing:
     * no row, and no cover file -- the cover's bytes are fetched and held in the draft.
     *
     * @param isbn Raw ISBN-10 or ISBN-13, with or without hyphens/spaces.
     * @return [Resource.Error] for an invalid ISBN or a failed lookup. A cover that cannot be fetched
     *   is not a failed lookup: the draft simply has none. Never throws.
     */
    public override suspend fun lookup(isbn: String): Resource<BookDraft> {
        val normalizedIsbn = normalizeIsbn(isbn)
        if (!isValidIsbn(normalizedIsbn)) {
            return Resource.Error("Invalid ISBN: '$isbn'")
        }

        val metadataResult = metadataProvider.fetchByIsbn(normalizedIsbn)
        if (metadataResult !is Resource.Success) {
            return metadataResult as Resource.Error
        }
        val metadata = metadataResult.data

        val coverBytes = metadata.coverImageUrl?.let { url -> downloadCover(url) }

        // Lifecycle tracing (ROADMAP Task 15 Phase C). Adding a book is the app's most common
        // deliberate action and traced nothing at all, so an ingestion that silently came back
        // thin -- no author, no cover, no work key -- left the log with nothing to say about it.
        // Shapes only, never the title or author: the log is user-viewable and Settings promises
        // it "never includes your titles, authors, or notes".
        logger.info(TAG) {
            "Book lookup resolved via ${metadata.provider}: " +
                "authors=${metadata.authors.size}, cover=${coverBytes != null}, " +
                "workKey=${metadata.workKey != null}, pageCount=${metadata.pageCount != null}"
        }
        return Resource.Success(BookDraft(normalizedIsbn, metadata, coverBytes))
    }

    /**
     * Adds the reviewed book: [values] as the user left them, plus the [draft]'s identifiers and
     * authors, and its cover stored now that the book is being kept. A cover that cannot be stored
     * is logged and the book saved without one -- by design a cover never fails an add.
     *
     * The media item, details and identifiers are written atomically by [BookRepository.addBook].
     * Two identifier rows when available, plus the work key: the provider-native id and the
     * normalized ISBN, deduplicated when the provider *is* ISBN (see [buildExternalIdentifiers]).
     * [BookMetadata.authors] is kept via [joinAuthors]: both providers already resolve author names,
     * so keeping them is simply not discarding data already paid for in network calls.
     *
     * @return [Resource.Success] with the new media ID, or [Resource.Error]. Never throws.
     */
    public override suspend fun save(
        draft: BookDraft,
        values: BookFormValues,
    ): Resource<String> {
        val coverImageHash = draft.coverBytes?.let { storeCover(it) }
        return bookRepository.addBook(
            title = values.title,
            releaseYear = values.releaseYear,
            purchasePrice = values.purchasePrice,
            format = values.format,
            totalPages = values.totalPages,
            isbn = draft.normalizedIsbn,
            coverImageHash = coverImageHash,
            externalIdentifiers = buildExternalIdentifiers(draft.metadata, draft.normalizedIsbn),
            status = values.status,
            trackingMode = values.trackingMode,
            authors = joinAuthors(draft.metadata.authors),
        )
    }

    /** Downloads the cover at [url]; `null` (logged by the downloader, not an error) on any failure. */
    private suspend fun downloadCover(url: String): ByteArray? =
        (coverDownloader.download(url) as? Resource.Success)?.data

    /**
     * Saves [bytes] to content-addressed storage. Returns null (not an error) on failure: a book is
     * complete without a cover.
     */
    private suspend fun storeCover(bytes: ByteArray): String? =
        // getOrNull() drops the cause on the floor. Not failing the add over a cover is right, but
        // doing it silently leaves "why has this book got no cover?" unanswerable -- the same
        // silent-drop shape as OpenLibraryClient.fetchAuthorName.
        imageStorage
            .saveImage(bytes)
            .onFailure { logger.warn(TAG, it) { "Cover save failed while adding a book" } }
            .getOrNull()

    /**
     * Builds the external identifier rows for [metadata]: the provider-native id, the work key
     * where the provider has one, plus the normalized ISBN — deduped to a single row if
     * [BookMetadata.provider] is already [IdentifierProvider.ISBN].
     *
     * The work key is written even though no feature reads it yet. That is deliberate and is the
     * cheap half of a decision that is expensive to reverse: it costs one row at ingestion, while
     * backfilling it onto books already added costs another rate-limited crawl over the whole
     * library — the exact hole ROADMAP Task 14 existed to dig covers and authors out of. See
     * [IdentifierProvider.OPEN_LIBRARY_WORK].
     */
    private fun buildExternalIdentifiers(
        metadata: BookMetadata,
        normalizedIsbn: String,
    ): List<Pair<IdentifierProvider, String>> =
        buildList {
            if (metadata.provider != IdentifierProvider.ISBN && metadata.externalId != null) {
                add(metadata.provider to metadata.externalId)
            }
            // Its own provider slot rather than overwriting the edition key: the table's primary key is
            // (mediaId, provider), so the two coexist and a later reader can tell a printing from the
            // book it is a printing of.
            metadata.workKey?.let { add(IdentifierProvider.OPEN_LIBRARY_WORK to it) }
            add(IdentifierProvider.ISBN to normalizedIsbn)
        }
}

/** Strips hyphens and whitespace and upper-cases any trailing ISBN-10 check digit. */
internal fun normalizeIsbn(rawIsbn: String): String = rawIsbn.filterNot { it == '-' || it.isWhitespace() }.uppercase()

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
 * @param coverRateLimiter Shared ISBN-cover-probe quota tracker (ROADMAP Task 14 Phase A) --
 *   forwarded to [createDefaultBookMetadataProvider]; see that function's KDoc for why production
 *   callers pass the same instance used elsewhere in the app rather than the default.
 * @param googleBooksApiKeyProvider Suspending source of the user-supplied Google Books API key --
 *   forwarded to [createDefaultBookMetadataProvider]; defaults to no key, which is the keyless
 *   behavior every caller had before this parameter existed.
 */
public fun createDefaultAddBookByIsbnUseCase(
    httpClient: HttpClient,
    imageStorage: LocalImageStorageManager,
    bookRepository: BookRepository,
    coverRateLimiter: OpenLibraryCoverRateLimiter = OpenLibraryCoverRateLimiter(),
    googleBooksApiKeyProvider: suspend () -> String? = { null },
): AddBookByIsbnUseCase =
    AddBookByIsbnUseCase(
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

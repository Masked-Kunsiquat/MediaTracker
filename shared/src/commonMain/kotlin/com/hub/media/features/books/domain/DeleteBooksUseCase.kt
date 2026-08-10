package com.hub.media.features.books.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.deleteImage
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import com.hub.media.core.util.Resource
import com.hub.media.core.util.error

/**
 * Outcome of a bulk delete.
 *
 * @property booksDeleted Rows actually removed. Can be fewer than the ids requested if something
 *   else deleted one first -- see [DeleteBooksUseCase.execute] for why that is reported rather
 *   than treated as a failure.
 * @property coversRemoved Cover files deleted because nothing referenced them any more.
 * @property coversKept Cover files left in place because a surviving book still references them.
 *   Reported separately from [coversRemoved] so the shared-cover case is observable in a test
 *   rather than inferred from a file listing.
 */
public data class DeleteBooksSummary(
    val booksDeleted: Int,
    val coversRemoved: Int,
    val coversKept: Int,
)

/**
 * Abstraction over bulk delete so [com.hub.media.ui.LibraryViewModel] can depend on a narrow
 * contract instead of the concrete [DeleteBooksUseCase] -- the same reason
 * [com.hub.media.features.portability.domain.ExportUseCase] exists (AGENTS.md section 5: no mocking
 * library, so `commonTest` hand-rolls fakes).
 *
 * Specifically this is what makes the *failure* path testable. The only way to provoke a real
 * database failure from a test is to close the database, and Room answers a closed database with
 * `CancellationException`, which this use case deliberately rethrows rather than converting to
 * [Resource.Error]. So without a fake there is no way to exercise "the delete failed" at all, and
 * the error surface it drives would ship unverified.
 */
public interface BulkDeleteUseCase {
    /** See [DeleteBooksUseCase.execute]. */
    public suspend fun execute(ids: List<String>): Resource<DeleteBooksSummary>
}

/**
 * Deletes one or more books and cleans up any cover files that become unreferenced (ROADMAP Task
 * 14 Phase B).
 *
 * ### Why cover cleanup cannot be a plain "delete the file too"
 * Covers are content-addressed (AGENTS.md §4): the filename is the SHA-256 of the image bytes, so
 * two books with identical artwork **share one file**. Deleting that file because one of them was
 * removed would silently blank the other's cover -- a failure the user would not notice until they
 * next looked at an unrelated book, and could not explain when they did.
 *
 * So each candidate hash is checked against the database *after* the rows are gone, and the file is
 * removed only when the count reaches zero. The alternative -- leaving every file behind -- was
 * considered and rejected: a bulk purge of fifty books would strand fifty files permanently, and
 * "deleted books still cost storage" is surprising. This also retires the standing orphaned-cover
 * backlog item instead of growing it.
 *
 * ### Ordering, which is the part that matters
 * Rows are deleted **first**, then references are counted. Both halves of that are deliberate:
 * - Counting *after* the delete is what makes a zero result trustworthy. Counting before would
 *   include the very books being deleted, so nothing would ever look unreferenced and no file would
 *   ever be cleaned up.
 * - Deleting rows before files means the worst case of a crash in between is an orphaned file --
 *   wasted disk, nothing broken. The reverse order risks the opposite: files gone, rows still
 *   present, and surviving books pointing at artwork that no longer exists. Given the choice
 *   between leaking disk and corrupting what the user sees, this leaks disk.
 *
 * ### Known race with concurrent cover writes, accepted rather than locked against
 * Between `countByCoverHash` returning zero and the file being deleted, another operation (the bulk
 * backfill, or an interactive cover re-fetch) could save an image with that same hash and point a
 * book at it. The file would then be deleted out from under a book that references it.
 *
 * Not fixed with a shared per-hash lock, deliberately. That would mean threading a coordinator
 * through `saveImage`/`updateCoverImageHash` and every writer that calls them --
 * `AddBookByIsbnUseCase`, `RefetchCoverUseCase`, `BulkBackfillUseCase` -- which is a large,
 * cross-cutting change for a window measured in microseconds, on two operations a single user has
 * to run simultaneously from two different screens.
 *
 * It is also self-healing, which is what makes deferring reasonable rather than lazy: covers are
 * content-addressed, so the affected book simply shows no cover until the next backfill re-fetches
 * it and `saveImage` writes the same file back. That is precisely the failure mode already
 * documented and accepted for restoring a backup taken before a deletion (ROADMAP Task 14 Phase B)
 * -- recoverable, not data loss. Revisit if cover writes ever move off a user-initiated path.
 *
 * ### Failure handling
 * A failed *file* delete never fails the operation. The books are already gone, which is what the
 * user asked for; a cover that outlives its last reference is wasted space, not broken state, and
 * failing here would report a successful deletion as an error. Such failures are logged at `ERROR`
 * with the hash only -- an opaque content address, never a title (see [Logger]'s identifier rule).
 *
 * @param database Source of the delete and the reference counts.
 * @param imageStorage Where cover files live.
 * @param logger Injected for testability, defaulting to the production [AppLogger] exactly as the
 *   other adoption sites in this codebase do.
 */
public class DeleteBooksUseCase(
    private val database: AppDatabase,
    private val imageStorage: LocalImageStorageManager,
    private val logger: Logger = AppLogger,
) : BulkDeleteUseCase {

    /**
     * Deletes the books identified by [ids] and removes any cover file left unreferenced.
     *
     * An empty [ids] is a no-op returning zeroes rather than an error -- a selection can legitimately
     * be emptied between the confirmation and the confirm tap, and there is nothing wrong about
     * being asked to delete nothing.
     *
     * A count lower than `ids.size` is likewise reported, not failed: the requested end state (these
     * books are gone) holds either way, and turning "already deleted" into an error would surface a
     * scary message for an outcome the user wanted.
     */
    public override suspend fun execute(ids: List<String>): Resource<DeleteBooksSummary> {
        if (ids.isEmpty()) return Resource.Success(DeleteBooksSummary(0, 0, 0))
        return try {

            val dao = database.mediaItemDao()
            // Read the candidate hashes before the rows go: afterwards there is nothing left to ask.
            val candidateHashes = dao.getCoverHashesForIds(ids)
            val booksDeleted = dao.deleteByIds(ids)

            var removed = 0
            var kept = 0
            for (hash in candidateHashes) {
                if (dao.countByCoverHash(hash) > 0) {
                    // A surviving book still shows this artwork. Leaving it is the whole point.
                    kept++
                    continue
                }
                if (imageStorage.deleteImage(hash)) {
                    removed++
                } else {
                    logger.error(TAG) { "Failed to delete unreferenced cover file: $hash" }
                }
            }
            Resource.Success(
                DeleteBooksSummary(booksDeleted = booksDeleted, coversRemoved = removed, coversKept = kept),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A database failure must not escape into the caller's coroutine scope. This runs from
            // viewModelScope, where an uncaught exception takes the whole scope down -- so a failed
            // delete would crash the app rather than report. AGENTS.md section 5 requires database
            // operations to surface as Resource for exactly this reason.
            logger.error(TAG, e) { "Bulk delete failed for ${ids.size} books" }
            Resource.Error(message = "Failed to delete books: ${e.message ?: "Unknown error"}", cause = e)
        }
    }

    private companion object {
        const val TAG = "DeleteBooksUseCase"
    }
}

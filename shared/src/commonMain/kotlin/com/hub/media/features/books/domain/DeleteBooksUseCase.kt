package com.hub.media.features.books.domain

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.storage.LocalImageStorageManager
import com.hub.media.core.storage.deleteImage
import com.hub.media.core.util.AppLogger
import com.hub.media.core.util.Logger
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
) {

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
    public suspend fun execute(ids: List<String>): DeleteBooksSummary {
        if (ids.isEmpty()) return DeleteBooksSummary(0, 0, 0)

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
        return DeleteBooksSummary(booksDeleted = booksDeleted, coversRemoved = removed, coversKept = kept)
    }

    private companion object {
        const val TAG = "DeleteBooksUseCase"
    }
}

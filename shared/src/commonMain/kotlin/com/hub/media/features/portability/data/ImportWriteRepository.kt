package com.hub.media.features.portability.data

import com.hub.media.core.database.AppDatabase
import com.hub.media.core.database.dao.ImportBookInsert
import com.hub.media.core.database.dao.ImportBookUpdate
import com.hub.media.core.database.entities.ReadingSessionEntity
import com.hub.media.core.util.Resource

/**
 * Thin repository wrapping [com.hub.media.core.database.dao.ImportWriteDao]'s single
 * all-or-nothing transaction (ROADMAP Task 8 Phase B), so
 * [com.hub.media.features.portability.domain.ImportDataUseCase] depends on a repository like
 * every other use case in this codebase, rather than reaching into [AppDatabase]/DAOs directly --
 * the same architectural line [com.hub.media.features.books.data.BookRepository]/
 * [com.hub.media.features.books.data.ReadingSessionRepository] already draw for every other write
 * path.
 */
public class ImportWriteRepository(private val db: AppDatabase) {

    /**
     * Applies every queued book/session insert/update in one database transaction (see
     * [com.hub.media.core.database.dao.ImportWriteDao.importAtomically]).
     *
     * @return [Resource.Success] if every write applied, or [Resource.Error] if any of them threw
     *   -- in which case nothing was applied: Room rolled the whole transaction back (never
     *   throws itself).
     */
    public suspend fun importAtomically(
        bookInserts: List<ImportBookInsert>,
        bookUpdates: List<ImportBookUpdate>,
        sessionInserts: List<ReadingSessionEntity>,
        sessionUpdates: List<ReadingSessionEntity>,
    ): Resource<Unit> = try {
        db.importWriteDao().importAtomically(bookInserts, bookUpdates, sessionInserts, sessionUpdates)
        Resource.Success(Unit)
    } catch (e: Exception) {
        Resource.Error(
            message = "Import failed -- nothing was written: ${e.message ?: "Unknown error"}",
            cause = e,
        )
    }
}

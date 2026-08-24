package com.hub.media.ui

import com.hub.media.core.database.dao.TVProgressRow
import com.hub.media.features.media.data.MediaWithDetails

/**
 * UI state for the library/media-list screen.
 *
 * @property media Every currently tracked media item with its details. Consolidated from
 *   `books` per Issue #67. ordered by title. Always the *unfiltered* full list — see
 *   [filteredMedia] for the one [statusFilter] has been applied to.
 * @property statusFilter The currently selected status filter, or `null` for "All" (no filter).
 * @property searchQuery The current local-library search text (ROADMAP Task 9 Phase A).
 * @property isEmpty True when [media] (the unfiltered library) is empty.
 * @property selectedIds Media ids currently selected for a bulk action (ROADMAP Task 14 Phase B).
 * @property deleteError Message from the most recent failed bulk delete, or `null`.
 */

/**
 * A delete failure, carried as an event rather than a bare message.
 */
public data class DeleteErrorEvent(
    public val id: Long,
    public val message: String,
)

public data class LibraryUiState(
    val media: List<MediaWithDetails> = emptyList(),
    /**
     * Episode counts per show id (ROADMAP Task 13 Phase C), for the shows that have episodes —
     * a show with none is absent rather than present with zeros, since
     * [com.hub.media.core.database.dao.EpisodeDao.observeProgress] groups by `mediaId`.
     *
     * Carried here rather than folded into [MediaWithDetails.TVShow] because it is derived, not
     * stored: putting a count on the row would invite exactly the cached-progress field
     * [com.hub.media.core.database.entities.TVDetailsEntity]'s KDoc forbids. Feeds both
     * [LibraryStatusFilter.ofShow] and the library card's "4 / 10 episodes".
     */
    val tvProgress: Map<String, TVProgressRow> = emptyMap(),
    val statusFilter: LibraryStatusFilter? = null,
    val searchQuery: String = "",
    val isEmpty: Boolean = media.isEmpty(),
    val selectedIds: Set<String> = emptySet(),
    val deleteError: DeleteErrorEvent? = null,
) {
    /**
     * True when a bulk selection is active.
     */
    public val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()

    /**
     * The media items [selectedIds] refers to, in library order.
     */
    public val selectedMedia: List<MediaWithDetails>
        get() = media.filter { it.item.id in selectedIds }

    /**
     * [media] narrowed by [statusFilter] and [searchQuery] **together (AND/intersection)**.
     */
    public val filteredMedia: List<MediaWithDetails>
        get() {
            val statusFiltered =
                if (statusFilter == null) {
                    media
                } else {
                    media.filter { statusFilter.matches(it, tvProgress[it.item.id]) }
                }
            val query = searchQuery.trim()
            if (query.isEmpty()) return statusFiltered
            return statusFiltered.filter { mediaItem ->
                val titleMatch = mediaItem.item.title.contains(query, ignoreCase = true)
                val creatorMatch =
                    when (mediaItem) {
                        is MediaWithDetails.Book ->
                            mediaItem.details?.authors?.contains(query, ignoreCase = true) ==
                                true
                        // Movies and shows have no creator field to search yet -- director/cast
                        // arrive with TMDB in Phase D. Title search still covers them.
                        is MediaWithDetails.Movie,
                        is MediaWithDetails.TVShow,
                        -> false
                    }
                titleMatch || creatorMatch
            }
        }
}

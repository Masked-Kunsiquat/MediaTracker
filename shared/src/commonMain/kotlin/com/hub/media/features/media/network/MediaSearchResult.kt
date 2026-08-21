package com.hub.media.features.media.network

import com.hub.media.core.database.entities.IdentifierProvider
import com.hub.media.core.database.entities.MediaType

/**
 * One provider-agnostic search hit from [MediaSearchProvider.search]
 * (ROADMAP Task 9 Phase B1).
 *
 * Consolidated from `BookSearchResult` per Issue #67.
 *
 * @property title Media title — the one field a hit is dropped for lacking.
 * @property type The type of media item (BOOK, MOVIE, TV_SHOW).
 * @property authors Author/Creator display names as the index holds them.
 * @property firstPublishYear First publication year of the *work*.
 * @property coverThumbnailUrl A remote cover/poster URL sized for a list row.
 * @property editionCount How many editions/versions the work has, when known.
 * @property medianPageCount Median page count or runtime, when known.
 * @property provider Which catalog this hit came from.
 * @property workKey The provider-native work/series identifier.
 * @property coverEditionKey The provider's chosen representative edition/item key.
 */
public data class MediaSearchResult(
    val title: String,
    val type: MediaType,
    val authors: List<String> = emptyList(),
    val firstPublishYear: Int? = null,
    val coverThumbnailUrl: String? = null,
    val editionCount: Int? = null,
    val medianPageCount: Int? = null,
    val provider: IdentifierProvider,
    val workKey: String? = null,
    val coverEditionKey: String? = null,
)

package com.hub.media.features.stats.data

/**
 * The key top authors groups by (#219): [name] lowercased, keeping only letters and digits. Authors
 * are stored as fetched or typed, so one person can arrive as "J.R.R. Tolkien" from one source and
 * "J. R. R. Tolkien" from another; both become `jrrtolkien`. Letters include non-Latin ones, so
 * "Brontë" keeps its ë.
 *
 * Deliberately loose and no looser (owner's decision): "Tolkien, J.R.R." is a different key, because
 * reordering around a comma risks merging two people. A name with no letters or digits at all ("—")
 * gives an empty key, which callers drop rather than count as an author.
 */
internal fun authorKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

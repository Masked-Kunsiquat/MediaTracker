package com.hub.media.core.util

import kotlin.uuid.Uuid

/**
 * Generates KMP-safe random UUID strings for use as primary keys.
 *
 * Per AGENTS.md §3.1, primary keys MUST be generated UUID strings — titles or other
 * user-supplied/mutable values must never be used as unique identifiers.
 */
public fun newId(): String = Uuid.random().toString()

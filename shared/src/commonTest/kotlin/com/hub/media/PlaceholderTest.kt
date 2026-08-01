package com.hub.media

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {

    @Test
    fun moduleNameIsShared() {
        assertEquals("shared", Placeholder.MODULE_NAME)
    }
}

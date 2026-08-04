package com.sec.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemLevelTest {
    @Test
    fun `resolves every documented code`() {
        assertEquals(SystemLevel.L2, SystemLevel.fromCode("L2"))
        assertEquals("L2 – Segment", SystemLevel.L2.label)
    }

    @Test
    fun `rejects a code outside the closed enum`() {
        assertNull(SystemLevel.fromCode("L9"))
    }
}

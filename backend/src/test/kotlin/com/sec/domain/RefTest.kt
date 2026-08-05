package com.sec.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RefTest {
    @Test
    fun `round-trips an id through encode and decode`() {
        val id = "https://doors.example/DOORS!/12345/rm/objects/000969a2"
        assertEquals(id, Ref.decodeOrNull(Ref.encode(id)))
    }

    @Test
    fun `never emits padding or plus-slash characters`() {
        val ref = Ref.encode("id-with-enough-length-to-need-padding")
        assertFalse(ref.contains('='))
        assertFalse(ref.contains('+'))
        assertFalse(ref.contains('/'))
    }

    // A hand-edited address bar is an expected failure, not an exception: the caller turns null
    // into a 400 rather than letting the JDK decoder's IllegalArgumentException become a 500.
    @Test
    fun `decoding rejects input that is not base64url`() {
        assertNull(Ref.decodeOrNull("!!!not-base64!!!"))
        assertNull(Ref.decodeOrNull("a b c"))
    }

    @Test
    fun `decoding rejects input that decodes to nothing`() {
        assertNull(Ref.decodeOrNull(""))
        assertNull(Ref.decodeOrNull(Ref.encode("   ")))
    }
}

package com.sec.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RefTest {
    @Test
    fun `round-trips an id through encode and decode`() {
        val id = "https://doors.example/DOORS!/12345/rm/objects/000969a2"
        assertEquals(id, Ref.decode(Ref.encode(id)))
    }

    @Test
    fun `never emits padding or plus-slash characters`() {
        val ref = Ref.encode("id-with-enough-length-to-need-padding")
        assertFalse(ref.contains('='))
        assertFalse(ref.contains('+'))
        assertFalse(ref.contains('/'))
    }
}

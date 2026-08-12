package com.sec.source.windchill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Tier-1 ordering contract for Windchill (R3).
 *
 * Every assertion here is about **a plain string sort**, deliberately: that is the contract the
 * frontend depends on, and testing the derivation any other way would test the algorithm instead of
 * the promise. `sorted()` is the whole test method.
 */
class WindchillSortKeyTest {

    private val fixed = "XXX-ADSF-RP-000014092"

    /** Numbers group, and inside a group the newest revision comes first. */
    @Test
    fun `sorting the keys groups by number and puts the newest version first`() {
        val rows = listOf(
            "XXX-ADSF-RP-000823676" to "01 [2]",
            fixed to "01 [2]",
            fixed to "03 [1]",
            fixed to "02 [2]",
        )

        val order = rows
            .sortedBy { (number, version) -> WindchillSortKey.derive(number, version) }
            .map { (number, version) -> "$number $version" }

        assertEquals(
            listOf(
                "$fixed 03 [1]",
                "$fixed 02 [2]",
                "$fixed 01 [2]",
                "XXX-ADSF-RP-000823676 01 [2]",
            ),
            order,
        )
    }

    /** The iteration orders too, and it orders after the revision — `01 [2]` is newer than `01 [1]`. */
    @Test
    fun `the iteration breaks a tie between two identical revisions, newest first`() {
        val order = listOf("01 [1]", "01 [3]", "01 [2]")
            .sortedBy { WindchillSortKey.derive(fixed, it) }

        assertEquals(listOf("01 [3]", "01 [2]", "01 [1]"), order)
    }

    /** Two-digit revisions must not sort as text, which is the whole reason a key exists. */
    @Test
    fun `revision 10 sorts above revision 9, which a string comparison would not`() {
        val order = listOf("09 [1]", "10 [1]").sortedBy { WindchillSortKey.derive(fixed, it) }

        assertEquals(listOf("10 [1]", "09 [1]"), order)
        // The failure this pins: the raw strings compare the other way round.
        assertTrue("09 [1]" < "10 [1]")
    }

    /**
     * A number that is a prefix of another must not have its versions interleaved with the other's.
     *
     * This is what the separator is for, and it is invisible until two numbers share a prefix.
     */
    @Test
    fun `a number that prefixes another keeps its versions together`() {
        val order = listOf(
            "ABC" to "01 [1]",
            "ABC-1" to "01 [1]",
            "ABC" to "02 [1]",
        )
            .sortedBy { (number, version) -> WindchillSortKey.derive(number, version) }
            .map { (number, version) -> "$number $version" }

        assertEquals(listOf("ABC 02 [1]", "ABC 01 [1]", "ABC-1 01 [1]"), order)
    }

    /** A version with no digits sorts last within its group rather than anywhere. */
    @Test
    fun `a version carrying no number sorts after every version that has one`() {
        val order = listOf("draft", "01 [1]").sortedBy { WindchillSortKey.derive(fixed, it) }

        assertEquals(listOf("01 [1]", "draft"), order)
    }

    /** And it is reported, which is what turns "arbitrary order" into "a warning somebody read". */
    @Test
    fun `a version is readable only when it carries a number`() {
        assertTrue(WindchillSortKey.isReadable("01 [2]"))
        assertTrue(WindchillSortKey.isReadable("A.2"))
        assertFalse(WindchillSortKey.isReadable("draft"))
        assertFalse(WindchillSortKey.isReadable(""))
    }

    /** Same input, same key: the sweep compares against what a previous run wrote. */
    @Test
    fun `the derivation is stable`() {
        assertEquals(
            WindchillSortKey.derive(fixed, "01 [2]"),
            WindchillSortKey.derive(fixed, "01 [2]"),
        )
    }
}

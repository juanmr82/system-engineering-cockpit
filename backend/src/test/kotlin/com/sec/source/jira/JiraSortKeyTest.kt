package com.sec.source.jira

import com.sec.source.jira.mapping.sortKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `__sortKey` for a JIRA issue — the R3 contract, tested as the contract rather than as a format.
 *
 * The promise is not "pads to nine digits". It is **a plain string sort reproduces JIRA's own
 * order**, and every test here asserts an ordering rather than a string, because the padding width
 * is an implementation detail and the ordering is what the review table depends on.
 */
class JiraSortKeyTest {

    /**
     * The failure the whole derivation exists for.
     *
     * `SCRUM-10` sorts before `SCRUM-2` as text and after it in every JIRA screen. This is the
     * `objectNumber` problem arriving through a different source.
     */
    @Test
    fun `issue numbers sort numerically, not as text`() {
        val keys = listOf("SCRUM-10", "SCRUM-2", "SCRUM-1", "SCRUM-100")

        assertEquals(
            listOf("SCRUM-1", "SCRUM-2", "SCRUM-10", "SCRUM-100"),
            keys.sortedBy(::sortKey),
        )
        assertTrue(
            keys.sorted() != listOf("SCRUM-1", "SCRUM-2", "SCRUM-10", "SCRUM-100"),
            "the raw keys already sort correctly, so this derivation would be pointless",
        )
    }

    /** JIRA's `ORDER BY key` groups by project first, and so does this. */
    @Test
    fun `issues group by project before number`() {
        assertEquals(
            listOf("ALPHA-2", "ALPHA-30", "BETA-1"),
            listOf("BETA-1", "ALPHA-30", "ALPHA-2").sortedBy(::sortKey),
        )
    }

    /**
     * The width has to be beyond what JIRA can produce, not merely generous.
     *
     * Padding fails *hard* rather than gracefully on overflow: with a six-digit pad, `PROJ-1000000`
     * sorts before `PROJ-999999`, because `'1' < '9'`. Nine digits is a billion issues in one
     * project — which is the point of asserting the ordering at the boundary rather than the width.
     */
    @Test
    fun `a project with a million issues still sorts correctly`() {
        assertEquals(
            listOf("PROJ-999999", "PROJ-1000000", "PROJ-10000000"),
            listOf("PROJ-10000000", "PROJ-1000000", "PROJ-999999").sortedBy(::sortKey),
        )
    }

    /** A project key can contain a dash, so the *last* one is what separates the number. */
    @Test
    fun `a project key containing a dash keeps its number`() {
        assertEquals("MY-PROJ-000000007", sortKey("MY-PROJ-7"))
    }

    /**
     * A key this code has never seen keeps its own text.
     *
     * Returning something invented for an unrecognised shape would sort it into the middle of the
     * list, where nobody would look for it. Sorting by its own text is the best available answer
     * and it is stable.
     */
    @Test
    fun `a key that is not project-number is left alone`() {
        assertEquals("SCRUM", sortKey("SCRUM"))
        assertEquals("SCRUM-", sortKey("SCRUM-"))
        assertEquals("SCRUM-7a", sortKey("SCRUM-7a"))
        assertEquals("", sortKey(""))
    }
}

package com.sec.source.jira

import com.sec.api.dto.JiraColumnDto
import com.sec.source.jira.JiraFieldsProjection.Companion.isSortable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which columns may be ordered by, and what happens when a client asks for one that may not.
 *
 * Two rules that have to agree, tested together because a disagreement between them is invisible:
 * the server decides `sortable` from the declared type, and it then refuses any `sort` parameter
 * that is not one of the columns it just said were sortable.
 */
class JiraSortableColumnTest {

    @Test
    fun `a scalar sorts`() {
        assertTrue(isSortable("string"))
        assertTrue(isSortable("number"))
        assertTrue(isSortable("datetime"))
        // Complex, but §7.4 derives one display string for it, and that is what sorts.
        assertTrue(isSortable("option"))
        assertTrue(isSortable("user"))
        assertTrue(isSortable("priority"))
    }

    /** An array's projection is a list of strings; ordering by it orders by element order. */
    @Test
    fun `an array does not sort, however it is spelled`() {
        assertFalse(isSortable("array"))
        assertFalse(isSortable("array<string>"))
    }

    /** These project to null by design — sorting would order every row equally and say it sorted. */
    @Test
    fun `a type with no single display value does not sort`() {
        assertFalse(isSortable("timetracking"))
        assertFalse(isSortable("attachment"))
        assertFalse(isSortable("any"))
    }

    /**
     * A type nobody has seen is assumed scalar.
     *
     * The wrong guess costs a strange order; refusing would cost a column that cannot be sorted for
     * no reason a user can see. A field with *no* type is different — it is not offerable at all.
     */
    @Test
    fun `an unknown type sorts and an absent one does not`() {
        assertTrue(isSortable("sd-approvals"))
        assertFalse(isSortable(null))
    }

    @Test
    fun `the default sort is JIRA's own order`() {
        assertEquals("key", JiraIssuesProjection.SortField.of(null)?.id)
        assertEquals("key", JiraIssuesProjection.SortField.of("")?.id)
        assertEquals("key", JiraIssuesProjection.SortField.of("key")?.id)
    }

    @Test
    fun `a configured sortable column may be sorted by`() {
        val columns = listOf(JiraColumnDto("status", "Status", "status", sortable = true))

        assertEquals("status", JiraIssuesProjection.SortField.of("status", columns)?.id)
    }

    /**
     * Everything else is null here and a 400 at the route.
     *
     * Null rather than a silent fall back to the default, and this is the assertion that pins it:
     * a table that quietly ignored the header a user clicked looks broken in a way no message
     * explains. The unconfigured case is the one that matters most — that string reaches Cypher as
     * a property key, so "not a column of this request" is the only thing that may pass.
     */
    @Test
    fun `an unsortable, stale or unconfigured column is refused`() {
        val columns = listOf(
            JiraColumnDto("labels", "Labels", "array", sortable = false),
            JiraColumnDto("customfield_9", "Gone", null, sortable = false, stale = true),
        )

        assertNull(JiraIssuesProjection.SortField.of("labels", columns))
        assertNull(JiraIssuesProjection.SortField.of("customfield_9", columns))
        assertNull(JiraIssuesProjection.SortField.of("summary", columns))
        assertNull(JiraIssuesProjection.SortField.of("__sortKey", columns))
    }
}

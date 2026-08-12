package com.sec.source.jira.mapping

import com.sec.source.jira.jiraJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The display derivation of spec §7.4 — the scalar a table column can sort on.
 *
 * Every value here is derived, so none of it may touch an imported node (R2); it lands on a
 * `:__JiraProjection` companion that can be thrown away and rebuilt. These tests are what make that
 * rebuild safe, because they pin what the rules produce independently of any stored copy.
 */
class DisplayProjectorTest {

    @Test
    fun `an option projects to its value`() {
        assertEquals("WSS", project("""{"self":"https://jira/x","value":"WSS","id":"38303"}"""))
    }

    /**
     * **Checked before the plain-option rule, and the ordering is the whole test.**
     *
     * Spec §7.4 lists `{value, …}` above `{value, child:{value}}` and says first match wins, which
     * read literally would make every option-with-child project to its parent alone — and the
     * child row of that table unreachable. A child, when present, is part of the value.
     */
    @Test
    fun `an option with a child projects to parent and child`() {
        assertEquals(
            "Hardware - Connector",
            project("""{"value":"Hardware","child":{"value":"Connector","id":"1"},"id":"2"}"""),
        )
    }

    @Test
    fun `status, priority, issuetype and project all project to their name`() {
        assertEquals("In Progress", project("""{"self":"https://jira/s","name":"In Progress"}"""))
        assertEquals("Major", project("""{"self":"https://jira/p","name":"Major","iconUrl":"x"}"""))
    }

    /** `displayName`, never `name`: on a JIRA user object, `name` is the login (R5). */
    @Test
    fun `a user projects to the display name and not the login`() {
        assertEquals(
            "Ada Lovelace",
            project("""{"name":"alovelace","displayName":"Ada Lovelace","active":true}"""),
        )
    }

    /** `0/0` is a real and extremely common value — 100 of them in the export — not an absence. */
    @Test
    fun `progress projects to a fraction, including when it is zero`() {
        assertEquals("0/0", project("""{"progress":0,"total":0}"""))
        assertEquals("3/8", project("""{"progress":3,"total":8}"""))
    }

    @Test
    fun `votes and watches project to their count`() {
        assertEquals("0", project("""{"self":"https://jira/v","votes":0,"hasVoted":false}"""))
        assertEquals("2", project("""{"self":"https://jira/w","watchCount":2,"isWatching":true}"""))
    }

    /**
     * A checklist is **counted**, not listed: 1 673 of them in the export, up to nine properties an
     * item. What a reviewer wants in a column is "3/7"; the item names stay in the raw JSON on the
     * issue node, so nothing is lost.
     */
    @Test
    fun `a checklist projects to checked over total`() {
        assertEquals(
            "2/3",
            project(
                """[{"name":"a","checked":true},{"name":"b","checked":false},
                   {"name":"c","checked":true}]""",
            ),
        )
    }

    /**
     * The checklist rule has to win over the element-wise one, because a checklist item also has a
     * `name` and would otherwise render as a list of every item's text.
     */
    @Test
    fun `a checklist is counted rather than listed by name`() {
        assertEquals("0/2", project("""[{"name":"Daten gesichert","checked":false},{"name":"b","checked":false}]"""))
    }

    @Test
    fun `an array of options projects to a list, in order`() {
        assertEquals(
            listOf("A", "B"),
            project("""[{"value":"A","id":"1"},{"value":"B","id":"2"}]"""),
        )
    }

    @Test
    fun `an array of components projects to their names`() {
        assertEquals(
            listOf("Avionics", "Power"),
            project("""[{"self":"https://jira/c/1","name":"Avionics"},{"self":"https://jira/c/2","name":"Power"}]"""),
        )
    }

    // -- refusing to guess -------------------------------------------------------------------------

    /**
     * The most important rule in this file. A cell reading
     * `{"self":"https://…","id":"38303"}` is worse than an empty one: it is wide, unsortable, and it
     * tells the reader the application understood something it did not.
     */
    @Test
    fun `an unrecognised object projects to null, never to raw JSON`() {
        assertNull(project("""{"self":"https://jira/x","id":"38303","disabled":false}"""))
        assertNull(project("""{"nothing":"familiar"}"""))
    }

    /** Same rule, applied to a list: holes would read as empty values rather than as ignorance. */
    @Test
    fun `an array with one unrecognisable element projects to null, not to a list with a hole`() {
        assertNull(project("""[{"value":"A"},{"id":"only-an-id"}]"""))
    }

    @Test
    fun `an empty array projects to null`() {
        assertNull(project("[]"))
    }

    /**
     * A scalar never reaches the projection at all — the mapper only projects complex values,
     * because a scalar on the issue node is already sortable. Asserted here so that stays true if
     * somebody wires the projector to everything.
     */
    @Test
    fun `a plain scalar has no projection`() {
        assertNull(project("\"already a string\""))
        assertNull(project("42"))
    }

    private fun project(json: String): Any? =
        DisplayProjector.project(jiraJson.parseToJsonElement(json))
}

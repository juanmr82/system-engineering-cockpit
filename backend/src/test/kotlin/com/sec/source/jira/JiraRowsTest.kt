package com.sec.source.jira

import com.sec.domain.Prop
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire-object-to-graph-row mapping.
 *
 * The fixtures here are the shapes the design doc quotes from the JIRA API (§8), not invented
 * ones — the link array in particular, because reading the wrong half of it is what would draw
 * every dependency twice in opposite directions.
 */
class JiraRowsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun issue(raw: String): JiraIssueDto = json.decodeFromString(raw)

    @Suppress("UNCHECKED_CAST")
    private fun props(row: Map<String, Any?>): Map<String, Any?> = row["props"] as Map<String, Any?>

    // -- the issue row --------------------------------------------------------------------

    @Test
    fun `an issue row carries the Tier-1 four and its own key`() {
        val row = JiraRows.issueRow(
            issue("""{ "id": "10001", "key": "PROJ-42", "self": "https://j/rest/api/2/issue/10001",
                       "fields": { "summary": "Fix it", "project": { "key": "PROJ" } } }"""),
            storeRawFields = false,
        )

        assertEquals("jira:issue:PROJ-42", row["id"])
        val props = props(row)
        assertEquals("Fix it", props[Prop.NAME])
        assertEquals("current", props[Prop.VERSION])
        assertEquals("PROJ-0000000042", props[Prop.SORT_KEY])
        assertEquals("PROJ", props[JiraProp.PROJECT_KEY])
        assertEquals("PROJ-42", props[JiraFieldId.KEY])
        assertEquals("10001", props[JiraFieldId.ID])
    }

    @Test
    fun `a JIRA field cannot overwrite a Tier-1 property`() {
        // Impossible today — a field id never carries the prefix — and it costs one map ordering
        // to keep it impossible.
        val row = JiraRows.issueRow(
            issue("""{ "key": "P-1", "fields": { "__name": "hijacked", "summary": "real" } }"""),
            storeRawFields = false,
        )
        assertEquals("real", props(row)[Prop.NAME])
    }

    @Test
    fun `raw fields are stored only when asked for`() {
        val raw = """{ "key": "P-1", "fields": { "summary": "x" } }"""
        assertTrue(JiraProp.RAW_FIELDS in props(JiraRows.issueRow(issue(raw), storeRawFields = true)))
        assertTrue(JiraProp.RAW_FIELDS !in props(JiraRows.issueRow(issue(raw), storeRawFields = false)))
    }

    @Test
    fun `a moved issue is filed under the project it is in, not the one its key names`() {
        // SEG-42 genuinely living in AVI is what happens after a project move, and the key never
        // changes. Trusting the prefix would file it under a project that has not held it for years.
        val row = JiraRows.issueRow(
            issue("""{ "key": "SEG-42", "fields": { "project": { "key": "AVI" } } }"""),
            storeRawFields = false,
        )
        assertEquals("AVI", props(row)[JiraProp.PROJECT_KEY])
    }

    @Test
    fun `with no project field the key prefix is the fallback`() {
        val row = JiraRows.issueRow(issue("""{ "key": "SEG-42", "fields": {} }"""), false)
        assertEquals("SEG", props(row)[JiraProp.PROJECT_KEY])
    }

    // -- links ----------------------------------------------------------------------------

    @Test
    fun `only the outward half of a link is emitted`() {
        val rows = JiraRows.linkRows(
            issue(
                """
                { "key": "PR-1", "fields": { "issuelinks": [
                    { "id": "10001",
                      "type": { "id": "10000", "name": "Dependent",
                                "inward": "depends on", "outward": "is depended by" },
                      "outwardIssue": { "id": "2", "key": "PR-2" } },
                    { "id": "10002",
                      "type": { "id": "10000", "name": "Dependent",
                                "inward": "depends on", "outward": "is depended by" },
                      "inwardIssue": { "id": "3", "key": "PR-3" } } ] } }
                """,
            ),
        )

        // JIRA states each link on both of its issues. Taking both halves would draw every link
        // twice, in opposite directions, and make everything look bidirectional.
        assertEquals(1, rows.size)
        assertEquals("jira:issue:PR-1", rows[0]["fromId"])
        assertEquals("jira:issue:PR-2", rows[0]["toId"])
    }

    @Test
    fun `both phrases of the link type travel with the edge`() {
        val rows = JiraRows.linkRows(
            issue(
                """
                { "key": "PR-1", "fields": { "issuelinks": [
                    { "type": { "id": "10000", "name": "Blocks",
                                "inward": "is blocked by", "outward": "blocks" },
                      "outwardIssue": { "key": "PR-2" } } ] } }
                """,
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val linkProps = rows[0]["props"] as Map<String, Any?>
        // So the UI can say "PR-1 blocks PR-2" and "PR-2 is blocked by PR-1" from either end,
        // without a second lookup and without a relationship type per link type.
        assertEquals("blocks", linkProps[JiraLinkProp.OUTWARD])
        assertEquals("is blocked by", linkProps[JiraLinkProp.INWARD])
        assertEquals("10000", linkProps[JiraLinkProp.TYPE_ID])
        assertEquals("Blocks", linkProps[JiraLinkProp.TYPE_NAME])
    }

    @Test
    fun `a link to an unimported issue carries a stub payload rather than being dropped`() {
        val rows = JiraRows.linkRows(
            issue(
                """{ "key": "PR-1", "fields": { "issuelinks": [
                     { "type": { "id": "1" }, "outwardIssue": { "key": "OTHER-9" } } ] } }""",
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val stub = rows[0]["stub"] as Map<String, Any?>
        // That a dependency leaves the import scope is information, not noise (design doc §8a).
        assertEquals("OTHER-9", stub[JiraFieldId.KEY])
        assertEquals("OTHER", stub[JiraProp.PROJECT_KEY])
        assertEquals("OTHER-0000000009", stub[Prop.SORT_KEY])
    }

    @Test
    fun `no issuelinks and a malformed one both yield nothing`() {
        assertTrue(JiraRows.linkRows(issue("""{ "key": "P-1", "fields": {} }""")).isEmpty())
        assertTrue(
            JiraRows.linkRows(
                issue("""{ "key": "P-1", "fields": { "issuelinks": [ { "type": { "id": "1" } } ] } }"""),
            ).isEmpty(),
            "a link with neither inwardIssue nor outwardIssue names no target",
        )
    }

    // -- hierarchy and type ----------------------------------------------------------------

    @Test
    fun `parent key is read from the parent field`() {
        assertEquals(
            "PROJ-1",
            JiraRows.parentKeyOf(issue("""{ "key": "PROJ-2", "fields": { "parent": { "key": "PROJ-1" } } }""")),
        )
        assertNull(JiraRows.parentKeyOf(issue("""{ "key": "PROJ-1", "fields": {} }""")))
    }

    @Test
    fun `an issue type is read by id, and can be rebuilt from the issue itself`() {
        val withType = issue(
            """{ "key": "P-1", "fields": { "issuetype": { "id": "10002", "name": "Bug", "subtask": false } } }""",
        )

        assertEquals("10002", JiraRows.issueTypeIdOf(withType))

        // Some instances scope a type to one project and leave it out of GET /issuetype; the copy
        // embedded in the issue is what stops the hasType edge disappearing silently.
        val embedded = JiraRows.embeddedIssueType(withType)
        assertEquals("10002", embedded?.id)
        assertEquals("Bug", embedded?.name)

        assertNull(JiraRows.issueTypeIdOf(issue("""{ "key": "P-1", "fields": {} }""")))
        assertNull(JiraRows.embeddedIssueType(issue("""{ "key": "P-1", "fields": {} }""")))
    }

    // -- JQL -------------------------------------------------------------------------------

    @Test
    fun `a project key is validated rather than escaped`() {
        assertTrue(JiraJql.isValidProjectKey("PROJ"))
        assertTrue(JiraJql.isValidProjectKey("MY_PROJ2"))
        assertTrue(!JiraJql.isValidProjectKey(""))
        assertTrue(!JiraJql.isValidProjectKey("2PROJ"))
        // The value is concatenated into a query string, so anything that could end the literal
        // has to be refused rather than quoted.
        assertTrue(!JiraJql.isValidProjectKey("""PROJ" OR key ~ "x"""))
        assertTrue(!JiraJql.isValidProjectKey("PROJ-X"))
    }

    @Test
    fun `the search query is ordered, with and without an extra clause`() {
        assertEquals("""project = "PROJ" ORDER BY key ASC""", JiraJql.forProject("PROJ", ""))
        assertEquals(
            """project = "PROJ" AND (status != Done) ORDER BY key ASC""",
            JiraJql.forProject("PROJ", "  status != Done  "),
        )
    }

    @Test
    fun `the order clause is what makes offset paging safe`() {
        // Data Center pages by startAt. An unordered query may return rows in a different order
        // between pages, which skips some issues and imports others twice.
        assertTrue(JiraJql.forProject("PROJ", "").endsWith("ORDER BY key ASC"))
        assertTrue(JiraJql.forProject("PROJ", "labels = x").endsWith("ORDER BY key ASC"))
    }
}

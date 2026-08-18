package com.sec.source.jira

import com.sec.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write rows for phases 1 and 2 (spec §9.1, §9.2), as pure values.
 *
 * The fixture-driven cases at the end are the ones worth having: they shape all 1 171 real field
 * definitions and assert the properties every row must carry, which is the check a container test
 * would make far more slowly and only where Docker is running.
 */
class JiraGraphWriterRowsTest {

    private val host = "https://jira.example.com/jira"

    // -- issue types ----------------------------------------------------------------------------

    @Test
    fun `an issue type is identified by its resource URL, never by its name`() {
        val row = issueTypeRow(
            JiraIssueTypeDefinition(
                id = "10002",
                name = "Task",
                self = "https://jira.example.com/jira/rest/api/2/issuetype/10002",
            ),
        )

        assertEquals("https://jira.example.com/jira/rest/api/2/issuetype/10002", row["id"])
    }

    @Test
    fun `an issue type carries the three SEItem properties every node has`() {
        val props = props(issueTypeRow(JiraIssueTypeDefinition(name = "Bug", self = "s")))

        assertEquals("Bug", props["__name"])
        assertEquals("current", props["__version"])
        // __id travels beside the map, not inside it — see `row`.
        assertFalse(props.containsKey("__id"))
    }

    /**
     * `Epic` uses a static SVG icon and has no avatar. Writing null removes the property, which is
     * what a type that lost its avatar should look like; writing 0 would invent an avatar id.
     */
    @Test
    fun `an absent avatar id is written as null so the property is removed`() {
        val props = props(issueTypeRow(JiraIssueTypeDefinition(name = "Epic", self = "s")))

        assertTrue(props.containsKey("avatarId"))
        assertNull(props["avatarId"])
    }

    @Test
    fun `a present avatar id survives`() {
        val props = props(issueTypeRow(JiraIssueTypeDefinition(name = "Task", self = "s", avatarId = 10318)))

        assertEquals(10318L, props["avatarId"])
    }

    /**
     * All nine real issue types, shaped.
     *
     * The hand-written cases above pin one behaviour each; this pins that the whole export goes
     * through — including `Epic`, whose missing `avatarId` is the only reason that field is
     * nullable, and `Sub-task`, the only one carrying `subtask: true`.
     */
    @Test
    fun `every issue type in the real catalogue shapes into a complete row`() {
        val rows = issueTypes().map(::issueTypeRow)

        assertEquals(9, rows.size)
        assertEquals(9, rows.map { it["id"] }.toSet().size, "two issue types shaped to one __id")
        rows.forEach { row ->
            val props = props(row)
            assertEquals("current", props["__version"])
            assertTrue((props["__name"] as String).isNotBlank())
            assertEquals(row["id"], props["self"], "identity and the stored self URL disagree")
        }
        assertEquals(1, rows.count { props(it)["avatarId"] == null })
        assertEquals(1, rows.count { props(it)["subtask"] == true })
    }

    // -- field definitions ----------------------------------------------------------------------

    /**
     * `/field` returns no `self`, so identity is synthesised — and synthesised to look like the URL
     * JIRA would have given it, so that "identity is the resource URL" holds for every node in the
     * graph with no exception a later reader has to learn.
     */
    @Test
    fun `a field id is synthesised to look like the URL JIRA would have returned`() {
        val row = fieldRow(host, JiraFieldDefinition(id = "customfield_23700", name = "Work Package"))

        assertEquals("https://jira.example.com/jira/rest/api/2/field/customfield_23700", row["id"])
    }

    @Test
    fun `the schema is flattened to four keys with its values untouched`() {
        val props = props(
            fieldRow(
                host,
                JiraFieldDefinition(
                    id = "customfield_1",
                    name = "Sprint",
                    schema = JiraFieldSchema(
                        type = "array",
                        items = "string",
                        custom = "com.example:sprint",
                        customId = 10100,
                    ),
                ),
            ),
        )

        assertEquals("array", props["schemaType"])
        assertEquals("string", props["schemaItems"])
        assertEquals("com.example:sprint", props["schemaCustom"])
        assertEquals(10100L, props["schemaCustomId"])
    }

    /**
     * "No schema" and "a schema whose type is blank" are different facts, and collapsing them
     * would make `issuekey` indistinguishable from a real field with an empty type.
     */
    @Test
    fun `a field with no schema writes nulls rather than empty strings`() {
        val props = props(fieldRow(host, JiraFieldDefinition(id = "issuekey", name = "Key")))

        assertNull(props["schemaType"])
        assertNull(props["schemaItems"])
    }

    /** Derived, so R2 keeps it off the imported node however convenient it would be to store. */
    @Test
    fun `no derived displayable flag is written`() {
        val props = props(fieldRow(host, JiraFieldDefinition(id = "summary", name = "Summary")))

        assertTrue(
            props.keys.none { it.contains("displayable", ignoreCase = true) },
            "a derived flag reached an imported node: ${props.keys}",
        )
    }

    /**
     * The `__` namespace is ours and no source may emit into it (R1, R3). JIRA field ids are
     * `summary`, `duedate`, `customfield_23700` — this is the assertion that the assumption holds
     * against the real catalogue, and it is what makes `SET n += row.props` safe without escaping.
     */
    @Test
    fun `no real field id could ever collide with the application namespace`() {
        val ids = fieldDefinitions().map { it.id }

        assertTrue(ids.none { it.startsWith("__") }, "a JIRA field id entered the __ namespace")
        // Valid Neo4j property keys by construction, which is why no backticks are needed anywhere.
        assertTrue(ids.all { it.matches(Regex("^[A-Za-z][A-Za-z0-9_]*$")) })
    }

    /** All 1 171 real definitions, shaped. Nothing may throw and nothing may lose its identity. */
    @Test
    fun `every field in the real catalogue shapes into a complete row`() {
        val rows = fieldDefinitions().map { fieldRow(host, it) }

        assertEquals(1171, rows.size)
        assertEquals(1171, rows.map { it["id"] }.toSet().size, "two fields shaped to one __id")
        rows.forEach { row ->
            val props = props(row)
            assertTrue((row["id"] as String).startsWith(host))
            assertEquals("current", props["__version"])
            assertTrue(props.containsKey("__name"))
            assertTrue(props.containsKey("id"))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun props(row: Map<String, Any?>): Map<String, Any?> = row["props"] as Map<String, Any?>

    private fun fieldDefinitions(): List<JiraFieldDefinition> =
        jiraJson.decodeFromString(sample(Fixtures.JIRA_FIELDS))

    // `.md` rather than `.json`, which is how the issue-type export was added. Nothing here parses
    // by extension; only the name differs from its siblings.
    private fun issueTypes(): List<JiraIssueTypeDefinition> =
        jiraJson.decodeFromString(sample(Fixtures.JIRA_ISSUE_TYPES))

    private fun sample(name: String): String = Fixtures.text(name)
}

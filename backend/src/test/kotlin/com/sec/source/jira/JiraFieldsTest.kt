package com.sec.source.jira

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The flattener and the Tier-1 derivations, against JSON that looks like JIRA's.
 *
 * Every case here is named after the thing that breaks if the rule goes: this is the file that
 * stands between "a JIRA schema nobody controls" and "properties a Cypher `+=` will accept", and
 * most of its rules are one line each in [JiraFields].
 */
class JiraFieldsTest {

    private fun fields(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    // -- flattening -----------------------------------------------------------------------

    @Test
    fun `a scalar field keeps its name and its type`() {
        val flat = JiraFields.flatten(
            fields("""{ "summary": "Fix the thing", "customfield_10032": 5, "flagged": true }"""),
        )

        assertEquals("Fix the thing", flat["summary"])
        // A long, not a string and not a double: story points have to sort and total as numbers.
        assertEquals(5L, flat["customfield_10032"])
        assertEquals(true, flat["flagged"])
    }

    @Test
    fun `a decimal stays a double`() {
        val flat = JiraFields.flatten(fields("""{ "customfield_10100": 2.5 }"""))
        assertEquals(2.5, flat["customfield_10100"])
    }

    @Test
    fun `an object field becomes one property per sub-key, dotted`() {
        val flat = JiraFields.flatten(
            fields("""{ "status": { "id": "3", "name": "In Progress", "iconUrl": "https://j/i.png" } }"""),
        )

        assertEquals("In Progress", flat["status.name"])
        assertEquals("https://j/i.png", flat["status.iconUrl"])
        assertEquals("3", flat["status.id"])
        // The parent itself is not a property: there is nothing scalar to put in a cell for it.
        assertTrue("status" !in flat)
    }

    @Test
    fun `nesting is followed to the depth limit and then reduced to a name`() {
        val flat = JiraFields.flatten(
            fields(
                """
                { "status": { "name": "Open",
                              "statusCategory": { "id": 2, "key": "new", "name": "To Do",
                                                  "colorName": { "name": "blue-gray" } } } }
                """,
            ),
        )

        assertEquals("To Do", flat["status.statusCategory.name"])
        assertEquals("new", flat["status.statusCategory.key"])
        // Depth 4 would be an embedded document, not a field value. Its `name` is kept and the
        // rest is a raw-fields question.
        assertEquals("blue-gray", flat["status.statusCategory.colorName"])
        assertTrue(flat.keys.none { it.count { c -> c == '.' } > 2 })
    }

    @Test
    fun `an array of scalars becomes a list`() {
        val flat = JiraFields.flatten(fields("""{ "labels": ["thermal", "safety"] }"""))
        assertEquals(listOf("thermal", "safety"), flat["labels"])
    }

    @Test
    fun `an array of objects becomes one list per sub-key`() {
        val flat = JiraFields.flatten(
            fields(
                """
                { "components": [ { "id": "1", "name": "Avionics" },
                                  { "id": "2", "name": "Power" } ] }
                """,
            ),
        )

        assertEquals(listOf("Avionics", "Power"), flat["components.name"])
        assertEquals(listOf("1", "2"), flat["components.id"])
    }

    @Test
    fun `an element missing a sub-key contributes nothing rather than a null hole`() {
        val flat = JiraFields.flatten(
            fields("""{ "fixVersions": [ { "name": "R1" }, { "id": "9" } ] }"""),
        )

        // A Neo4j list cannot carry a null, so a hole would fail the write for the whole batch.
        assertEquals(listOf("R1"), flat["fixVersions.name"])
        assertEquals(listOf("9"), flat["fixVersions.id"])
    }

    @Test
    fun `a mixed-type list is stringified so the write cannot fail`() {
        val flat = JiraFields.flatten(fields("""{ "customfield_10001": [1, "two", 3] }"""))

        // Two projects defining one custom field differently is the case the design doc opens
        // with. Readable beats rejected.
        assertEquals(listOf("1", "two", "3"), flat["customfield_10001"])
    }

    @Test
    fun `a null is emitted rather than skipped, so a cleared field is cleared in the graph`() {
        val flat = JiraFields.flatten(fields("""{ "resolution": null }"""))

        assertTrue("resolution" in flat, "the key must be present for `SET n += props` to remove it")
        assertNull(flat["resolution"])
    }

    @Test
    fun `an empty array and an empty object clear their property too`() {
        val flat = JiraFields.flatten(fields("""{ "labels": [], "status": {} }"""))

        assertTrue("labels" in flat)
        assertNull(flat["labels"])
        assertTrue("status" in flat)
        assertNull(flat["status"])
    }

    @Test
    fun `structural fields are never flattened`() {
        val flat = JiraFields.flatten(
            fields(
                """
                { "summary": "x",
                  "issuelinks": [ { "id": "1" } ],
                  "subtasks":   [ { "key": "P-2" } ],
                  "comment":    { "comments": [ { "body": "long" } ] },
                  "worklog":    { "worklogs": [] },
                  "attachment": [ { "filename": "a.pdf" } ] }
                """,
            ),
        )

        // The first two are relationships; the rest are unbounded collections nothing reads.
        assertEquals(setOf("summary"), flat.keys)
    }

    // -- Tier-1 derivations ----------------------------------------------------------------

    @Test
    fun `sort key pads the issue number so a string sort is the issue order`() {
        assertEquals("PROJ-0000000042", JiraFields.deriveSortKey("PROJ-42"))
        assertEquals("PROJ-0000000100", JiraFields.deriveSortKey("PROJ-100"))

        // The whole point: unpadded, "PROJ-42" sorts after "PROJ-100" and the table lies.
        val sorted = listOf("PROJ-42", "PROJ-100", "PROJ-7")
            .sortedBy(JiraFields::deriveSortKey)
        assertEquals(listOf("PROJ-7", "PROJ-42", "PROJ-100"), sorted)
    }

    @Test
    fun `a key with a hyphen in the project part still splits at the last hyphen`() {
        assertEquals("MY-PROJ-0000000005", JiraFields.deriveSortKey("MY-PROJ-5"))
    }

    @Test
    fun `a malformed key sorts by itself rather than throwing`() {
        // One bad key must not cost the other twenty thousand issues.
        assertEquals("NOTANISSUE", JiraFields.deriveSortKey("NOTANISSUE"))
        assertEquals("PROJ-", JiraFields.deriveSortKey("PROJ-"))
        assertEquals("PROJ-1a", JiraFields.deriveSortKey("PROJ-1a"))
        assertEquals("-5", JiraFields.deriveSortKey("-5"))
    }

    @Test
    fun `name is the summary, and the key when there is no usable summary`() {
        assertEquals("Fix it", JiraFields.deriveName(fields("""{ "summary": "Fix it" }"""), "P-1"))
        assertEquals("P-1", JiraFields.deriveName(fields("""{ "summary": "" }"""), "P-1"))
        assertEquals("P-1", JiraFields.deriveName(fields("""{ "summary": "   " }"""), "P-1"))
        assertEquals("P-1", JiraFields.deriveName(fields("""{ "summary": null }"""), "P-1"))
        assertEquals("P-1", JiraFields.deriveName(fields("""{}"""), "P-1"))
    }

    // -- what the catalogue can promise about a field with no data -------------------------

    @Test
    fun `a scalar schema type states the path the flattener will write`() {
        // The case this exists for: a field unset on every issue is null in the JSON, so
        // `SET n += props` removes it and no node ever carries the key. The declared type is then
        // the only thing that knows the field exists, and for a scalar it also knows its path.
        assertTrue(JiraFields.flattensToOwnPath("string"))
        assertTrue(JiraFields.flattensToOwnPath("number"))
        assertTrue(JiraFields.flattensToOwnPath("date"))
        assertTrue(JiraFields.flattensToOwnPath("datetime"))
    }

    @Test
    fun `an array of scalars is one list property at the field's own path`() {
        assertTrue(JiraFields.flattensToOwnPath("array", "string"))
        assertTrue(JiraFields.flattensToOwnPath("array", "number"))
    }

    @Test
    fun `an object, and an array of objects, promise sub-keys this cannot name`() {
        // `status` flattens to status.name, status.iconUrl, … — and which sub-keys exist comes from
        // the data. Answering true here would mean guessing `name` and handing somebody a column
        // that is blank for ever on a field JIRA calls something else.
        assertFalse(JiraFields.flattensToOwnPath("option"))
        assertFalse(JiraFields.flattensToOwnPath("user"))
        assertFalse(JiraFields.flattensToOwnPath("status"))
        assertFalse(JiraFields.flattensToOwnPath("array", "component"))
        assertFalse(JiraFields.flattensToOwnPath("array", "user"))
    }

    @Test
    fun `an undeclared or open-ended type promises nothing`() {
        assertFalse(JiraFields.flattensToOwnPath(""))
        // JIRA's own "this could be anything". A field the API declines to describe is not one
        // this code should claim to know the shape of.
        assertFalse(JiraFields.flattensToOwnPath("any"))
    }

    @Test
    fun `the promise matches what the flattener actually does`() {
        // The two halves must not drift: the prediction is only worth anything if it is the same
        // rule the writer follows.
        val scalar = JiraFields.flatten(fields("""{ "customfield_1": "x", "customfield_2": [1, 2] }"""))
        assertTrue("customfield_1" in scalar && "customfield_2" in scalar)
        assertTrue(JiraFields.flattensToOwnPath("string"))
        assertTrue(JiraFields.flattensToOwnPath("array", "number"))

        val structured = JiraFields.flatten(fields("""{ "status": { "name": "Open" } }"""))
        assertTrue("status" !in structured && "status.name" in structured)
        assertFalse(JiraFields.flattensToOwnPath("status"))
    }

    @Test
    fun `splitPath separates the field from its sub-key trail`() {
        assertEquals("status" to "name", JiraFields.splitPath("status.name"))
        assertEquals("status" to "statusCategory.name", JiraFields.splitPath("status.statusCategory.name"))
        // A custom field id carries an underscore and never a dot, so the first dot is the boundary.
        assertEquals("customfield_10032" to null, JiraFields.splitPath("customfield_10032"))
    }

    @Test
    fun `projectKeyOf reads the key prefix`() {
        assertEquals("PROJ", JiraFields.projectKeyOf("PROJ-42"))
        assertEquals("MY-PROJ", JiraFields.projectKeyOf("MY-PROJ-42"))
        assertEquals("", JiraFields.projectKeyOf("NODASH"))
    }
}

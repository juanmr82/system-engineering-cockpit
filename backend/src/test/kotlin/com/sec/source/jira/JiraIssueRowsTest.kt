package com.sec.source.jira

import com.sec.source.jira.mapping.IssueMapper
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shaping of phase 3's write rows — the decisions, without the database.
 *
 * The `UNWIND` around these rows is exercised against a real Neo4j in `JiraIssueImportTest`. What
 * lives here is everything that is a *choice*: what travels beside the property map, what gets
 * deduplicated, and what the prune is told to keep. Those are the places a mistake is silent.
 */
class JiraIssueRowsTest {

    /**
     * `presentKeys` travels beside `props` rather than being derived from it in Cypher.
     *
     * `keys(row.props)` would name only what is being written now — and the removal statement has to
     * compare against everything that *should* be on the node, including the envelope's `key`, `id`
     * and `self`, which no field list mentions. Deriving it would sweep the issue's own key off the
     * node on every run.
     */
    @Test
    fun `an issue row carries presentKeys beside its properties`() {
        val row = issueRow(mapper.map(issue()))

        @Suppress("UNCHECKED_CAST")
        val presentKeys = row["presentKeys"] as List<String>

        assertTrue("key" in presentKeys, "the envelope's key would be swept off the node")
        assertTrue("id" in presentKeys)
        assertTrue("self" in presentKeys)
        assertTrue("summary" in presentKeys)
    }

    /**
     * Identity is the resource URL, never the issue key.
     *
     * A key changes when an issue moves between projects; the numeric id inside `self` never does
     * (spec §6.2). The property map carries the same value, and the two agreeing is what makes
     * `MERGE (i {__id: row.id}) SET i += row.props` safe — a disagreement would have `+=` rewrite
     * the very property the `MERGE` matched on.
     */
    @Test
    fun `an issue row keys on the resource URL, not the issue key`() {
        val row = issueRow(mapper.map(issue()))

        assertEquals("$HOST/rest/api/2/issue/1", row["id"])

        @Suppress("UNCHECKED_CAST")
        val props = row["props"] as Map<String, Any?>
        assertEquals(row["id"], props["__id"], "the row key and the stored __id disagree")
        assertEquals("SCRUM-1", props["key"])
    }

    /**
     * Written for every issue, including one with nothing to project.
     *
     * An absent companion and an empty one would read identically to anything downstream, and the
     * acceptance criterion is one per issue (spec §16.2).
     */
    @Test
    fun `a projection row exists even when there is nothing to project`() {
        val row = projectionRow(mapper.map(issue(fields = """"summary":"plain string only"""")))

        assertEquals("$HOST/rest/api/2/issue/1#projection", row["id"])
        assertEquals("$HOST/rest/api/2/issue/1", row["issueId"])

        @Suppress("UNCHECKED_CAST")
        assertTrue((row["props"] as Map<String, Any?>).isEmpty())
    }

    /**
     * The dedup that keeps one project node from being merged once per issue.
     *
     * Across the page, not within an issue: the same project appears on every issue of it, and
     * `UNWIND`ing all of them means N `MERGE`s on one node inside a transaction every read waits
     * behind (spec §12 phase 3, §15).
     */
    @Test
    fun `entity rows are deduplicated across the whole page`() {
        val page = (1..3).map { n -> mapper.map(issue(n = n)) }

        val rows = entityRows(page)
        val projects = rows.filter { it["label"] == JiraLabel.PROJECT }

        assertEquals(1, projects.size, "the same project produced ${projects.size} rows")
        assertEquals(3, page.size, "the fixture stopped producing three issues")
    }

    @Test
    fun `every entity row carries the label its node gets`() {
        val rows = entityRows(listOf(mapper.map(issue())))

        assertEquals(
            setOf(JiraLabel.PROJECT, JiraLabel.ISSUE_TYPE, JiraLabel.USER),
            rows.map { it["label"] }.toSet(),
        )
    }

    /**
     * The prune's keep-list is `(type, id)` pairs, not flattened strings.
     *
     * The ids are URLs; a delimiter would be one more thing that can appear in the data it separates.
     */
    @Test
    fun `the prune row names the type and the id of everything kept`() {
        val row = pruneRow(mapper.map(issue()))

        @Suppress("UNCHECKED_CAST")
        val keep = row["keep"] as List<Map<String, String>>

        assertTrue(keep.all { it.keys == setOf("type", "id") }, "keep entries were $keep")
        assertTrue(keep.any { it["type"] == JiraRel.ASSIGNED_TO })
        assertTrue(keep.any { it["type"] == JiraRel.IN_PROJECT })
    }

    /**
     * An issue with no assignee keeps an empty keep-list rather than none at all.
     *
     * This is the unassignment case, and it is the one the prune exists for: an empty list must
     * still delete the old edge, so the row cannot be skipped when there is nothing to keep.
     */
    @Test
    fun `an issue with nothing promoted still produces a prune row`() {
        val row = pruneRow(mapper.map(issue(fields = """"summary":"no entities here"""")))

        @Suppress("UNCHECKED_CAST")
        assertTrue((row["keep"] as List<*>).isEmpty())
        assertEquals("$HOST/rest/api/2/issue/1", row["issueId"])
    }

    // -- phase 4: stubs, links and sub-tasks -------------------------------------------------------

    /**
     * The rule the whole of phase 4 turns on: stub what this run did not import, and nothing else.
     *
     * Getting it backwards is not a visible failure — it is a `MERGE` on a real issue's `__id`,
     * which finds the real issue and, thanks to `ON CREATE`, does nothing at all. The graph looks
     * right and the link silently never appears.
     */
    @Test
    fun `only a link target this run did not import gets a stub`() {
        val imported = mapper.map(issue(1))
        val links = mapper.map(issue(2, fields = LINKS_TO_3_AND_1)).links

        val rows = placeholderRows(links, parents = emptyMap(), seenIds = setOf(imported.id))

        assertEquals(listOf("$HOST/rest/api/2/issue/3"), rows.map { it["id"] })
    }

    /**
     * Fifty issues linking to one unimported epic is one stub, not fifty.
     *
     * The same reason the entity rows are deduplicated: fifty rows merging one node inside one
     * transaction is fifty lock acquisitions for a node that will be identical either way.
     */
    @Test
    fun `stubs are deduplicated across every link in the run`() {
        val links = mapper.map(issue(1, fields = LINKS_TO_3_AND_1)).links +
            mapper.map(issue(2, fields = LINKS_TO_3_AND_1)).links

        val rows = placeholderRows(links, parents = emptyMap(), seenIds = emptySet())

        assertEquals(setOf("$HOST/rest/api/2/issue/3", "$HOST/rest/api/2/issue/1"), rows.map { it["id"] }.toSet())
    }

    /** A parent nobody imported is a stub too — same rule, and the read path must not tell them apart. */
    @Test
    fun `an unimported parent gets a stub as well`() {
        val child = mapper.map(issue(1, fields = HAS_PARENT))

        val rows = placeholderRows(emptyList(), parents = mapOf(child.id to child.parent!!), seenIds = setOf(child.id))

        assertEquals(listOf("$HOST/rest/api/2/issue/9"), rows.map { it["id"] })
    }

    /**
     * The stub's shape is a subset of the real issue's, never a parallel one.
     *
     * `summary` under JIRA's own field id is what makes phase 3 able to fill the stub in rather than
     * leave two spellings of the same fact on one node.
     */
    @Test
    fun `a stub carries the key, the summary and a version that is not current`() {
        val links = mapper.map(issue(2, fields = LINKS_TO_3_AND_1)).links
        val row = placeholderRows(links, emptyMap(), seenIds = emptySet()).first { it["id"] == "$HOST/rest/api/2/issue/3" }

        @Suppress("UNCHECKED_CAST")
        val props = row["props"] as Map<String, Any?>

        assertEquals("SCRUM-3", props["key"])
        assertEquals("The third one", props["summary"])
        assertEquals("<unresolved SCRUM-3>", props["__name"])
        assertEquals("unresolved", props["__version"], "a stub must not read as a current issue")
    }

    /**
     * A reference with no `self` is dropped rather than stubbed.
     *
     * Identity is the resource URL, so a node keyed on `""` is one that every future reference
     * without a `self` would collide with — one shared node standing for every unknown issue.
     */
    @Test
    fun `a reference with no self is not stubbed`() {
        val links = mapper.map(
            issue(1, fields = """"issuelinks":[{"id":"7","type":{"id":"1","name":"Blocks"},"outwardIssue":{"id":"3","key":"SCRUM-3"}}]"""),
        ).links

        assertEquals(emptyList(), placeholderRows(links, emptyMap(), emptySet()))
    }

    /**
     * `linkId` travels beside the properties because it is the `MERGE` key.
     *
     * Both phrases are stored: `IsRelated` has identical inward and outward text, so direction is
     * not recoverable from the words and the UI renders the phrase rather than inferring from it.
     */
    @Test
    fun `a link row keys on the link id and carries both phrases`() {
        val link = mapper.map(issue(2, fields = LINKS_TO_3_AND_1)).links.first()
        val row = linkRow(link)

        assertEquals(link.linkId, row["linkId"])
        assertEquals(link.fromId, row["fromId"])
        assertEquals(link.toId, row["toId"])

        @Suppress("UNCHECKED_CAST")
        val props = row["props"] as Map<String, Any?>
        assertEquals("blocks", props["outward"])
        assertEquals("is blocked by", props["inward"])
        assertTrue("linkId" !in props, "the merge key must not also be in the map that += rewrites")
    }

    // -- fixtures ----------------------------------------------------------------------------------

    private val mapper = IssueMapper()

    private fun issue(n: Int = 1, fields: String? = null): JiraIssueEnvelope {
        val body = fields ?: """
            "summary":"Issue $n",
            "project":{"self":"$HOST/rest/api/2/project/1","key":"SCRUM","name":"Scrum"},
            "issuetype":{"self":"$HOST/rest/api/2/issuetype/1","name":"Task"},
            "assignee":{"self":"$HOST/rest/api/2/user?username=ada","name":"ada","displayName":"Ada"}
        """.trimIndent()

        return jiraJson.decodeFromString(
            """{"id":"$n","key":"SCRUM-$n","self":"$HOST/rest/api/2/issue/$n","fields":{$body}}""",
        )
    }

    private companion object {
        const val HOST = "https://jira.example.com"

        /** Two links: one to an issue the run may not have, one to issue 1. */
        val LINKS_TO_3_AND_1 = """
            "issuelinks":[
              {"id":"100","type":{"id":"1","name":"Blocks","inward":"is blocked by","outward":"blocks"},
               "outwardIssue":{"id":"3","key":"SCRUM-3","self":"$HOST/rest/api/2/issue/3",
                "fields":{"summary":"The third one"}}},
              {"id":"101","type":{"id":"1","name":"Blocks","inward":"is blocked by","outward":"blocks"},
               "inwardIssue":{"id":"1","key":"SCRUM-1","self":"$HOST/rest/api/2/issue/1",
                "fields":{"summary":"The first one"}}}
            ]
        """.trimIndent()

        val HAS_PARENT = """
            "parent":{"id":"9","key":"SCRUM-9","self":"$HOST/rest/api/2/issue/9",
             "fields":{"summary":"The epic"}}
        """.trimIndent()
    }
}

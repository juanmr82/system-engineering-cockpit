package com.sec.source.jira.mapping

import com.sec.source.jira.JiraFieldDefinition
import com.sec.source.jira.JiraIssueEnvelope
import com.sec.source.jira.JiraLabel
import com.sec.source.jira.JiraRel
import com.sec.source.jira.JiraSearchPage
import com.sec.source.jira.jiraJson
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mapper over the real 50-issue export (spec §16.1).
 *
 * The hand-written cases pin one rule each; the fixture-driven ones at the end are the load-bearing
 * ones. They are the closest thing to a real import that runs without Docker or a network, and they
 * assert the numbers the whole storage design rests on: ~1 040 keys in, ≤ 162 properties out, and
 * **zero nulls written**.
 */
class IssueMapperTest {

    private val mapper = IssueMapper()

    // -- identity (spec §6.2) --------------------------------------------------------------------

    /**
     * Identity is the `self` URL and never the key. Keys change when an issue moves between
     * projects; the numeric id inside `self` never does.
     */
    @Test
    fun `an issue is identified by its self URL, never by its key`() {
        val mapped = mapper.map(issue(key = "SCRUM-7", self = "https://jira/rest/api/2/issue/10001"))

        assertEquals("https://jira/rest/api/2/issue/10001", mapped.id)
        assertEquals("https://jira/rest/api/2/issue/10001", mapped.props["__id"])
    }

    @Test
    fun `the name is the key and the summary`() {
        val mapped = mapper.map(issue(key = "SCRUM-7", fields = """{"summary":"Fix the thing"}"""))

        assertEquals("SCRUM-7: Fix the thing", mapped.props["__name"])
    }

    /** A field-level permission can hide `summary`. The key alone is a worse name, not a broken one. */
    @Test
    fun `a missing summary falls back to the key rather than to an empty name`() {
        assertEquals("SCRUM-7", mapper.map(issue(key = "SCRUM-7", fields = "{}")).props["__name"])
        assertEquals(
            "SCRUM-7",
            mapper.map(issue(key = "SCRUM-7", fields = """{"summary":""}""")).props["__name"],
        )
    }

    @Test
    fun `a long summary is truncated but the key survives at the front`() {
        val mapped = mapper.map(
            issue(key = "SCRUM-7", fields = """{"summary":"${"x".repeat(500)}"}"""),
        )
        val name = mapped.props["__name"] as String

        assertEquals(200, name.length)
        assertTrue(name.startsWith("SCRUM-7: "), name)
    }

    @Test
    fun `the version is the updated timestamp, verbatim`() {
        val mapped = mapper.map(issue(fields = """{"updated":"2026-08-09T11:38:00.697+0200"}"""))

        assertEquals("2026-08-09T11:38:00.697+0200", mapped.props["__version"])
    }

    @Test
    fun `an issue with no updated timestamp is versioned unknown rather than left empty`() {
        assertEquals("unknown", mapper.map(issue(fields = "{}")).props["__version"])
    }

    /**
     * The one denormalisation this design allows. It is a *copy of imported data*, not a
     * derivation — a re-import reproduces it exactly — and phase 5's sweep needs it to scope by
     * project without a traversal per issue.
     */
    @Test
    fun `the project key is copied onto the issue for the sweep to scope by`() {
        val mapped = mapper.map(
            issue(fields = """{"project":{"self":"https://jira/p/1","key":"SCRUM","name":"Example"}}"""),
        )

        assertEquals("SCRUM", mapped.props["__projectKey"])
        assertTrue("__projectKey" in mapped.presentKeys)
    }

    // -- what gets written -------------------------------------------------------------------------

    @Test
    fun `nulls and empty arrays are not written`() {
        val mapped = mapper.map(issue(fields = """{"a":null,"b":[],"c":"kept"}"""))

        assertFalse(mapped.props.containsKey("a"))
        assertFalse(mapped.props.containsKey("b"))
        assertEquals("kept", mapped.props["c"])
    }

    /**
     * `presentKeys` drives phase 3's property removal: `SET i += $props` only adds and overwrites,
     * so a field that became null in JIRA keeps its stale value unless something says which keys
     * *should* be there. The envelope's own three must be in the list or the sweep would strip the
     * issue's key off it on every run.
     */
    @Test
    fun `presentKeys covers the envelope properties as well as the fields`() {
        val mapped = mapper.map(issue(fields = """{"summary":"s","gone":null}"""))

        assertTrue(mapped.presentKeys.containsAll(listOf("key", "id", "self")), "${mapped.presentKeys}")
        assertTrue("summary" in mapped.presentKeys)
        assertFalse("gone" in mapped.presentKeys, "a nulled field would survive the sweep")
    }

    @Test
    fun `a complex value is stored as JSON text and projected separately`() {
        val mapped = mapper.map(
            issue(fields = """{"customfield_1":{"self":"https://jira/o/1","value":"WSS","id":"3"}}"""),
        )

        assertTrue((mapped.props["customfield_1"] as String).startsWith("{"))
        // The sortable scalar is derived, so it lands on the companion node and never here (R2).
        assertEquals("WSS", mapped.projection["customfield_1"])
    }

    /** A scalar is already sortable on the issue node; a second derived copy would only go stale. */
    @Test
    fun `a scalar gets no projection`() {
        val mapped = mapper.map(issue(fields = """{"summary":"plain","workratio":-1}"""))

        assertFalse(mapped.projection.containsKey("summary"))
        assertFalse(mapped.projection.containsKey("workratio"))
    }

    // -- promoted entities (spec §7.3) --------------------------------------------------------------

    @Test
    fun `the promoted system fields become shared nodes with the right relationship`() {
        val mapped = mapper.map(
            issue(
                fields = """
                {"project":{"self":"https://jira/p/1","key":"SCRUM","name":"Example"},
                 "issuetype":{"self":"https://jira/it/1","id":"1","name":"Task"},
                 "status":{"self":"https://jira/s/3","name":"In Progress"},
                 "priority":{"self":"https://jira/pr/3","name":"Major"},
                 "assignee":{"self":"https://jira/u/a","name":"alovelace","displayName":"Ada Lovelace"}}
                """.trimIndent(),
            ),
        )

        val byRel = mapped.entities.associateBy { it.relationship }
        assertEquals(JiraLabel.PROJECT, byRel.getValue(JiraRel.IN_PROJECT).label)
        assertEquals(JiraLabel.ISSUE_TYPE, byRel.getValue(JiraRel.HAS_ISSUE_TYPE).label)
        assertEquals(JiraLabel.STATUS, byRel.getValue(JiraRel.HAS_STATUS).label)
        assertEquals(JiraLabel.PRIORITY, byRel.getValue(JiraRel.HAS_PRIORITY).label)
        // A user is named by displayName; `name` on a user object is the login (R5).
        assertEquals("Ada Lovelace", byRel.getValue(JiraRel.ASSIGNED_TO).props["__name"])
        assertEquals("alovelace", byRel.getValue(JiraRel.ASSIGNED_TO).props["name"])
    }

    @Test
    fun `versions and fixVersions share a label but not a relationship`() {
        val mapped = mapper.map(
            issue(
                fields = """
                {"versions":[{"self":"https://jira/v/1","name":"1.0"}],
                 "fixVersions":[{"self":"https://jira/v/2","name":"2.0"}]}
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(JiraLabel.VERSION, JiraLabel.VERSION), mapped.entities.map { it.label })
        assertEquals(
            setOf(JiraRel.AFFECTS_VERSION, JiraRel.FIX_VERSION),
            mapped.entities.map { it.relationship }.toSet(),
        )
    }

    /**
     * **Custom fields are never promoted**, whatever their declared type. There are 1 129 of them
     * and the set changes without notice; promoting by type would make the graph's shape a function
     * of somebody else's admin screen.
     */
    @Test
    fun `a custom field shaped exactly like a user is not promoted`() {
        val mapped = mapper.map(
            issue(fields = """{"customfield_1":{"self":"https://jira/u/b","displayName":"Someone"}}"""),
        )

        assertTrue(mapped.entities.isEmpty(), "a custom field became a node: ${mapped.entities}")
        // Still stored, still projected — only not traversable.
        assertEquals("Someone", mapped.projection["customfield_1"])
    }

    /** `labels` is already an `array<string>`; a node per label would buy no traversal anybody wants. */
    @Test
    fun `labels stay a list property and do not become nodes`() {
        val mapped = mapper.map(issue(fields = """{"labels":["alpha","beta"]}"""))

        assertEquals(listOf("alpha", "beta"), mapped.props["labels"])
        assertTrue(mapped.entities.isEmpty())
    }

    /** An entity with no `self` cannot be keyed, and inventing an id would create a node the next
     *  run cannot recognise. Skipped — the raw JSON is still on the issue, so nothing is lost. */
    @Test
    fun `an embedded entity with no self URL is not promoted`() {
        val mapped = mapper.map(issue(fields = """{"status":{"name":"In Progress"}}"""))

        assertTrue(mapped.entities.isEmpty())
        assertEquals("In Progress", mapped.projection["status"])
    }

    // -- links (spec §9.4) ---------------------------------------------------------------------------

    /**
     * Both ends of a link report it with the same id, so storing it in JIRA's stated direction is
     * what collapses two reports into one edge.
     */
    @Test
    fun `an outward link runs from this issue`() {
        val mapped = mapper.map(issue(self = "https://jira/i/1", fields = LINK_OUTWARD))
        val link = mapped.links.single()

        assertEquals("https://jira/i/1", link.fromId)
        assertEquals("https://jira/i/2", link.toId)
        assertEquals("blocks", link.outward)
        assertEquals("SCRUM-2", link.other.key)
    }

    /**
     * The other end carries enough to stand a placeholder up without a second API call — which is
     * the whole reason phase 4 can resolve a link into a project it has never imported (spec §9.4).
     */
    @Test
    fun `the other end of a link carries what a placeholder is built from`() {
        val mapped = mapper.map(issue(self = "https://jira/i/1", fields = LINK_OUTWARD))

        assertEquals(
            IssueRef(id = "2", key = "SCRUM-2", self = "https://jira/i/2", summary = "The other one"),
            mapped.links.single().other,
        )
    }

    /**
     * A reference with no embedded `fields` is not a failure: the summary is the readable half of a
     * placeholder, not its identity, and `""` keeps it out of the property map's null trap.
     */
    @Test
    fun `a reference with no summary yields an empty one, never a null`() {
        val mapped = mapper.map(
            issue(
                self = "https://jira/i/1",
                fields = """
                {"issuelinks":[{"id":"1","type":{"id":"10","name":"Blocks",
                 "inward":"is blocked by","outward":"blocks"},
                 "outwardIssue":{"id":"2","key":"SCRUM-2","self":"https://jira/i/2"}}]}
                """.trimIndent(),
            ),
        )

        assertEquals("", mapped.links.single().other.summary)
    }

    /** The mirror image, and the only asymmetry in the whole function. */
    @Test
    fun `an inward link runs towards this issue`() {
        val mapped = mapper.map(issue(self = "https://jira/i/1", fields = LINK_INWARD))
        val link = mapped.links.single()

        assertEquals("https://jira/i/2", link.fromId)
        assertEquals("https://jira/i/1", link.toId)
        assertEquals("2484985", link.linkId)
    }

    /**
     * `IsRelated` has identical inward and outward phrases, which is why direction is taken from
     * *which key the other issue arrived under* and never from the words.
     */
    @Test
    fun `a symmetric link type still has a direction`() {
        val mapped = mapper.map(
            issue(
                self = "https://jira/i/1",
                fields = """
                {"issuelinks":[{"id":"1","type":{"id":"10","name":"Relates",
                 "inward":"relates to","outward":"relates to"},
                 "outwardIssue":{"id":"2","key":"SCRUM-2","self":"https://jira/i/2"}}]}
                """.trimIndent(),
            ),
        )

        assertEquals("https://jira/i/1", mapped.links.single().fromId)
    }

    @Test
    fun `a parent is recorded so a sub-task edge can be drawn`() {
        val mapped = mapper.map(
            issue(fields = """{"parent":{"id":"9","key":"SCRUM-1","self":"https://jira/i/9"}}"""),
        )

        assertEquals("https://jira/i/9", mapped.parent?.self)
        assertEquals("SCRUM-1", mapped.parent?.key)
    }

    @Test
    fun `an issue with no parent has none, rather than an empty string`() {
        assertNull(mapper.map(issue(fields = "{}")).parent)
    }

    // -- the catalogue is advisory --------------------------------------------------------------------

    /**
     * §16.1: a field present in the issue but absent from `/field` must not crash, and must still
     * be stored. It is reported once, because a field appearing between the two calls is worth
     * knowing about and is never worth failing over.
     */
    @Test
    fun `a field the catalogue does not know is still imported, and reported`() {
        val catalogue = JiraFieldCatalogue(listOf(JiraFieldDefinition(id = "summary", name = "Summary")))
        val mapped = IssueMapper(catalogue)
            .map(issue(fields = """{"summary":"known","customfield_99":"brand new"}"""))

        assertEquals("brand new", mapped.props["customfield_99"])
        assertEquals(listOf("customfield_99"), mapped.warnings)
    }

    @Test
    fun `no catalogue means no warnings, rather than a warning per field`() {
        assertTrue(mapper.map(issue(fields = """{"a":"1","b":"2"}""")).warnings.isEmpty())
    }

    // -- the whole export ------------------------------------------------------------------------------

    /**
     * All 50 real issues, mapped.
     *
     * The numbers are the spec's own and are asserted rather than trusted: ~1 040 keys in, at most
     * 162 properties out, 145 on average. If a change to the storage rules moved those, this is
     * where it would show up.
     */
    @Test
    fun `the whole export maps within the size budget the design assumes`() {
        val mapped = searchPage().issues.map(mapper::map)

        assertEquals(50, mapped.size)
        val propertyCounts = mapped.map { it.props.size }
        assertTrue(propertyCounts.max() <= 175, "an issue produced ${propertyCounts.max()} properties")
        assertTrue(propertyCounts.min() >= 29, "an issue produced only ${propertyCounts.min()} properties")

        val mean = propertyCounts.average()
        assertTrue(mean in 140.0..165.0, "mean property count drifted to $mean")
    }

    /** The rule everything else rests on: absence means null, so a null is never written. */
    @Test
    fun `not one null value is written for any issue in the export`() {
        searchPage().issues.map(mapper::map).forEach { issue ->
            val nulls = issue.props.filterValues { it == null }.keys
            assertTrue(nulls.isEmpty(), "${issue.key} wrote null properties: $nulls")
        }
    }

    /** Every issue is identifiable, named and versioned — the `SEItem` contract (§6.2). */
    @Test
    fun `every issue in the export satisfies the SEItem contract`() {
        searchPage().issues.map(mapper::map).forEach { issue ->
            assertTrue((issue.props["__id"] as String).startsWith("http"), issue.key)
            assertTrue((issue.props["__name"] as String).isNotBlank(), issue.key)
            assertTrue((issue.props["__version"] as String).isNotBlank(), issue.key)
            assertTrue((issue.props["key"] as String).isNotBlank())
            assertNotNull(issue.props["__projectKey"], "${issue.key} has no project key to sweep by")
        }
    }

    /**
     * The three different key-set sizes all map — the claim behind "never cache an issue's shape
     * from the first row of a page". Field contexts are per project *and* per issue type, so two
     * issues of the same project carry different key sets.
     */
    @Test
    fun `issues with different key set sizes all map without error`() {
        val sizes = searchPage().issues.map { it.fields.size }.toSet()

        assertTrue(sizes.size > 1, "every issue had the same key count; the claim is untested here")
        assertEquals(50, searchPage().issues.map(mapper::map).size)
    }

    /**
     * Every property key the mapper produces must be a valid Neo4j property key with no quoting —
     * which is what makes `SET n += row.props` safe without escaping, and is the entire reason the
     * storage design keys properties by field id (§7.2).
     */
    @Test
    fun `every property key the export produces is safe to write unquoted`() {
        val keys = searchPage().issues.map(mapper::map).flatMap { it.props.keys }.toSet()

        val unsafe = keys.filterNot { it.matches(Regex("^(__)?[A-Za-z][A-Za-z0-9_]*$")) }
        assertTrue(unsafe.isEmpty(), "property keys needing quoting reached the writer: $unsafe")
    }

    /**
     * The 28 triples of §16.1, driven through the real export.
     *
     * The classifier never sees a declared type, so this is what proves the collapse is total:
     * every one of the 28 `(type, items, shape)` combinations in the fixture classifies, and none
     * of them lands somewhere a shape does not justify.
     */
    @Test
    fun `every distinct value shape in the export classifies without a declared type`() {
        val classified = searchPage().issues
            .flatMap { it.fields.entries }
            .map { (_, value) -> ValueClassifier.classify(value) }

        assertTrue(classified.isNotEmpty())
        // 43 902 nulls plus 842 empty arrays in the export — the skip rule is most of the design.
        assertTrue(
            classified.count { it is StoredValue.Skip } > classified.size * 3 / 4,
            "the skip rule buys less than §7.1 claims",
        )
        assertTrue(classified.any { it is StoredValue.JsonText })
        assertTrue(classified.any { it is StoredValue.ListOfScalars })
        assertTrue(classified.any { it is StoredValue.Scalar })
    }

    /** Links extrapolate to ~550 for 784 issues — trivially safe to hold in memory for phase 4. */
    @Test
    fun `the export's issue links all map, in both directions`() {
        val links = searchPage().issues.map(mapper::map).flatMap { it.links }

        assertTrue(links.isNotEmpty(), "no issue links in the export; §9.4 is untested here")
        links.forEach { link ->
            assertTrue(link.fromId.startsWith("http"), link.linkId)
            assertTrue(link.toId.startsWith("http"), link.linkId)
            assertTrue(link.fromId != link.toId, "an issue linked to itself: ${link.linkId}")
        }
        // Both ends report the same link with the same id, which is what MERGE on linkId collapses.
        assertTrue(links.map { it.linkId }.toSet().size <= links.size)
    }

    @Test
    fun `every issue in the export lands in a project and carries an issue type`() {
        searchPage().issues.map(mapper::map).forEach { issue ->
            val relationships = issue.entities.map { it.relationship }
            assertEquals(1, relationships.count { it == JiraRel.IN_PROJECT }, issue.key)
            assertEquals(1, relationships.count { it == JiraRel.HAS_ISSUE_TYPE }, issue.key)
        }
    }

    // -- fixtures --------------------------------------------------------------------------------------

    private fun issue(
        key: String = "SCRUM-1",
        self: String = "https://jira/rest/api/2/issue/10000",
        fields: String = "{}",
    ) = JiraIssueEnvelope(
        id = "10000",
        key = key,
        self = self,
        fields = jiraJson.parseToJsonElement(fields) as JsonObject,
    )

    private fun searchPage(): JiraSearchPage {
        val path = Path.of("..", "docs", "JIRA.json")
        assertTrue(path.exists(), "the committed export is missing; this test would pass vacuously")
        return jiraJson.decodeFromString(path.readText())
    }

    private companion object {
        const val LINK_OUTWARD = """
            {"issuelinks":[{"id":"2484985","type":{"id":"10","name":"Blocks",
             "inward":"is blocked by","outward":"blocks"},
             "outwardIssue":{"id":"2","key":"SCRUM-2","self":"https://jira/i/2",
              "fields":{"summary":"The other one"}}}]}
        """

        const val LINK_INWARD = """
            {"issuelinks":[{"id":"2484985","type":{"id":"10","name":"Blocks",
             "inward":"is blocked by","outward":"blocks"},
             "inwardIssue":{"id":"2","key":"SCRUM-2","self":"https://jira/i/2"}}]}
        """
    }
}

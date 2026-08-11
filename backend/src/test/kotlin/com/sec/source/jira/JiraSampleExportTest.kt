package com.sec.source.jira

import com.sec.config.JiraSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser against the real exports in `docs/`, which spec §16.1 names as the fixtures.
 *
 * Two jobs, and the second is the less obvious one. It proves the loose types read real JIRA — and
 * it **pins the numbers the design is built on**. Every claim asserted here is one some later
 * change could quietly invalidate: that `name` is not unique, that two fields carry no schema at
 * all, that the key set differs between issues of the same project. Each of those is load-bearing
 * somewhere else, and a fixture swapped for a tidier one would take the reasoning with it.
 */
class JiraSampleExportTest {

    // -- the field catalogue --------------------------------------------------------------------

    @Test
    fun `the catalogue parses, and is the size the design assumes`() {
        val fields = fieldDefinitions()

        assertEquals(1171, fields.size)
        assertEquals(42, fields.count { !it.custom })
        assertEquals(1129, fields.count { it.custom })
    }

    /**
     * The two navigable pseudo-fields. They are excluded from the picker entirely — `issuekey`
     * duplicates the fixed Key column and `thumbnail` is not a data field — and the way to
     * recognise them is that they alone have no `schema` (spec §5, §13.3).
     */
    @Test
    fun `exactly issuekey and thumbnail have no schema, and neither is displayable`() {
        val schemaless = fieldDefinitions().filter { it.schema == null }

        assertEquals(listOf("issuekey", "thumbnail"), schemaless.map { it.id }.sorted())
        assertTrue(schemaless.none { it.isDisplayable })
    }

    /**
     * The property that forbids keying anything on a field's display name.
     *
     * The spec says 15 names over 33 fields; this export has **16 over 38**. The exact count does
     * not matter and is asserted loosely for that reason — what matters is that it is not zero,
     * because a picker that shows two rows both reading `Classification` with no way to tell them
     * apart is useless, and a *config* that stored the name would resolve to the wrong field.
     */
    @Test
    fun `display names are not unique, so they can never be a key`() {
        val byName = fieldDefinitions().groupBy { it.name }.filterValues { it.size > 1 }

        assertTrue(byName.size >= 15, "expected ambiguous display names, found ${byName.size}")
        assertTrue(byName.values.sumOf { it.size } >= 33)
        // The ones the spec names, still true here and worth pinning by name.
        assertTrue(byName.keys.containsAll(listOf("Work Package", "DOORS-ID", "Classification")))
    }

    /**
     * `any` means the shape is genuinely unconstrained, which is why such a field cannot be
     * offered as a sortable column however tempting its name looks.
     */
    @Test
    fun `any-typed fields exist and are not displayable`() {
        val anyTyped = fieldDefinitions().filter { it.schemaType == JiraSchemaType.ANY }

        assertTrue(anyTyped.isNotEmpty(), "no any-typed field in the sample; §7 has nothing to guard")
        assertTrue(anyTyped.none { it.isDisplayable })
    }

    /**
     * The schema types present go well beyond the table in spec §5.1 — `securitylevel`,
     * `comments-page`, `sd-approvals` and others are here too. That is the argument for
     * classifying values by observed *shape* rather than by declared type: the type list is open
     * and a plugin can extend it, so a `when` over known types would drop data silently.
     */
    @Test
    fun `the declared type vocabulary is wider than the spec table lists`() {
        val types = fieldDefinitions().mapNotNull { it.schema?.type }.toSet()

        assertTrue(types.containsAll(listOf("option", "array", "string", "user", "progress")))
        assertTrue(
            types.size > 15,
            "only ${types.size} distinct schema types; the shape-driven argument weakens",
        )
    }

    // -- issue types ------------------------------------------------------------------------------

    @Test
    fun `the issue type catalogue parses`() {
        val types = issueTypes()

        assertEquals(9, types.size)
        assertTrue(types.all { it.self.isNotBlank() && it.id.isNotBlank() && it.name.isNotBlank() })
    }

    /**
     * `Epic` has no `avatarId` — it uses a static SVG icon rather than an avatar — which is why the
     * DTO makes that field nullable instead of defaulting it to 0. The writer turns the null into a
     * property removal; defaulting would invent an avatar id that resolves to somebody else's icon.
     */
    @Test
    fun `an issue type without an avatar parses as absent, not as zero`() {
        val epic = issueTypes().single { it.name == "Epic" }

        assertNull(epic.avatarId)
        assertTrue(epic.iconUrl.endsWith(".svg"), "Epic is the static-icon case: ${epic.iconUrl}")
    }

    @Test
    fun `issue types with an avatar keep it`() {
        assertEquals(10318L, issueTypes().single { it.name == "Task" }.avatarId)
    }

    /**
     * An empty description is normal — four of the nine have one — and it means the type exists
     * with no description, not that the field is missing. The same `"" is a value` rule the DOORS
     * side already lives by (CLAUDE.md §11).
     */
    @Test
    fun `an empty description is a value, and common`() {
        val descriptions = issueTypes().map { it.description }

        assertTrue(descriptions.count { it.isEmpty() } >= 4)
        assertTrue(descriptions.any { it.isNotEmpty() })
    }

    /** Sub-task types are flagged, which is what phase 4 needs to know a `parent` edge is expected. */
    @Test
    fun `exactly one sample type is a sub-task type`() {
        assertEquals(listOf("Sub-task"), issueTypes().filter { it.subtask }.map { it.name })
    }

    @Test
    fun `the client reads the real issue type catalogue end to end`() {
        val client = clientServing("JIRA_ISSUE_TYPES_DTO_EXAMPLE.md")

        val types = runBlocking { client.issueTypes() }.getOrThrow()

        assertEquals(9, types.size)
        assertEquals(1, types.count { it.avatarId == null })
    }

    // -- one page of issues -----------------------------------------------------------------------

    @Test
    fun `the search page parses into the envelope the design commits to`() {
        val page = searchPage()

        assertEquals(0, page.startAt)
        assertEquals(50, page.maxResults)
        assertEquals(784, page.total)
        assertEquals(50, page.issues.size)
    }

    @Test
    fun `every issue carries the identity the graph keys on`() {
        searchPage().issues.forEach { issue ->
            assertTrue(issue.self.startsWith("http"), "an issue has no self URL: ${issue.key}")
            assertTrue(issue.key.isNotBlank())
            assertTrue(issue.id.isNotBlank())
        }
    }

    /**
     * The claim behind "never cache the shape of an issue from the first row of a page".
     *
     * Field contexts are per project *and* per issue type, so two issues of the same project can
     * carry different key sets. A mapper that read the first issue's keys and assumed the rest
     * would drop real values, and only for some issues, which is the hardest kind of loss to see.
     */
    @Test
    fun `the field key set differs between issues, even within one project`() {
        val sizes = searchPage().issues.map { it.fields.size }.toSet()

        assertTrue(sizes.size > 1, "every issue had the same key count; §3.4's warning is untested")
        assertTrue(sizes.max() > 1000, "expected ~1041 keys per issue, saw ${sizes.max()}")
    }

    /**
     * The number that justifies the whole skip rule in §7.1: about 86% of every payload is null,
     * so writing them would make a ~1 040-property node instead of a ~145-property one.
     */
    @Test
    fun `most of every payload is null, which is what makes skipping worth doing`() {
        val issue = searchPage().issues.first()
        val nulls = issue.fields.count { (_, value) -> value.toString() == "null" }

        assertTrue(
            nulls > issue.fields.size * 3 / 4,
            "only $nulls of ${issue.fields.size} keys were null; the skip rule buys less than §7.1 claims",
        )
    }

    /** R8 at full scale: the real export, through the real parser, with nothing typed but the envelope. */
    @Test
    fun `the client reads the real export end to end`() {
        val client = clientServing("JIRA.json")

        val pages = mutableListOf<JiraSearchPage>()
        // One page in the fixture, and `startAt` advances past `total` only after 16 of them, so
        // the loop is capped here rather than fed 15 more copies of the same page.
        val summary = runBlocking { client.searchAll(JQL, maxPages = 1) { pages += it } }

        assertTrue(summary.isFailure, "the cap should stop a fixture that never runs out")
        assertEquals(1, pages.size)
        assertEquals(50, pages.single().issues.size)
        assertTrue(pages.single().issues.first().fields.jsonObject.isNotEmpty())
    }

    @Test
    fun `the client reads the real field catalogue end to end`() {
        val fields = runBlocking { clientServing(FIELDS).fieldDefinitions() }.getOrThrow()

        assertEquals(1171, fields.size)
        assertFalse(fields.any { it.id.isBlank() })
    }

    // -- fixtures ---------------------------------------------------------------------------------

    private fun fieldDefinitions(): List<JiraFieldDefinition> =
        jiraJson.decodeFromString(sample(FIELDS))

    private fun searchPage(): JiraSearchPage =
        jiraJson.decodeFromString(sample(SEARCH))

    private fun issueTypes(): List<JiraIssueTypeDefinition> =
        jiraJson.decodeFromString(sample(ISSUE_TYPES))

    /** The real client, over a `MockEngine` that answers every request with one sample export. */
    private fun clientServing(name: String): JiraHttpClient = JiraHttpClient(
        JiraSettings(host = "https://jira.example.com", token = "t"),
        MockEngine {
            respond(sample(name), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    /**
     * The exports live in `docs/`, beside the spec that describes them; the working directory for
     * these tests is `backend/`.
     *
     * The existence check is not defensive noise. Every assertion in this file is *about* the
     * fixture, so a missing one would turn the whole class into a test of nothing — and it would
     * do so silently, since a fixture is exactly the kind of file a repository tidy-up moves.
     */
    private fun sample(name: String): String {
        val path: Path = Path.of("..", "docs", name)
        assertTrue(
            path.exists(),
            "sample export $name is missing. It is a committed fixture (spec §16.1); this test " +
                "would otherwise pass vacuously.",
        )
        return path.readText()
    }

    private companion object {
        const val SEARCH = "JIRA.json"
        const val FIELDS = "JIRA_FIELDS.json"

        /**
         * `.md` rather than `.json`, which is how it was added. The content is a raw `/issuetype`
         * array; only the extension differs from its siblings, and nothing here parses by
         * extension. Worth renaming one day, not worth a divergent copy.
         */
        const val ISSUE_TYPES = "JIRA_ISSUE_TYPES_DTO_EXAMPLE.md"

        const val JQL = """project in ("ProjectCRPT") AND created <= "2026/08/11 14:32" ORDER BY key ASC"""
    }
}

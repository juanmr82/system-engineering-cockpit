package com.sec

import com.sec.config.JiraDeployment
import com.sec.config.JiraSettings
import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.importer.ImportContext
import com.sec.importer.ImportLogLevel
import com.sec.source.jira.JiraGraphWriter
import com.sec.source.jira.JiraHttpClient
import com.sec.source.jira.JiraImporter
import com.sec.source.jira.JiraSettingsStore
import com.sec.source.jira.jiraJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3 against a real Neo4j Community image — spec §16.2 tests 1–3.
 *
 * Everything here runs the **whole importer** over a stubbed JIRA rather than calling the writer
 * directly, because three of the things most able to break are not in the Cypher: the phase order,
 * the catalogue being carried from phase 2 to phase 3, and the project list being read from the
 * graph rather than from configuration.
 *
 * The riskiest statements in the product are the ones exercised here. Two of them use Cypher 25
 * features — dynamic labels and `REMOVE n[key]` — that no unit test can check, because whether the
 * server supports them is a property of the server (CLAUDE.md §7: Community, never Enterprise).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class JiraIssueImportTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var settingsStore: JiraSettingsStore

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        settingsStore = JiraSettingsStore(graphDriver)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n")) { }
        settingsStore.saveProjectKeys(listOf(PROJECT), "test").getOrThrow()
    }

    // -- test 1: fresh import ---------------------------------------------------------------------

    @Test
    fun `a fresh import writes issues, projections and promoted edges`() = runBlocking {
        val run = import(issues = fixtureIssues())

        assertEquals(FIXTURE_ISSUES.toLong(), run.counters["issuesSeen"])
        assertEquals(FIXTURE_ISSUES, count("MATCH (i:JiraIssue) RETURN count(i) AS n"))

        // Every issue is an SEItem, which is the only thing a future cross-source link joins on (R6).
        assertEquals(
            FIXTURE_ISSUES,
            count("MATCH (i:JiraIssue) WHERE i:SEItem RETURN count(i) AS n"),
        )

        // Exactly one projection each — spec §16.2's own wording, and the reason a projection is
        // written even for an issue with nothing to project.
        assertEquals(
            FIXTURE_ISSUES,
            count("MATCH (i:JiraIssue)-[:__projection]->(p:__JiraProjection) RETURN count(p) AS n"),
        )
        assertEquals(
            0,
            count(
                """
                MATCH (i:JiraIssue)
                WHERE NOT (i)-[:__projection]->()
                RETURN count(i) AS n
                """,
            ),
        )

        // The promoted entities got their own labels, which is the dynamic-label statement working.
        assertTrue(count("MATCH (p:JiraProject) RETURN count(p) AS n") > 0, "no project node")
        assertTrue(count("MATCH (u:JiraUser) RETURN count(u) AS n") > 0, "no user nodes")
        assertEquals(
            FIXTURE_ISSUES,
            count("MATCH (:JiraIssue)-[r:inProject]->(:JiraProject) RETURN count(r) AS n"),
        )
    }

    /**
     * The dedup that stops one project node being merged once per issue.
     *
     * The committed export spans five projects across its fifty issues — nine of them in
     * [PROJECT] — so "one node per project" and "one node per issue" are different numbers here and
     * the assertion can tell them apart.
     *
     * Asserted as a node count rather than as a write count, because the observable consequence of
     * getting it wrong is not wrongness — `MERGE` is idempotent — it is nine lock acquisitions on
     * one node inside a transaction every read waits behind (spec §15).
     */
    @Test
    fun `shared entities are one node however many issues name them`() = runBlocking {
        import(issues = fixtureIssues())

        assertEquals(
            FIXTURE_PROJECTS,
            count("MATCH (p:JiraProject) RETURN count(p) AS n"),
            "projects were merged as more nodes than there are projects",
        )
        assertEquals(
            1,
            count("MATCH (p:JiraProject {key: '$PROJECT'}) RETURN count(p) AS n"),
            "the project naming nine issues became more than one node",
        )
    }

    // -- test 2: idempotence ----------------------------------------------------------------------

    @Test
    fun `a second identical import changes nothing`() = runBlocking {
        import(issues = fixtureIssues())
        val first = graphSnapshot()

        import(issues = fixtureIssues())

        assertEquals(first, graphSnapshot(), "the second run changed the graph")
    }

    /**
     * R2's mandatory regression test, at the one place it is most likely to be violated.
     *
     * A comment on an issue is the only data in this system a re-import cannot reconstruct, and an
     * importer that writes `i += props` beside it is exactly the shape that quietly takes it away.
     * The assertion is the anchor's own property map, byte for byte, before and after.
     */
    @Test
    fun `an import leaves annotations on an issue untouched`() = runBlocking {
        import(issues = fixtureIssues())

        val issueId = firstIssueId()
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (i:JiraIssue {__id: ${'$'}id})
                CREATE (i)-[:__noteOn]->(:__Meta:__Note {
                  __metaId: 'note-1', __metaKind: 'note', __schemaVersion: 1,
                  text: 'Reviewed against the ICD', __createdBy: 'test', __createdAt: '2026-08-11'
                })
                """,
                mapOf("id" to issueId),
            ),
        ) { }

        val before = propertiesOf(issueId)

        import(issues = fixtureIssues())

        assertEquals(before, propertiesOf(issueId), "the re-import rewrote the annotated issue")
        assertEquals(
            1,
            count("MATCH (:JiraIssue)-[:__noteOn]->(n:__Note) RETURN count(n) AS n"),
            "the note did not survive the re-import",
        )
    }

    // -- test 3: update, and the property removal that is the point of this phase ------------------

    @Test
    fun `a changed summary is updated and a nulled field is removed`() = runBlocking {
        val original = fixtureIssues()
        val target = original.first().jsonObject
        val issueId = target["self"]!!.jsonPrimitive.content

        // A field this issue actually has a value for — chosen from the data rather than named, so
        // the test does not depend on which custom fields the sanitised export happens to carry.
        val populated = target["fields"]!!.jsonObject.entries
            .first { (key, value) ->
                key.startsWith("customfield_") && value is JsonPrimitive && value.isString
            }.key

        import(issues = original)
        assertTrue(propertiesOf(issueId).containsKey(populated), "the fixture field was not stored")

        val edited = original.toMutableList()
        edited[0] = target.edit { fields ->
            fields["summary"] = JsonPrimitive("A summary somebody changed")
            // JIRA sends a cleared field as null, which the mapper skips — so the *only* thing that
            // can remove it from the node is phase 3's stale-key sweep.
            fields[populated] = kotlinx.serialization.json.JsonNull
        }

        import(issues = edited)

        val after = propertiesOf(issueId)
        assertEquals("A summary somebody changed", after["summary"])
        assertNull(after[populated], "the cleared field kept its stale value")

        // The rest of the issue is untouched: a sweep that removed more than it should would show
        // up here rather than as a missing column three views away.
        assertEquals(PROJECT, after["__projectKey"])
        assertTrue(after.containsKey("key"), "the envelope's own key was swept off the node")
    }

    /**
     * The same bug one level up, and the one the spec does not mention.
     *
     * `MERGE` adds the new `assignedTo` and leaves the old, so re-assigning an issue would leave it
     * assigned to two people and every "issues assigned to X" query answering with the wrong one.
     * Nothing about the node looks damaged, which is what makes it worth a test of its own.
     */
    @Test
    fun `a reassigned issue loses the edge to its former assignee`() = runBlocking {
        val original = fixtureIssues()
        val target = original.first().jsonObject
        val issueId = target["self"]!!.jsonPrimitive.content

        val reassigned = original.toMutableList()
        reassigned[0] = target.edit { fields ->
            fields["assignee"] = buildJsonObject {
                put("self", JsonPrimitive("$HOST/rest/api/2/user?accountId=someone-else"))
                put("displayName", JsonPrimitive("Someone Else"))
                put("name", JsonPrimitive("selse"))
            }
        }

        import(issues = original)
        import(issues = reassigned)

        val assignees = queryStrings(
            """
            CYPHER 25
            MATCH (:JiraIssue {__id: ${'$'}id})-[:assignedTo]->(u:JiraUser)
            RETURN u.__name AS value
            """,
            mapOf("id" to issueId),
        )

        assertEquals(listOf("Someone Else"), assignees)
    }

    /** An issue that leaves JIRA is phase 5's job — asserted here as the *absence* of a sweep. */
    @Test
    fun `phase 3 does not delete an issue that stopped being returned`() = runBlocking {
        import(issues = fixtureIssues())
        import(issues = fixtureIssues().drop(1))

        assertEquals(
            FIXTURE_ISSUES,
            count("MATCH (i:JiraIssue) RETURN count(i) AS n"),
            "phase 3 deleted an issue; removing them belongs to the phase 5 sweep",
        )
    }

    // -- refusing to run ---------------------------------------------------------------------------

    /**
     * Spec §8: never fall back to an unbounded query over the whole instance.
     *
     * Checked in preflight, so it fails **before** the schema and two catalogues are written rather
     * than at the start of the phase that takes all the time.
     */
    @Test
    fun `an import with no configured projects refuses to start`() = runBlocking {
        settingsStore.saveProjectKeys(emptyList(), "test")
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (s:__JiraSettings) DETACH DELETE s")) { }

        val context = RecordingContext()
        val failure = runCatching { importer(fixtureIssues()).run(context) }.exceptionOrNull()

        assertTrue(failure is com.sec.source.jira.JiraFailure.NoProjectsConfigured, "was $failure")
        assertEquals(0, count("MATCH (i:JiraIssue) RETURN count(i) AS n"))
    }

    // -- harness -----------------------------------------------------------------------------------

    private suspend fun import(issues: List<JsonObject>): RecordingContext {
        val context = RecordingContext()
        importer(issues).run(context)
        return context
    }

    private fun importer(issues: List<JsonObject>): JiraImporter {
        val settings = JiraSettings(
            host = HOST,
            token = "t",
            deployment = JiraDeployment.DATA_CENTER,
            pageSize = 100,
        )
        return JiraImporter(
            settings,
            JiraHttpClient(settings, stubJira(issues)),
            JiraGraphWriter(graphDriver, HOST),
            settingsStore,
        )
    }

    /**
     * A JIRA that answers the four endpoints an import calls.
     *
     * The search page is rebuilt around the supplied issues with `total` equal to their count, so
     * the paging loop terminates after one page. The committed fixture's own `total` is the real
     * instance's 784, which against a one-page stub would re-serve the same 50 issues sixteen times.
     */
    private fun stubJira(issues: List<JsonObject>) = MockEngine { request ->
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("/myself") ->
                """{"name":"tester","key":"tester","displayName":"Tester","timeZone":"Europe/Berlin"}"""
            path.endsWith("/issuetype") -> sample(ISSUE_TYPES)
            path.endsWith("/field") -> sample(FIELDS)
            else -> """{"startAt":0,"maxResults":100,"total":${issues.size},""" +
                """"issues":${JsonArray(issues)}}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    /** The fixture's issues, with their project key rewritten to the one under test. */
    private fun fixtureIssues(): List<JsonObject> =
        jiraJson.parseToJsonElement(sample(SEARCH)).jsonObject["issues"]!!.jsonArray
            .map { it.jsonObject }

    /** Replace an issue's `fields`, leaving the envelope alone. */
    private fun JsonObject.edit(block: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): JsonObject {
        val fields = this["fields"]!!.jsonObject.toMutableMap()
        block(fields)
        return JsonObject(toMutableMap().apply { put("fields", JsonObject(fields)) })
    }

    private suspend fun count(cypher: String): Int =
        graphDriver.executeRead(Query(cypher.withPrefix())) { records ->
            records.firstOrNull()?.get("n")?.asInt() ?: 0
        }

    private suspend fun queryStrings(cypher: String, params: Map<String, Any>): List<String> =
        graphDriver.executeRead(Query(cypher, params)) { records ->
            records.map { it["value"].asString() }.sorted()
        }

    private suspend fun firstIssueId(): String =
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (i:JiraIssue) RETURN i.__id AS value ORDER BY value LIMIT 1"),
        ) { records -> records.first()["value"].asString() }

    private suspend fun propertiesOf(id: String): Map<String, Any?> =
        graphDriver.executeRead(
            Query(
                "CYPHER 25 MATCH (i:JiraIssue {__id: \$id}) RETURN properties(i) AS props",
                mapOf("id" to id),
            ),
        ) { records -> records.first()["props"].asMap() }

    /**
     * Everything an import writes, as comparable values — the idempotence assertion's subject.
     *
     * Property maps and relationship endpoints, sorted. `:__ImportRun` is excluded because a second
     * run is *supposed* to add one, which is the one difference spec §16.2 permits.
     */
    private suspend fun graphSnapshot(): List<String> {
        // Formatted in Kotlin rather than in Cypher: `toString()` takes a scalar, and both halves
        // of what makes a node comparable — its labels and its property map — are collections.
        val nodes = graphDriver.executeRead(
            Query(
                """
                CYPHER 25
                MATCH (n)
                WHERE NOT n:__ImportRun
                RETURN n.__id AS id, labels(n) AS labels, properties(n) AS props
                """,
            ),
        ) { records ->
            records.map { record ->
                val labels = record["labels"].asList { it.asString() }.sorted()
                val props = record["props"].asMap().toSortedMap().toString()
                "node:${record["id"]}:$labels:$props"
            }
        }

        val relationships = graphDriver.executeRead(
            Query(
                """
                CYPHER 25
                MATCH (a)-[r]->(b)
                RETURN 'rel:' + a.__id + '-' + type(r) + '->' + b.__id AS value
                """,
            ),
        ) { records -> records.map { it["value"].asString() } }

        return (nodes + relationships).sorted()
    }

    private fun String.withPrefix(): String =
        if (trimStart().startsWith("CYPHER")) this else "CYPHER 25\n$this"

    private fun sample(name: String): String {
        val path: Path = Path.of("..", "docs", name)
        assertTrue(path.exists(), "sample export $name is missing; this test would pass vacuously.")
        return path.readText()
    }

    /**
     * An [ImportContext] that records instead of publishing.
     *
     * The framework's own behaviour — throttling, events, persistence — has 23 tests of its own in
     * `ImportRunServiceTest`. Re-exercising it here would make every failure in this file ambiguous
     * about which half it came from.
     */
    private class RecordingContext : ImportContext {
        val counters = mutableMapOf<String, Long>()
        val warnings = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var params: Map<String, String> = emptyMap()

        override val runId: String = "test-run"
        override suspend fun phase(phaseId: String) = Unit
        override suspend fun progress(current: Int, total: Int) = Unit
        override suspend fun log(message: String, level: ImportLogLevel) { logs += message }
        override suspend fun warn(message: String) { warnings += message }
        override suspend fun count(name: String, delta: Long) {
            counters[name] = (counters[name] ?: 0) + delta
        }
        override suspend fun setCount(name: String, value: Long) { counters[name] = value }
        override suspend fun params(params: Map<String, String>) { this.params += params }
        override suspend fun ensureActive() = Unit
    }

    private companion object {
        const val HOST = "https://jira.example.com"
        const val SEARCH = "JIRA.json"
        const val FIELDS = "JIRA_FIELDS.json"
        const val ISSUE_TYPES = "JIRA_ISSUE_TYPES_DTO_EXAMPLE.md"

        /** The committed export's size, asserted rather than assumed by every count in this file. */
        const val FIXTURE_ISSUES = 50

        /** Distinct projects across those fifty issues — the number the dedup assertion turns on. */
        const val FIXTURE_PROJECTS = 5

        /** The first issue's project, and the one configured for the import. Nine issues carry it. */
        const val PROJECT = "ProjectCRPT"
    }
}

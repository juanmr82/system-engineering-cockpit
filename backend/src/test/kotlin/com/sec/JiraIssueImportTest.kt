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
import com.sec.source.jira.jiraJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phases 3, 4 and 5 against a real Neo4j Community image — spec §16.2 tests 1–8.
 *
 * Everything here runs the **whole importer** over a stubbed JIRA rather than calling the writer
 * directly, because two of the things most able to break are not in the Cypher: the phase order,
 * and the catalogue being carried from phase 2 to phase 3.
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

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n")) { }
    }

    // -- test 1: fresh import ---------------------------------------------------------------------

    @Test
    fun `a fresh import writes issues, projections and promoted edges`() = runBlocking {
        val run = import(issues = fixtureIssues())

        assertEquals(FIXTURE_ISSUES.toLong(), run.counters["issuesSeen"])
        assertEquals(FIXTURE_ISSUES, count(REAL_ISSUES))

        // Every issue is an SEItem, which is the only thing a future cross-source link joins on (R6).
        assertEquals(
            FIXTURE_ISSUES,
            count("MATCH (i:JiraIssue) WHERE i:SEItem AND NOT i:__UNDEFINED RETURN count(i) AS n"),
        )

        // Exactly one projection each — spec §16.2's own wording, and the reason a projection is
        // written even for an issue with nothing to project.
        assertEquals(
            FIXTURE_ISSUES,
            count("MATCH (i:JiraIssue)-[:__projection]->(p:__JiraProjection) RETURN count(p) AS n"),
        )
        // Stubs are excluded, and that exclusion is the assertion: a stub has no projection,
        // because there is nothing to project until the issue itself is imported.
        assertEquals(
            0,
            count(
                """
                MATCH (i:JiraIssue)
                WHERE NOT i:__UNDEFINED AND NOT (i)-[:__projection]->()
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

    // -- test 4: an issue deleted in JIRA ----------------------------------------------------------

    /**
     * The sweep's whole purpose, and the phase that can do the most damage if its input is wrong.
     *
     * Asserted on the projection as well as the issue, because a projection left behind is a node no
     * query can reach: nothing points at it and its only edge came from the issue that has gone.
     */
    @Test
    fun `an issue that left JIRA is removed with its projection`() = runBlocking {
        val all = issuesIn(PROJECT)
        val dropped = all.first()
        val droppedId = dropped["self"]!!.jsonPrimitive.content

        val first = import(issues = all)
        assertEquals(0L, first.counters["deleted"], "a fresh import deleted something")

        val second = import(issues = all.drop(1))

        assertEquals(1L, second.counters["deleted"])
        assertEquals(all.size - 1, count(REAL_ISSUES))
        assertEquals(0, count("MATCH (i:JiraIssue {__id: '$droppedId'}) RETURN count(i) AS n"))

        // One projection per surviving issue, and none over.
        assertEquals(
            all.size - 1,
            count("MATCH (p:__JiraProjection) RETURN count(p) AS n"),
            "a projection outlived the issue it belonged to",
        )

        // DETACH DELETE takes the edges with it; what this really asserts is that no link now runs
        // to or from something that is not there.
        assertEquals(
            0,
            count("MATCH (:JiraIssue)-[r:linkedTo]->(b) WHERE b.__id IS NULL RETURN count(r) AS n"),
        )
    }

    /**
     * A stub that lost its last link is removed with it.
     *
     * It stood for a link, and once no link points at it there is nothing it stands for — but the
     * cleanup counts *every* relationship, so a stub somebody annotated stays. That is the half
     * worth testing, because getting it wrong deletes user data (R2).
     */
    @Test
    fun `an orphaned stub is cleaned up, unless somebody annotated it`() = runBlocking {
        val all = issuesIn(PROJECT)
        import(issues = all)

        val stubs = count("MATCH (i:JiraIssue:__UNDEFINED) RETURN count(i) AS n")
        assertTrue(stubs > 1, "the fixture no longer links outside its project; this test is vacuous")

        // A note on one stub — the one thing in this system a re-import cannot reconstruct.
        val annotated = queryStrings(
            "CYPHER 25 MATCH (i:JiraIssue:__UNDEFINED) RETURN i.__id AS value ORDER BY value LIMIT 1",
            emptyMap(),
        ).single()
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (i:JiraIssue {__id: ${'$'}id})
                CREATE (i)-[:__noteOn]->(:__Meta:__Note {
                  __metaId: 'note-stub', __metaKind: 'note', __schemaVersion: 1,
                  text: 'Chase this one in JIRA', __createdBy: 'test', __createdAt: '2026-08-12'
                })
                """,
                mapOf("id" to annotated),
            ),
        ) { }

        // Every issue gone means every link gone, so every stub is now standing for nothing.
        import(issues = emptyList())

        assertEquals(
            1,
            count("MATCH (i:JiraIssue:__UNDEFINED) RETURN count(i) AS n"),
            "the annotated stub was swept with the unannotated ones, or none of them went",
        )
        assertEquals(annotated, queryStrings(
            "CYPHER 25 MATCH (i:JiraIssue:__UNDEFINED) RETURN i.__id AS value",
            emptyMap(),
        ).single())
    }

    // -- test 6: unresolved links ------------------------------------------------------------------

    /**
     * A link into a project this import never looked at (spec §9.4).
     *
     * The stub is what stops "no links" being shown for an issue that has them — a reviewer reading
     * that concludes nothing depends on this requirement, which is the opposite of the truth. Its
     * `__id` is the target's `self`, identical to the value the real issue will carry, and that is
     * what the second half of this test is about.
     */
    @Test
    fun `a link outside the imported set gets a stub carrying the target's own id`() = runBlocking {
        val mine = issuesIn(PROJECT)
        val expected = linkTargetsOutside(mine)
        assertTrue(expected.isNotEmpty(), "the fixture links nowhere outside $PROJECT; this test is vacuous")

        import(issues = mine)

        assertEquals(
            expected,
            queryStrings("CYPHER 25 MATCH (i:JiraIssue:__UNDEFINED) RETURN i.__id AS value", emptyMap()).toSet(),
        )

        // Still a JiraIssue and still an SEItem: a stub is reached by every JIRA query, and is not a
        // second kind of node the read path has to know about.
        assertEquals(
            expected.size,
            count("MATCH (i:JiraIssue:__UNDEFINED) WHERE i:SEItem RETURN count(i) AS n"),
        )
        // And it is named after the issue it stands for, not after a URL.
        assertTrue(
            queryStrings("CYPHER 25 MATCH (i:__UNDEFINED) RETURN i.__name AS value", emptyMap())
                .all { it.startsWith("<unresolved ") },
        )
        assertEquals(
            emptyList(),
            queryStrings("CYPHER 25 MATCH (i:__UNDEFINED)-[:__projection]->(p) RETURN p.__id AS value", emptyMap()),
            "a stub was given a projection, which only a real issue has",
        )
    }

    /**
     * The second half of spec §16.2 test 6: widen the import and the stub becomes the real issue.
     *
     * **No duplicate node** is the assertion that matters. The stub was keyed on the target's `self`
     * precisely so phase 3 fills it in; had it been keyed on anything else, this would pass every
     * count except the one that says how many issues there are.
     */
    @Test
    fun `importing the target resolves the stub in place, without a second node`() = runBlocking {
        val mine = issuesIn(PROJECT)
        import(issues = mine)

        val stub = queryStrings(
            "CYPHER 25 MATCH (i:JiraIssue:__UNDEFINED) RETURN i.__id AS value ORDER BY value LIMIT 1",
            emptyMap(),
        ).single()
        val before = count("MATCH (i:JiraIssue) RETURN count(i) AS n")

        // The stub's issue now comes back from the search — the stub form of "widen the project
        // list", since this JIRA answers with whatever the test hands it.
        val run = import(issues = mine + issueWithSelf(mine.first().jsonObject, stub))

        // A stub carries :JiraIssue and has no projection, both deliberately. Count the two without
        // excluding stubs and every run over a graph that has any reports a false inconsistency —
        // which is invisible in a fresh database and permanent in a real one.
        assertEquals(
            emptyList(),
            run.warnings.filter { "projection" in it },
            "the run warned about missing projections; stubs are being counted as issues",
        )

        assertEquals(before, count("MATCH (i:JiraIssue) RETURN count(i) AS n"), "a second node was created")
        assertEquals(
            0,
            count("MATCH (i:JiraIssue {__id: '$stub'}) WHERE i:__UNDEFINED RETURN count(i) AS n"),
            "the stub kept its label after the real issue arrived",
        )

        val resolved = propertiesOf(stub)
        assertTrue(
            resolved["__version"] != "unresolved",
            "the stub's placeholder version survived the import of the real issue",
        )
        assertTrue(resolved.containsKey("__projectKey"), "the resolved issue has no project key")
        assertEquals(
            1,
            count("MATCH (i:JiraIssue {__id: '$stub'})-[:__projection]->() RETURN count(*) AS n"),
            "the resolved issue got no projection",
        )
    }

    /**
     * A link a user deleted in JIRA.
     *
     * Distinct from the issue-deletion case above, and it has to be tested separately: there,
     * `DETACH DELETE` takes the edges with it and the diff never runs. Here both issues survive and
     * the only thing that can remove the edge is phase 4 noticing that this run was never told
     * about it.
     */
    @Test
    fun `a link removed in JIRA is removed from the graph`() = runBlocking {
        val all = issuesIn(PROJECT)
        // Chosen from the data rather than by position: not every issue in the export has links, and
        // a test that asserts a removal on an issue with nothing to remove asserts nothing.
        val index = all.indexOfFirst { it.links().isNotEmpty() }
        assertTrue(index >= 0, "no issue in $PROJECT has links; this test is vacuous")

        val source = all[index]
        val sourceId = source["self"]!!.jsonPrimitive.content
        val removed = source.links().size

        import(issues = all)
        val before = count("MATCH ()-[r:linkedTo]->() RETURN count(r) AS n")

        val edited = all.toMutableList()
        edited[index] = source.edit { fields -> fields["issuelinks"] = JsonArray(emptyList()) }
        import(issues = edited)

        assertEquals(
            0,
            count("MATCH (a)-[r:linkedTo]-(b) WHERE a.__id = '$sourceId' RETURN count(r) AS n"),
            "the issue still carries links it no longer reports",
        )
        assertEquals(before - removed, count("MATCH ()-[r:linkedTo]->() RETURN count(r) AS n"))
        assertEquals(all.size, count(REAL_ISSUES), "removing a link removed an issue")
    }

    // -- sub-tasks (spec §9.5) ---------------------------------------------------------------------

    /**
     * `fields.parent` as an edge, and the prune that keeps it single.
     *
     * The fixture has no sub-tasks — the export was taken from projects that do not use them — so
     * the parent is injected here. That is worth doing rather than skipping: these two statements
     * are the only ones in the importer that no other test executes, and a Cypher fault in them is
     * invisible until the first instance that has a sub-task.
     */
    @Test
    fun `a sub-task points at its parent, and moves when the parent changes`() = runBlocking {
        val all = issuesIn(PROJECT)
        val child = all[1]
        val childId = child["self"]!!.jsonPrimitive.content
        val firstParent = all[0]["self"]!!.jsonPrimitive.content
        val secondParent = all[2]["self"]!!.jsonPrimitive.content

        import(issues = all.withParent(1, refOf(all[0])))
        assertEquals(listOf(firstParent), parentsOf(childId))

        import(issues = all.withParent(1, refOf(all[2])))
        assertEquals(
            listOf(secondParent),
            parentsOf(childId),
            "the issue kept its old parent as well as the new one",
        )

        import(issues = all)
        assertEquals(emptyList(), parentsOf(childId), "the edge outlived the parent field")
    }

    /** A parent outside the imported set gets the same stub a link target does. */
    @Test
    fun `a parent this import never saw gets a stub`() = runBlocking {
        val all = issuesIn(PROJECT)
        val childId = all[1]["self"]!!.jsonPrimitive.content
        val absent = "$HOST/rest/api/2/issue/999999"

        import(issues = all.withParent(1, parentRef(absent, "$PROJECT-999999", "An epic elsewhere")))

        assertEquals(listOf(absent), parentsOf(childId))
        assertEquals(
            1,
            count("MATCH (i:JiraIssue:__UNDEFINED {__id: '$absent'}) RETURN count(i) AS n"),
            "the parent was not stubbed, so the edge points at a bare node",
        )
    }

    // -- test 7: a failure part-way through --------------------------------------------------------

    /**
     * Spec §16.2 test 7, and the assertion the whole sweep design exists for.
     *
     * A run that fails on page two has seen one page, and to the sweep that is indistinguishable
     * from an instance whose other issues were deleted. What is asserted here is a **negative**:
     * nothing went. Had the sweep run against the partial seen set it would have removed every issue
     * the failed pages carried, and the graph would look exactly like a successful import of a
     * shrinking project.
     */
    @Test
    fun `a failure part-way through leaves every issue alone`() = runBlocking {
        val all = issuesIn(PROJECT)
        import(issues = all)
        val before = count("MATCH (i:JiraIssue) RETURN count(i) AS n")

        val context = RecordingContext()
        val failure = runCatching {
            importer(all, pageSize = 4, failFromPage = 2).run(context)
        }.exceptionOrNull()

        assertTrue(failure is com.sec.source.jira.JiraFailure, "the run did not fail: $failure")
        assertEquals(before, count("MATCH (i:JiraIssue) RETURN count(i) AS n"), "the sweep ran on a partial import")
        assertEquals(
            all.size,
            count(REAL_ISSUES),
        )
    }

    // -- test 8: cancellation ----------------------------------------------------------------------

    /**
     * Spec §16.2 test 8. The same negative as test 7, reached the other way.
     *
     * Cancellation is the more dangerous of the two, because it is *ordinary*: a user presses stop,
     * and nothing about that says "do not delete the rest of the database". The framework's own
     * handling — the run ending `CANCELLED`, the event, the record — is tested in
     * `ImportRunServiceTest`; what is tested here is that the graph survives it.
     */
    @Test
    fun `a cancelled run sweeps nothing`() = runBlocking {
        val all = issuesIn(PROJECT)
        import(issues = all)
        val before = count("MATCH (i:JiraIssue) RETURN count(i) AS n")

        val context = RecordingContext(cancelAfterFirstPage = true)
        val failure = runCatching {
            importer(all.take(2), pageSize = 1).run(context)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException, "the run was not cancelled: $failure")
        assertEquals(before, count("MATCH (i:JiraIssue) RETURN count(i) AS n"), "the sweep ran after a cancellation")
    }

    // -- harness -----------------------------------------------------------------------------------

    private suspend fun import(issues: List<JsonObject>): RecordingContext {
        val context = RecordingContext()
        importer(issues).run(context)
        return context
    }

    /**
     * The importer under test, over a JIRA that answers exactly what this test wants it to.
     *
     * `maxRetries = 0` so the failure case is immediate: the retry policy is real transport
     * behaviour with its own tests, and exercising it here would buy nothing but an exponential
     * backoff inside an assertion about the graph.
     */
    private fun importer(
        issues: List<JsonObject>,
        pageSize: Int = 100,
        failFromPage: Int? = null,
    ): JiraImporter {
        val settings = JiraSettings(
            host = HOST,
            token = "t",
            deployment = JiraDeployment.DATA_CENTER,
            pageSize = pageSize,
            maxRetries = 0,
        )
        return JiraImporter(
            settings,
            JiraHttpClient(settings, stubJira(issues, pageSize, failFromPage)),
            JiraGraphWriter(graphDriver, HOST),
        )
    }

    /**
     * A JIRA that answers the four endpoints an import calls.
     *
     * The search response is rebuilt around the supplied issues rather than replayed from the
     * committed export: the fixture's own `total` is the real instance's 784, which against a stub
     * that serves everything it has would re-serve the same 50 issues sixteen times.
     *
     * It pages honestly — `startAt` slices, `maxResults` is the stride the client must re-read — so
     * a test can put a failure on page two and mean it. [failFromPage] is 1-based, to match the way
     * the log lines and the spec's own test 7 count pages.
     */
    private fun stubJira(
        issues: List<JsonObject>,
        pageSize: Int,
        failFromPage: Int?,
    ) = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/myself") -> respondJson(
                """{"name":"tester","key":"tester","displayName":"Tester","timeZone":"Europe/Berlin"}""",
            )
            path.endsWith("/issuetype") -> respondJson(sample(ISSUE_TYPES))
            path.endsWith("/field") -> respondJson(sample(FIELDS))
            else -> {
                val startAt = request.url.parameters["startAt"]?.toIntOrNull() ?: 0
                val page = startAt / pageSize + 1

                if (failFromPage != null && page >= failFromPage) {
                    // A 500 that never clears — spec §16.2 test 7 is about a permanent failure, not
                    // a flake, because a flake is what the retry policy is for.
                    respond("upstream is unwell", HttpStatusCode.InternalServerError)
                } else {
                    respondJson(
                        """{"startAt":$startAt,"maxResults":$pageSize,"total":${issues.size},""" +
                            """"issues":${JsonArray(issues.drop(startAt).take(pageSize))}}""",
                    )
                }
            }
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    /** Every issue in the committed export. */
    private fun fixtureIssues(): List<JsonObject> =
        jiraJson.parseToJsonElement(sample(SEARCH)).jsonObject["issues"]!!.jsonArray
            .map { it.jsonObject }

    /**
     * The export's issues for the named projects — how a test scopes what the stub JIRA serves for
     * one run, without touching the other 41 issues in the export.
     */
    private fun issuesIn(vararg keys: String): List<JsonObject> = fixtureIssues().filter {
        it["fields"]!!.jsonObject["project"]?.jsonObject?.get("key")?.jsonPrimitive?.content in keys
    }

    /** Every link target of [issues] that is not itself one of [issues] — i.e. what gets a stub. */
    private fun linkTargetsOutside(issues: List<JsonObject>): Set<String> {
        val own = issues.map { it["self"]!!.jsonPrimitive.content }.toSet()

        return issues.flatMap { issue ->
            (issue["fields"]!!.jsonObject["issuelinks"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val other = entry.jsonObject["outwardIssue"] ?: entry.jsonObject["inwardIssue"]
                other?.jsonObject?.get("self")?.jsonPrimitive?.content
            }
        }.toSet() - own
    }

    /**
     * A real issue standing where a stub stands: [template]'s fields under [self]'s identity.
     *
     * Its links are stripped. A copied issue would otherwise report the template's link ids from a
     * second pair of endpoints, which cannot happen in JIRA — a link id belongs to one pair — and
     * would make the assertion about node counts read as an assertion about links.
     */
    private fun issueWithSelf(template: JsonObject, self: String): JsonObject {
        val fields = template["fields"]!!.jsonObject.toMutableMap()
        fields.remove("issuelinks")
        fields.remove("parent")

        return JsonObject(
            template.toMutableMap().apply {
                put("self", JsonPrimitive(self))
                put("id", JsonPrimitive(self.substringAfterLast('/')))
                put("key", JsonPrimitive("$PROJECT-resolved"))
                put("fields", JsonObject(fields))
            },
        )
    }

    /** Replace an issue's `fields`, leaving the envelope alone. */
    private fun JsonObject.edit(block: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): JsonObject {
        val fields = this["fields"]!!.jsonObject.toMutableMap()
        block(fields)
        return JsonObject(toMutableMap().apply { put("fields", JsonObject(fields)) })
    }

    /** An issue's `issuelinks`, or none — the field is absent on an issue that has no links. */
    private fun JsonObject.links(): JsonArray =
        (this["fields"]!!.jsonObject["issuelinks"] as? JsonArray) ?: JsonArray(emptyList())

    /** [this], with the issue at [index] given [parent] as its `fields.parent`. */
    private fun List<JsonObject>.withParent(index: Int, parent: JsonObject): List<JsonObject> =
        toMutableList().also { issues ->
            issues[index] = issues[index].edit { fields -> fields["parent"] = parent }
        }

    /** Built from a fixture issue, so a real issue can be made somebody's parent. */
    private fun refOf(issue: JsonObject): JsonObject = parentRef(
        self = issue["self"]!!.jsonPrimitive.content,
        key = issue["key"]!!.jsonPrimitive.content,
        summary = issue["fields"]!!.jsonObject["summary"]?.jsonPrimitive?.content.orEmpty(),
    )

    /** The reference shape JIRA embeds under `fields.parent` — id, key, self, and a small `fields`. */
    private fun parentRef(self: String, key: String, summary: String): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(self.substringAfterLast('/')))
        put("key", JsonPrimitive(key))
        put("self", JsonPrimitive(self))
        put("fields", buildJsonObject { put("summary", JsonPrimitive(summary)) })
    }

    private suspend fun parentsOf(childId: String): List<String> = queryStrings(
        """
        CYPHER 25
        MATCH (c:JiraIssue {__id: ${'$'}id})-[:subTaskOf]->(p)
        RETURN p.__id AS value
        """,
        mapOf("id" to childId),
    )

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

    private fun sample(name: String): String = Fixtures.text(name)

    /**
     * An [ImportContext] that records instead of publishing.
     *
     * The framework's own behaviour — throttling, events, persistence — has 23 tests of its own in
     * `ImportRunServiceTest`. Re-exercising it here would make every failure in this file ambiguous
     * about which half it came from.
     */
    private class RecordingContext(private val cancelAfterFirstPage: Boolean = false) : ImportContext {
        val counters = mutableMapOf<String, Long>()
        val warnings = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var params: Map<String, String> = emptyMap()

        override val runId: String = "test-run"

        // The JIRA importer is self-driving: its input is configuration and the graph, never an
        // uploaded file, so a run of it never carries a request.
        override val request: com.sec.importer.ImportRequest? = null

        override suspend fun phase(phaseId: String) = Unit
        override suspend fun progress(current: Int, total: Int) = Unit
        override suspend fun log(message: String, level: ImportLogLevel) { logs += message }
        override suspend fun warn(message: String) { warnings += message }
        override suspend fun count(name: String, delta: Long) {
            counters[name] = (counters[name] ?: 0) + delta
        }
        override suspend fun setCount(name: String, value: Long) { counters[name] = value }
        override suspend fun params(params: Map<String, String>) { this.params += params }

        /**
         * Cancels once a page has been written, rather than after a counted number of calls.
         *
         * Counting calls would encode the number of phases into the test, so adding one would move
         * the cancellation somewhere else and quietly stop testing what this test is about — that a
         * cancellation with issues already written does not lead to a sweep.
         */
        override suspend fun ensureActive() {
            if (cancelAfterFirstPage && (counters["issuesSeen"] ?: 0) > 0) {
                throw CancellationException("cancelled by the test after the first page")
            }
        }
    }

    private companion object {
        const val HOST = "https://jira.example.com"
        const val SEARCH = Fixtures.JIRA_SEARCH
        const val FIELDS = Fixtures.JIRA_FIELDS
        const val ISSUE_TYPES = Fixtures.JIRA_ISSUE_TYPES

        /**
         * Issues that were really imported, as opposed to stubs standing in for link targets.
         *
         * A stub carries `:JiraIssue` deliberately — it is reached by every JIRA query and is not a
         * second kind of node — so every count of "the issues" has to say which it means.
         */
        const val REAL_ISSUES = "MATCH (i:JiraIssue) WHERE NOT i:__UNDEFINED RETURN count(i) AS n"

        /** The committed export's size, asserted rather than assumed by every count in this file. */
        const val FIXTURE_ISSUES = 50

        /** Distinct projects across those fifty issues — the number the dedup assertion turns on. */
        const val FIXTURE_PROJECTS = 5

        /** The project most tests scope [issuesIn] to. Nine issues carry it. */
        const val PROJECT = "ProjectCRPT"
    }
}

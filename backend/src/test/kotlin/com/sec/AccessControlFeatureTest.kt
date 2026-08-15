package com.sec

import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.security.AccessResolver
import com.sec.source.doors.ReviewProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2's acceptance question (`docs/features/access-control.md` §15): a module tagged by hand
 * in Cypher is visible to one group and invisible to another. Everything here is hand-written
 * Cypher rather than a write path, on purpose — `AccessAdminService` (the categories/grants write
 * path) is phase 6; this is proving the read side alone, against a fixture shaped the way phase 6
 * will eventually write it.
 *
 * Only `/modules/{ref}/objects` (`ReviewProjection.getModuleObjects`) is filtered this phase, so
 * that is the one read path exercised here. Every other endpoint is unfiltered until phase 4.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class AccessControlFeatureTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var reviewProjection: ReviewProjection
    private lateinit var accessResolver: AccessResolver

    private val moduleId = "acl-module"

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
        reviewProjection = ReviewProjection(graphDriver)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    /**
     * A fresh resolver per test, not just a fresh graph: [AccessResolver] caches on the group-key
     * set (`docs/features/access-control.md` §5), and two tests granting the same group key
     * different categories would otherwise read each other's cached answer.
     */
    @BeforeEach
    fun reset(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n", emptyMap())) { }
        accessResolver = AccessResolver(graphDriver)
    }

    private suspend fun seedModuleTaggedInto(categoryKey: String, everyGroup: Boolean = false) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (m:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}mid, __name: 'ACL SRD', __version: 'current'
                })
                CREATE (r:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'acl-obj-1', __moduleUrl: ${'$'}mid, __name: 'ACL-1', __version: 'current',
                    __sortKey: '000001', id: 'ACL-1', objectNumber: '1', objectLevel: 1
                })
                CREATE (c:__Meta:__AccessCategory {
                    __metaId: ${'$'}categoryKey, __metaKind: 'accessCategory', __schemaVersion: 1,
                    __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z',
                    key: ${'$'}categoryKey, name: ${'$'}categoryKey, everyGroup: ${'$'}everyGroup
                })
                CREATE (r)-[:__inAccessCategory {
                    origin: 'direct', __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'
                }]->(c)
                """.trimIndent(),
                mapOf("mid" to moduleId, "categoryKey" to categoryKey, "everyGroup" to everyGroup),
            ),
        ) { }
    }

    private suspend fun grant(groupKey: String, categoryKey: String, seesAll: Boolean = false) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MERGE (g:__Group {key: ${'$'}groupKey})
                  ON CREATE SET g.name = ${'$'}groupKey
                SET g.seesAll = ${'$'}seesAll
                WITH g
                MATCH (c:__AccessCategory {key: ${'$'}categoryKey})
                MERGE (g)-[:__mayRead {__createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'}]->(c)
                """.trimIndent(),
                mapOf("groupKey" to groupKey, "categoryKey" to categoryKey, "seesAll" to seesAll),
            ),
        ) { }
    }

    @Test
    fun `a module tagged into a category is visible to a group granted it, and invisible to another`() =
        runBlocking {
            seedModuleTaggedInto("acl-cat-thermal")
            grant("/SEC/Thermal", "acl-cat-thermal")

            val thermalAccess = accessResolver.resolve(listOf("/SEC/Thermal"))
            val avionicsAccess = accessResolver.resolve(listOf("/SEC/Avionics"))

            val visibleToThermal = reviewProjection.getModuleObjects(moduleId, thermalAccess)
            val visibleToAvionics = reviewProjection.getModuleObjects(moduleId, avionicsAccess)

            assertEquals(1, visibleToThermal.total)
            assertEquals(listOf("ACL-1"), visibleToThermal.rows.map { it.id })
            assertEquals(0, visibleToAvionics.total)
            assertTrue(visibleToAvionics.rows.isEmpty())
        }

    // R8: a user in no group sees nothing at all, including a category granted to every group —
    // everyGroup means "every group a user actually belongs to", never a bypass of group membership.
    @Test
    fun `a user in no group sees nothing, even a category granted to every group`() = runBlocking {
        seedModuleTaggedInto("acl-cat-everyone", everyGroup = true)

        val noGroupAccess = accessResolver.resolve(emptyList())

        assertEquals(0, reviewProjection.getModuleObjects(moduleId, noGroupAccess).total)
    }

    // §8.4: everyGroup grants without a __mayRead edge at all — that absence is the point.
    @Test
    fun `everyGroup categories are granted without an explicit __mayRead edge`() = runBlocking {
        seedModuleTaggedInto("acl-cat-everyone-2", everyGroup = true)

        val access = accessResolver.resolve(listOf("/SEC/SomeGroupWithNoGrantAtAll"))

        assertEquals(1, reviewProjection.getModuleObjects(moduleId, access).total)
    }

    // §16 Q4: seesAll bypasses category membership entirely, distinct from sec-admin (a role, not
    // a group property) and distinct from everyGroup (still membership-gated).
    @Test
    fun `seesAll bypasses category membership entirely`() = runBlocking {
        seedModuleTaggedInto("acl-cat-restricted")
        graphDriver.executeWrite(
            Query(
                "CYPHER 25 MERGE (g:__Group {key: ${'$'}key}) SET g.seesAll = true, g.name = ${'$'}key",
                mapOf("key" to "/SEC/All-Read"),
            ),
        ) { }

        val access = accessResolver.resolve(listOf("/SEC/All-Read"))

        assertEquals(1, reviewProjection.getModuleObjects(moduleId, access).total)
    }

    // The correct default state for a freshly imported object (§4.4, R8): no category at all is
    // invisible to everyone, administrators included — capability and visibility are separate axes.
    @Test
    fun `an untagged object is invisible to every group, seesAll included`() = runBlocking {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (m:DOORSModule:DOORSObject:SEItem {__id: ${'$'}mid, __name: 'Untagged', __version: 'current'})
                CREATE (r:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'untagged-1', __moduleUrl: ${'$'}mid, __name: 'U-1', __version: 'current',
                    __sortKey: '000001', id: 'U-1', objectNumber: '1', objectLevel: 1
                })
                """.trimIndent(),
                mapOf("mid" to moduleId),
            ),
        ) { }

        val member = accessResolver.resolve(listOf("/SEC/SomeGroup"))
        assertEquals(0, reviewProjection.getModuleObjects(moduleId, member).total)
    }
}

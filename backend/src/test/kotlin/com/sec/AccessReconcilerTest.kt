package com.sec

import com.sec.config.Neo4jSettings
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
import com.sec.security.AccessContainment
import com.sec.security.AccessReconciler
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

/**
 * Phase 3's acceptance question (`docs/features/access-control.md` §15): tagging a module
 * propagates to every one of its objects and untagging retracts them, twice, with the same
 * counts — idempotent and restartable (§8.3), which the "twice" half of every test here checks
 * directly rather than assuming.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class AccessReconcilerTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var reconciler: AccessReconciler

    private val moduleId = "recon-module"
    private val objectCount = 10

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
        reconciler = AccessReconciler(graphDriver)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
        neo4j.stop()
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (n) DETACH DELETE n", emptyMap())) { }
    }

    private suspend fun seedModuleWithObjects(count: Int, categoryKey: String, tagModule: Boolean) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (c:__Meta:__AccessCategory {
                    __metaId: ${'$'}categoryKey, __metaKind: 'accessCategory', __schemaVersion: 1,
                    __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z',
                    key: ${'$'}categoryKey, name: ${'$'}categoryKey, everyGroup: false
                })
                CREATE (m:DOORSModule:DOORSObject:SEItem {__id: ${'$'}mid, __name: 'Recon SRD', __version: 'current'})
                WITH c, m
                UNWIND range(1, ${'$'}count) AS i
                CREATE (o:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'recon-obj-' + toString(i), __moduleUrl: ${'$'}mid, __name: 'R-' + toString(i),
                    __version: 'current', __sortKey: toString(i), id: toString(i), objectNumber: toString(i),
                    objectLevel: 1
                })
                WITH c, m, collect(o) AS ignored
                FOREACH (_ IN CASE WHEN ${'$'}tagModule THEN [1] ELSE [] END |
                    CREATE (m)-[:__inAccessCategory {
                        origin: 'direct', __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'
                    }]->(c)
                )
                """.trimIndent(),
                mapOf("mid" to moduleId, "categoryKey" to categoryKey, "count" to count, "tagModule" to tagModule),
            ),
        ) { }
    }

    private suspend fun untagModule() {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (m:DOORSModule {__id: ${'$'}mid})-[r:__inAccessCategory {origin: 'direct'}]->()
                DELETE r
                """.trimIndent(),
                mapOf("mid" to moduleId),
            ),
        ) { }
    }

    private suspend fun inheritedCount(): Long =
        graphDriver.executeWrite(
            Query(
                "CYPHER 25 MATCH ()-[r:__inAccessCategory {origin: 'inherited'}]->() RETURN count(r) AS n",
                emptyMap(),
            ),
        ) { records -> records.single().get("n").asLong() }

    // By name, not sourceId: DOORS declares two containments now — objects and placeholders
    // (§16.1a) — so `single { it.sourceId == "doors" }` is ambiguous. These tests seed
    // :DOORSObject nodes, so the object containment is the one they mean.
    private val doors get() = AccessContainment.all.single { it.name == "doors.objects" }

    @Test
    fun `propagate tags every object once, and again produces zero new relationships`() = runBlocking {
        seedModuleWithObjects(objectCount, "recon-cat", tagModule = true)

        val first = reconciler.reconcile(doors)
        assertEquals(objectCount.toLong(), first.propagated)
        assertEquals(0L, first.retracted)
        assertEquals(objectCount.toLong(), inheritedCount())

        val second = reconciler.reconcile(doors)
        assertEquals(0L, second.propagated, "nothing new to propagate — every object already carries it")
        assertEquals(objectCount.toLong(), inheritedCount(), "idempotent: still exactly one edge per object")
    }

    @Test
    fun `untagging the module retracts every inherited edge, twice, with the same counts`() = runBlocking {
        seedModuleWithObjects(objectCount, "recon-cat", tagModule = true)
        reconciler.reconcile(doors)
        assertEquals(objectCount.toLong(), inheritedCount())

        untagModule()
        val firstRetract = reconciler.reconcile(doors)
        assertEquals(0L, firstRetract.propagated, "the module no longer carries a direct category to propagate")
        assertEquals(objectCount.toLong(), firstRetract.retracted)
        assertEquals(0L, inheritedCount())

        // Untagging + reconciling a second time from the same (already-clean) state retracts
        // nothing new — the "twice, with the same counts" half of the acceptance line.
        val secondRetract = reconciler.reconcile(doors)
        assertEquals(0L, secondRetract.retracted)
        assertEquals(0L, inheritedCount())
    }

    @Test
    fun `an untagged module propagates nothing`() = runBlocking {
        seedModuleWithObjects(objectCount, "recon-cat", tagModule = false)

        val result = reconciler.reconcile(doors)

        assertEquals(0L, result.propagated)
        assertEquals(0L, inheritedCount())
    }

    // §8.3: "with no default configured, a new container gets nothing" — the no-op case.
    @Test
    fun `seed is a no-op with no source default configured`() = runBlocking {
        seedModuleWithObjects(objectCount, "recon-cat", tagModule = false)

        val result = reconciler.reconcile(doors)

        assertEquals(0L, result.seeded)
    }

    // §8.3: a container an access manager deliberately emptied must not be re-filled — the
    // __accessSeeded marker, not "does it currently have a direct category", is what is checked.
    @Test
    fun `a seeded container is not re-seeded after its category is removed by hand`() = runBlocking {
        seedModuleWithObjects(objectCount, "recon-cat", tagModule = false)
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (cat:__AccessCategory {key: ${'$'}categoryKey})
                CREATE (d:__AccessDefault {sourceId: 'doors', containerLabel: 'DOORSModule'})-[:__assigns]->(cat)
                """.trimIndent(),
                mapOf("categoryKey" to "recon-cat"),
            ),
        ) { }

        val first = reconciler.reconcile(doors)
        assertEquals(1L, first.seeded, "the one never-categorised module gets the default")

        val second = reconciler.reconcile(doors)
        assertEquals(0L, second.seeded, "already seeded — not re-seeded on an unchanged pass")

        untagModule()
        val third = reconciler.reconcile(doors)
        assertEquals(
            0L, third.seeded,
            "deliberately emptied by hand — the __accessSeeded marker survives the direct edge's removal",
        )
    }
}

package com.sec

import com.sec.config.Neo4jSettings
import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.graph.GraphDriver
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.meta.MetaWriter
import com.sec.source.doors.DoorsProjection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.Query
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Acceptance criteria from docs/features/requirements-modules.md §8, exercised against a real
// Neo4j Community image (CLAUDE.md §7: never Enterprise, the constraint differences are the
// whole point).
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModulesFeatureTest {

    @Container
    private val neo4j = Neo4jContainer("neo4j:2026.01-community").withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var doorsProjection: DoorsProjection
    private lateinit var metaWriter: MetaWriter

    @BeforeAll
    fun setUp() {
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        doorsProjection = DoorsProjection(graphDriver)
        metaWriter = MetaWriter(graphDriver, doorsProjection)
    }

    @AfterAll
    fun tearDown() {
        graphDriver.close()
    }

    private fun seedModule(moduleId: String, objects: List<Map<String, Any>>): Unit = runBlocking {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (m:DOORSModule:DOORSObject:SEItem {
                    __id: ${'$'}id, __name: ${'$'}id, __version: 'current',
                    description: 'A description', moduleFullPath: '/Level 1/' + ${'$'}id,
                    prefix: 'PFX-', created_By: 'alice', created_On: '01-Jan-26',
                    last_Modified_By: 'bob', last_Modified_On: '12 March 2026',
                    url: ${'$'}id, __objectId: 'obj-1'
                })
                WITH m
                UNWIND ${'$'}objects AS obj
                CREATE (o:DOORSObject:DOORSRequirement:SEItem {
                    __id: obj.id, __moduleUrl: ${'$'}id,
                    id: obj.id, objectNumber: obj.id, objectLevel: 1
                })
                SET o += obj.attrs
                """.trimIndent(),
                mapOf("id" to moduleId, "objects" to objects),
            ),
        ) { }
    }

    private fun rawModuleProperties(moduleId: String): Map<String, Any> = runBlocking {
        graphDriver.executeRead(
            Query("CYPHER 25 MATCH (m:DOORSModule {__id: \$id}) RETURN m", mapOf("id" to moduleId)),
        ) { records -> records.single().get("m").asNode().asMap() }
    }

    @Test
    fun `save writes classification and policy without touching the module node, then clears both`() = runBlocking {
        val moduleId = "module-happy-path"
        seedModule(
            moduleId,
            listOf(
                mapOf("id" to "$moduleId-o1", "attrs" to mapOf("Object Text" to "Shall do X", "Priority" to "1")),
                mapOf("id" to "$moduleId-o2", "attrs" to mapOf("Object Text" to "Shall do Y", "Priority" to "2")),
            ),
        )
        val before = rawModuleProperties(moduleId)

        val saved = metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevelCode = "L2",
            addAttributes = listOf("Object Text"),
            removeAttributes = emptyList(),
        )
        assertEquals(SaveModuleSettingsOutcome.Saved, saved)

        // Criterion 14: the DOORSModule node's property map is byte-identical before and after.
        assertEquals(before, rawModuleProperties(moduleId))

        val detail = doorsProjection.getModuleDetail(moduleId)
        assertEquals("L2", detail?.systemLevel)

        val listRow = doorsProjection.listModules().first { it.ref == detail!!.ref }
        assertEquals("L2", listRow.systemLevel?.code)

        val attributes = doorsProjection.getModuleAttributes(moduleId).associateBy { it.name }
        assertTrue(attributes.getValue("Object Text").mandatory)
        assertTrue(!attributes.getValue("Priority").mandatory)

        // Selecting Empty removes the classification node (criterion 15).
        val cleared = metaWriter.saveModuleSettings(
            moduleId = moduleId,
            systemLevelCode = null,
            addAttributes = emptyList(),
            removeAttributes = listOf("Object Text"),
        )
        assertEquals(SaveModuleSettingsOutcome.Saved, cleared)
        assertNull(doorsProjection.getModuleDetail(moduleId)?.systemLevel)
        assertTrue(!doorsProjection.getExistingMandatoryAttributes(moduleId).contains("Object Text"))

        // Criterion 16: the one meta-deletion query removes everything this feature wrote, and
        // nothing else — the module node must still be present afterwards.
        graphDriver.executeWrite(Query("CYPHER 25 MATCH (m:__Meta) DETACH DELETE m", emptyMap())) { }
        assertEquals(before, rawModuleProperties(moduleId))
    }

    @Test
    fun `save rejects an unknown module, level or attribute without writing anything`() = runBlocking {
        val moduleId = "module-validation"
        seedModule(moduleId, listOf(mapOf("id" to "$moduleId-o1", "attrs" to mapOf("Object Text" to "Shall do X"))))

        assertEquals(
            SaveModuleSettingsOutcome.ModuleNotFound,
            metaWriter.saveModuleSettings("does-not-exist", null, emptyList(), emptyList()),
        )
        assertEquals(
            SaveModuleSettingsOutcome.InvalidSystemLevel("L9"),
            metaWriter.saveModuleSettings(moduleId, "L9", emptyList(), emptyList()),
        )
        assertEquals(
            SaveModuleSettingsOutcome.UnknownAttributes(listOf("Not A Real Attribute")),
            metaWriter.saveModuleSettings(moduleId, null, listOf("Not A Real Attribute"), emptyList()),
        )
        assertNull(doorsProjection.getModuleDetail(moduleId)?.systemLevel)
    }
}

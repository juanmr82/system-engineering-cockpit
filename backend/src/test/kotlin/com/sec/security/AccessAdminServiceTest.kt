package com.sec.security

import com.sec.config.Neo4jSettings
import com.sec.domain.CreateCategoryOutcome
import com.sec.domain.DeleteCategoryOutcome
import com.sec.domain.SaveGrantsOutcome
import com.sec.domain.SetSeesAllOutcome
import com.sec.domain.UpdateCategoryOutcome
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 6, step 1 (`docs/features/access-control.md` §10.2 screen 1): `AccessAdminService`'s
 * category CRUD, against a real Neo4j Community instance — the `access_category_key` constraint
 * `createCategory`'s pre-check stands in front of, and `AccessResolver.invalidate()`'s cache
 * behaviour, both need a real database to prove rather than assert about.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class AccessAdminServiceTest {

    private val neo4j = Neo4jContainer(
        "neo4j:" + System.getProperty("sec.test.neo4jImage", "2026.06.0-community"),
    ).withoutAuthentication()

    private lateinit var graphDriver: GraphDriver
    private lateinit var service: AccessAdminService

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
        service = AccessAdminService(graphDriver, AccessResolver(graphDriver))
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

    @Test
    fun `create returns the category with zero counts, and it appears in the list`(): Unit = runBlocking {
        val outcome = service.createCategory("doors-srd", "SRD", "The SRD module", everyGroup = false, user = "test")
        val created = assertIs<CreateCategoryOutcome.Created>(outcome).category

        assertEquals("doors-srd", created.key)
        assertEquals("SRD", created.name)
        assertEquals("The SRD module", created.description)
        assertEquals(0L, created.objectCount)
        assertEquals(0L, created.groupCount)

        assertEquals(listOf(created), service.listCategories())
    }

    @Test
    fun `create refuses a key already in use`(): Unit = runBlocking {
        service.createCategory("doors-srd", "SRD", "", everyGroup = false, user = "test")

        val outcome = service.createCategory("doors-srd", "SRD again", "", everyGroup = false, user = "test")

        assertIs<CreateCategoryOutcome.KeyInUse>(outcome)
        assertEquals(1, service.listCategories().size, "the second attempt must not have created anything")
    }

    @Test
    fun `rename changes name, description and everyGroup independently, key never included`(): Unit = runBlocking {
        val metaId = createOne("doors-srd", "SRD", "Original", everyGroup = false)

        val renamed = assertIs<UpdateCategoryOutcome.Updated>(
            service.renameCategory(metaId, name = "SRD (renamed)", description = null, everyGroup = null, user = "test"),
        ).category
        assertEquals("SRD (renamed)", renamed.name)
        assertEquals("Original", renamed.description, "null means unchanged, not cleared")
        assertEquals("doors-srd", renamed.key, "key is not a field a rename can touch")

        val flagged = assertIs<UpdateCategoryOutcome.Updated>(
            service.renameCategory(metaId, name = null, description = null, everyGroup = true, user = "test"),
        ).category
        assertEquals("SRD (renamed)", flagged.name, "unaffected by the everyGroup-only edit")
        assertTrue(flagged.everyGroup)
    }

    // Regression guard for a real bug found while writing this file: a @Test method whose Kotlin
    // body's last expression is not Unit-typed (a bare `assertIs<...>(...)` returns the narrowed
    // value) compiles to a non-void JVM method, and JUnit Jupiter silently excludes it from the
    // run rather than failing loudly — every method here declares `(): Unit` for that reason.
    @Test
    fun `rename reports not found for an unknown category`(): Unit = runBlocking {
        val outcome = service.renameCategory("no-such-id", name = "x", description = null, everyGroup = null, user = "test")
        assertIs<UpdateCategoryOutcome.NotFound>(outcome)
    }

    @Test
    fun `delete removes an unused category`(): Unit = runBlocking {
        val metaId = createOne("doors-srd", "SRD", "", everyGroup = false)

        assertIs<DeleteCategoryOutcome.Deleted>(service.deleteCategory(metaId))

        assertTrue(service.listCategories().isEmpty())
    }

    @Test
    fun `delete reports not found for an unknown category`(): Unit = runBlocking {
        assertIs<DeleteCategoryOutcome.NotFound>(service.deleteCategory("no-such-id"))
    }

    @Test
    fun `delete refuses a category still granted to a group, with the count`(): Unit = runBlocking {
        val metaId = createOne("doors-srd", "SRD", "", everyGroup = false)
        grantToGroup(metaId, "/SEC/Thermal")

        val outcome = assertIs<DeleteCategoryOutcome.InUse>(service.deleteCategory(metaId))
        assertEquals(0L, outcome.objectCount)
        assertEquals(1L, outcome.groupCount)
        assertEquals(1, service.listCategories().size, "still there — the delete must not have run")
    }

    @Test
    fun `delete refuses a category still assigned to an object, with the count`(): Unit = runBlocking {
        val metaId = createOne("doors-srd", "SRD", "", everyGroup = false)
        assignToOneObject(metaId)

        val outcome = assertIs<DeleteCategoryOutcome.InUse>(service.deleteCategory(metaId))
        assertEquals(1L, outcome.objectCount)
        assertEquals(0L, outcome.groupCount)
    }

    // -- Groups & Grants --------------------------------------------------------------------

    @Test
    fun `listGroups returns every group with its own grants`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        val catB = createOne("cat-b", "B", "", everyGroup = false)
        grantToGroup(catA, "/SEC/Thermal")
        grantToGroup(catB, "/SEC/Thermal")
        seedGroup("/SEC/Avionics")

        val groups = service.listGroups().associateBy { it.key }

        assertEquals(setOf(catA, catB), groups.getValue("/SEC/Thermal").categoryIds.toSet())
        assertTrue(groups.getValue("/SEC/Avionics").categoryIds.isEmpty())
    }

    @Test
    fun `saveGrants replaces the whole set for one group`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        val catB = createOne("cat-b", "B", "", everyGroup = false)
        seedGroup("/SEC/Thermal")

        val first = assertIs<SaveGrantsOutcome.Saved>(
            service.saveGrants("/SEC/Thermal", listOf(catA, catB), user = "test"),
        ).group
        assertEquals(setOf(catA, catB), first.categoryIds.toSet())

        val second = assertIs<SaveGrantsOutcome.Saved>(
            service.saveGrants("/SEC/Thermal", listOf(catB), user = "test"),
        ).group
        assertEquals(listOf(catB), second.categoryIds, "catA dropped, catB kept — a real replace, not a merge")
    }

    @Test
    fun `saveGrants with an empty set revokes every grant`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        grantToGroup(catA, "/SEC/Thermal")

        val outcome = assertIs<SaveGrantsOutcome.Saved>(
            service.saveGrants("/SEC/Thermal", emptyList(), user = "test"),
        ).group

        assertTrue(outcome.categoryIds.isEmpty())
    }

    @Test
    fun `saveGrants reports group not found for a group nobody has signed in as`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        assertIs<SaveGrantsOutcome.GroupNotFound>(service.saveGrants("/SEC/NoSuchGroup", listOf(catA), user = "test"))
    }

    @Test
    fun `saveGrants reports unknown categories and changes nothing`(): Unit = runBlocking {
        seedGroup("/SEC/Thermal")

        val outcome = service.saveGrants("/SEC/Thermal", listOf("no-such-category"), user = "test")

        val unknown = assertIs<SaveGrantsOutcome.UnknownCategories>(outcome)
        assertEquals(listOf("no-such-category"), unknown.categoryIds)
        assertTrue(
            service.listGroups().single { it.key == "/SEC/Thermal" }.categoryIds.isEmpty(),
            "rejected before any write — a partially-applied grant set would be worse than none",
        )
    }

    @Test
    fun `setSeesAll flips the flag and reports the group's grants alongside it`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        grantToGroup(catA, "/SEC/Thermal")

        val outcome = assertIs<SetSeesAllOutcome.Updated>(
            service.setSeesAll("/SEC/Thermal", seesAll = true, user = "test"),
        ).group

        assertTrue(outcome.seesAll)
        assertEquals(listOf(catA), outcome.categoryIds, "seesAll does not clear existing grants")
    }

    @Test
    fun `setSeesAll reports group not found`(): Unit = runBlocking {
        assertIs<SetSeesAllOutcome.GroupNotFound>(
            service.setSeesAll("/SEC/NoSuchGroup", seesAll = true, user = "test"),
        )
    }

    // -- fixtures ---------------------------------------------------------------------------

    private suspend fun createOne(key: String, name: String, description: String, everyGroup: Boolean): String =
        assertIs<CreateCategoryOutcome.Created>(
            service.createCategory(key, name, description, everyGroup, user = "test"),
        ).category.metaId

    private suspend fun seedGroup(groupKey: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MERGE (g:__Group {key: ${'$'}groupKey})
                  ON CREATE SET g.name = ${'$'}groupKey, g.seesAll = false,
                                g.firstSeenAt = '2026-01-01T00:00:00Z', g.lastSeenAt = '2026-01-01T00:00:00Z'
                """.trimIndent(),
                mapOf("groupKey" to groupKey),
            ),
        ) { }
    }

    private suspend fun grantToGroup(metaId: String, groupKey: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (c:__AccessCategory {__metaId: ${'$'}metaId})
                MERGE (g:__Group {key: ${'$'}groupKey})
                  ON CREATE SET g.name = ${'$'}groupKey, g.seesAll = false,
                                g.firstSeenAt = '2026-01-01T00:00:00Z', g.lastSeenAt = '2026-01-01T00:00:00Z'
                CREATE (g)-[:__mayRead {__createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'}]->(c)
                """.trimIndent(),
                mapOf("metaId" to metaId, "groupKey" to groupKey),
            ),
        ) { }
    }

    private suspend fun assignToOneObject(metaId: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (c:__AccessCategory {__metaId: ${'$'}metaId})
                CREATE (o:DOORSObject:DOORSRequirement:SEItem {
                    __id: 'admin-test-obj-1', __moduleUrl: 'admin-test-module', __name: 'R-1',
                    __version: 'current', __sortKey: '1', id: '1', objectNumber: '1', objectLevel: 1
                })
                CREATE (o)-[:__inAccessCategory {
                    origin: 'direct', __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'
                }]->(c)
                """.trimIndent(),
                mapOf("metaId" to metaId),
            ),
        ) { }
    }
}

package com.sec.security

import com.sec.config.Neo4jSettings
import com.sec.domain.AccessDefaultEntry
import com.sec.domain.CreateCategoryOutcome
import com.sec.domain.DeleteCategoryOutcome
import com.sec.domain.SaveDefaultsOutcome
import com.sec.domain.SaveDirectCategoriesOutcome
import com.sec.domain.SaveGrantsOutcome
import com.sec.domain.SetSeesAllOutcome
import com.sec.domain.UpdateCategoryOutcome
import com.sec.graph.GraphDriver
import com.sec.graph.executeWrite
import com.sec.meta.MetaSchema
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
    private lateinit var accessResolver: AccessResolver
    private lateinit var service: AccessAdminService

    @BeforeAll
    fun setUp() {
        neo4j.start()
        graphDriver = GraphDriver(Neo4jSettings(neo4j.boltUrl, "neo4j", "neo4j", "ignored"))
        graphDriver.verifyConnectivity()
        runBlocking { MetaSchema.apply(graphDriver) }
        accessResolver = AccessResolver(graphDriver)
        service = AccessAdminService(graphDriver, accessResolver)
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

    // -- Unassigned containers & direct categories -------------------------------------------

    @Test
    fun `listUnassignedContainers lists an uncategorised module and excludes a categorised one`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        seedDoorsModule("mod-unassigned", "Unassigned SRD", objectCount = 3, placeholderCount = 1, directCategoryMetaId = null)
        seedDoorsModule("mod-assigned", "Assigned SRD", objectCount = 2, placeholderCount = 0, directCategoryMetaId = catA)

        val unassigned = service.listUnassignedContainers(source = null, q = null)
        val ids = unassigned.map { it.containerId }

        assertTrue("mod-unassigned" in ids)
        assertTrue("mod-assigned" !in ids, "carries a direct category — must not appear in the queue")
        val row = unassigned.single { it.containerId == "mod-unassigned" }
        assertEquals("doors", row.sourceId)
        assertEquals(4L, row.invisibleItemCount, "3 objects + 1 placeholder — both containments summed (§16.1a)")
    }

    @Test
    fun `listUnassignedContainers filters by source and by name`(): Unit = runBlocking {
        seedDoorsModule("mod-unassigned", "Thermal SRD", objectCount = 1, placeholderCount = 0, directCategoryMetaId = null)
        seedJiraProject("proj-unassigned", "Avionics Board", issueCount = 2)

        val doorsOnly = service.listUnassignedContainers(source = "doors", q = null)
        assertEquals(listOf("mod-unassigned"), doorsOnly.map { it.containerId })

        val byName = service.listUnassignedContainers(source = null, q = "avionics")
        assertEquals(listOf("proj-unassigned"), byName.map { it.containerId })
    }

    // -- Containers (spec §10.2 screen 5) — "change the grant of any container on demand" -----

    @Test
    fun `listContainers lists both an uncategorised module and an already-categorised one`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        seedDoorsModule("mod-uncategorised", "Uncategorised", objectCount = 1, placeholderCount = 0, directCategoryMetaId = null)
        seedDoorsModule("mod-categorised", "Categorised", objectCount = 1, placeholderCount = 0, directCategoryMetaId = catA)

        val byId = service.listContainers(source = null, q = null).associateBy { it.containerId }

        assertTrue("mod-uncategorised" in byId, "an uncategorised container must still be found here")
        assertEquals(emptyList(), byId.getValue("mod-uncategorised").categoryIds)
        assertEquals(listOf(catA), byId.getValue("mod-categorised").categoryIds)
    }

    @Test
    fun `listContainers filters by source and by name`(): Unit = runBlocking {
        seedDoorsModule("mod-1", "Thermal SRD", objectCount = 1, placeholderCount = 0, directCategoryMetaId = null)
        seedJiraProject("proj-1", "Avionics Board", issueCount = 1)

        val doorsOnly = service.listContainers(source = "doors", q = null)
        assertEquals(listOf("mod-1"), doorsOnly.map { it.containerId })

        val byName = service.listContainers(source = null, q = "avionics")
        assertEquals(listOf("proj-1"), byName.map { it.containerId })
    }

    @Test
    fun `changing an already-categorised container's grant through saveContainerCategories is reflected in listContainers`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        val catB = createOne("cat-b", "B", "", everyGroup = false)
        seedDoorsModule("mod-1", "SRD", objectCount = 1, placeholderCount = 0, directCategoryMetaId = catA)

        service.saveContainerCategories("mod-1", listOf(catB), user = "test")

        val row = service.listContainers(source = null, q = null).single { it.containerId == "mod-1" }
        assertEquals(listOf(catB), row.categoryIds, "the write path already supports changing an existing grant")
    }

    @Test
    fun `saveContainerCategories assigns a direct category and removes the module from the queue`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        seedDoorsModule("mod-1", "SRD", objectCount = 1, placeholderCount = 0, directCategoryMetaId = null)

        val outcome = assertIs<SaveDirectCategoriesOutcome.Saved>(
            service.saveContainerCategories("mod-1", listOf(catA), user = "test"),
        )
        assertEquals(listOf(catA), outcome.categoryIds)
        assertTrue(service.listUnassignedContainers(null, null).none { it.containerId == "mod-1" })
    }

    @Test
    fun `saveContainerCategories reports not found for an unknown id`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        assertIs<SaveDirectCategoriesOutcome.AnchorNotFound>(
            service.saveContainerCategories("no-such-module", listOf(catA), user = "test"),
        )
    }

    @Test
    fun `saveContainerCategories reports unknown categories`(): Unit = runBlocking {
        seedDoorsModule("mod-1", "SRD", objectCount = 1, placeholderCount = 0, directCategoryMetaId = null)

        val outcome = service.saveContainerCategories("mod-1", listOf("no-such-category"), user = "test")

        val unknown = assertIs<SaveDirectCategoriesOutcome.UnknownCategories>(outcome)
        assertEquals(listOf("no-such-category"), unknown.categoryIds)
    }

    @Test
    fun `saveItemCategories promotes an inherited category to direct rather than duplicating the edge`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        seedItemWithInheritedCategory("item-1", catA)

        val outcome = assertIs<SaveDirectCategoriesOutcome.Saved>(
            service.saveItemCategories("item-1", listOf(catA), user = "test"),
        )

        assertEquals(listOf(catA), outcome.categoryIds)
        assertEquals(1L, inAccessCategoryEdgeCount("item-1", catA), "promoted in place, never duplicated")
    }

    @Test
    fun `saveItemCategories reports not found for an unknown id`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        assertIs<SaveDirectCategoriesOutcome.AnchorNotFound>(
            service.saveItemCategories("no-such-item", listOf(catA), user = "test"),
        )
    }

    // -- Import defaults & summary ------------------------------------------------------------

    @Test
    fun `listDefaults returns every known source-container pair, empty by default`(): Unit = runBlocking {
        val defaults = service.listDefaults()

        val pairs = defaults.map { it.sourceId to it.containerLabel }.toSet()
        assertEquals(
            setOf("doors" to "DOORSModule", "jira" to "JiraProject", "windchill" to "WindchillDocument"),
            pairs,
            "doors and doors.placeholders share one pair — three distinct pairs, not four",
        )
        assertTrue(defaults.all { it.categoryId == null }, "no :__AccessDefault node yet — every row reads empty")
    }

    @Test
    fun `saveDefaults sets a default and it round-trips through listDefaults, other pairs untouched`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)

        val outcome = assertIs<SaveDefaultsOutcome.Saved>(
            service.saveDefaults(listOf(AccessDefaultEntry("doors", "DOORSModule", catA)), user = "test"),
        )

        val byPair = outcome.defaults.associateBy { it.sourceId to it.containerLabel }
        assertEquals(catA, byPair.getValue("doors" to "DOORSModule").categoryId)
        assertEquals(null, byPair.getValue("jira" to "JiraProject").categoryId, "untouched pair stays empty")

        val reread = service.listDefaults().associateBy { it.sourceId to it.containerLabel }
        assertEquals(catA, reread.getValue("doors" to "DOORSModule").categoryId, "persisted, not just echoed back")
    }

    @Test
    fun `saveDefaults with a null categoryId clears a previously set default`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        service.saveDefaults(listOf(AccessDefaultEntry("doors", "DOORSModule", catA)), user = "test")

        service.saveDefaults(listOf(AccessDefaultEntry("doors", "DOORSModule", null)), user = "test")

        val reread = service.listDefaults().associateBy { it.sourceId to it.containerLabel }
        assertEquals(null, reread.getValue("doors" to "DOORSModule").categoryId)
    }

    @Test
    fun `saveDefaults reports an unknown source-container pair and changes nothing`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)

        val outcome = service.saveDefaults(listOf(AccessDefaultEntry("cameo", "CameoElement", catA)), user = "test")

        val unknown = assertIs<SaveDefaultsOutcome.UnknownSourceContainerPair>(outcome)
        assertEquals(listOf("cameo" to "CameoElement"), unknown.pairs)
        assertTrue(service.listDefaults().all { it.categoryId == null }, "rejected before any write")
    }

    @Test
    fun `saveDefaults reports unknown categories`(): Unit = runBlocking {
        val outcome = service.saveDefaults(
            listOf(AccessDefaultEntry("doors", "DOORSModule", "no-such-category")),
            user = "test",
        )

        val unknown = assertIs<SaveDefaultsOutcome.UnknownCategories>(outcome)
        assertEquals(listOf("no-such-category"), unknown.categoryIds)
    }

    @Test
    fun `summary reports zero counts when the graph is empty`(): Unit = runBlocking {
        // The regression case for SUMMARY_COUNTS's COUNT {} rewrite: a chained
        // MATCH...WITH count()...MATCH...RETURN count() would return zero rows (not one row of
        // zeros) here, which records.single() cannot survive. This is what proves that fix live.
        val summary = service.summary()

        assertEquals(0L, summary.categoryCount)
        assertEquals(0L, summary.groupCount)
        assertEquals(0L, summary.unassignedContainerCount)
    }

    @Test
    fun `summary counts categories, groups and unassigned containers together`(): Unit = runBlocking {
        val catA = createOne("cat-a", "A", "", everyGroup = false)
        createOne("cat-b", "B", "", everyGroup = false)
        grantToGroup(catA, "/SEC/Thermal")
        seedGroup("/SEC/Avionics")
        seedDoorsModule("mod-unassigned", "Unassigned SRD", objectCount = 1, placeholderCount = 0, directCategoryMetaId = null)
        seedDoorsModule("mod-assigned", "Assigned SRD", objectCount = 1, placeholderCount = 0, directCategoryMetaId = catA)

        val summary = service.summary()

        assertEquals(2L, summary.categoryCount)
        assertEquals(2L, summary.groupCount)
        assertEquals(1L, summary.unassignedContainerCount)
    }

    // -- phase 2's staleness gap, closed live (docs/features/access-control.md §5's "one
    // operational trap") ---------------------------------------------------------------------

    /**
     * `accessResolver` is the same instance across every test in this class (`@BeforeAll`), and
     * that is the point here: nothing is restarted and nothing is re-constructed between the
     * "before" and "after" resolves below, exactly as a running backend would answer two
     * successive `/auth/me` calls. Unique group and category keys, never reused by another test
     * in this file, so no other test's cached [AccessResolver] entry can be mistaken for this one.
     *
     * The two halves are deliberately not conflated: a caller's [AccessSet] updates the instant
     * [AccessAdminService.saveGrants] runs — that is the staleness gap phase 2 named — but an
     * *object* stays invisible until [AccessReconciler] has actually tagged it. Reconcile never
     * touches [AccessResolver], so the access set resolved before it remains valid afterwards and
     * needs no second resolve.
     */
    @Test
    fun `a category created and granted becomes visible only after reconcile, with no restart`(): Unit = runBlocking {
        seedDoorsModule(
            "mod-staleness", "Staleness Module", objectCount = 3, placeholderCount = 0, directCategoryMetaId = null,
        )

        val before = accessResolver.resolve(listOf("/SEC/Staleness"))
        assertTrue(before.categoryIds.isEmpty() && !before.seesAll, "a freshly seen group starts with nothing")

        val catA = createOne("cat-staleness", "Staleness", "", everyGroup = false)
        service.saveGrants("/SEC/Staleness", listOf(catA), user = "test")

        val access = accessResolver.resolve(listOf("/SEC/Staleness"))
        assertEquals(listOf(catA), access.categoryIds, "the grant must resolve immediately, with no restart")

        val review = ReviewProjection(graphDriver)
        assertEquals(
            0,
            review.getModuleObjects("mod-staleness", access).total,
            "granted, but nothing in the graph is tagged with the category yet",
        )

        service.saveContainerCategories("mod-staleness", listOf(catA), user = "test")
        assertEquals(
            0,
            review.getModuleObjects("mod-staleness", access).total,
            "the container itself carries the category now, but reconcile has not propagated it to its objects",
        )

        AccessReconciler(graphDriver).reconcile(AccessContainment.all.single { it.name == "doors.objects" })

        assertEquals(
            3,
            review.getModuleObjects("mod-staleness", access).total,
            "the same, already-resolved access set now sees every object reconcile just tagged",
        )
    }

    @Test
    fun `a seesAll flip is observable on the very next resolve, with no restart`(): Unit = runBlocking {
        val before = accessResolver.resolve(listOf("/SEC/StalenessSeesAll"))
        assertTrue(!before.seesAll, "a freshly seen group starts without seesAll")

        service.setSeesAll("/SEC/StalenessSeesAll", seesAll = true, user = "test")

        val after = accessResolver.resolve(listOf("/SEC/StalenessSeesAll"))
        assertTrue(after.seesAll, "the flip must be visible on the very next resolve, mirroring /auth/me, no restart")
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

    /**
     * A `CALL (m) { }` unit subquery around each `UNWIND range(1, n)`, not a bare `UNWIND` in the
     * outer clause chain: `range(1, 0)` is empty, and an `UNWIND` over an empty list terminates the
     * row stream it is part of — which would silently skip both the placeholder block and the
     * category-linking `FOREACH` below it whenever a test passes `placeholderCount = 0`. A `CALL`
     * subquery aggregates to exactly one row regardless of how many rows its `UNWIND` produced
     * inside, so the outer row survives either way — the same reason the production
     * `AccessCypher.propagate`/`retract`/`seed` all use the same shape.
     */
    private suspend fun seedDoorsModule(
        moduleId: String,
        name: String,
        objectCount: Int,
        placeholderCount: Int,
        directCategoryMetaId: String?,
    ) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (m:DOORSModule:DOORSObject:SEItem {__id: ${'$'}mid, __name: ${'$'}name, __version: 'current'})
                WITH m
                CALL (m) {
                  WITH m
                  UNWIND range(1, ${'$'}objectCount) AS i
                  CREATE (o:DOORSObject:DOORSRequirement:SEItem {
                      __id: ${'$'}mid + '-obj-' + toString(i), __moduleUrl: ${'$'}mid, __name: 'R-' + toString(i),
                      __version: 'current', __sortKey: toString(i), id: toString(i), objectNumber: toString(i),
                      objectLevel: 1
                  })
                  RETURN count(*) AS created
                }
                CALL (m) {
                  WITH m
                  UNWIND range(1, ${'$'}placeholderCount) AS j
                  CREATE (:__UNDEFINED:SEItem {__id: ${'$'}mid + '-ph-' + toString(j), __moduleUrl: ${'$'}mid})
                  RETURN count(*) AS createdPh
                }
                WITH m
                OPTIONAL MATCH (cat:__AccessCategory {__metaId: ${'$'}categoryId})
                FOREACH (_ IN CASE WHEN cat IS NOT NULL THEN [1] ELSE [] END |
                    CREATE (m)-[:__inAccessCategory {
                        origin: 'direct', __createdBy: 'test', __createdAt: '2026-01-01T00:00:00Z'
                    }]->(cat)
                )
                """.trimIndent(),
                mapOf(
                    "mid" to moduleId,
                    "name" to name,
                    "objectCount" to objectCount,
                    "placeholderCount" to placeholderCount,
                    "categoryId" to directCategoryMetaId,
                ),
            ),
        ) { }
    }

    private suspend fun seedJiraProject(projectId: String, name: String, issueCount: Int) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                CREATE (p:JiraProject:SEItem {__id: ${'$'}pid, __name: ${'$'}name, __version: 'current'})
                WITH p
                CALL (p) {
                  WITH p
                  UNWIND range(1, ${'$'}issueCount) AS i
                  CREATE (:JiraIssue:SEItem {
                      __id: ${'$'}pid + '-issue-' + toString(i), __name: 'Issue ' + toString(i),
                      __version: 'current'
                  })-[:inProject]->(p)
                }
                """.trimIndent(),
                mapOf("pid" to projectId, "name" to name, "issueCount" to issueCount),
            ),
        ) { }
    }

    private suspend fun seedItemWithInheritedCategory(itemId: String, metaId: String) {
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (c:__AccessCategory {__metaId: ${'$'}metaId})
                CREATE (o:DOORSObject:DOORSRequirement:SEItem {
                    __id: ${'$'}itemId, __moduleUrl: 'admin-test-module', __name: 'R-1',
                    __version: 'current', __sortKey: '1', id: '1', objectNumber: '1', objectLevel: 1
                })
                CREATE (o)-[:__inAccessCategory {
                    origin: 'inherited', __createdBy: 'system', __createdAt: '2026-01-01T00:00:00Z'
                }]->(c)
                """.trimIndent(),
                mapOf("itemId" to itemId, "metaId" to metaId),
            ),
        ) { }
    }

    private suspend fun inAccessCategoryEdgeCount(itemId: String, metaId: String): Long =
        graphDriver.executeWrite(
            Query(
                """
                CYPHER 25
                MATCH (o {__id: ${'$'}itemId})-[r:__inAccessCategory]->(c:__AccessCategory {__metaId: ${'$'}metaId})
                RETURN count(r) AS n
                """.trimIndent(),
                mapOf("itemId" to itemId, "metaId" to metaId),
            ),
        ) { records -> records.single().get("n").asLong() }
}

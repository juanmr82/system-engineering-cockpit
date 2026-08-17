package com.sec.security

import com.sec.domain.AccessCategorySummary
import com.sec.domain.CreateCategoryOutcome
import com.sec.domain.DeleteCategoryOutcome
import com.sec.domain.GroupWithGrants
import com.sec.domain.SaveGrantsOutcome
import com.sec.domain.SetSeesAllOutcome
import com.sec.domain.UpdateCategoryOutcome
import com.sec.domain.UuidV7
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.AccessCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import io.github.oshai.kotlinlogging.KotlinLogging
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * The write path for categories, grants, containers and defaults (`docs/features/
 * access-control.md` §9, §10.2) — phase 6, built one screen at a time; today, categories only.
 *
 * **Every public method calls [AccessResolver.invalidate] after a successful write** —
 * unconditionally, per that class's own doc comment ("after every write to categories, grants or
 * defaults"). This class does not try to reason about which particular write actually changes a
 * cached `AccessSet`; the resolver's cache is cheap to recompute and expensive to leave stale.
 *
 * Category reads and writes never carry an [AccessSet] of their own: `:__AccessCategory` is not a
 * `:SEItem` or a type label, so [security.AccessCypher.visible] does not apply to it, and every
 * caller reaching this class is already `sec-access-manager`-gated at the route (`Routes.kt`).
 */
public class AccessAdminService(
    private val graphDriver: GraphDriver,
    private val accessResolver: AccessResolver,
) {

    public suspend fun listCategories(): List<AccessCategorySummary> =
        graphDriver.executeRead(Query(AccessCypher.CATEGORIES_WITH_COUNTS)) { records ->
            records.map { it.toCategorySummary() }
        }

    public suspend fun createCategory(
        key: String,
        name: String,
        description: String,
        everyGroup: Boolean,
        user: String,
    ): CreateCategoryOutcome {
        val keyTaken = graphDriver.executeRead(
            Query(AccessCypher.CATEGORY_KEY_EXISTS, mapOf("key" to key)),
        ) { records -> records.single().get("n").asLong() > 0 }
        if (keyTaken) {
            return CreateCategoryOutcome.KeyInUse
        }

        val metaId = UuidV7.generate()
        val now = Instant.now().toString()
        graphDriver.executeWrite(
            Query(
                AccessCypher.CREATE_CATEGORY,
                mapOf(
                    "metaId" to metaId,
                    "key" to key,
                    "name" to name,
                    "description" to description,
                    "everyGroup" to everyGroup,
                    "user" to user,
                    "now" to now,
                ),
            ),
        ) { }
        accessResolver.invalidate()

        // A category is granted to nothing and assigned to nothing the instant it is created —
        // no need to re-read what CATEGORIES_WITH_COUNTS would only just confirm is zero.
        return CreateCategoryOutcome.Created(
            AccessCategorySummary(
                metaId = metaId,
                key = key,
                name = name,
                description = description,
                everyGroup = everyGroup,
                objectCount = 0,
                groupCount = 0,
            ),
        )
    }

    public suspend fun renameCategory(
        metaId: String,
        name: String?,
        description: String?,
        everyGroup: Boolean?,
        user: String,
    ): UpdateCategoryOutcome {
        val now = Instant.now().toString()
        val summary = graphDriver.executeWrite(
            Query(
                AccessCypher.UPDATE_CATEGORY,
                mapOf(
                    "metaId" to metaId,
                    "name" to name,
                    "description" to description,
                    "everyGroup" to everyGroup,
                    "user" to user,
                    "now" to now,
                ),
            ),
        ) { records -> records.firstOrNull()?.toCategorySummary() }
            ?: return UpdateCategoryOutcome.NotFound

        accessResolver.invalidate()
        return UpdateCategoryOutcome.Updated(summary)
    }

    public suspend fun deleteCategory(metaId: String): DeleteCategoryOutcome {
        val usage = graphDriver.executeRead(
            Query(AccessCypher.CATEGORY_USAGE_COUNTS, mapOf("metaId" to metaId)),
        ) { records ->
            records.firstOrNull()?.let { it.get("objectCount").asLong(0) to it.get("groupCount").asLong(0) }
        } ?: return DeleteCategoryOutcome.NotFound

        val (objectCount, groupCount) = usage
        if (objectCount > 0 || groupCount > 0) {
            return DeleteCategoryOutcome.InUse(objectCount, groupCount)
        }

        // The defensive backstop for the window between the usage read above and this write —
        // see DELETE_CATEGORY_IF_UNUSED's own doc comment.
        val deleted = graphDriver.executeWrite(
            Query(AccessCypher.DELETE_CATEGORY_IF_UNUSED, mapOf("metaId" to metaId)),
        ) { records -> records.single().get("deleted").asLong(0) > 0 }
        if (!deleted) {
            return DeleteCategoryOutcome.NotFound
        }

        accessResolver.invalidate()
        return DeleteCategoryOutcome.Deleted
    }

    // -- Groups & Grants (spec §9, §10.2 screen 2) ---------------------------------------------

    public suspend fun listGroups(): List<GroupWithGrants> =
        graphDriver.executeRead(Query(AccessCypher.GROUPS_WITH_GRANTS)) { records ->
            records.map { it.toGroupWithGrants() }
        }

    public suspend fun saveGrants(groupKey: String, categoryIds: List<String>, user: String): SaveGrantsOutcome {
        val groupExists = graphDriver.executeRead(
            Query(AccessCypher.GROUP_EXISTS, mapOf("groupKey" to groupKey)),
        ) { records -> records.single().get("n").asLong() > 0 }
        if (!groupExists) {
            return SaveGrantsOutcome.GroupNotFound
        }

        val unknown = graphDriver.executeRead(
            Query(AccessCypher.UNKNOWN_CATEGORY_IDS, mapOf("categoryIds" to categoryIds)),
        ) { records -> records.single().get("unknown").asList { it.asString() } }
        if (unknown.isNotEmpty()) {
            return SaveGrantsOutcome.UnknownCategories(unknown)
        }

        val now = Instant.now().toString()
        graphDriver.executeWrite(
            Query(
                AccessCypher.REPLACE_GRANTS,
                mapOf("groupKey" to groupKey, "categoryIds" to categoryIds, "user" to user, "now" to now),
            ),
        ) { }
        accessResolver.invalidate()

        val group = graphDriver.executeRead(
            Query(AccessCypher.GROUP_WITH_GRANTS, mapOf("groupKey" to groupKey)),
        ) { records -> records.single().toGroupWithGrants() }
        return SaveGrantsOutcome.Saved(group)
    }

    /**
     * "Audited loudly" (spec §9): `:__Group` carries no `__createdBy`/`__updatedBy` of its own
     * (it is not `:__Meta`, ADR 0016 §6.2, so it has no audit props to set), so the log line below
     * — at `WARN`, naming who and which group — is the whole of that loudness. [seesAll] is the
     * one control that turns the entire feature off for a group (§10.2), which is why it gets a
     * log level none of this class's other writes do.
     */
    public suspend fun setSeesAll(groupKey: String, seesAll: Boolean, user: String): SetSeesAllOutcome {
        val group = graphDriver.executeWrite(
            Query(AccessCypher.SET_SEES_ALL, mapOf("groupKey" to groupKey, "seesAll" to seesAll)),
        ) { records -> records.firstOrNull()?.toGroupWithGrants() } ?: return SetSeesAllOutcome.GroupNotFound

        logger.warn { "seesAll set to $seesAll for group '$groupKey' by '$user'" }
        accessResolver.invalidate()
        return SetSeesAllOutcome.Updated(group)
    }

    private fun Record.toCategorySummary(): AccessCategorySummary = AccessCategorySummary(
        metaId = get("metaId").asString(),
        key = get("key").asString(),
        name = get("name").asString(),
        description = get("description").asString(""),
        everyGroup = get("everyGroup").asBoolean(false),
        objectCount = get("objectCount").asLong(0),
        groupCount = get("groupCount").asLong(0),
    )

    private fun Record.toGroupWithGrants(): GroupWithGrants = GroupWithGrants(
        key = get("key").asString(),
        name = get("name").asString(),
        seesAll = get("seesAll").asBoolean(false),
        categoryIds = get("categoryIds").asList { it.asString() },
        firstSeenAt = get("firstSeenAt").asString(""),
        lastSeenAt = get("lastSeenAt").asString(""),
    )
}

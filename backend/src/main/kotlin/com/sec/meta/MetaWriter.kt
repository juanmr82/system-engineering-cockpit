package com.sec.meta

import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.domain.SystemLevel
import com.sec.domain.UuidV7
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ModuleCypher
import com.sec.graph.executeWrite
import com.sec.security.CurrentUser
import com.sec.source.doors.DoorsProjection
import org.neo4j.driver.Query
import java.time.Instant

// The single guarded write path for Tier-2 data (CLAUDE.md R2). Every write that touches
// :__Meta nodes and their __-prefixed relationships goes through here, and nothing else does —
// enforced in this one place, not per route. Expected failures are a sealed result, not an
// exception (CLAUDE.md §11).
public class MetaWriter(
    private val graphDriver: GraphDriver,
    private val doorsProjection: DoorsProjection,
) {
    // docs/features/requirements-modules.md §5: one transaction covers both the classification
    // and the mandatory-attribute policy diff, because the dialog's Save button is one gesture.
    public suspend fun saveModuleSettings(
        moduleId: String,
        systemLevelCode: String?,
        addAttributes: List<String>,
        removeAttributes: List<String>,
        user: String = CurrentUser.PLACEHOLDER,
    ): SaveModuleSettingsOutcome {
        if (!doorsProjection.moduleExists(moduleId)) {
            return SaveModuleSettingsOutcome.ModuleNotFound
        }

        if (systemLevelCode != null && SystemLevel.fromCode(systemLevelCode) == null) {
            return SaveModuleSettingsOutcome.InvalidSystemLevel(systemLevelCode)
        }

        // A client may not invent an attribute to add; a client may always remove one, even a
        // stale name no longer discovered on this module's objects — un-marking is always safe.
        if (addAttributes.isNotEmpty()) {
            val discovered = doorsProjection.discoverAttributeNames(moduleId).toSet()
            val unknown = addAttributes.filterNot { it in discovered }
            if (unknown.isNotEmpty()) {
                return SaveModuleSettingsOutcome.UnknownAttributes(unknown)
            }
        }

        val now = Instant.now().toString()
        val queries = buildList {
            add(systemLevelQuery(moduleId, systemLevelCode, user, now))
            if (addAttributes.isNotEmpty()) {
                add(addMandatoryPoliciesQuery(moduleId, addAttributes, user, now))
            }
            if (removeAttributes.isNotEmpty()) {
                add(Query(ModuleCypher.REMOVE_MANDATORY_POLICIES, mapOf("moduleId" to moduleId, "remove" to removeAttributes)))
            }
        }

        graphDriver.executeWrite(queries)
        return SaveModuleSettingsOutcome.Saved
    }

    private fun systemLevelQuery(moduleId: String, code: String?, user: String, now: String): Query =
        if (code != null) {
            Query(
                ModuleCypher.SET_SYSTEM_LEVEL,
                mapOf(
                    "moduleId" to moduleId,
                    "code" to code,
                    "metaId" to UuidV7.generate(),
                    "user" to user,
                    "now" to now,
                ),
            )
        } else {
            Query(ModuleCypher.CLEAR_SYSTEM_LEVEL, mapOf("moduleId" to moduleId))
        }

    private fun addMandatoryPoliciesQuery(moduleId: String, names: List<String>, user: String, now: String): Query {
        val rows = names.map { name -> mapOf("attributeName" to name, "metaId" to UuidV7.generate()) }
        return Query(
            ModuleCypher.ADD_MANDATORY_POLICIES,
            mapOf("moduleId" to moduleId, "add" to rows, "user" to user, "now" to now),
        )
    }
}

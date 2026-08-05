package com.sec.source.doors

import com.sec.api.dto.ModuleAttributeDto
import com.sec.api.dto.ModuleDetailDto
import com.sec.api.dto.ModulePropertyDto
import com.sec.api.dto.ModuleRowDto
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.domain.Aliases
import com.sec.domain.Ref
import com.sec.domain.SystemLevel
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ModuleCypher
import com.sec.graph.cypher.ReviewCypher
import com.sec.graph.executeRead
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.types.Node

// DOORS-specific read projections — module listings, runtime attribute discovery. Nothing
// DOORS-specific may exist outside this package and the DOORS-specific API routes (CLAUDE.md §1).
public class DoorsProjection(private val graphDriver: GraphDriver) {

    public suspend fun listModules(limit: Int = 500): List<ModuleRowDto> =
        graphDriver.executeRead(Query(ModuleCypher.LIST_MODULES, mapOf("limit" to limit))) { records ->
            records.map { it.toModuleRowDto() }
        }

    public suspend fun getModuleDetail(moduleId: String): ModuleDetailDto? =
        graphDriver.executeRead(Query(ModuleCypher.MODULE_DETAIL, mapOf("moduleId" to moduleId))) { records ->
            records.firstOrNull()?.toModuleDetailDto(moduleId)
        }

    public suspend fun moduleExists(moduleId: String): Boolean =
        graphDriver.executeRead(Query(ModuleCypher.MODULE_EXISTS, mapOf("moduleId" to moduleId))) { records ->
            records.isNotEmpty()
        }

    /**
     * Every attribute name any object of this module carries.
     *
     * The whole module is read, not a sample: attribute sets are *not* uniform within a module, and
     * a missed name is an attribute the settings dialog cannot offer and the table therefore cannot
     * show. See ModuleCypher.DISCOVER_ATTRIBUTES for the measurement behind that.
     */
    public suspend fun discoverAttributeNames(moduleId: String): List<String> =
        graphDriver.executeRead(
            Query(
                ModuleCypher.DISCOVER_ATTRIBUTES,
                mapOf("moduleUrl" to moduleId, "limit" to MAX_ATTRIBUTES),
            ),
        ) { records -> records.map { it.get("name").asString() } }

    public suspend fun getExistingMandatoryAttributes(moduleId: String): Set<String> =
        graphDriver.executeRead(
            Query(ModuleCypher.EXISTING_MANDATORY_POLICIES, mapOf("moduleId" to moduleId)),
        ) { records -> records.map { it.get("name").asString() }.toSet() }

    public suspend fun getExistingAttributeSettings(moduleId: String): Map<String, AttributeFlags> =
        graphDriver.executeRead(
            Query(ReviewCypher.EXISTING_ATTRIBUTE_SETTINGS, mapOf("moduleId" to moduleId)),
        ) { records ->
            records.associate {
                it.get("name").asString() to AttributeFlags(
                    visible = it.get("visible").asBoolean(false),
                    verification = it.get("verification").asBoolean(false),
                )
            }
        }

    /**
     * The module's attribute list with all three flags merged onto it (REQ_REVIEW.md §6, §9.2).
     *
     * The list itself is *discovered at runtime* and namespace-filtered server-side, so a module
     * with a different attribute set changes the review table's columns with no code change.
     * Attributes never configured default to all-false rather than being absent, so the dialog
     * shows every discovered attribute whether or not anyone has touched it.
     */
    public suspend fun getModuleAttributes(moduleId: String): List<ModuleAttributeDto> {
        val discovered = discoverAttributeNames(moduleId)
        val mandatory = getExistingMandatoryAttributes(moduleId)
        val settings = getExistingAttributeSettings(moduleId)
        return discovered.map { name ->
            val flags = settings[name]
            ModuleAttributeDto(
                name = name,
                mandatory = name in mandatory,
                visible = flags?.visible ?: false,
                verification = flags?.verification ?: false,
                // Nothing discovered is ever a fixed column: ID, Type, Name, References and
                // Comment are the view's own columns and are not DOORS attributes, so they never
                // appear in this list to begin with (REQ_REVIEW.md §5, "Fixed columns").
                fixed = false,
            )
        }
    }

    public data class AttributeFlags(
        public val visible: Boolean,
        public val verification: Boolean,
    )

    private fun Record.toModuleRowDto(): ModuleRowDto {
        val levelCode = get("levelCode").takeUnless { it.isNull() }?.asString()
        val level = levelCode?.let(SystemLevel::fromCode)
        return ModuleRowDto(
            ref = Ref.encode(get("id").asString()),
            name = get("name").asString(),
            lastModified = get("lastModified").asString(""),
            path = get("path").asString(""),
            systemLevel = level?.let { SystemLevelOptionDto(it.code, it.label) },
        )
    }

    private fun Record.toModuleDetailDto(moduleId: String): ModuleDetailDto {
        val module: Node = get("module").asNode()
        val levelCode = get("levelCode").takeUnless { it.isNull() }?.asString()
        val props = module.asMap()

        val ordered = mutableListOf<ModulePropertyDto>()
        if (props.containsKey("__version")) {
            val raw = props["__version"]?.toString().orEmpty()
            ordered += ModulePropertyDto(
                label = Aliases.propertyLabels.getValue("__version"),
                value = Aliases.renderVersionValue(raw),
            )
        }
        for ((key, label) in Aliases.modulePropertyLabels) {
            if (props.containsKey(key)) {
                ordered += ModulePropertyDto(label = label, value = props[key]?.toString().orEmpty())
            }
        }

        return ModuleDetailDto(
            ref = Ref.encode(moduleId),
            name = props["__name"]?.toString().orEmpty(),
            systemLevel = levelCode,
            properties = ordered,
        )
    }

    private companion object {
        // A cap on distinct attribute names, not on objects read. The reference modules carry 53
        // and 78; this is the "Community has no query governor" safety net, not a working limit.
        const val MAX_ATTRIBUTES = 500
    }
}

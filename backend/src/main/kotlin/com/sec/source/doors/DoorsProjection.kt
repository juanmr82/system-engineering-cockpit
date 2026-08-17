package com.sec.source.doors

import com.sec.api.dto.ModuleAttributeDto
import com.sec.api.dto.ModuleDetailDto
import com.sec.api.dto.ModulePropertyDto
import com.sec.api.dto.ModuleRowDto
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.domain.Aliases
import com.sec.domain.Prop
import com.sec.domain.Ref
import com.sec.domain.SystemLevel
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ModuleCypher
import com.sec.graph.cypher.ReviewCypher
import com.sec.graph.executeRead
import com.sec.security.AccessSet
import org.neo4j.driver.Record
import org.neo4j.driver.types.Node

// DOORS-specific read projections — module listings, runtime attribute discovery. Nothing
// DOORS-specific may exist outside this package and the DOORS-specific API routes (CLAUDE.md §1).
public class DoorsProjection(private val graphDriver: GraphDriver) {

    public suspend fun listModules(access: AccessSet, limit: Int = 500): List<ModuleRowDto> =
        graphDriver.executeRead(ModuleCypher.LIST_MODULES, mapOf("limit" to limit), access) { records ->
            records.map { it.toModuleRowDto() }
        }

    public suspend fun getModuleDetail(moduleId: String, access: AccessSet): ModuleDetailDto? =
        graphDriver.executeRead(ModuleCypher.MODULE_DETAIL, mapOf("moduleId" to moduleId), access) { records ->
            records.firstOrNull()?.toModuleDetailDto(moduleId)
        }

    /**
     * Whether this caller can see a module at all — the `404` behind every module-scoped route, and
     * the guard the three meta-write paths in `MetaWriter` share with them (§7's 404-vs-403 rule).
     *
     * Filtered, write path included, so a module cannot 404 on read and accept a write at the same
     * time. That is a deliberate, bounded pull-forward of phase 5's anchor guard: phase 5 widens it
     * to the individual anchors, and this closes the case where the *container* is invisible.
     */
    public suspend fun moduleExists(moduleId: String, access: AccessSet): Boolean =
        graphDriver.executeRead(ModuleCypher.MODULE_EXISTS, mapOf("moduleId" to moduleId), access) { records ->
            records.isNotEmpty()
        }

    /**
     * Every attribute name any object of this module carries.
     *
     * The whole module is read, not a sample: attribute sets are *not* uniform within a module, and
     * a missed name is an attribute the settings dialog cannot offer and the table therefore cannot
     * show. See ModuleCypher.DISCOVER_ATTRIBUTES for the measurement behind that.
     */
    public suspend fun discoverAttributeNames(moduleId: String, access: AccessSet): List<String> =
        graphDriver.executeRead(
            ModuleCypher.DISCOVER_ATTRIBUTES,
            mapOf("moduleUrl" to moduleId, "limit" to MAX_ATTRIBUTES),
            access,
        ) { records -> records.map { it.get("name").asString() } }

    public suspend fun getExistingMandatoryAttributes(moduleId: String, access: AccessSet): Set<String> =
        graphDriver.executeRead(
            ModuleCypher.EXISTING_MANDATORY_POLICIES, mapOf("moduleId" to moduleId), access,
        ) { records -> records.map { it.get("name").asString() }.toSet() }

    public suspend fun getExistingAttributeSettings(
        moduleId: String,
        access: AccessSet,
    ): Map<String, AttributeFlags> =
        graphDriver.executeRead(
            ReviewCypher.EXISTING_ATTRIBUTE_SETTINGS, mapOf("moduleId" to moduleId), access,
        ) { records ->
            records.associate {
                it.get("name").asString() to AttributeFlags(
                    visible = it.get("visible").asBoolean(false),
                    verification = it.get("verification").asBoolean(false),
                    excludedFromOpenPoints = it.get("excludedFromOpenPoints").asBoolean(false),
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
    public suspend fun getModuleAttributes(moduleId: String, access: AccessSet): List<ModuleAttributeDto> {
        val discovered = discoverAttributeNames(moduleId, access)
        val mandatory = getExistingMandatoryAttributes(moduleId, access)
        val settings = getExistingAttributeSettings(moduleId, access)
        return discovered.map { name ->
            val flags = settings[name]
            ModuleAttributeDto(
                name = name,
                mandatory = name in mandatory,
                visible = flags?.visible ?: false,
                verification = flags?.verification ?: false,
                excludedFromOpenPoints = flags?.excludedFromOpenPoints ?: false,
                // Two discovered attributes *are* fixed columns, because the review table's
                // Description column is built out of them: a heading shows `objectNumber` plus
                // `Object Heading`, everything else shows `Object Text` (REQ_REVIEW.md §5).
                // Offering them as optional columns would let a module show the same sentence
                // twice, in a table whose whole problem was already too many columns.
                fixed = name in DoorsAttr.description,
            )
        }
    }

    public data class AttributeFlags(
        public val visible: Boolean,
        public val verification: Boolean,
        public val excludedFromOpenPoints: Boolean,
    )

    private fun Record.toModuleRowDto(): ModuleRowDto {
        val levelCode = get("levelCode").takeUnless { it.isNull() }?.asString()
        val level = levelCode?.let(SystemLevel::fromCode)
        return ModuleRowDto(
            ref = Ref.encode(get("id").asString()),
            name = get("name").asString(),
            lastModified = get("lastModified").asString(""),
            path = get("path").asString(""),
            wordExportTitle = get("wordExportTitle").asString(""),
            wordExportNumber = get("wordExportNumber").asString(""),
            systemLevel = level?.let { SystemLevelOptionDto(it.code, it.label) },
        )
    }

    private fun Record.toModuleDetailDto(moduleId: String): ModuleDetailDto {
        val module: Node = get("module").asNode()
        val levelCode = get("levelCode").takeUnless { it.isNull() }?.asString()
        val props = module.asMap()

        val ordered = mutableListOf<ModulePropertyDto>()
        if (props.containsKey(Prop.VERSION)) {
            val raw = props[Prop.VERSION]?.toString().orEmpty()
            ordered += ModulePropertyDto(
                label = Aliases.propertyLabels.getValue(Prop.VERSION),
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
            name = props[Prop.NAME]?.toString().orEmpty(),
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

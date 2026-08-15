package com.sec.source.doors

import com.sec.api.dto.CommentDto
import com.sec.api.dto.ItemDetailDto
import com.sec.api.dto.ModuleObjectsResponseDto
import com.sec.api.dto.ModulePropertyDto
import com.sec.api.dto.ReferenceDto
import com.sec.api.dto.ReferencesDto
import com.sec.api.dto.ReviewRowDto
import com.sec.api.dto.TracesResponseDto
import com.sec.domain.Aliases
import com.sec.domain.NodeLabel
import com.sec.domain.Prop
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ReviewCypher
import com.sec.graph.executeRead
import com.sec.security.AccessSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.Value
import org.neo4j.driver.types.Node

/**
 * Read projections for the Req review table (docs/REQ_REVIEW.md).
 *
 * DOORS-specific, so it lives here and nowhere else (CLAUDE.md §1). Everything crossing the wire
 * is built from `Aliases`; no `__`-prefixed name reaches a field name or a value, except `labels`,
 * which is a state channel the UI maps to language (§5).
 */
public class ReviewProjection(private val graphDriver: GraphDriver) {

    // The type vocabulary and the consistency checks live in DoorsChecks, shared with the
    // Statistics view. They are not duplicated here: two implementations of "missing mandatory"
    // would let the review table and its own summary disagree about the same module
    // (requirements-statistics.md §3.2).

    public suspend fun getModuleObjects(
        moduleId: String,
        access: AccessSet,
        skip: Int = 0,
        limit: Int = DEFAULT_PAGE,
    ): ModuleObjectsResponseDto {
        val total = graphDriver.executeRead(
            ReviewCypher.COUNT_MODULE_OBJECTS, mapOf("moduleUrl" to moduleId), access,
        ) { records -> records.firstOrNull()?.get("total")?.asInt() ?: 0 }

        // One extra read for the whole page, not one per row: a module carries on the order of ten
        // mandatory policies and they are the same for every object in it.
        val policies = getMandatoryPolicies(moduleId)

        val rows = graphDriver.executeRead(
            ReviewCypher.MODULE_OBJECTS,
            mapOf("moduleUrl" to moduleId, "skip" to skip, "limit" to limit),
            access,
        ) { records -> records.map { it.toReviewRow(policies) } }

        return ModuleObjectsResponseDto(
            rows = withModuleNames(rows),
            total = total,
            truncated = skip + rows.size < total,
        )
    }

    /**
     * Fills in the module name on every reference, in one query for the whole page.
     *
     * Done here rather than in the row query because a page of 984 rows carrying ~400 references
     * would otherwise join the module node once per reference to fetch the same handful of names.
     */
    private suspend fun withModuleNames(rows: List<ReviewRowDto>): List<ReviewRowDto> {
        val moduleIds = rows
            .flatMap { it.references.outgoing + it.references.incoming }
            .mapNotNull { it.moduleRef }
            .distinct()
            .mapNotNull(Ref::decodeOrNull)
        if (moduleIds.isEmpty()) {
            return rows
        }

        val namesById = lookupModuleNames(moduleIds)
        if (namesById.isEmpty()) {
            return rows
        }

        return rows.map { row ->
            row.copy(
                references = row.references.copy(
                    outgoing = row.references.outgoing.map { it.named(namesById) },
                    incoming = row.references.incoming.map { it.named(namesById) },
                ),
            )
        }
    }

    private suspend fun lookupModuleNames(moduleIds: List<String>): Map<String, String> =
        graphDriver.executeRead(
            Query(ReviewCypher.MODULE_NAMES, mapOf("moduleIds" to moduleIds)),
        ) { records -> records.associate { it.get("id").asString() to it.get("name").asString("") } }

    private fun ReferenceDto.named(namesById: Map<String, String>): ReferenceDto {
        val moduleId = moduleRef?.let(Ref::decodeOrNull) ?: return this
        return copy(moduleName = namesById[moduleId])
    }

    /**
     * One item for the detail panel (§7): **one** read, and it stays one.
     *
     * The panel shows the attributes this object carries — an empty value included, rendered as
     * *Empty* rather than as a blank line. It deliberately does **not** show the module's whole
     * attribute set: that took a module-wide scan for attributes this object does not have, and
     * measured against the running service it turned an 8ms panel open into 26ms for a list nobody
     * asked for.
     */
    public suspend fun getItemDetail(itemId: String): ItemDetailDto? =
        graphDriver.executeRead(Query(ReviewCypher.ITEM_DETAIL, mapOf("itemId" to itemId))) { records ->
            records.firstOrNull()?.toItemDetail()
        }

    public suspend fun getTraces(itemId: String, incoming: Boolean, limit: Int = DEFAULT_PAGE): TracesResponseDto {
        val statement = if (incoming) ReviewCypher.ITEM_TRACES_IN else ReviewCypher.ITEM_TRACES_OUT
        val references = graphDriver.executeRead(
            Query(statement, mapOf("itemId" to itemId, "limit" to limit)),
        ) { records -> records.map { it.toReference() } }

        // Out-links are everything the source asserted. In-links are only those whose referencing
        // module has itself been imported, which is a property of what has been imported so far,
        // not of the data — so it is stated rather than left for the caller to infer.
        return TracesResponseDto(references = references, complete = !incoming)
    }

    // --- Mapping ---------------------------------------------------------------------------------

    private suspend fun getMandatoryPolicies(moduleId: String): List<DoorsChecks.MandatoryPolicy> =
        graphDriver.executeRead(
            Query(ReviewCypher.MANDATORY_POLICIES, mapOf("moduleId" to moduleId)),
        ) { records ->
            records.map { record ->
                DoorsChecks.MandatoryPolicy(
                    attributeName = record.get("attributeName").asString(""),
                    appliesToLabels = record.get("appliesToLabels").asList { it.asString() }.toSet(),
                )
            }
        }

    private fun Record.toReviewRow(policies: List<DoorsChecks.MandatoryPolicy>): ReviewRowDto {
        val node: Node = get("object").asNode()
        val labels: List<String> = get("labels").asList { it.asString() }
        val props = node.asMap()

        val commentId = get("commentId").takeUnless { it.isNull() }?.asString()

        // Built before the DTO because the Issues column is one of its readers: a link whose far
        // end DOORS deleted is a finding on *this* row, and counting it here means the count and
        // the cell that lists them can never disagree.
        val references = ReferencesDto(
            outgoing = get("outgoing").toReferences(),
            incoming = get("incoming").toReferences(),
            // True since the importer reads `__inputLinks`: a module's export states every link
            // pointing at it, so this list is as complete as that export (ADR 0012).
            incomingComplete = true,
        )
        return ReviewRowDto(
            ref = Ref.encode(props[Prop.ID]?.toString().orEmpty()),
            // `id` is DOORS's own module-local identifier: display only, never a key (R6).
            id = props[DoorsAttr.ID]?.toString().orEmpty(),
            name = props[Prop.NAME]?.toString().orEmpty(),
            // The outline number is display data, not the sort key: it is the first half of a
            // heading's Description. Rows still arrive in `__sortKey` order (§5).
            objectNumber = props[DoorsAttr.OBJECT_NUMBER]?.toString().orEmpty(),
            type = Aliases.renderType(props[Prop.TYPE_RAW]?.toString(), labels),
            labels = labels,
            level = (props[DoorsAttr.OBJECT_LEVEL] as? Number)?.toInt() ?: 1,
            requirementLike = DoorsChecks.isRequirementLike(labels),
            issues = DoorsChecks.issuesFor(policies, labels, props, references.deletedCount()),
            attributes = attributeBag(props),
            references = references,
            comment = commentId?.let {
                CommentDto(
                    metaId = it,
                    text = get("commentText").takeUnless { v -> v.isNull() }?.asString().orEmpty(),
                    updatedAt = get("commentUpdatedAt").takeUnless { v -> v.isNull() }?.asString(),
                )
            },
        )
    }

    private fun Record.toItemDetail(): ItemDetailDto {
        val node: Node = get("item").asNode()
        val labels: List<String> = get("labels").asList { it.asString() }
        val props = node.asMap()
        val moduleId = get("moduleId").takeUnless { it.isNull() }?.asString()

        // The identity triple rendered through the alias map, so the panel shows "Version:
        // Current" rather than a property called __version.
        val properties = buildList {
            props[Prop.VERSION]?.let {
                add(
                    ModulePropertyDto(
                        label = Aliases.propertyLabels.getValue(Prop.VERSION),
                        value = Aliases.renderVersionValue(it.toString()),
                    ),
                )
            }
        }

        return ItemDetailDto(
            ref = Ref.encode(props[Prop.ID]?.toString().orEmpty()),
            // Display only, never a key (R6). Absent on a placeholder, and on a module — neither
            // has a DOORS object id, and neither may fall back to __name (R5).
            id = props[DoorsAttr.ID]?.toString().takeIf { NodeLabel.UNDEFINED !in labels },
            name = props[Prop.NAME]?.toString().orEmpty(),
            type = Aliases.renderType(props[Prop.TYPE_RAW]?.toString(), labels),
            labels = labels,
            moduleRef = moduleId?.let(Ref::encode),
            moduleName = get("moduleName").takeUnless { it.isNull() }?.asString(),
            properties = properties,
            attributes = attributeBag(props),
        )
    }

    // The traces endpoints return one flat row per link, rather than the per-object lists the
    // table query builds — same shape on the wire, different shape coming out of the driver.
    private fun Record.toReference(): ReferenceDto =
        ReferenceDto(
            ref = Ref.encode(get("ref").asString("")),
            id = get("id").takeUnless { it.isNull() }?.asString(),
            resolved = get("resolved").asBoolean(true),
            deletedInSource = get("deleted").asBoolean(false),
            moduleRef = get("moduleUrl").takeUnless { it.isNull() }?.asString()?.let(Ref::encode),
            moduleName = null,
        )

    /**
     * Links on this row whose far end DOORS deleted, in either direction.
     *
     * Both directions count, because both are the same defect seen from two sides and both are
     * fixed in the same place. An outgoing one says this requirement refines something that no
     * longer exists; an incoming one says something that no longer exists claims to refine this.
     * Neither can be repaired here — the link only exists in DOORS.
     */
    private fun ReferencesDto.deletedCount(): Int =
        outgoing.count { it.deletedInSource } + incoming.count { it.deletedInSource }

    // distinctBy(ref): two refersTo edges between the same pair read as one reference to a person,
    // and nothing in the graph forbids the duplicate. Names are filled in later, per page.
    private fun Value.toReferences(): List<ReferenceDto> =
        asList { entry ->
            val map = entry.asMap()
            ReferenceDto(
                ref = Ref.encode(map["ref"]?.toString().orEmpty()),
                id = map["id"]?.toString(),
                resolved = map["resolved"] as? Boolean ?: true,
                deletedInSource = map["deleted"] as? Boolean ?: false,
                moduleRef = (map["moduleUrl"] as? String)?.let(Ref::encode),
                moduleName = null,
            )
        }.distinctBy { it.ref }

    /**
     * The dynamic DOORS attribute bag.
     *
     * The `__` filter is R5's runtime namespace filter applied server-side, so no requirements
     * table can sprout a `__sortKey` column the moment a new module is imported. [DoorsAttr]'s
     * structural three are excluded because they are already dedicated columns — and it is the
     * *same set object* attribute discovery filters on, so the two cannot drift apart.
     *
     * `""` is preserved as an empty string, never dropped: from DOORS that means "attribute exists
     * and is empty", which is different from absent (CLAUDE.md §11).
     */
    private fun attributeBag(props: Map<String, Any?>): Map<String, JsonElement> =
        props
            .filterKeys { !it.startsWith(Prop.NAMESPACE) && it !in DoorsAttr.structural }
            .mapValues { (_, value) -> value.toJson() }

    private fun Any?.toJson(): JsonElement =
        when (this) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }

    private companion object {
        const val DEFAULT_PAGE = 2_000
    }
}

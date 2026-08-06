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
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ReviewCypher
import com.sec.graph.executeRead
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

    /**
     * Labels that make an object "requirement-like" for the view's requirements-only filter
     * (REQ_REVIEW.md §11 O4). Table structure, headings and information objects are context, not
     * requirements — the same scope the mandatory-attribute check uses
     * (attribute-policy-checks.md §1), so the two views agree on what a requirement is.
     *
     * DOORSTBD counts: a sanitised export blanks `Object Type`, so every object imports as TBD and
     * excluding it would empty the table on exactly the fixtures people share (CLAUDE.md §10).
     */
    private val requirementLikeTypes = setOf("DOORSRequirement", "DOORSTBD")
    private val structuralTypes = setOf("DOORSTable", "DOORSTableRow", "DOORSTableCell")

    /**
     * The **fixed** consistency check: an object whose `Object Type` never resolved to a real type
     * carries `DOORSTBD`, and a requirement that was never classified is a defect in the export,
     * not a state to live with. Unlike the mandatory-attribute rules this one is not configurable
     * and runs on every module (`REQ_REVIEW.md` §5.3).
     *
     * Table structure is exempt because DOORS genuinely does not type the cells and rows of an
     * embedded table, and `:__UNDEFINED` is exempt because a placeholder for an object no import
     * has reached has no `Object Type` to be wrong — reporting either would be reporting on the
     * importer's own bookkeeping.
     */
    private val tbdCheckExclusions = structuralTypes + "__UNDEFINED"

    public suspend fun getModuleObjects(
        moduleId: String,
        skip: Int = 0,
        limit: Int = DEFAULT_PAGE,
    ): ModuleObjectsResponseDto {
        val total = graphDriver.executeRead(
            Query(ReviewCypher.COUNT_MODULE_OBJECTS, mapOf("moduleUrl" to moduleId)),
        ) { records -> records.firstOrNull()?.get("total")?.asInt() ?: 0 }

        // One extra read for the whole page, not one per row: a module carries on the order of ten
        // mandatory policies and they are the same for every object in it.
        val policies = getMandatoryPolicies(moduleId)

        val rows = graphDriver.executeRead(
            Query(
                ReviewCypher.MODULE_OBJECTS,
                mapOf("moduleUrl" to moduleId, "skip" to skip, "limit" to limit),
            ),
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

    /** One mandatory-attribute rule: which attribute, and which objects it applies to. */
    private data class MandatoryPolicy(
        val attributeName: String,
        val appliesToLabels: Set<String>,
    )

    private suspend fun getMandatoryPolicies(moduleId: String): List<MandatoryPolicy> =
        graphDriver.executeRead(
            Query(ReviewCypher.MANDATORY_POLICIES, mapOf("moduleId" to moduleId)),
        ) { records ->
            records.map { record ->
                MandatoryPolicy(
                    attributeName = record.get("attributeName").asString(""),
                    appliesToLabels = record.get("appliesToLabels").asList { it.asString() }.toSet(),
                )
            }
        }

    /**
     * Everything the consistency checks find wrong with one object (`REQ_REVIEW.md` §5.3).
     *
     * Fixed rules first, then the configured ones: a typed object with an unfilled attribute is a
     * different conversation from an object that was never classified at all, and the second is
     * the more fundamental problem.
     */
    private fun issuesFor(
        policies: List<MandatoryPolicy>,
        labels: List<String>,
        props: Map<String, Any?>,
    ): List<String> = buildList {
        if (labels.contains(TBD_LABEL) && labels.none { it in tbdCheckExclusions }) {
            add(TBD_ISSUE)
        }
        addAll(missingMandatory(policies, labels, props))
    }

    /**
     * The mandatory attributes this object should carry a value for and does not.
     *
     * Scope comes from the policy's own `appliesToLabels`, never from a default living here, and
     * table structure is excluded whatever the policy says — a table cell is a fragment of a
     * requirement's layout, not a requirement (`attribute-policy-checks.md` §1).
     *
     * "Missing" is absent **or** blank. DOORS `""` means "the attribute exists and is empty",
     * which the table renders as an empty cell rather than as absent (CLAUDE.md §11) — but for
     * this check the two are equally a violation, and the distinction is not surfaced.
     */
    private fun missingMandatory(
        policies: List<MandatoryPolicy>,
        labels: List<String>,
        props: Map<String, Any?>,
    ): List<String> {
        if (policies.isEmpty() || labels.any { it in structuralTypes }) {
            return emptyList()
        }
        val labelSet = labels.toSet()
        return policies
            .filter { policy -> policy.appliesToLabels.any { it in labelSet } }
            .map { it.attributeName }
            .filter { name ->
                when (val value = props[name]) {
                    null -> true
                    is String -> value.isBlank()
                    // A non-string property is present and typed — the importer coerces a handful
                    // to integers. Comparing one to "" would quietly evaluate false rather than
                    // throw, so it is answered explicitly instead of by accident.
                    else -> false
                }
            }
    }

    private fun Record.toReviewRow(policies: List<MandatoryPolicy>): ReviewRowDto {
        val node: Node = get("object").asNode()
        val labels: List<String> = get("labels").asList { it.asString() }
        val props = node.asMap()

        val commentId = get("commentId").takeUnless { it.isNull() }?.asString()

        return ReviewRowDto(
            ref = Ref.encode(props["__id"]?.toString().orEmpty()),
            // `id` is DOORS's own module-local identifier: display only, never a key (R6).
            id = props["id"]?.toString().orEmpty(),
            name = props["__name"]?.toString().orEmpty(),
            // The outline number is display data, not the sort key: it is the first half of a
            // heading's Description. Rows still arrive in `__sortKey` order (§5).
            objectNumber = props["objectNumber"]?.toString().orEmpty(),
            type = Aliases.renderType(props["__typeRaw"]?.toString(), labels),
            labels = labels,
            level = (props["objectLevel"] as? Number)?.toInt() ?: 1,
            requirementLike = labels.any { it in requirementLikeTypes } &&
                labels.none { it in structuralTypes },
            issues = issuesFor(policies, labels, props),
            attributes = attributeBag(props),
            references = ReferencesDto(
                outgoing = get("outgoing").toReferences(),
                incoming = get("incoming").toReferences(),
                incomingComplete = false,
            ),
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

        // The identity triple rendered through the alias map, so the panel shows "Baseline:
        // Current" rather than a property called __version.
        val properties = buildList {
            props["__version"]?.let {
                add(
                    ModulePropertyDto(
                        label = Aliases.propertyLabels.getValue("__version"),
                        value = Aliases.renderVersionValue(it.toString()),
                    ),
                )
            }
        }

        return ItemDetailDto(
            ref = Ref.encode(props["__id"]?.toString().orEmpty()),
            // Display only, never a key (R6). Absent on a placeholder, and on a module — neither
            // has a DOORS object id, and neither may fall back to __name (R5).
            id = props["id"]?.toString().takeIf { UNRESOLVED_LABEL !in labels },
            name = props["__name"]?.toString().orEmpty(),
            type = Aliases.renderType(props["__typeRaw"]?.toString(), labels),
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
            moduleRef = get("moduleUrl").takeUnless { it.isNull() }?.asString()?.let(Ref::encode),
            moduleName = null,
        )

    // distinctBy(ref): two refersTo edges between the same pair read as one reference to a person,
    // and nothing in the graph forbids the duplicate. Names are filled in later, per page.
    private fun Value.toReferences(): List<ReferenceDto> =
        asList { entry ->
            val map = entry.asMap()
            ReferenceDto(
                ref = Ref.encode(map["ref"]?.toString().orEmpty()),
                id = map["id"]?.toString(),
                resolved = map["resolved"] as? Boolean ?: true,
                moduleRef = (map["moduleUrl"] as? String)?.let(Ref::encode),
                moduleName = null,
            )
        }.distinctBy { it.ref }

    /**
     * The dynamic DOORS attribute bag.
     *
     * The `__` filter is R5's runtime namespace filter applied server-side, so no requirements
     * table can sprout a `__sortKey` column the moment a new module is imported. `id`,
     * `objectNumber` and `objectLevel` are excluded because they are already dedicated columns —
     * the same exclusion list as attribute discovery, kept identical on purpose.
     *
     * `""` is preserved as an empty string, never dropped: from DOORS that means "attribute exists
     * and is empty", which is different from absent (CLAUDE.md §11).
     */
    private fun attributeBag(props: Map<String, Any?>): Map<String, JsonElement> =
        props
            .filterKeys { !it.startsWith("__") && it !in RESERVED_KEYS }
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
        val RESERVED_KEYS = setOf("id", "objectNumber", "objectLevel")

        const val TBD_LABEL = "DOORSTBD"
        const val UNRESOLVED_LABEL = "__UNDEFINED"

        // The wording a reviewer reads. "Object Type" is the DOORS attribute the label came from
        // and "TBD" is that label's alias, so this sentence is displayable under R5 — no
        // `DOORSTBD` and no `__`-prefixed name reaches it.
        const val TBD_ISSUE = "Object Type shall not be TBD"
    }
}

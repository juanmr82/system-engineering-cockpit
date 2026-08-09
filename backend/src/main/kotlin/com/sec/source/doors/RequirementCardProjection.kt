package com.sec.source.doors

import com.sec.api.dto.RequirementAttributeDto
import com.sec.api.dto.RequirementCardDto
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.domain.NodeLabel
import com.sec.domain.Prop
import com.sec.domain.Ref
import com.sec.domain.SystemLevel
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.RequirementCardCypher
import com.sec.graph.executeRead
import org.neo4j.driver.Query
import org.neo4j.driver.Record
import org.neo4j.driver.types.Node

/**
 * Builds the shared requirement card (`RequirementCardDto`) for a set of items.
 *
 * One card shape, one place that builds it. The Breakdown tab draws it as a row and the dependency
 * graph draws it as a node (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1), and both read it from here — so
 * "the graph node and the breakdown row render the same field set for the same DTO" (§7) is true
 * by construction rather than by two implementations agreeing.
 *
 * DOORS-specific — the description derivation reads `Object Heading` and `Object Text` — so it
 * lives here and nowhere else (CLAUDE.md §1).
 *
 * **Nothing computed here is stored.** The verification attributes come from `:__AttributeSetting`
 * flags read fresh on every call; the description is a function of the imported graph. Storing
 * either would be storing a derivation, which R2 excludes from `:__Meta` for exactly that reason.
 */
public class RequirementCardProjection(private val graphDriver: GraphDriver) {

    /**
     * One card per id that exists, keyed by `__id`.
     *
     * An id with no node is simply absent from the result. That is what lets a caller tell "no such
     * object" from "an object with nothing attached" without a second query.
     */
    public suspend fun loadCards(ids: Collection<String>): Map<String, RequirementCardDto> =
        load(ids).cards

    /**
     * The cards **and** the raw property bag each was built from, in one round trip.
     *
     * The dependency graph's `OUTLINE_LEVEL` strategy needs `objectLevel`, which is structural and
     * therefore deliberately not on the card — the card shows what a reviewer reads, and an outline
     * depth is not that. Handing back the rows beats adding a field to the shared DTO that only one
     * consumer of one strategy ever looks at, and beats querying the same nodes twice.
     */
    public suspend fun load(ids: Collection<String>): Cards {
        if (ids.isEmpty()) {
            return Cards(emptyMap(), emptyMap())
        }

        val rows = graphDriver.executeRead(
            Query(RequirementCardCypher.NODES, mapOf("ids" to ids.toList())),
        ) { records -> records.associate { it.get("node").asNode().nodeKey() to it.toCardRow() } }

        val verification = loadVerificationAttributes(rows.values.mapNotNull { it.moduleId }.distinct())

        return Cards(
            cards = rows.mapValues { (id, row) -> row.toDto(id, verification[row.moduleId].orEmpty()) },
            rows = rows,
        )
    }

    /** One node's raw properties, for the handful of decisions the card itself does not carry. */
    public class CardRow(
        internal val props: Map<String, Any?>,
        internal val labels: List<String>,
        internal val moduleId: String?,
        internal val moduleName: String?,
        internal val levelCode: String?,
    ) {
        public fun property(name: String): Any? = props[name]

        /** The system level of the owning module, as its ordinal on the L0–L4 scale. */
        public fun systemLevelOrdinal(): Int? = levelCode?.let(SystemLevel::fromCode)?.ordinal
    }

    public class Cards(
        public val cards: Map<String, RequirementCardDto>,
        public val rows: Map<String, CardRow>,
    )

    // Not `id()`: the driver's `Node` already has one, it returns the internal element id, and a
    // member always wins over an extension — so the map would have been keyed by the wrong thing
    // with no compiler complaint (R6: a source identifier is never our key, and neither is Neo4j's).
    private fun Node.nodeKey(): String = get(Prop.ID).asString("")

    private fun Record.toCardRow(): CardRow {
        val node: Node = get("node").asNode()
        return CardRow(
            props = node.asMap(),
            labels = get("labels").asList { it.asString() },
            moduleId = get("moduleId").takeUnless { it.isNull() }?.asString(),
            moduleName = get("moduleName").takeUnless { it.isNull() }?.asString(),
            levelCode = get("levelCode").takeUnless { it.isNull() }?.asString(),
        )
    }

    /** Attribute names flagged `verification` per module (`REQ_REVIEW.md` §9.2), never per object. */
    private suspend fun loadVerificationAttributes(moduleIds: List<String>): Map<String, List<String>> {
        if (moduleIds.isEmpty()) {
            return emptyMap()
        }
        return graphDriver.executeRead(
            Query(RequirementCardCypher.VERIFICATION_ATTRIBUTES, mapOf("moduleIds" to moduleIds)),
        ) { records ->
            records
                .groupBy({ it.get("moduleId").asString() }, { it.get("name").asString("") })
                .mapValues { (_, names) -> names.filter { it.isNotBlank() } }
        }
    }

    private fun CardRow.toDto(id: String, verificationAttributes: List<String>): RequirementCardDto {
        val resolved = NodeLabel.UNDEFINED !in labels
        // Not a kind of unresolved, and deliberately independent of it: a deleted object was
        // really imported once and still carries its id, its text and its type label, so it
        // renders as the requirement it was and says, additionally, that DOORS no longer has it
        // (ADR 0012). A placeholder is the other case entirely — nothing was ever imported.
        val deletedInSource = NodeLabel.DELETED in labels
        return RequirementCardDto(
            ref = Ref.encode(id),
            // A placeholder's __name is its __id spelled out, so there is no display id to send
            // (R5) — the wording and the module name are the whole of what the UI can show.
            id = if (resolved) props[DoorsAttr.ID]?.toString() ?: props[Prop.NAME]?.toString() else null,
            level = levelCode?.let(SystemLevel::fromCode)?.let { SystemLevelOptionDto(it.code, it.label) },
            description = if (resolved) describe() else "",
            resolved = resolved,
            deletedInSource = deletedInSource,
            moduleRef = moduleId?.let(Ref::encode),
            moduleName = moduleName,
            // All of them, name and value — a module can flag more than one and showing only the
            // first would silently hide the rest.
            verificationAttributes = verificationAttributes.map { name ->
                RequirementAttributeDto(name = name, value = props[name]?.toString().orEmpty())
            },
        )
    }

    /**
     * The same Description rule the review table uses (`REQ_REVIEW.md` §5): a heading reads as its
     * outline number plus its heading text, everything else as its requirement statement.
     *
     * Derived here rather than client-side, unlike in the review table, because a breakdown or a
     * graph spans modules and sending each node's whole attribute bag to let the client apply the
     * rule would ship a 78-attribute map per node for two strings. The rule is the one in
     * `review-table.model.ts`'s `describe()`; if one changes, so does the other.
     */
    private fun CardRow.describe(): String =
        if (DoorsLabel.HEADING in labels) {
            listOf(
                props[DoorsAttr.OBJECT_NUMBER]?.toString().orEmpty(),
                props[DoorsAttr.OBJECT_HEADING]?.toString().orEmpty(),
            )
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        } else {
            // Absent falls back to the name; present-but-"" renders empty, because from DOORS that
            // means "the attribute exists and is empty" (CLAUDE.md §11).
            props[DoorsAttr.OBJECT_TEXT]?.toString() ?: props[Prop.NAME]?.toString().orEmpty()
        }
}

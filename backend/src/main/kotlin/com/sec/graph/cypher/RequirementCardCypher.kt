package com.sec.graph.cypher

import com.sec.domain.MetaProp.ATTRIBUTE_NAME
import com.sec.domain.MetaProp.CODE
import com.sec.domain.MetaProp.SCHEME
import com.sec.domain.MetaProp.VERIFICATION
import com.sec.domain.MetaValue.SYSTEM_LEVEL_SCHEME
import com.sec.domain.NodeLabel.ATTRIBUTE_SETTING
import com.sec.domain.NodeLabel.CLASSIFICATION
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.NAME
import com.sec.domain.Rel.ATTRIBUTE_SETTING_FOR
import com.sec.domain.Rel.CLASSIFIED_AS
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE

/**
 * Cypher behind the shared requirement card — the payload the Breakdown tab draws as a row and the
 * dependency graph draws as a node (docs/REQ_BREAKDOWN_GRAPH_VIEW §5.1).
 *
 * These two statements were `BreakdownCypher`'s until the graph needed exactly the same card. One
 * card shape means one statement that builds it: a view that fetched the level from somewhere else
 * would be a second answer to "what system level is this requirement at", and the two would drift.
 *
 * Every statement is CYPHER 25-prefixed and parameterised, and every graph name is interpolated
 * from a constant (ADR 0010). The transaction timeout is applied to every session in graph/Read.kt.
 */
public object RequirementCardCypher {

    /**
     * Every node of a set, by `__id`.
     *
     * The system-level badge is resolved from the node's **owning module**, not from the node — a
     * classification is anchored on the module node (CLAUDE.md §2, Shape A). A module with no
     * classification yields a null code and the card simply renders an empty outlined badge.
     */
    public const val NODES: String = """
        CYPHER 25
        UNWIND ${'$'}ids AS id
        MATCH (n:$SE_ITEM {$ID: id})
        OPTIONAL MATCH (m:$DOORS_MODULE {$ID: n.$MODULE_URL})
        OPTIONAL MATCH (m)-[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
        RETURN n            AS node,
               labels(n)    AS labels,
               m.$ID        AS moduleId,
               m.$NAME      AS moduleName,
               c.$CODE      AS levelCode
    """

    /**
     * Which attributes each module in the set has flagged as verification attributes
     * (`REQ_REVIEW.md` §9.2).
     *
     * Read fresh on every call and never stored: a module reconfigured between two clicks answers
     * correctly on the second click with no migration. Scoped to the modules the set actually
     * touches, which is a handful even for a wide graph.
     */
    public const val VERIFICATION_ATTRIBUTES: String = """
        CYPHER 25
        UNWIND ${'$'}moduleIds AS moduleId
        MATCH (:$DOORS_MODULE {$ID: moduleId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        WHERE s.$VERIFICATION = true
        RETURN moduleId          AS moduleId,
               s.$ATTRIBUTE_NAME AS name
        ORDER BY name
    """
}

package com.sec.graph.cypher

import com.sec.domain.MetaKind.CLASSIFICATION as CLASSIFICATION_KIND
import com.sec.domain.MetaKind.POLICY as POLICY_KIND
import com.sec.domain.MetaProp.APPLIES_TO_LABELS
import com.sec.domain.MetaProp.ATTRIBUTE_NAME
import com.sec.domain.MetaProp.CODE
import com.sec.domain.MetaProp.RULE
import com.sec.domain.MetaProp.SCHEME
import com.sec.domain.MetaValue.CURRENT_SCHEMA_VERSION
import com.sec.domain.MetaValue.MANDATORY_RULE
import com.sec.domain.MetaValue.SYSTEM_LEVEL_SCHEME
import com.sec.domain.NodeLabel.CLASSIFICATION
import com.sec.domain.NodeLabel.DELETED
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.POLICY
import com.sec.domain.Prop.CREATED_AT
import com.sec.domain.Prop.CREATED_BY
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.META_ID
import com.sec.domain.Prop.META_KIND
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.NAME
import com.sec.domain.Prop.NAMESPACE
import com.sec.domain.Prop.SCHEMA_VERSION
import com.sec.domain.Prop.UPDATED_AT
import com.sec.domain.Prop.UPDATED_BY
import com.sec.domain.Rel.CLASSIFIED_AS
import com.sec.domain.Rel.POLICY_FOR
import com.sec.source.doors.DoorsAttr.ID as DOORS_ID
import com.sec.source.doors.DoorsAttr.OBJECT_LEVEL
import com.sec.source.doors.DoorsAttr.OBJECT_NUMBER
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT
import com.sec.source.doors.DoorsLabel.REQUIREMENT as DOORS_REQUIREMENT
import com.sec.source.doors.DoorsModuleAttr.FULL_PATH
import com.sec.source.doors.DoorsModuleAttr.LAST_MODIFIED_ON
import com.sec.source.doors.DoorsModuleAttr.WORD_DOC_NUMBER
import com.sec.source.doors.DoorsModuleAttr.WORD_DOC_TITLE

// Cypher for docs/features/requirements-modules.md §5.3. Every statement is CYPHER 25-prefixed
// and parameterised, and every read carries a LIMIT. The transaction timeout that the other half
// of CLAUDE.md §7 asks for is not here — it is applied to every session in graph/Read.kt and
// graph/Write.kt, from GraphDriver, so no statement in this file can be issued without one.
//
// Every graph name is interpolated from a constant (ADR 0010), so a rename is one edit in
// domain/GraphNames.kt or source/doors/DoorsNames.kt. A bare $NAME is a *name*; the escaped
// form is a query *parameter*. The two look alike and are not.
public object ModuleCypher {
    // The Word-export title and number are read from the module node by name rather than being
    // discovered: they are :DOORSModule properties, not object attributes, so they never come back
    // from DISCOVER_ATTRIBUTES and the Modules table has to ask for them.
    public const val LIST_MODULES: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE)
        OPTIONAL MATCH (m)-[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
        RETURN m.$ID                  AS id,
               m.$NAME                AS name,
               m['$LAST_MODIFIED_ON'] AS lastModified,
               m['$FULL_PATH']        AS path,
               m['$WORD_DOC_TITLE']   AS wordExportTitle,
               m['$WORD_DOC_NUMBER']  AS wordExportNumber,
               c.$CODE                AS levelCode
        ORDER BY m.$NAME
        LIMIT ${'$'}limit
    """

    public const val MODULE_DETAIL: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        OPTIONAL MATCH (m)-[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
        RETURN m AS module, c.$CODE AS levelCode
        LIMIT 1
    """

    // __moduleUrl is what a module's own objects store to point back at it; a module's plain
    // `url` property carries the same value as its __id (requirements-modules.md §4.1), so the
    // module's own __id is the value to bind here.
    // Every object of the module is read, not a sample of them.
    //
    // This used to take the first 25 objects on the theory that attribute sets are uniform within a
    // module. They are not: in the reference SRD module 774 of 977 objects carry `Object Text` and
    // 203 do not, and the 25 the planner happened to return were among the 203 — so the module's
    // most important attribute was missing from the settings dialog entirely, with no way to show
    // it in the table. The sample also bought nothing: measured through the driver, sampling 25 and
    // scanning all 977 both answer in ~17ms, because the cost is the index seek, not the rows.
    //
    // The LIMIT is on the distinct attribute names rather than on the objects scanned, which is
    // what makes it a safety net (CLAUDE.md §7, no query governor) instead of a correctness hole.
    public const val DISCOVER_ATTRIBUTES: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT o:$DELETED
        UNWIND keys(o) AS k
        WITH DISTINCT k
        WHERE NOT k STARTS WITH '$NAMESPACE'
          AND NOT k IN ['$DOORS_ID', '$OBJECT_NUMBER', '$OBJECT_LEVEL']
        RETURN k AS name
        ORDER BY k
        LIMIT ${'$'}limit
    """

    public const val EXISTING_MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$POLICY_FOR]->(p:$META:$POLICY)
        WHERE p.$RULE = '$MANDATORY_RULE'
        RETURN p.$ATTRIBUTE_NAME AS name
    """

    // Which of these ids are actually objects of this module. The comment write path uses it to
    // refuse an arbitrary __id in a request body — without it, a crafted payload could attach a
    // note to any node in the graph, which is not what "comment on a row you loaded" means.
    public const val MODULE_OBJECT_IDS: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE o.$ID IN ${'$'}itemIds AND NOT o:$DOORS_MODULE
        RETURN o.$ID AS id
    """

    public const val MODULE_EXISTS: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        RETURN m.$ID AS id
        LIMIT 1
    """

    public const val SET_SYSTEM_LEVEL: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        MERGE (m)-[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
          ON CREATE SET c.$META_ID        = ${'$'}metaId,
                        c.$META_KIND      = '$CLASSIFICATION_KIND',
                        c.$SCHEMA_VERSION = $CURRENT_SCHEMA_VERSION,
                        c.$CREATED_BY     = ${'$'}user,
                        c.$CREATED_AT     = ${'$'}now
        SET c.$CODE       = ${'$'}code,
            c.$UPDATED_BY = ${'$'}user,
            c.$UPDATED_AT = ${'$'}now
    """

    public const val CLEAR_SYSTEM_LEVEL: String = """
        CYPHER 25
        MATCH (:$DOORS_MODULE {$ID: ${'$'}moduleId})
              -[:$CLASSIFIED_AS]->(c:$META:$CLASSIFICATION {$SCHEME: '$SYSTEM_LEVEL_SCHEME'})
        DETACH DELETE c
    """

    public const val ADD_MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        UNWIND ${'$'}add AS row
        MERGE (m)-[:$POLICY_FOR]->(p:$META:$POLICY {$ATTRIBUTE_NAME: row.attributeName,
                                                    $RULE: '$MANDATORY_RULE'})
          ON CREATE SET p.$META_ID           = row.metaId,
                        p.$META_KIND         = '$POLICY_KIND',
                        p.$SCHEMA_VERSION    = $CURRENT_SCHEMA_VERSION,
                        p.$APPLIES_TO_LABELS = ['$DOORS_REQUIREMENT'],
                        p.$CREATED_BY        = ${'$'}user,
                        p.$CREATED_AT        = ${'$'}now
        SET p.$UPDATED_BY = ${'$'}user,
            p.$UPDATED_AT = ${'$'}now
    """

    public const val REMOVE_MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$POLICY_FOR]->(p:$META:$POLICY)
        WHERE p.$RULE = '$MANDATORY_RULE' AND p.$ATTRIBUTE_NAME IN ${'$'}remove
        DETACH DELETE p
    """
}

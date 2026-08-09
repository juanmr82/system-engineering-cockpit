package com.sec.graph.cypher

import com.sec.domain.MetaKind.ATTRIBUTE_SETTING as ATTRIBUTE_SETTING_KIND
import com.sec.domain.MetaKind.NOTE as NOTE_KIND
import com.sec.domain.MetaProp.APPLIES_TO_LABELS
import com.sec.domain.MetaProp.ATTRIBUTE_NAME
import com.sec.domain.MetaProp.RULE
import com.sec.domain.MetaProp.TEXT
import com.sec.domain.MetaProp.VERIFICATION
import com.sec.domain.MetaProp.VISIBLE
import com.sec.domain.MetaValue.CURRENT_SCHEMA_VERSION
import com.sec.domain.MetaValue.MANDATORY_RULE
import com.sec.domain.NodeLabel.ATTRIBUTE_SETTING
import com.sec.domain.NodeLabel.DELETED
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.NOTE
import com.sec.domain.NodeLabel.POLICY
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.CREATED_AT
import com.sec.domain.Prop.CREATED_BY
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.META_ID
import com.sec.domain.Prop.META_KIND
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.NAME
import com.sec.domain.Prop.SCHEMA_VERSION
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Prop.UPDATED_AT
import com.sec.domain.Prop.UPDATED_BY
import com.sec.domain.Rel.ATTRIBUTE_SETTING_FOR
import com.sec.domain.Rel.NOTE_ON
import com.sec.domain.Rel.POLICY_FOR
import com.sec.source.doors.DoorsAttr.ID as DOORS_ID
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT
import com.sec.source.doors.DoorsLabel.REQUIREMENT as DOORS_REQUIREMENT
import com.sec.source.doors.DoorsRel.REFERS_TO

// Cypher for docs/REQ_REVIEW.md — the Req review table, its references and its comments.
// Every statement is CYPHER 25-prefixed and parameterised; the transaction timeout is applied to
// every session in graph/Read.kt and graph/Write.kt, so nothing here can be issued without one.
//
// Every graph name is interpolated from a constant (ADR 0010). A bare $NAME is a *name*; the
// escaped form is a query *parameter*.
public object ReviewCypher {

    // One row per object of a module, in DOORS document order.
    //
    // ORDER BY __sortKey, never objectNumber: the outline number does not sort correctly as a
    // string, which is the entire reason __sortKey exists (R3, CLAUDE.md §11).
    //
    // References are collected in the same statement rather than in a second round trip per row —
    // 984 rows would otherwise be 984 queries. `resolved` is false when the target is a
    // placeholder the importer created for an object no import has reached yet; the frontend
    // renders those as "Not yet imported" and does not link them (§5.1).
    //
    // Incoming links are deliberately included here and are *incomplete by design*: importers
    // ingest out-links only, so an incoming edge exists only when the referencing module has
    // itself been imported. That caveat is surfaced in the UI, never silently (SE_ITEM_SCHEMA §8.2).
    //
    // `deleted` is the other thing a reference can be, and it is not a kind of unresolved: the
    // target is a real imported object with its `id` and its text, which a later export of its own
    // module stopped containing. DOORS deleted it and left this link behind. So `resolved` stays
    // true and the row still shows what it points at — what changes is that the link itself is the
    // defect, and the only fix is in DOORS (ADR 0012).
    // NOT o:$DELETED throughout: an object DOORS deleted is not part of the module any more, and
    // a module listing that still contained it would be showing a document DOORS does not have.
    // It stays in the graph only as the far end of the links DOORS left behind, and it is reached
    // from those links -- never by listing the module (ADR 0012).
    public const val MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT o:$DOORS_MODULE AND NOT o:$DELETED
        WITH o
        ORDER BY o.$SORT_KEY
        SKIP ${'$'}skip
        LIMIT ${'$'}limit
        WITH o,
             [(o)-[:$REFERS_TO]->(out:$SE_ITEM) | {
                 ref: out.$ID,
                 id: CASE WHEN out:$UNDEFINED THEN null ELSE coalesce(out.$DOORS_ID, out.$NAME) END,
                 resolved: NOT out:$UNDEFINED,
                 deleted: out:$DELETED,
                 moduleUrl: out.$MODULE_URL
             }] AS outgoing,
             [(o)<-[:$REFERS_TO]-(inc:$SE_ITEM) | {
                 ref: inc.$ID,
                 id: CASE WHEN inc:$UNDEFINED THEN null ELSE coalesce(inc.$DOORS_ID, inc.$NAME) END,
                 resolved: NOT inc:$UNDEFINED,
                 deleted: inc:$DELETED,
                 moduleUrl: inc.$MODULE_URL
             }] AS incoming
        OPTIONAL MATCH (o)-[:$NOTE_ON]->(n:$META:$NOTE)
        RETURN o                AS object,
               labels(o)        AS labels,
               outgoing         AS outgoing,
               incoming         AS incoming,
               n.$META_ID       AS commentId,
               n.$TEXT          AS commentText,
               n.$UPDATED_AT    AS commentUpdatedAt
    """

    /**
     * The module's mandatory-attribute policies (`attribute-policy-checks.md` §4, step 1).
     *
     * Read once per request and evaluated against each row's property map in Kotlin — the row
     * query above already returns every property of every object, so checking them costs a map
     * lookup per (object × mandatory attribute) and no second scan of the module.
     *
     * **The result is never stored.** A violation is a function of (imported data × policy), and
     * the policy is Tier-2 configuration a user changes from the settings dialog at any moment —
     * so it is not a property of the import and cannot be computed at import time without going
     * stale on the next checkbox. R2 excludes derived data from `:__Meta` for exactly this reason.
     *
     * `appliesToLabels` is read, never assumed: a policy that applies to everything is a policy
     * nobody can reason about (CLAUDE.md R2). The default matches the one the write path stores.
     */
    public const val MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$POLICY_FOR]->(p:$META:$POLICY)
        WHERE p.$RULE = '$MANDATORY_RULE'
        RETURN p.$ATTRIBUTE_NAME                                    AS attributeName,
               coalesce(p.$APPLIES_TO_LABELS, ['$DOORS_REQUIREMENT']) AS appliesToLabels
    """

    // Names for the modules a page's references point into, fetched once for the whole page rather
    // than joined per reference. An unresolved target names a module that has usually *not* been
    // imported, in which case there is no name to find and the UI says "Not yet imported" without
    // one — but when the module is present, naming it is what makes the message actionable (§5.1).
    public const val MODULE_NAMES: String = """
        CYPHER 25
        UNWIND ${'$'}moduleIds AS moduleId
        MATCH (m:$DOORS_MODULE {$ID: moduleId})
        RETURN m.$ID AS id, m.$NAME AS name
    """

    // Counted separately from the page so the client can show "n of m" without holding every row.
    public const val COUNT_MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT o:$DOORS_MODULE AND NOT o:$DELETED
        RETURN count(o) AS total
    """

    // One item for the detail panel (§7). The module is returned as its __name so the panel can
    // render __moduleUrl as a link labelled with the module's name, per the R5 alias map.
    public const val ITEM_DETAIL: String = """
        CYPHER 25
        MATCH (i:$SE_ITEM {$ID: ${'$'}itemId})
        OPTIONAL MATCH (m:$DOORS_MODULE {$ID: i.$MODULE_URL})
        RETURN i          AS item,
               labels(i)  AS labels,
               m.$NAME    AS moduleName,
               m.$ID      AS moduleId
        LIMIT 1
    """

    public const val ITEM_TRACES_OUT: String = """
        CYPHER 25
        MATCH (:$SE_ITEM {$ID: ${'$'}itemId})-[:$REFERS_TO]->(t:$SE_ITEM)
        RETURN t.$ID        AS ref,
               CASE WHEN t:$UNDEFINED THEN null ELSE coalesce(t.$DOORS_ID, t.$NAME) END AS id,
               NOT t:$UNDEFINED         AS resolved,
               t:$DELETED               AS deleted,
               t.$MODULE_URL            AS moduleUrl
        ORDER BY id
        LIMIT ${'$'}limit
    """

    public const val ITEM_TRACES_IN: String = """
        CYPHER 25
        MATCH (:$SE_ITEM {$ID: ${'$'}itemId})<-[:$REFERS_TO]-(t:$SE_ITEM)
        RETURN t.$ID        AS ref,
               CASE WHEN t:$UNDEFINED THEN null ELSE coalesce(t.$DOORS_ID, t.$NAME) END AS id,
               NOT t:$UNDEFINED         AS resolved,
               t:$DELETED               AS deleted,
               t.$MODULE_URL            AS moduleUrl
        ORDER BY id
        LIMIT ${'$'}limit
    """

    // --- Comments (Tier 2, Shape A) -------------------------------------------------------------

    // Exactly one comment per object (§5.2). Community cannot constrain that, so the write path
    // enforces it: MERGE on the *relationship* rather than on the node means an object that
    // already has a note updates that node instead of gaining a second one. __metaId is only set
    // ON CREATE, so re-saving a comment never rewrites its identity, and __createdBy/__createdAt
    // survive every later edit.
    public const val UPSERT_COMMENTS: String = """
        CYPHER 25
        UNWIND ${'$'}comments AS c
        MATCH (i:$SE_ITEM {$ID: c.itemId})
        MERGE (i)-[:$NOTE_ON]->(n:$META:$NOTE)
          ON CREATE SET n.$META_ID   = c.metaId,
                        n.$CREATED_BY = ${'$'}user,
                        n.$CREATED_AT = ${'$'}now
        SET n.$META_KIND      = '$NOTE_KIND',
            n.$SCHEMA_VERSION = $CURRENT_SCHEMA_VERSION,
            n.$TEXT           = c.text,
            n.$UPDATED_BY     = ${'$'}user,
            n.$UPDATED_AT     = ${'$'}now
    """

    // Clearing a comment deletes the node rather than storing "", so MATCH (m:__Meta) stays a true
    // inventory of what the application knows (§5.2).
    public const val DELETE_COMMENTS: String = """
        CYPHER 25
        UNWIND ${'$'}itemIds AS itemId
        MATCH (:$SE_ITEM {$ID: itemId})-[:$NOTE_ON]->(n:$META:$NOTE)
        DETACH DELETE n
    """

    // Read back after the write so the client can clear its dirty marks without reloading the
    // table (§8): the server, not the client, decides what was stored.
    public const val READ_COMMENTS: String = """
        CYPHER 25
        UNWIND ${'$'}itemIds AS itemId
        MATCH (i:$SE_ITEM {$ID: itemId})-[:$NOTE_ON]->(n:$META:$NOTE)
        RETURN i.$ID        AS ref,
               n.$META_ID   AS metaId,
               n.$TEXT      AS text,
               n.$UPDATED_AT AS updatedAt
    """

    // --- Attribute settings (Tier 2, Shape B) ---------------------------------------------------

    public const val EXISTING_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        RETURN s.$ATTRIBUTE_NAME AS name,
               coalesce(s.$VISIBLE, false)      AS visible,
               coalesce(s.$VERIFICATION, false) AS verification
    """

    // One node per (module, attributeName) — MERGE on attributeName is what enforces it, since
    // Community has no composite constraint to lean on.
    public const val UPSERT_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        UNWIND ${'$'}settings AS row
        MERGE (m)-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING {$ATTRIBUTE_NAME: row.attributeName})
          ON CREATE SET s.$META_ID    = row.metaId,
                        s.$CREATED_BY = ${'$'}user,
                        s.$CREATED_AT = ${'$'}now
        SET s.$META_KIND      = '$ATTRIBUTE_SETTING_KIND',
            s.$SCHEMA_VERSION = $CURRENT_SCHEMA_VERSION,
            s.$VISIBLE        = row.visible,
            s.$VERIFICATION   = row.verification,
            s.$UPDATED_BY     = ${'$'}user,
            s.$UPDATED_AT     = ${'$'}now
    """

    // An attribute set back to all-false carries no information, so its node goes rather than
    // lingering as a row of false — same reasoning as an emptied comment.
    public const val DELETE_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (:$DOORS_MODULE {$ID: ${'$'}moduleId})-[:$ATTRIBUTE_SETTING_FOR]->(s:$META:$ATTRIBUTE_SETTING)
        WHERE s.$ATTRIBUTE_NAME IN ${'$'}names
        DETACH DELETE s
    """
}

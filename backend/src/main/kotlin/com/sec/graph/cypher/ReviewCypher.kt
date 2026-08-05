package com.sec.graph.cypher

// Cypher for docs/REQ_REVIEW.md — the Req review table, its references and its comments.
// Every statement is CYPHER 25-prefixed and parameterised; the transaction timeout is applied to
// every session in graph/Read.kt and graph/Write.kt, so nothing here can be issued without one.
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
    public const val MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:DOORSObject {__moduleUrl: ${'$'}moduleUrl})
        WHERE NOT o:DOORSModule
        WITH o
        ORDER BY o.__sortKey
        SKIP ${'$'}skip
        LIMIT ${'$'}limit
        WITH o,
             [(o)-[:refersTo]->(out:SEItem) | {
                 ref: out.__id,
                 id: coalesce(out.id, out.__name),
                 resolved: NOT out:__UNDEFINED,
                 moduleUrl: out.__moduleUrl
             }] AS outgoing,
             [(o)<-[:refersTo]-(inc:SEItem) | {
                 ref: inc.__id,
                 id: coalesce(inc.id, inc.__name),
                 resolved: NOT inc:__UNDEFINED,
                 moduleUrl: inc.__moduleUrl
             }] AS incoming
        OPTIONAL MATCH (o)-[:__noteOn]->(n:__Meta:__Note)
        RETURN o                AS object,
               labels(o)        AS labels,
               outgoing         AS outgoing,
               incoming         AS incoming,
               n.__metaId       AS commentId,
               n.text           AS commentText,
               n.__updatedAt    AS commentUpdatedAt
    """

    // Names for the modules a page's references point into, fetched once for the whole page rather
    // than joined per reference. An unresolved target names a module that has usually *not* been
    // imported, in which case there is no name to find and the UI says "Not yet imported" without
    // one — but when the module is present, naming it is what makes the message actionable (§5.1).
    public const val MODULE_NAMES: String = """
        CYPHER 25
        UNWIND ${'$'}moduleIds AS moduleId
        MATCH (m:DOORSModule {__id: moduleId})
        RETURN m.__id AS id, m.__name AS name
    """

    // Counted separately from the page so the client can show "n of m" without holding every row.
    public const val COUNT_MODULE_OBJECTS: String = """
        CYPHER 25
        MATCH (o:DOORSObject {__moduleUrl: ${'$'}moduleUrl})
        WHERE NOT o:DOORSModule
        RETURN count(o) AS total
    """

    // One item for the detail panel (§7). The module is returned as its __name so the panel can
    // render __moduleUrl as a link labelled with the module's name, per the R5 alias map.
    public const val ITEM_DETAIL: String = """
        CYPHER 25
        MATCH (i:SEItem {__id: ${'$'}itemId})
        OPTIONAL MATCH (m:DOORSModule {__id: i.__moduleUrl})
        RETURN i          AS item,
               labels(i)  AS labels,
               m.__name   AS moduleName,
               m.__id     AS moduleId
        LIMIT 1
    """

    public const val ITEM_TRACES_OUT: String = """
        CYPHER 25
        MATCH (:SEItem {__id: ${'$'}itemId})-[:refersTo]->(t:SEItem)
        RETURN t.__id       AS ref,
               coalesce(t.id, t.__name) AS id,
               NOT t:__UNDEFINED        AS resolved,
               t.__moduleUrl            AS moduleUrl
        ORDER BY id
        LIMIT ${'$'}limit
    """

    public const val ITEM_TRACES_IN: String = """
        CYPHER 25
        MATCH (:SEItem {__id: ${'$'}itemId})<-[:refersTo]-(t:SEItem)
        RETURN t.__id       AS ref,
               coalesce(t.id, t.__name) AS id,
               NOT t:__UNDEFINED        AS resolved,
               t.__moduleUrl            AS moduleUrl
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
        MATCH (i:SEItem {__id: c.itemId})
        MERGE (i)-[:__noteOn]->(n:__Meta:__Note)
          ON CREATE SET n.__metaId   = c.metaId,
                        n.__createdBy = ${'$'}user,
                        n.__createdAt = ${'$'}now
        SET n.__metaKind      = 'note',
            n.__schemaVersion = 1,
            n.text            = c.text,
            n.__updatedBy     = ${'$'}user,
            n.__updatedAt     = ${'$'}now
    """

    // Clearing a comment deletes the node rather than storing "", so MATCH (m:__Meta) stays a true
    // inventory of what the application knows (§5.2).
    public const val DELETE_COMMENTS: String = """
        CYPHER 25
        UNWIND ${'$'}itemIds AS itemId
        MATCH (:SEItem {__id: itemId})-[:__noteOn]->(n:__Meta:__Note)
        DETACH DELETE n
    """

    // Read back after the write so the client can clear its dirty marks without reloading the
    // table (§8): the server, not the client, decides what was stored.
    public const val READ_COMMENTS: String = """
        CYPHER 25
        UNWIND ${'$'}itemIds AS itemId
        MATCH (i:SEItem {__id: itemId})-[:__noteOn]->(n:__Meta:__Note)
        RETURN i.__id       AS ref,
               n.__metaId   AS metaId,
               n.text       AS text,
               n.__updatedAt AS updatedAt
    """

    // --- Attribute settings (Tier 2, Shape B) ---------------------------------------------------

    public const val EXISTING_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (:DOORSModule {__id: ${'$'}moduleId})-[:__attributeSettingFor]->(s:__Meta:__AttributeSetting)
        RETURN s.attributeName AS name,
               coalesce(s.visible, false)      AS visible,
               coalesce(s.verification, false) AS verification
    """

    // One node per (module, attributeName) — MERGE on attributeName is what enforces it, since
    // Community has no composite constraint to lean on.
    public const val UPSERT_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (m:DOORSModule {__id: ${'$'}moduleId})
        UNWIND ${'$'}settings AS row
        MERGE (m)-[:__attributeSettingFor]->(s:__Meta:__AttributeSetting {attributeName: row.attributeName})
          ON CREATE SET s.__metaId    = row.metaId,
                        s.__createdBy = ${'$'}user,
                        s.__createdAt = ${'$'}now
        SET s.__metaKind      = 'attributeSetting',
            s.__schemaVersion = 1,
            s.visible         = row.visible,
            s.verification    = row.verification,
            s.__updatedBy     = ${'$'}user,
            s.__updatedAt     = ${'$'}now
    """

    // An attribute set back to all-false carries no information, so its node goes rather than
    // lingering as a row of false — same reasoning as an emptied comment.
    public const val DELETE_ATTRIBUTE_SETTINGS: String = """
        CYPHER 25
        MATCH (:DOORSModule {__id: ${'$'}moduleId})-[:__attributeSettingFor]->(s:__Meta:__AttributeSetting)
        WHERE s.attributeName IN ${'$'}names
        DETACH DELETE s
    """
}

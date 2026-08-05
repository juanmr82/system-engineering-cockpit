package com.sec.graph.cypher

// Cypher for docs/features/requirements-modules.md §5.3. Every statement is CYPHER 25-prefixed
// and parameterised, and every read carries a LIMIT. The transaction timeout that the other half
// of CLAUDE.md §7 asks for is not here — it is applied to every session in graph/Read.kt and
// graph/Write.kt, from GraphDriver, so no statement in this file can be issued without one.
public object ModuleCypher {
    public const val LIST_MODULES: String = """
        CYPHER 25
        MATCH (m:DOORSModule)
        OPTIONAL MATCH (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
        RETURN m.__id             AS id,
               m.__name           AS name,
               m.last_Modified_On AS lastModified,
               m.moduleFullPath   AS path,
               c.code             AS levelCode
        ORDER BY m.__name
        LIMIT ${'$'}limit
    """

    public const val MODULE_DETAIL: String = """
        CYPHER 25
        MATCH (m:DOORSModule {__id: ${'$'}moduleId})
        OPTIONAL MATCH (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
        RETURN m AS module, c.code AS levelCode
        LIMIT 1
    """

    // __moduleUrl is what a module's own objects store to point back at it; a module's plain
    // `url` property carries the same value as its __id (requirements-modules.md §4.1), so the
    // module's own __id is the value to bind here.
    public const val DISCOVER_ATTRIBUTES: String = """
        CYPHER 25
        MATCH (o:DOORSObject {__moduleUrl: ${'$'}moduleUrl})
        WITH o LIMIT ${'$'}sampleSize
        UNWIND keys(o) AS k
        WITH DISTINCT k
        WHERE NOT k STARTS WITH '__'
          AND NOT k IN ['id', 'objectNumber', 'objectLevel']
        RETURN k AS name
        ORDER BY k
    """

    public const val EXISTING_MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (:DOORSModule {__id: ${'$'}moduleId})-[:__policyFor]->(p:__Meta:__Policy)
        WHERE p.rule = 'mandatory'
        RETURN p.attributeName AS name
    """

    // Which of these ids are actually objects of this module. The comment write path uses it to
    // refuse an arbitrary __id in a request body — without it, a crafted payload could attach a
    // note to any node in the graph, which is not what "comment on a row you loaded" means.
    public const val MODULE_OBJECT_IDS: String = """
        CYPHER 25
        MATCH (o:DOORSObject {__moduleUrl: ${'$'}moduleUrl})
        WHERE o.__id IN ${'$'}itemIds AND NOT o:DOORSModule
        RETURN o.__id AS id
    """

    public const val MODULE_EXISTS: String = """
        CYPHER 25
        MATCH (m:DOORSModule {__id: ${'$'}moduleId})
        RETURN m.__id AS id
        LIMIT 1
    """

    public const val SET_SYSTEM_LEVEL: String = """
        CYPHER 25
        MATCH (m:DOORSModule {__id: ${'$'}moduleId})
        MERGE (m)-[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
          ON CREATE SET c.__metaId        = ${'$'}metaId,
                        c.__metaKind      = 'classification',
                        c.__schemaVersion = 1,
                        c.__createdBy     = ${'$'}user,
                        c.__createdAt     = ${'$'}now
        SET c.code        = ${'$'}code,
            c.__updatedBy = ${'$'}user,
            c.__updatedAt = ${'$'}now
    """

    public const val CLEAR_SYSTEM_LEVEL: String = """
        CYPHER 25
        MATCH (:DOORSModule {__id: ${'$'}moduleId})
              -[:__classifiedAs]->(c:__Meta:__Classification {scheme: 'systemLevel'})
        DETACH DELETE c
    """

    public const val ADD_MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (m:DOORSModule {__id: ${'$'}moduleId})
        UNWIND ${'$'}add AS row
        MERGE (m)-[:__policyFor]->(p:__Meta:__Policy {attributeName: row.attributeName,
                                                      rule: 'mandatory'})
          ON CREATE SET p.__metaId        = row.metaId,
                        p.__metaKind      = 'policy',
                        p.__schemaVersion = 1,
                        p.appliesToLabels = ['DOORSRequirement'],
                        p.__createdBy     = ${'$'}user,
                        p.__createdAt     = ${'$'}now
        SET p.__updatedBy = ${'$'}user,
            p.__updatedAt = ${'$'}now
    """

    public const val REMOVE_MANDATORY_POLICIES: String = """
        CYPHER 25
        MATCH (:DOORSModule {__id: ${'$'}moduleId})-[:__policyFor]->(p:__Meta:__Policy)
        WHERE p.rule = 'mandatory' AND p.attributeName IN ${'$'}remove
        DETACH DELETE p
    """
}

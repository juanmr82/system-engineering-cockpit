package com.sec.graph.cypher

import com.sec.domain.NodeLabel.DELETED
import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.NodeLabel.UNDEFINED
import com.sec.domain.Prop.EXPORT_CHECKSUM
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.MODULE_URL
import com.sec.domain.Prop.NAME as ITEM_NAME
import com.sec.domain.Prop.OBJECT_URL
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Prop.VERSION
import com.sec.domain.Rel.CHILD
import com.sec.source.doors.DoorsLabel
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT
import com.sec.source.doors.DoorsProp.ABSOLUTE_NUMBER
import com.sec.source.doors.DoorsProp.IMPORTED_AT
import com.sec.source.doors.DoorsProp.SOURCE_MODULE_URL
import com.sec.source.doors.DoorsRel.REFERS_TO

/**
 * Every statement that writes imported DOORS data, ported clause for clause from
 * `importers/src/sec_import/doors/importer.py` (ADR 0019 §1, §7) — the MERGE phases and the
 * seven-statement ADR-0012 reconciliation.
 *
 * [mergeObjects] is a `fun`, not a `const val`, because an object's label set is data — a module's
 * objects group into as many distinct label combinations as it has type/table shapes, the same
 * reason [AccessCypher.propagate] is a function of its [com.sec.security.Containment] rather than
 * one statement per source. [VALID_OBJECT_LABELS] is what keeps that dynamic label clause a *closed*
 * vocabulary rather than an open door — [mergeObjects] refuses anything outside it before the string
 * is ever built, the same discipline `importer.py`'s own `_label_str` asserts.
 *
 * `RETURN count(*) AS …` follows every write that reports a count, rather than reading the driver's
 * own summary counters the way `importer.py` reads `result.consume().counters` — `graph/Write.kt`'s
 * `executeWrite` only exposes row data, the same convention `WindchillCypher.SWEEP_DELETED` already
 * uses (CLAUDE.md §5, backend/CLAUDE.md "Names").
 */
public object DoorsImportCypher {

    /** Every label [mergeObjects] may write — [DoorsLabel.all] plus [SE_ITEM], which `derive_labels`
     *  always includes and which is not itself a DOORS name. */
    public val VALID_OBJECT_LABELS: Set<String> = DoorsLabel.all + SE_ITEM

    /** Constraints and indexes for the labels this importer writes. Idempotent, and applied at the
     *  start of every run — a deployment with no DOORS import should not carry DOORS indexes. */
    public val SCHEMA: List<String> = listOf(
        """
        CYPHER 25
        CREATE CONSTRAINT se_item_id_unique IF NOT EXISTS
        FOR (n:$SE_ITEM) REQUIRE n.$ID IS UNIQUE
        """,
        """
        CYPHER 25
        CREATE CONSTRAINT doors_object_url_unique IF NOT EXISTS
        FOR (n:$DOORS_OBJECT) REQUIRE n.$OBJECT_URL IS UNIQUE
        """,
        "CYPHER 25 CREATE INDEX doors_object_id IF NOT EXISTS FOR (n:$DOORS_OBJECT) ON (n.id)",
        "CYPHER 25 CREATE INDEX doors_object_module IF NOT EXISTS FOR (n:$DOORS_OBJECT) ON (n.$MODULE_URL)",
        "CYPHER 25 CREATE INDEX doors_object_sortkey IF NOT EXISTS FOR (n:$DOORS_OBJECT) ON (n.$SORT_KEY)",
        "CYPHER 25 CREATE INDEX se_item_name IF NOT EXISTS FOR (n:$SE_ITEM) ON (n.$ITEM_NAME)",
        // Label-property indexes are per label (CLAUDE.md §7) — the planner will not use
        // doors_object_module for a pattern scoped to :DOORSRequirement specifically.
        """
        CYPHER 25
        CREATE INDEX doors_requirement_module IF NOT EXISTS
        FOR (n:${DoorsLabel.REQUIREMENT}) ON (n.$MODULE_URL)
        """,
    )

    public const val MERGE_MODULE: String = """
        CYPHER 25
        MERGE (n:$SE_ITEM {$ID: ${'$'}id})
        SET n:$DOORS_MODULE
        SET n += ${'$'}props
    """

    /**
     * One batch of objects sharing exactly [labels]. `REMOVE` takes `:__UNDEFINED`/`:__DELETED` off
     * on every write — a placeholder becomes real the moment an import reaches it, and an object
     * that reappears in DOORS stops being a ghost the instant its own export mentions it again.
     */
    public fun mergeObjects(labels: Set<String>): String {
        require(labels.isNotEmpty() && VALID_OBJECT_LABELS.containsAll(labels)) {
            "Not a recognised DOORS object label: ${labels - VALID_OBJECT_LABELS}"
        }
        val labelClause = (labels - SE_ITEM).sorted().joinToString(":")
        return """
            CYPHER 25
            UNWIND ${'$'}rows AS row
            MERGE (n:$SE_ITEM {$ID: row.id})
            REMOVE n:$UNDEFINED:$DELETED
            SET n:$labelClause
            SET n += row.props
            SET n.$IMPORTED_AT = ${'$'}importedAt
        """.trimIndent()
    }

    /** `MATCH`, not `MERGE`, for both ends — they must already exist from an earlier phase. */
    public const val MERGE_CHILD: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (p:$SE_ITEM {$ID: row.parentId})
        MATCH (c:$SE_ITEM {$ID: row.childId})
        MERGE (p)-[r:$CHILD]->(c)
        SET r.$IMPORTED_AT = ${'$'}importedAt
    """

    /**
     * `refersTo`, as this module's objects assert it (`__outputLinks`). A target this run has not
     * reached yet is created as a `:__UNDEFINED` placeholder — reached from the *other* side too,
     * see [MERGE_INCOMING], which is what makes an incoming link visible before the module naming
     * it has ever been imported.
     */
    public const val MERGE_REFERS_TO: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (s:$SE_ITEM {$ID: row.sourceId})
        MERGE (t:$SE_ITEM {$ID: row.targetId})
        ON CREATE SET
            t:$UNDEFINED,
            t.$OBJECT_URL = row.targetId,
            t.$MODULE_URL = row.targetModuleUrl,
            t.$ABSOLUTE_NUMBER = row.absoluteNumber,
            t.$ITEM_NAME = row.targetName,
            t.$VERSION = row.targetVersion
        MERGE (s)-[r:$REFERS_TO]->(t)
        ON CREATE SET r.$SOURCE_MODULE_URL = row.sourceModuleUrl
        SET r.$IMPORTED_AT = row.importedAt
    """

    /** The mirror of [MERGE_REFERS_TO], from an object's own `__inputLinks`: what other modules
     *  assert about it, read regardless of whether the asserting module has been imported. */
    public const val MERGE_INCOMING: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MATCH (t:$SE_ITEM {$ID: row.targetId})
        MERGE (s:$SE_ITEM {$ID: row.sourceId})
        ON CREATE SET
            s:$UNDEFINED,
            s.$OBJECT_URL = row.sourceId,
            s.$MODULE_URL = row.sourceModuleUrl,
            s.$ABSOLUTE_NUMBER = row.absoluteNumber,
            s.$ITEM_NAME = row.sourceName,
            s.$VERSION = row.sourceVersion
        MERGE (s)-[r:$REFERS_TO]->(t)
        ON CREATE SET r.$SOURCE_MODULE_URL = row.sourceModuleUrl
        SET r.$IMPORTED_AT = row.importedAt
    """

    // -- Reconciliation (ADR 0012), seven statements ------------------------------------------

    /** 1. Mark: every object of this module whose stamp this run did not confirm. Excludes the
     *  module node itself, which carries [DOORS_OBJECT] too but is never a ghost of its own export. */
    public const val MARK_DELETED: String = """
        CYPHER 25
        MATCH (n:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE NOT n:$DOORS_MODULE AND coalesce(n.$IMPORTED_AT, '') <> ${'$'}importedAt
        WITH n, NOT n:$DELETED AS wasNotDeleted
        SET n:$DELETED
        RETURN count(n) AS ghosts, sum(CASE WHEN wasNotDeleted THEN 1 ELSE 0 END) AS newlyDeleted
    """

    /** 2. Stale hierarchy — every `__child` pointing at this module's objects that this run did
     *  not re-stamp, which is also what takes a ghost out of the tree. */
    public const val DELETE_STALE_CHILD: String = """
        CYPHER 25
        MATCH ()-[r:$CHILD]->(c:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})
        WHERE coalesce(r.$IMPORTED_AT, '') <> ${'$'}importedAt
        DELETE r
        RETURN count(*) AS deleted
    """

    /** 3. Stale traceability, for the objects the export still describes — excludes ghosts, whose
     *  links were never re-stamped and are exactly the ones worth keeping. */
    public const val DELETE_STALE_REFERS_TO: String = """
        CYPHER 25
        MATCH (s:$DOORS_OBJECT {$MODULE_URL: ${'$'}moduleUrl})-[r:$REFERS_TO]->()
        WHERE NOT s:$DELETED AND coalesce(r.$IMPORTED_AT, '') <> ${'$'}importedAt
        DELETE r
        RETURN count(*) AS deleted
    """

    /** 4a. The annotations go with the object — the one place this importer deletes Tier 2 (R2). */
    public const val DELETE_GHOST_META: String = """
        CYPHER 25
        MATCH (n:$DOORS_OBJECT:$DELETED {$MODULE_URL: ${'$'}moduleUrl})--(m:$META)
        DETACH DELETE m
        RETURN count(*) AS deleted
    """

    /** 4b. What a ghost is allowed to keep: `refersTo` to and from other DOORS objects, nothing else
     *  — the only edges a reviewer can act on, because both ends are DOORS. */
    public const val STRIP_GHOST_EDGES: String = """
        CYPHER 25
        MATCH (n:$DOORS_OBJECT:$DELETED {$MODULE_URL: ${'$'}moduleUrl})-[r]-(o)
        WHERE NOT (type(r) = '$REFERS_TO' AND o:$DOORS_OBJECT)
        DELETE r
        RETURN count(*) AS deleted
    """

    /** 5. Ghosts nothing points at any more, anywhere — global, not module-scoped, because
     *  re-importing one module is exactly what can strand a ghost belonging to another. */
    public const val COLLECT_GHOSTS: String = """
        CYPHER 25
        MATCH (n:$DOORS_OBJECT:$DELETED)
        WHERE COUNT { (n)--() } = 0
        DELETE n
        RETURN count(*) AS deleted
    """

    /** 6. The same for placeholders — global, for the same reason. */
    public const val COLLECT_PLACEHOLDERS: String = """
        CYPHER 25
        MATCH (n:$UNDEFINED)
        WHERE COUNT { (n)--() } = 0
        DELETE n
        RETURN count(*) AS deleted
    """

    // -- The upload gate (ADR 0019 §3, §4) -----------------------------------------------------

    /**
     * Whether a module with this `__id` already exists, whether the caller may see it, and its
     * current checksum if it has one — the one query both the checksum skip and the visibility
     * refusal are decided from, read once before a run is ever started.
     */
    public val MODULE_GATE: String = """
        CYPHER 25
        OPTIONAL MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        RETURN m IS NOT NULL AS exists,
               m.$EXPORT_CHECKSUM AS storedChecksum,
               (m IS NOT NULL AND ${AccessCypher.visible("m")}) AS visible
    """

    /** Written last, after a run's whole pipeline succeeds (ADR 0019 §3) — never alongside the
     *  module's other properties, so a run that fails partway leaves no checksum a retry would
     *  mistake for "already done". */
    public const val STAMP_CHECKSUM: String = """
        CYPHER 25
        MATCH (m:$DOORS_MODULE {$ID: ${'$'}moduleId})
        SET m.$EXPORT_CHECKSUM = ${'$'}checksum
    """
}

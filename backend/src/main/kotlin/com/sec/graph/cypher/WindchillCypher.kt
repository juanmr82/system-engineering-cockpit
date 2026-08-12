package com.sec.graph.cypher

import com.sec.domain.NodeLabel.META
import com.sec.domain.NodeLabel.SE_ITEM
import com.sec.domain.Prop.ID
import com.sec.domain.Prop.NAME as ITEM_NAME
import com.sec.domain.Prop.NAMESPACE
import com.sec.domain.Prop.SORT_KEY
import com.sec.domain.Prop.VERSION
import com.sec.source.windchill.WindchillLabel.DOCUMENT as WINDCHILL_DOCUMENT
import com.sec.source.windchill.WindchillProp.FOLDER_LOCATION
import com.sec.source.windchill.WindchillProp.NAME as WINDCHILL_NAME
import com.sec.source.windchill.WindchillProp.NUMBER
import com.sec.source.windchill.WindchillProp.OID
import com.sec.source.windchill.WindchillProp.STATE_DISPLAY
import com.sec.source.windchill.WindchillProp.VERSION as WINDCHILL_VERSION

/**
 * Every statement that reads or writes Windchill data (ADR 0010: names are interpolated, never
 * spelled out).
 *
 * The source is a flat list of documents with no hierarchy, no links and no shared entities, so
 * this file is short by nature rather than by omission — one upsert, one sweep, one read, and the
 * counts the run report needs.
 */
public object WindchillCypher {

    /**
     * Schema for the imported Windchill labels, applied by the importer on every run.
     *
     * Owned by the import rather than by startup, for the reason `JiraCypher.SCHEMA` gives: a
     * deployment with no Windchill should not be creating Windchill indexes. The `:SEItem`
     * constraint is created here too — `IF NOT EXISTS` matches an equivalent constraint as well as a
     * name, so a graph that has only ever seen Windchill still gets the one identity depends on.
     *
     * Each statement runs on its own; schema changes cannot share a transaction with anything else.
     */
    public val SCHEMA: List<String> = listOf(
        """
        CYPHER 25
        CREATE CONSTRAINT se_item_id_unique IF NOT EXISTS
        FOR (n:$SE_ITEM) REQUIRE n.$ID IS UNIQUE
        """,
        // Label-property indexes are per-label and the planner will not use a :SEItem index for a
        // :WindchillDocument pattern (CLAUDE.md §7). The Documents view reads every row in
        // __sortKey order on every load, which is the one query this source makes at all.
        """
        CYPHER 25
        CREATE INDEX windchill_document_sortkey IF NOT EXISTS
        FOR (n:$WINDCHILL_DOCUMENT) ON (n.$SORT_KEY)
        """,
        // Versions of one document share a Number, and it is the only property with structural
        // meaning here — the group a row belongs to. Indexed so answering "what else is this
        // document" stays a lookup when the set outgrows one screen's worth of rows.
        """
        CYPHER 25
        CREATE INDEX windchill_document_number IF NOT EXISTS
        FOR (n:$WINDCHILL_DOCUMENT) ON (n.$NUMBER)
        """,
    )

    /**
     * The documents, **including the removal of fields the export no longer carries**.
     *
     * `SET d += row.props` only adds and overwrites, so a document whose `State` disappeared from
     * the export would keep last import's state forever — a value shown in the table that the source
     * no longer asserts. `row.presentKeys` is what the row does carry, so the comprehension is "what
     * is on the node that should not be", and `REMOVE d[staleKey]` is Cypher 25's dynamic property
     * removal. The same shape `JiraCypher.UPSERT_ISSUES` uses, and for the same reason.
     *
     * `NOT k STARTS WITH '__'` keeps `__id`, `__name`, `__version` and `__sortKey` out of it: they
     * are ours, they are not Windchill fields, and no `presentKeys` list would ever name them.
     *
     * **`UNWIND` of an empty list produces no rows**, so a document with nothing stale leaves the
     * stream at that point. Its `MERGE` and `SET` have already committed — side effects happen as
     * the row flows — so this is correct, and it does mean nothing may be appended after the
     * `REMOVE`. Anything needing every document goes in its own statement.
     */
    public const val UPSERT_DOCUMENTS: String = """
        CYPHER 25
        UNWIND ${'$'}rows AS row
        MERGE (d:$SE_ITEM {$ID: row.id})
        SET d:$WINDCHILL_DOCUMENT,
            d.$ITEM_NAME = row.name,
            d.$VERSION = ${'$'}itemVersion,
            d.$SORT_KEY = row.sortKey,
            d += row.props
        WITH d, row
        UNWIND [k IN keys(d) WHERE NOT k STARTS WITH '$NAMESPACE' AND NOT k IN row.presentKeys] AS staleKey
        REMOVE d[staleKey]
    """

    /**
     * Documents the export no longer contains.
     *
     * The file is the truth, so anything not in it is gone from Windchill — and the sweep is scoped
     * to nothing else, by the user's decision: a partial export deletes the rest, and the importer's
     * job is to say loudly when it just did (see `WindchillImporter`). The caller must refuse to run
     * this unless the whole file was read; a seen set wrong by omission is indistinguishable from a
     * document that was deleted.
     *
     * **The annotations go with the document** (ADR 0012). A note about a document Windchill no
     * longer has is a note about nothing, and leaving one behind anchors Tier 2 to a node no export
     * will mention again. The match is undirected because Tier 2 attaches both ways: an annotation
     * hangs off the item, and a reified `:__Link` points *at* it.
     */
    public const val SWEEP_DELETED: String = """
        CYPHER 25
        MATCH (d:$WINDCHILL_DOCUMENT)
        WHERE NOT d.$ID IN ${'$'}seenIds
        OPTIONAL MATCH (d)--(m:$META)
        DETACH DELETE d, m
        RETURN count(DISTINCT d) AS deleted
    """

    /** How many documents stand. Read before and after the sweep, for the mass-deletion warning. */
    public const val COUNT_DOCUMENTS: String = """
        CYPHER 25
        MATCH (d:$WINDCHILL_DOCUMENT)
        RETURN count(d) AS documents
    """

    /**
     * Every document, in `__sortKey` order — the whole set, in one response.
     *
     * This is the one read path the Documents view has, and it is deliberately unpaged: the set is
     * ~1 500 rows, the view groups versions of one document together, and grouping needs every
     * version of a `Number` in hand at once. Filtering and sorting happen in the browser, which is
     * what makes the search instant.
     *
     * `${'$'}limit` is not a preference — Community has no query governor (§7), so it is the only
     * thing standing between a grown data set and a response nobody can render. The caller reports
     * when it was reached rather than truncating silently.
     *
     * `coalesce` on the order key, because a document imported before the derivation existed has
     * none — the ordering is then arbitrary rather than absent, and a re-import fixes it.
     */
    public const val LIST_DOCUMENTS: String = """
        CYPHER 25
        MATCH (d:$WINDCHILL_DOCUMENT)
        RETURN d.$ID AS id,
               d.$OID AS oid,
               d.$FOLDER_LOCATION AS folderLocation,
               d.$WINDCHILL_NAME AS name,
               d.$NUMBER AS number,
               d.$WINDCHILL_VERSION AS version,
               d.$STATE_DISPLAY AS state
        ORDER BY coalesce(d.$SORT_KEY, '')
        LIMIT ${'$'}limit
    """
}

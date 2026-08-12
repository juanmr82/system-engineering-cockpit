package com.sec.source.windchill

/**
 * Every name the backend uses to address Windchill data in the graph, in one place (ADR 0010).
 *
 * The rule that keeps sources independent is that **a source's names file never edits another's**
 * (R3). Source-agnostic names — `__id`, `:SEItem`, `__child`, `__sortKey`, the `:__Meta` catalogue —
 * stay in `domain/GraphNames.kt` and are imported from there.
 *
 * ## The naming rule this file inherits from JIRA's
 *
 * **No object, class or enum in this package may share its name with a value in
 * [WindchillLabel.all].** `GraphNamesTest`'s inverse check reads the *source text* of every file in
 * `graph/cypher/`, import lines included, and fails on any graph name written out — so a Kotlin
 * class called `WindchillDocument` would fail the build the moment a statement imported a constant
 * from it. Hence [WindchillRecord] for the parsed row and [WindchillDocumentRow] for the wire type.
 */

/**
 * Node labels for imported Windchill data.
 *
 * One label, and that is the whole model today: the export is a flat list of documents with no
 * containment, no links and no shared entities to promote. There is deliberately no folder node —
 * `FolderLocation` is a string on the document, exactly as Windchill sends it. When folders become
 * nodes they hang off `__child` like every other source's hierarchy (R3), and nothing here changes.
 *
 * Every one of these also carries `:SEItem`, which is the only thing a future cross-source link
 * joins on (R6).
 */
public object WindchillLabel {
    public const val DOCUMENT: String = "WindchillDocument"

    /** Every label this source declares. Read by `GraphNamesTest`, which is why it is exhaustive. */
    public val all: Set<String> = setOf(DOCUMENT)

    /**
     * The labels an import writes, as opposed to the ones the application owns.
     *
     * Identical to [all] today because Windchill has no application-owned nodes — no settings
     * singleton, no column config. The distinction is kept because the sweep depends on it: an
     * import may delete a document it no longer sees and must never reach anything else.
     */
    public val imported: Set<String> = setOf(DOCUMENT)
}

/**
 * Property names on imported Windchill nodes.
 *
 * **Every one of these is Windchill's own, spelled as Windchill spells it** — `PascalCase`, because
 * that is what OData sends, and R1 forbids reformatting source data. They look unlike DOORS's
 * `created_By` and JIRA's `iconUrl` for exactly that reason: each source keeps its own conventions.
 *
 * The two `State*` names are the one shape that is not a verbatim copy of a key, and the
 * flattening is explained on them.
 */
public object WindchillProp {
    /**
     * Windchill's own object id — `OR:wt.doc.WTDocument:905344148`.
     *
     * Stored but never shown (the user's own instruction), because it is what the info-page link is
     * built from. It is **not** the node's identity: `__id` is the OData resource URL, the same rule
     * every other source follows (R6).
     */
    public const val OID: String = "ID"

    /** The document's folder path — a string, not a hierarchy. See [WindchillLabel]. */
    public const val FOLDER_LOCATION: String = "FolderLocation"

    public const val NAME: String = "Name"

    /**
     * The document number, shared by every version of one document.
     *
     * The only property in this source with structural meaning: it is what makes two rows two
     * versions of one thing rather than two documents, and it is what the Documents view groups on.
     */
    public const val NUMBER: String = "Number"

    /**
     * Windchill's version string — `01 [2]`, revision and iteration.
     *
     * Deliberately **not** written to `__version`, which is the application's own word for "the
     * item as the source holds it now" and whose value is `current` for every node in the graph
     * (`ItemVersion`). A Windchill revision is a different fact from that one, it is source data,
     * and conflating them would make `__version` mean two things — the same mistake `__version`
     * already refuses for a DOORS baseline (R5).
     */
    public const val VERSION: String = "Version"

    /**
     * `State` arrives as an object — `{"Value":"RELEASED","Display":"Released"}` — and Neo4j has no
     * nested property, so it is flattened to two scalars with **both values untouched**.
     *
     * A *structural* flattening, which R1 permits and which the JIRA importer already does to
     * `schema`. The alternative, JSON text, would make the one column a reviewer filters and sorts
     * on a parse per row. `Value` is the code and `Display` is the wording; the table shows
     * [STATE_DISPLAY] because that is what Windchill itself shows.
     */
    public const val STATE_VALUE: String = "StateValue"
    public const val STATE_DISPLAY: String = "StateDisplay"

    /**
     * The columns the Documents view shows, in the order it shows them.
     *
     * Stated once here because three things must agree on it — the read projection, the search, and
     * the table — and a fourth, [OID], must stay out of all of them. Not a graph name in itself, so
     * it is not in `GraphNamesTest`'s sets; the names it contains are.
     */
    public val displayed: List<String> = listOf(
        FOLDER_LOCATION, NAME, NUMBER, VERSION, STATE_DISPLAY,
    )
}

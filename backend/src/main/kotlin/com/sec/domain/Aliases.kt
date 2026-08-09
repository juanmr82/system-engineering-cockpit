package com.sec.domain

import com.sec.source.doors.DoorsLabel
import com.sec.source.doors.DoorsModuleAttr
import com.sec.source.doors.DoorsRel

// R5's single source of truth: internal __-prefixed property/relationship names to user-facing
// labels. API DTOs are built from this. The frontend never carries a second copy and never
// string-manipulates a property name to make it presentable. Extend this table when adding a
// field, do not invent aliases locally (CLAUDE.md §2 R5 reference alias map).
//
// This is the one file in `domain/` that imports from a `source/` package, and it is inherent
// rather than accidental: the alias map is where *every* source's vocabulary meets, so it will
// import Windchill's and CAMEO's names too as those arrive. What it must never do is spell one
// out — a label written here as a literal is a second source of truth for that name.
public object Aliases {
    public val propertyLabels: Map<String, String> = mapOf(
        Prop.NAME to "Name",
        Prop.VERSION to "Version",
        Prop.TYPE_RAW to "Type",
        Prop.MODULE_URL to "Module",
        Prop.CREATED_BY to "Added by",
        Prop.CREATED_AT to "Added on",
        DoorsRel.REFERS_TO to "References",
    )

    public val metaKindLabels: Map<String, String> = mapOf(
        MetaKind.REVIEW to "Review",
        MetaKind.TAG to "Tag",
        MetaKind.NOTE to "Note",
        MetaKind.FLAG to "Flag",
        MetaKind.POLICY to "Rule",
        MetaKind.LINK to "Link",
        MetaKind.CLASSIFICATION to "Classification",
        MetaKind.ATTRIBUTE_SETTING to "Attribute setting",
    )

    // Scheme- and rule-scoped labels (docs/features/requirements-modules.md §4.1, §4.2).
    public val classificationSchemeLabels: Map<String, String> = mapOf(
        MetaValue.SYSTEM_LEVEL_SCHEME to "System level",
    )

    public val policyRuleLabels: Map<String, String> = mapOf(
        MetaValue.MANDATORY_RULE to "Mandatory attribute",
    )

    // The three per-module attribute flags of the Req review settings dialog (REQ_REVIEW.md §6).
    // `mandatory` is the :__Policy rule above under its dialog-column wording; the other two are
    // :__AttributeSetting payload fields (§9.2).
    public val attributeSettingLabels: Map<String, String> = mapOf(
        MetaValue.MANDATORY_RULE to "Mandatory",
        MetaProp.VISIBLE to "Shown in table",
        MetaProp.VERIFICATION to "Verification attribute",
    )

    // Type labels the importer derives, rendered as language rather than as label strings. The
    // API still ships raw `labels` as a state channel (CLAUDE.md §5) — this is what the *text*
    // must say when a label is all we have. A type absent from here has no wording of its own
    // and falls back to __typeRaw, which is source content and always preferred when present.
    public val typeLabels: Map<String, String> = mapOf(
        DoorsLabel.REQUIREMENT to "Requirement",
        DoorsLabel.HEADING to "Heading",
        DoorsLabel.INFORMATION to "Information",
        DoorsLabel.APP_MATRIX to "Applicability matrix",
        DoorsLabel.APP_MATRIX_HEADING to "Applicability matrix heading",
        DoorsLabel.TABLE to "Table",
        DoorsLabel.TABLE_ROW to "Table row",
        DoorsLabel.TABLE_CELL to "Table cell",
        DoorsLabel.TBD to "TBD",
        NodeLabel.UNDEFINED to "Not yet imported",
    )

    // `DOORSModule` property labels for the settings dialog's tab 1 (requirements-modules.md
    // §4.1). Deliberately closed to this list — a property missing from here does not render,
    // rather than falling back to a string-manipulated guess (R5 forbids that in the frontend;
    // keeping the set closed here means it can't happen server-side either).
    public val modulePropertyLabels: Map<String, String> = mapOf(
        DoorsModuleAttr.DESCRIPTION to "Description",
        DoorsModuleAttr.FULL_PATH to "Path",
        DoorsModuleAttr.PREFIX to "Object ID prefix",
        DoorsModuleAttr.CREATED_BY to "Created by",
        DoorsModuleAttr.CREATED_ON to "Created on",
        DoorsModuleAttr.LAST_MODIFIED_BY to "Last modified by",
        DoorsModuleAttr.LAST_MODIFIED_ON to "Last modified",
        DoorsModuleAttr.MODULE_TYPE to "Module type",
        DoorsModuleAttr.WORD_DOC_BASELINE to "Word export baseline",
        DoorsModuleAttr.WORD_DOC_CAPTION_LEVEL to "Word export caption level",
        DoorsModuleAttr.WORD_DOC_ISSUE to "Word export issue",
        DoorsModuleAttr.WORD_DOC_NUMBER to "Word export number",
        DoorsModuleAttr.WORD_DOC_TITLE to "Word export title",
    )

    // __version's value, not its name, is content the user needs (R5): "current" reads as
    // "Current"; any other value is already human-readable and passes through.
    //
    // The *name* is shown as "Version", not "Baseline". A DOORS baseline is a frozen, numbered
    // release of a module, and this field is not that — it is which snapshot of the module an
    // object came from, "current" for everything imported so far. Calling it Baseline claimed a
    // word that real baselines will need when they arrive.
    public fun renderVersionValue(raw: String): String = if (raw == "current") "Current" else raw

    // Which label answers "what kind of object is this". Neo4j returns labels in no defined
    // order, so this cannot be "the first one that maps" — an object labelled both DOORSTBD and
    // DOORSTableCell would render differently between two reads of the same node.
    //
    // The order encodes a real distinction: the first group is the Object Type enum the importer
    // derives, the second is structural markers a node carries *in addition* to its type. A table
    // cell's type is still its Object Type; being inside a table is not a type. __UNDEFINED is
    // last because a placeholder has no type at all, and that is what the wording says.
    private val TYPE_LABEL_PRIORITY: List<String> = listOf(
        DoorsLabel.REQUIREMENT,
        DoorsLabel.HEADING,
        DoorsLabel.APP_MATRIX_HEADING,
        DoorsLabel.APP_MATRIX,
        DoorsLabel.INFORMATION,
        DoorsLabel.TBD,
        DoorsLabel.TABLE,
        DoorsLabel.TABLE_ROW,
        DoorsLabel.TABLE_CELL,
        NodeLabel.UNDEFINED,
    )

    // The wording for an object's Type column (REQ_REVIEW.md §5). __typeRaw is source content and
    // wins whenever the importer captured it; otherwise the type label is mapped through
    // `typeLabels`. Returns null when neither is available — the caller renders nothing rather
    // than inventing a word.
    public fun renderType(typeRaw: String?, labels: List<String>): String? {
        if (!typeRaw.isNullOrBlank()) {
            return typeRaw
        }
        val present = labels.toSet()
        return TYPE_LABEL_PRIORITY.firstOrNull { it in present }?.let(typeLabels::get)
    }

    // -- The dependency graph's vocabulary (docs/REQ_BREAKDOWN_GRAPH_VIEW §2, §4.1, §4.4) ------

    /**
     * The scope control's wording, naming the relation rather than a direction.
     *
     * "Downstream" and "upstream" are the words the spec uses and they are the words this product
     * must not use: an outgoing `refersTo` is read here as *this requirement refines its target*,
     * so following it goes **up** the decomposition while the spec calls it downstream. Naming the
     * relation instead leaves nothing to get backwards.
     */
    public val graphDirectionLabels: Map<GraphDirection, String> = mapOf(
        GraphDirection.OUTGOING to "What these refine",
        GraphDirection.INCOMING to "What refines these",
        GraphDirection.BOTH to "Both directions",
    )

    /** The level strategies, as the dialog's overflow menu names them. */
    public val graphLevelStrategyLabels: Map<GraphLevelStrategy, String> = mapOf(
        GraphLevelStrategy.MODULE_SYSTEM_LEVEL to "System level of the module",
        GraphLevelStrategy.OUTLINE_LEVEL to "Outline level in the module",
        GraphLevelStrategy.GRAPH_RANK to "Position in this graph",
    )

    /**
     * What a band is called, given the strategy that produced it.
     *
     * The system-level strategy reuses [SystemLevel]'s own wording rather than inventing a second
     * spelling of "L2 – Segment": the band and the badge inside it name the same thing, so a
     * mismatch between them would read as two different levels.
     */
    public fun graphBandLabel(strategy: GraphLevelStrategy, level: Int): String = when (strategy) {
        GraphLevelStrategy.MODULE_SYSTEM_LEVEL ->
            SystemLevel.entries.getOrNull(level)?.label ?: "Level $level"

        GraphLevelStrategy.OUTLINE_LEVEL -> "Outline level $level"

        // A position, not a level: this strategy ranks the picture, and saying so stops the number
        // being read as a system level it has nothing to do with.
        GraphLevelStrategy.GRAPH_RANK -> if (level == 0) "Top of this graph" else "Step ${level + 1}"
    }

    /**
     * The band everything the strategy could not place falls into — always drawn, always last, and
     * never quietly merged into a real level (§4.1).
     *
     * It says what is missing rather than that something is wrong: a module nobody has classified
     * is a normal state, and the fix is a click in the Modules settings dialog.
     */
    public fun graphUnplacedBandLabel(strategy: GraphLevelStrategy): String = when (strategy) {
        GraphLevelStrategy.MODULE_SYSTEM_LEVEL -> "No system level set"
        GraphLevelStrategy.OUTLINE_LEVEL -> "No outline level"
        GraphLevelStrategy.GRAPH_RANK -> "Not placed"
    }

    /**
     * A placeholder whose owning module has not been imported either, so there is no module node to
     * name — and the `__moduleUrl` it does carry is an internal identifier that never reaches a
     * user (R5). This sentence is the whole of what can honestly be said.
     */
    public const val UNNAMED_UNRESOLVED_MODULE: String = "A module that has not been imported yet"

    /**
     * An object DOORS deleted while keeping the links to it — the `:__DELETED` label, in words
     * (ADR 0012). Never *Not yet imported*, which is the opposite situation and sends a reviewer
     * to run an import that cannot help; and never the label itself, which no user sees (R5).
     *
     * The hint says where the fix is, because it is the one finding in this application that
     * cannot be fixed *in* this application. The graph is reporting the source correctly; it is
     * the source that is wrong.
     */
    public const val DELETED_IN_SOURCE: String = "Deleted in DOORS"
    public const val DELETED_IN_SOURCE_HINT: String =
        "This object was deleted in DOORS and the links to it were left behind. " +
            "The link has to be removed in DOORS."

    /**
     * The Req review filter for rows carrying at least one such link (`REQ_REVIEW.md` §11).
     *
     * Worth its own filter rather than a search of the Issues column because it is the one
     * finding a reviewer cannot act on from inside the table, so the working pattern is to
     * collect them all and take the list to DOORS.
     */
    public const val DELETED_LINKS_FILTER: String = "Links to or from deleted objects"
}

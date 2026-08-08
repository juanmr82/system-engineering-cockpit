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
}

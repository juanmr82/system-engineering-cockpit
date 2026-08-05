package com.sec.domain

// R5's single source of truth: internal __-prefixed property/relationship names to user-facing
// labels. API DTOs are built from this. The frontend never carries a second copy and never
// string-manipulates a property name to make it presentable. Extend this table when adding a
// field, do not invent aliases locally (CLAUDE.md §2 R5 reference alias map).
public object Aliases {
    public val propertyLabels: Map<String, String> = mapOf(
        "__name" to "Name",
        "__version" to "Baseline",
        "__typeRaw" to "Type",
        "__moduleUrl" to "Module",
        "__createdBy" to "Added by",
        "__createdAt" to "Added on",
        "refersTo" to "References",
    )

    public val metaKindLabels: Map<String, String> = mapOf(
        "review" to "Review",
        "tag" to "Tag",
        "note" to "Note",
        "flag" to "Flag",
        "policy" to "Rule",
        "link" to "Link",
        "classification" to "Classification",
        "attributeSetting" to "Attribute setting",
    )

    // Scheme- and rule-scoped labels (docs/features/requirements-modules.md §4.1, §4.2).
    public val classificationSchemeLabels: Map<String, String> = mapOf(
        "systemLevel" to "System level",
    )

    public val policyRuleLabels: Map<String, String> = mapOf(
        "mandatory" to "Mandatory attribute",
    )

    // The three per-module attribute flags of the Req review settings dialog (REQ_REVIEW.md §6).
    // `mandatory` is the :__Policy rule above under its dialog-column wording; the other two are
    // :__AttributeSetting payload fields (§9.2).
    public val attributeSettingLabels: Map<String, String> = mapOf(
        "mandatory" to "Mandatory",
        "visible" to "Shown in table",
        "verification" to "Verification attribute",
    )

    // Type labels the importer derives, rendered as language rather than as label strings. The
    // API still ships raw `labels` as a state channel (CLAUDE.md §5) — this is what the *text*
    // must say when a label is all we have. A type absent from here has no wording of its own
    // and falls back to __typeRaw, which is source content and always preferred when present.
    public val typeLabels: Map<String, String> = mapOf(
        "DOORSRequirement" to "Requirement",
        "DOORSHeading" to "Heading",
        "DOORSInformation" to "Information",
        "DOORSAppMatrix" to "Applicability matrix",
        "DOORSAppMatrixHeading" to "Applicability matrix heading",
        "DOORSTable" to "Table",
        "DOORSTableRow" to "Table row",
        "DOORSTableCell" to "Table cell",
        "DOORSTBD" to "TBD",
        "__UNDEFINED" to "Not yet imported",
    )

    // `DOORSModule` property labels for the settings dialog's tab 1 (requirements-modules.md
    // §4.1). Deliberately closed to this list — a property missing from here does not render,
    // rather than falling back to a string-manipulated guess (R5 forbids that in the frontend;
    // keeping the set closed here means it can't happen server-side either).
    public val modulePropertyLabels: Map<String, String> = mapOf(
        "description" to "Description",
        "moduleFullPath" to "Path",
        "prefix" to "Object ID prefix",
        "created_By" to "Created by",
        "created_On" to "Created on",
        "last_Modified_By" to "Last modified by",
        "last_Modified_On" to "Last modified",
        "_ModuleType" to "Module type",
        "wordDocBaseline" to "Word export baseline",
        "wordDocCaptionLevel" to "Word export caption level",
        "wordDocIssue" to "Word export issue",
        "wordDocNumber" to "Word export number",
        "wordDocTitle" to "Word export title",
    )

    // __version's value, not its name, is content the user needs (R5): "current" reads as
    // "Current"; any other baseline value is already human-readable and passes through.
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
        "DOORSRequirement",
        "DOORSHeading",
        "DOORSAppMatrixHeading",
        "DOORSAppMatrix",
        "DOORSInformation",
        "DOORSTBD",
        "DOORSTable",
        "DOORSTableRow",
        "DOORSTableCell",
        "__UNDEFINED",
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

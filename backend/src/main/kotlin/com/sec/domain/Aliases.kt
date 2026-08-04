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
    )

    // Scheme- and rule-scoped labels (docs/features/requirements-modules.md §4.1, §4.2).
    public val classificationSchemeLabels: Map<String, String> = mapOf(
        "systemLevel" to "System level",
    )

    public val policyRuleLabels: Map<String, String> = mapOf(
        "mandatory" to "Mandatory attribute",
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
}

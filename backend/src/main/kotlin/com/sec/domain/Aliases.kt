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
    )
}

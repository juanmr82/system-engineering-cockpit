package com.sec.domain

// Expected failures modelled as a sealed result rather than exceptions (CLAUDE.md §11), the same
// discipline SaveModuleSettingsOutcome.kt applies. docs/features/access-control.md §9, §10.2 —
// AccessAdminService, phase 6.

/** One category row, as the Categories screen's table needs it (spec §10.2 screen 1). */
public data class AccessCategorySummary(
    public val metaId: String,
    public val key: String,
    public val name: String,
    public val description: String,
    public val everyGroup: Boolean,
    public val objectCount: Long,
    public val groupCount: Long,
)

public sealed interface CreateCategoryOutcome {
    public data class Created(public val category: AccessCategorySummary) : CreateCategoryOutcome

    /** The pre-check on `key`, not the `access_category_key` constraint's own exception. */
    public data object KeyInUse : CreateCategoryOutcome
}

public sealed interface UpdateCategoryOutcome {
    public data class Updated(public val category: AccessCategorySummary) : UpdateCategoryOutcome

    public data object NotFound : UpdateCategoryOutcome
}

public sealed interface DeleteCategoryOutcome {
    public data object Deleted : DeleteCategoryOutcome

    public data object NotFound : DeleteCategoryOutcome

    /** Spec §9: "409 if any object or grant still references it," with the counts in the message. */
    public data class InUse(public val objectCount: Long, public val groupCount: Long) : DeleteCategoryOutcome
}

/** One row of the Grants screen's matrix (spec §10.2 screen 2) — a group and the category ids
 *  (`__metaId`s) it may read; the matrix itself is built client-side from every group's own row. */
public data class GroupWithGrants(
    public val key: String,
    public val name: String,
    public val seesAll: Boolean,
    public val categoryIds: List<String>,
    public val firstSeenAt: String,
    public val lastSeenAt: String,
)

public sealed interface SaveGrantsOutcome {
    public data class Saved(public val group: GroupWithGrants) : SaveGrantsOutcome

    /** No `:__Group` node yet — nobody in this group has signed in (spec §5's own resolver note). */
    public data object GroupNotFound : SaveGrantsOutcome

    /** A client may only grant a category that actually exists (same discipline `MetaWriter`'s
     *  writes already apply to an arbitrary id in a request body). */
    public data class UnknownCategories(public val categoryIds: List<String>) : SaveGrantsOutcome
}

public sealed interface SetSeesAllOutcome {
    public data class Updated(public val group: GroupWithGrants) : SetSeesAllOutcome

    public data object GroupNotFound : SetSeesAllOutcome
}

/** One row of the Unassigned queue (spec §10.2 screen 3) — a container with no direct category,
 *  and how many of its members carry none at all. */
public data class UnassignedContainer(
    public val containerId: String,
    public val sourceId: String,
    public val name: String,
    public val invisibleItemCount: Long,
)

/**
 * Shared by `PUT /access/containers/{ref}/categories` and `PUT /access/items/{ref}/categories`
 * (spec §8.1's escape hatch) — the anchor's direct category set, replaced whole (R7).
 */
public sealed interface SaveDirectCategoriesOutcome {
    public data class Saved(public val categoryIds: List<String>) : SaveDirectCategoriesOutcome

    public data object AnchorNotFound : SaveDirectCategoriesOutcome

    public data class UnknownCategories(public val categoryIds: List<String>) : SaveDirectCategoriesOutcome
}

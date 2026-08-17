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

package com.sec.domain

// Expected failures modelled as a sealed result rather than exceptions (CLAUDE.md §11: "Result/
// sealed types over exceptions for expected failures"). The route maps each case to its HTTP
// status directly; nothing here throws. Adding a failure mode is a compile error at every
// call site, which is the point.
public sealed interface SaveModuleSettingsOutcome {
    public data object ModuleNotFound : SaveModuleSettingsOutcome

    public data class InvalidSystemLevel(public val code: String) : SaveModuleSettingsOutcome

    public data class UnknownAttributes(public val names: List<String>) : SaveModuleSettingsOutcome

    public data object Saved : SaveModuleSettingsOutcome
}

// The batch comment save behind the review table's save icon (docs/REQ_REVIEW.md §5.2). Partial
// success is impossible by construction: every comment is written in one transaction, so the
// outcome is one value for the whole batch.
public sealed interface SaveCommentsOutcome {
    public data object ModuleNotFound : SaveCommentsOutcome

    /** Refs that are not objects of this module. A client may only comment on what it loaded. */
    public data class UnknownItems(public val refs: List<String>) : SaveCommentsOutcome

    /** Refs that could not be decoded from base64url — a malformed request, not a missing item. */
    public data class MalformedRefs(public val refs: List<String>) : SaveCommentsOutcome

    public data class Saved(public val comments: List<SavedComment>) : SaveCommentsOutcome
}

// What the server actually stored, echoed back so the table can clear its dirty marks without a
// reload (REQ_REVIEW.md §8). A deleted comment comes back with a null `comment`.
public data class SavedComment(
    public val itemId: String,
    public val metaId: String?,
    public val text: String?,
    public val updatedAt: String?,
)

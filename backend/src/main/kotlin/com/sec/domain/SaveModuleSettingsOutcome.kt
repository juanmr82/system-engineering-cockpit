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

/**
 * The batch system-level save behind the Modules table's save icon
 * (`docs/features/requirements-modules.md`).
 *
 * Same shape and same reasoning as the comment batch: one transaction for every changed row, so
 * partial success is impossible and the outcome is one value for the whole batch. This one spans
 * *modules* rather than the objects of one module, which is why it is not a module-scoped route.
 */
public sealed interface SaveSystemLevelsOutcome {
    /** Refs that are not modules. A client may only classify what the list actually returned. */
    public data class UnknownModules(public val refs: List<String>) : SaveSystemLevelsOutcome

    /** Refs that could not be decoded from base64url — a malformed request, not a missing module. */
    public data class MalformedRefs(public val refs: List<String>) : SaveSystemLevelsOutcome

    public data class InvalidSystemLevel(public val code: String) : SaveSystemLevelsOutcome

    public data class Saved(public val levels: List<SavedSystemLevel>) : SaveSystemLevelsOutcome
}

/** What was stored for one module. A cleared classification comes back with a null `code`. */
public data class SavedSystemLevel(
    public val moduleId: String,
    public val code: String?,
)

package com.sec.domain

// Expected failures modelled as a sealed result rather than exceptions (CLAUDE.md §11: "Result/
// sealed types over exceptions for expected failures"). The route maps each case to its HTTP
// status directly; nothing here throws.
public sealed interface SaveModuleSettingsOutcome {
    public data object ModuleNotFound : SaveModuleSettingsOutcome

    public data class InvalidSystemLevel(public val code: String) : SaveModuleSettingsOutcome

    public data class UnknownAttributes(public val names: List<String>) : SaveModuleSettingsOutcome

    public data object Saved : SaveModuleSettingsOutcome
}

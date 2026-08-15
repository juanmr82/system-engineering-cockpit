package com.sec.security

// The audit identity (__createdBy / __updatedBy, R2) for a write with no signed-in caller behind
// it. Every route that writes Tier-2 data on a user's behalf now passes SecPrincipal.auditName
// instead (ModuleRoutes, ReviewRoutes, JiraRoutes) — this default only remains live for two kinds
// of caller that are correctly not a human: AccessReconciler's own propagate/retract/seed writes
// (its class doc explains why "system" is permanent there, not provisional), and tests that write
// Tier-2 data directly through MetaWriter/JiraColumnStore/JiraSettingsStore without a session.
public object CurrentUser {
    public const val PLACEHOLDER: String = "system"
}

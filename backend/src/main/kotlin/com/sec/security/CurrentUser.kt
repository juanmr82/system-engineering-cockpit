package com.sec.security

// Placeholder for the audit identity on every Tier-2 write (__createdBy / __updatedBy). No auth
// layer exists yet (CLAUDE.md §7: "exactly one credential, the service account" — user identity
// is Ktor-layer work not yet built). This is the one seam a real auth plugin plugs into later;
// every meta writer already takes a `user: String` parameter rather than hardcoding this inline.
public object CurrentUser {
    public const val PLACEHOLDER: String = "system"
}

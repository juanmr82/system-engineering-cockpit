package com.sec.security

// The four-layer guard for the ad-hoc Cypher console: read access mode, static validation,
// EXPLAIN plan inspection, resource limits. Implement exactly the four layers in
// docs/CYPHER_API_DESIGN.md — do not simplify, and do not wire this in before the read API
// works. Static validation must tokenize the query, never substring-match it (CLAUDE.md §5).
public class CypherGuard

package com.sec.meta

import com.sec.graph.GraphDriver

// The single guarded write path for Tier-2 data (CLAUDE.md R2). Every write that touches
// :__Meta nodes and their __-prefixed relationships goes through here, and nothing else does —
// enforced in this one place, not per route. __metaKind is validated against the closed enum
// before anything reaches the database; an unknown kind is rejected here as a 400, never
// silently written.
public class MetaWriter(private val graphDriver: GraphDriver)

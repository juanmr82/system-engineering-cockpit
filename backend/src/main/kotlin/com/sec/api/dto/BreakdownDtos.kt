package com.sec.api.dto

import kotlinx.serialization.Serializable

// Wire shapes for docs/requirement-breakdown-tree.md §6. `ref` is always the base64url encoding of
// __id (R5) — never __id itself, and no __-prefixed name appears in a field name or a value.
//
// The node payload itself is not here: it is [RequirementCardDto], shared with the dependency
// graph, so one requirement has one card shape across every view that draws one.

/**
 * One `refersTo` edge, read as **`from` refines `to`** (§2).
 *
 * That reading is a display convention for this tab and nothing more. It reuses the word "refines"
 * from the Shape-C link vocabulary purely as the generic verb for "traces upward toward", and is
 * never to be confused with an authored `:__Meta:__Link` carrying `semantics: 'refines'` — the tab
 * says so visibly, once (§2).
 *
 * `cyclic` marks the closing edge of a `refersTo` cycle. Nothing in Community's schema forbids one
 * (CLAUDE.md §7), so the traversal detects it and the client draws it as a chip rather than
 * recursing forever (§3).
 */
@Serializable
public data class BreakdownEdgeDto(
    public val from: String,
    public val to: String,
    public val cyclic: Boolean = false,
)

/**
 * The whole forest: flat node and edge lists with the DAG structure intact.
 *
 * The client applies the primary-parent rule (§3) when it renders, not the server, because "which
 * chain contains the item the reviewer clicked" is a rendering concern the client already knows
 * without a second call.
 *
 * `truncated` is true when a bound was hit mid-traversal — the panel says so in a footer rather
 * than presenting a partial forest as complete (§6).
 */
@Serializable
public data class BreakdownResponseDto(
    public val selectedRef: String,
    public val roots: List<String>,
    public val truncated: Boolean,
    public val nodes: List<RequirementCardDto>,
    public val edges: List<BreakdownEdgeDto>,
)

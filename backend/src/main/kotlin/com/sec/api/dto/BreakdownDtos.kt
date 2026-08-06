package com.sec.api.dto

import kotlinx.serialization.Serializable

// Wire shapes for docs/requirement-breakdown-tree.md §6. `ref` is always the base64url encoding of
// __id (R5) — never __id itself, and no __-prefixed name appears in a field name or a value.

/** One verification attribute of a node's module, name and value (§4). */
@Serializable
public data class BreakdownAttributeDto(
    public val name: String,
    public val value: String,
)

/**
 * One node of the breakdown forest.
 *
 * `resolved` is false for a placeholder the importer created for an object no import has reached.
 * Such a node carries no DOORS `id` — its `__name` is its `__id` spelled out, which R5 keeps off
 * the wire — so `id` is null and the wording plus the owning module's name is all the UI can
 * honestly show, exactly as in the References column (§7).
 *
 * `level` is the module's system-level classification resolved to code **and** label, reusing the
 * shape the Modules table already speaks, so the client never maps a stored code to wording of its
 * own (R5). Null when the owning module has no classification: the row renders with no chip rather
 * than with a blank one (§2).
 */
@Serializable
public data class BreakdownNodeDto(
    public val ref: String,
    public val id: String? = null,
    public val level: SystemLevelOptionDto? = null,
    public val description: String,
    public val resolved: Boolean = true,
    public val moduleRef: String? = null,
    public val moduleName: String? = null,
    public val verificationAttributes: List<BreakdownAttributeDto> = emptyList(),
)

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
    public val nodes: List<BreakdownNodeDto>,
    public val edges: List<BreakdownEdgeDto>,
)

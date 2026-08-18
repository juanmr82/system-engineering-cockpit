package com.sec.domain

/**
 * The dependency graph's request options (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §3.1, §4.1).
 *
 * Both are closed enums parsed at the API boundary, so an unknown value is a `400` rather than a
 * silently substituted default — the same discipline `__metaKind` gets (R2).
 */

/**
 * Which way the walk follows `refersTo`.
 *
 * **Named by the data, not by "up" and "down".** The spec calls the outgoing direction
 * `DOWNSTREAM` (§3.1), which is the opposite of what this product means by it: an outgoing
 * `refersTo` is read here as *this requirement refines its target*, so following it goes **up** the
 * decomposition, toward the customer requirement. Two words for one arrow, pointing opposite ways,
 * is how a reviewer reads a traceability picture backwards — so neither word is used. The wording a
 * user sees is in [Aliases.graphDirectionLabels], and it names the relation instead.
 */
public enum class GraphDirection {
    /** What these requirements refine. */
    OUTGOING,

    /** What refines these requirements. */
    INCOMING,

    /** Both, which is the default: a dependency picture with one side missing is a lie by omission. */
    BOTH,
    ;

    public companion object {
        public fun fromNameOrNull(raw: String): GraphDirection? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * How a node's vertical band is decided (§4.1).
 *
 * The vertical axis **is** the level axis, which is what makes "a bit lower" read as "still in the
 * same band, but downstream". The word "level" is overloaded in this project, so the choice is a
 * strategy rather than a constant, and the dialog offers it.
 *
 * [MODULE_SYSTEM_LEVEL] is the default and is **not** the spec's `MODULE_SE_LEVEL`. That one parses
 * a level out of the owning module's `moduleFullPath` with a configurable regex, guessed from a
 * single example path — and it was written before this product had a real per-module system level.
 * It does now: `:__Classification` with `scheme: systemLevel`, carrying L0–L4, set by a human in the
 * Modules settings dialog, already resolved onto every requirement card, already the source of the
 * level badge in the Breakdown tab and of the `--sec-level-*` colour ramp. Deriving a *second*,
 * differently-numbered notion of level from a path pattern would put two answers to the same
 * question on one screen — a badge reading L2 inside a band reading Level 1. See ADR 0011.
 */
public enum class GraphLevelStrategy {
    /** The owning module's L0–L4 classification. Unknown when nobody has classified the module. */
    MODULE_SYSTEM_LEVEL,

    /** The object's own outline depth. Meaningful when the graph is inside one module. */
    OUTLINE_LEVEL,

    /**
     * Longest path down the `refersTo` DAG, computed over the returned subgraph only.
     *
     * The fallback for a graph whose modules carry no classification at all: it always produces a
     * band for every node, and it is the only strategy whose answer changes when the scope changes,
     * because it is a property of the picture rather than of the data.
     */
    GRAPH_RANK,
    ;

    public companion object {
        public fun fromNameOrNull(raw: String): GraphLevelStrategy? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

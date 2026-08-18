package com.sec.api.dto

import kotlinx.serialization.Serializable

// Wire shapes for docs/REQ_BREAKDOWN_GRAPH_VIEW.md §3.3. `ref` is always the base64url encoding of
// __id (R5) — never __id itself, and no __-prefixed name appears in a field name or a value.

/**
 * One node: the shared requirement card, plus where the picture puts it.
 *
 * The card carries the identity, so there is no `itemId` beside it — `card.ref` is the only key,
 * and it is the opaque handle rather than the raw `__id` the spec's sketch names. There is no
 * `moduleUrl` either: `__moduleUrl` is an internal name whose *value* is an internal identifier,
 * and the card already carries `moduleRef` and `moduleName`, which is what a banner or a tooltip
 * can honestly show (R5). `isPlaceholder` is `card.resolved`, already on the card, and stating it
 * twice is how the two come to disagree.
 *
 * [level] is the strategy's raw level, **not** a band index: null means unknown, and the client
 * renumbers the distinct levels into dense layout partitions itself (§4.2, §7). Handing over a
 * pre-densified index would put the renumbering — the thing with the interesting edge cases — on
 * the side of the wire that has no unit tests for it.
 */
@Serializable
public data class GraphNodeDto(
    public val card: RequirementCardDto,
    public val level: Int? = null,
    public val seed: Boolean = false,
    /**
     * Neighbours this node has that the picture does not contain — cut by the node cap or by the
     * depth bound.
     *
     * Counted over both directions and reported so the node can carry a badge: a graph that simply
     * stopped, with no mark where it stopped, is read as a graph that ended (§1.1, §5.7).
     */
    public val truncatedNeighbours: Int = 0,
)

/**
 * One `refersTo` edge, source → target, drawn with an arrowhead and **no label**.
 *
 * There is deliberately no `type`, `label` or `weight` field. The DXL exporter discards the DOORS
 * link-module name (importer spec §10), so satisfies / verifies / refines do not exist in the
 * graph and there is nothing truthful to label an edge with. An empty field would get filled with
 * a guess (§8).
 */
@Serializable
public data class GraphEdgeDto(
    public val source: String,
    public val target: String,
)

/** One horizontal band, top to bottom. [level] null is the band unknown-level nodes fall into. */
@Serializable
public data class LevelBandDto(
    public val level: Int? = null,
    public val label: String,
)

/**
 * A module something in the picture points into that no import has reached (§1.1).
 *
 * Named rather than counted, because the action this prompts is "import that module" and a count
 * does not say which. `ref` is null when the module node itself has not been imported either —
 * a placeholder carries `__moduleUrl` and nothing else, so there is no module node to link to and
 * the name is all there is.
 */
@Serializable
public data class UnresolvedModuleDto(
    public val ref: String? = null,
    public val name: String,
    public val count: Int,
)

/**
 * The whole picture.
 *
 * `truncated` is true when the node cap stopped the walk — the dialog says so above the canvas
 * rather than presenting a partial graph as complete. Which nodes were on the boundary is on the
 * nodes themselves, as `truncatedNeighbours`.
 */
@Serializable
public data class DependencyGraphDto(
    public val seedRefs: List<String>,
    public val depth: Int,
    public val direction: String,
    public val levelStrategy: String,
    public val nodes: List<GraphNodeDto>,
    public val edges: List<GraphEdgeDto>,
    public val levels: List<LevelBandDto>,
    public val truncated: Boolean = false,
    public val unresolvedModules: List<UnresolvedModuleDto> = emptyList(),
)

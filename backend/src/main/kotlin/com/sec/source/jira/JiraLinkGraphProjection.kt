package com.sec.source.jira

import com.sec.api.dto.JiraGraphEdgeDto
import com.sec.api.dto.JiraGraphNodeDto
import com.sec.api.dto.JiraLinkGraphDto
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.executeRead
import com.sec.security.AccessSet
import org.neo4j.driver.Query
import org.neo4j.driver.Value

/**
 * The related-issues graph of one issue (spec §13.2's References column, JIRA's answer to it).
 *
 * ## A breadth-first walk in Kotlin, not one variable-length pattern
 *
 * The three reasons are `DependencyGraphCypher`'s, unchanged: Neo4j will not take a parameter as a
 * variable-length bound; the node cap has to be breadth-first from the seed, which a closure match
 * cannot express; and running the neighbour statement once more over the *admitted* set answers
 * both remaining questions at once — which edges are inside the picture, and how many links each
 * node has that the picture leaves out.
 *
 * ## What is deliberately not here
 *
 * No level bands and no direction control. DOORS has both because `refersTo` is a decomposition and
 * a requirement has a system level; a JIRA link is a lattice of *Blocks* and *Relates* between
 * issues that have neither. Rank in the layout is distance from the seed, and both directions are
 * always followed, because "what is this issue related to" has no upstream.
 */
public class JiraLinkGraphProjection(private val graphDriver: GraphDriver) {

    /**
     * The graph around [issueId], to [depth] hops.
     *
     * The seed itself is always node zero. An issue with no links comes back as one node and no
     * edges rather than as an error — the column that opens this only offers the control when there
     * is something to see, but a link can be deleted between the page loading and the click.
     */
    public suspend fun graphOf(issueId: String, depth: Int, access: AccessSet): JiraLinkGraphDto? {
        val seedRef = Ref.encode(issueId)
        val admitted = walk(issueId, depth.coerceIn(MIN_DEPTH, MAX_DEPTH), access)

        // The walk starts from the id it was given whether or not anything carries it, so an
        // unknown handle produces a non-empty *set* and an empty *graph*. Reading the nodes is what
        // tells the two apart, and the difference matters: a hand-edited address must be a 404,
        // never a picture of nothing offered as the answer.
        val found = nodesOf(admitted, access)
        if (found.none { it.ref == seedRef }) return null

        val links = neighboursOf(admitted, access)

        // An edge belongs to the picture when both of its ends do. The rest are what each node's
        // badge counts: a link that exists and is not drawn, which is the one thing a graph must
        // never leave a reader to infer from an absence.
        val edges = links
            .filter { it.sourceId in admitted && it.targetId in admitted }
            .map {
                JiraGraphEdgeDto(
                    source = Ref.encode(it.sourceId),
                    target = Ref.encode(it.targetId),
                    typeName = it.typeName,
                    subTask = it.subTask,
                )
            }
            .distinct()

        val cut = links
            .filterNot { it.sourceId in admitted && it.targetId in admitted }
            .groupingBy { it.fromId }
            .eachCount()

        val nodes = found.map { node ->
            node.copy(
                seed = node.ref == seedRef,
                truncatedNeighbours = cut[Ref.decodeOrNull(node.ref) ?: ""] ?: 0,
            )
        }

        return JiraLinkGraphDto(
            seedRef = seedRef,
            depth = depth,
            nodes = nodes,
            edges = edges,
            truncated = cut.isNotEmpty() || admitted.size >= MAX_NODES,
        )
    }

    /**
     * Breadth-first from the seed, one hop per query, stopping at the depth or the cap.
     *
     * A `LinkedHashSet` because the order is the picture's: the seed first, then its neighbours in
     * `__sortKey` order, then theirs. That order is what makes the same issue draw the same diagram
     * twice — ELK is deterministic given a deterministic input, and this is the input.
     */
    private suspend fun walk(seedId: String, depth: Int, access: AccessSet): Set<String> {
        val admitted = LinkedHashSet<String>()
        admitted += seedId
        var frontier = listOf(seedId)

        repeat(depth) {
            if (frontier.isEmpty() || admitted.size >= MAX_NODES) return@repeat

            val next = neighboursOf(frontier.toSet(), access)
                .map { it.otherId }
                .filterNot { it in admitted }
                .distinct()

            for (id in next) {
                if (admitted.size >= MAX_NODES) break
                admitted += id
            }
            frontier = next
        }

        return admitted
    }

    private suspend fun neighboursOf(ids: Set<String>, access: AccessSet): List<Link> =
        graphDriver.executeRead(
            JiraCypher.LINK_NEIGHBOURS,
            mapOf("ids" to ids.toList(), "limit" to NEIGHBOUR_LIMIT),
            access,
        ) { records ->
            records.map {
                Link(
                    fromId = it.get("fromId").asString(),
                    otherId = it.get("otherId").asString(),
                    sourceId = it.get("sourceId").asString(),
                    targetId = it.get("targetId").asString(),
                    subTask = it.get("relType").asString() == JiraRel.SUB_TASK_OF,
                    typeName = it.get("typeName").asStringOrNull(),
                )
            }
        }

    private suspend fun nodesOf(ids: Set<String>, access: AccessSet): List<JiraGraphNodeDto> =
        graphDriver.executeRead(
            JiraCypher.GRAPH_NODES, mapOf("ids" to ids.toList()), access,
        ) { records ->
            records.map {
                JiraGraphNodeDto(
                    ref = Ref.encode(it.get("id").asString()),
                    key = it.get("key").asStringOrNull().orEmpty(),
                    typeName = it.get("typeName").asStringOrNull(),
                    statusName = it.get("statusName").asStringOrNull(),
                    summary = it.get("summary").asStringOrNull(),
                    unresolved = it.get("unresolved").asBoolean(false),
                )
            }
        }

    /** One link as the walk needs it: which node it was found from, and how JIRA states it. */
    private data class Link(
        val fromId: String,
        val otherId: String,
        val sourceId: String,
        val targetId: String,
        val subTask: Boolean,
        val typeName: String?,
    )

    public companion object {
        public const val MIN_DEPTH: Int = 1
        public const val MAX_DEPTH: Int = 5
        public const val DEFAULT_DEPTH: Int = 2

        /**
         * The most nodes a picture may hold.
         *
         * The same number the DOORS graph uses, and for the same reason: past a few hundred a
         * diagram stops being readable long before it stops being renderable, and Community has no
         * query governor to stop the walk on its own (CLAUDE.md §7).
         */
        public const val MAX_NODES: Int = 300

        /** A ceiling per hop, so one pathologically linked issue cannot pull the whole graph in. */
        private const val NEIGHBOUR_LIMIT: Int = 2_000
    }
}

/** `null` for a null property, rather than the driver's exception on `asString()`. */
private fun Value.asStringOrNull(): String? = if (isNull) null else asString()

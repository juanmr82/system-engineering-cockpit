package com.sec.security

import com.sec.domain.Prop.ID
import com.sec.domain.Prop.MODULE_URL
import com.sec.source.doors.DoorsLabel.MODULE as DOORS_MODULE
import com.sec.source.doors.DoorsLabel.OBJECT as DOORS_OBJECT
import com.sec.source.jira.JiraLabel.ISSUE as JIRA_ISSUE
import com.sec.source.jira.JiraLabel.PROJECT as JIRA_PROJECT
import com.sec.source.jira.JiraRel.IN_PROJECT
import com.sec.source.windchill.WindchillLabel.DOCUMENT as WINDCHILL_DOCUMENT

/**
 * Declares how each source's items belong to a container, so [AccessReconciler] never has to name
 * a source (`docs/features/access-control.md` §8.2). One entry per source, contributed here rather
 * than split across each source's own package: the type is source-agnostic framework, the same
 * reason [com.sec.importer.ImportRequest] is empty and lives with the framework rather than with a
 * source — only the values in [all] name a source, and they do it as data, not as branching logic.
 *
 * @property sourceId matches [com.sec.importer.ImportJob.importerId] — how the import pipeline
 *   hook (`ImportRunService`) finds the containments a finished run should reconcile.
 * @property containerLabel a [com.sec.domain.NodeLabel] / source label constant, never a literal
 *   — for a [containerless] source, the item's own label, since there is no container to name.
 * @property memberMatch a Cypher pattern binding `o`, given a bound `c` — `(o:Label {...})` or
 *   `(o:Label)-[:rel]->(c)`. For a containerless source it binds `o` alone and never mentions `c`.
 */
public data class Containment(
    public val sourceId: String,
    public val containerLabel: String,
    public val memberMatch: String,
    public val containerless: Boolean = false,
)

public object AccessContainment {

    /**
     * DOORS objects carry `__moduleUrl` set to their module's own `__id` (`ModuleCypher.kt`'s own
     * comment: "a module's plain url property carries the same value as its __id … the module's
     * own __id is the value to bind here") rather than an edge to it — the indexed path
     * `docs/DOORS_TO_NEO4J_IMPORTER_SPEC.md` §7.3 already creates the index for.
     */
    private val doors: Containment = Containment(
        sourceId = "doors",
        containerLabel = DOORS_MODULE,
        memberMatch = "(o:$DOORS_OBJECT { $MODULE_URL: c.$ID })",
    )

    /** The `inProject` edge `IssueMapper` already writes on every issue — read here, not invented. */
    private val jira: Containment = Containment(
        sourceId = "jira",
        containerLabel = JIRA_PROJECT,
        memberMatch = "(o:$JIRA_ISSUE)-[:$IN_PROJECT]->(c)",
    )

    /**
     * No container yet (§8.2): every document gets the source default directly. When folder nodes
     * arrive (`__child`, R3) the folder becomes the container and only this entry changes.
     */
    private val windchill: Containment = Containment(
        sourceId = "windchill",
        containerLabel = WINDCHILL_DOCUMENT,
        memberMatch = "(o:$WINDCHILL_DOCUMENT)",
        containerless = true,
    )

    // Cameo is not yet imported (§8.2) — its entry arrives with the source, not before.
    public val all: List<Containment> = listOf(doors, jira, windchill)
}

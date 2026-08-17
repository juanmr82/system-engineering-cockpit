package com.sec.security

import com.sec.domain.NodeLabel
import com.sec.graph.cypher.AccessCypher
import com.sec.graph.cypher.BreakdownCypher
import com.sec.graph.cypher.DependencyGraphCypher
import com.sec.graph.cypher.ImportRunCypher
import com.sec.graph.cypher.ItemCypher
import com.sec.graph.cypher.JiraCypher
import com.sec.graph.cypher.ModuleCypher
import com.sec.graph.cypher.RequirementCardCypher
import com.sec.graph.cypher.ReviewCypher
import com.sec.graph.cypher.StatisticsCypher
import com.sec.graph.cypher.SystemCypher
import com.sec.graph.cypher.TableCypher
import com.sec.graph.cypher.WindchillCypher
import com.sec.meta.MetaSchema
import com.sec.source.doors.DoorsLabel
import com.sec.source.jira.JiraLabel
import com.sec.source.windchill.WindchillLabel
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What keeps R8's "one predicate, one function" true (`docs/features/access-control.md` §6.2).
 *
 * Every statement in `graph/cypher/` and `meta/MetaSchema.kt` that matches `:SEItem` or a type
 * label is either filtered — carrying the `/*ACL*/` marker [AccessCypher.visible] emits — or is
 * named here with a reason. Nothing in `source/` embeds Cypher of its own (every projection
 * delegates to a named constant in `graph/cypher/`), so scanning those two locations is scanning
 * every query this backend can issue, the same premise [com.sec.domain.GraphNamesTest] rests on.
 *
 * Deliberately its own statement list rather than sharing [com.sec.domain.GraphNamesTest]'s: the
 * two tests guard different things, and this codebase already prefers each test owning its own
 * fixture over a shared base (see `ReviewFeatureTest`'s container setup) — a second, independent
 * enumeration is also a second chance to notice a file the other missed.
 *
 * Phases 4 and 5 have both landed, so **nothing in [exemptions] is deferred any more**. Every
 * remaining entry is permanently exempt and says why in its own words: an importer's own writes,
 * the reconciler building `__inAccessCategory` itself, schema statements, per-user preference
 * nodes, and the JIRA field catalogue, which is instance schema with no container to inherit from.
 *
 * An entry leaving that map without the statement gaining a real `/*ACL*/` marker is a regression,
 * and [`every declared exemption still needs one`] catches it the moment it happens.
 */
class AccessGuardTest {

    // -- what must not be readable without the predicate -------------------------------------

    private val filteredLabels: Set<String> =
        setOf(NodeLabel.SE_ITEM) + DoorsLabel.all + JiraLabel.all + WindchillLabel.all

    // -- the statements, mirroring GraphNamesTest's own enumeration --------------------------

    private val statements: List<Pair<String, String>> = buildList {
        fun add(owner: String, vararg queries: String) =
            queries.forEachIndexed { i, q -> add("$owner[$i]" to q) }

        add("BreakdownCypher", BreakdownCypher.EDGES_UP, BreakdownCypher.EDGES_DOWN)
        add(
            "DependencyGraphCypher",
            DependencyGraphCypher.OUT_NEIGHBOURS, DependencyGraphCypher.IN_NEIGHBOURS,
            DependencyGraphCypher.SEEDS,
        )
        add(
            "RequirementCardCypher",
            RequirementCardCypher.NODES, RequirementCardCypher.VERIFICATION_ATTRIBUTES,
        )
        add("ItemCypher", ItemCypher.FIND_BY_ID)
        add(
            "ModuleCypher",
            ModuleCypher.LIST_MODULES, ModuleCypher.MODULE_DETAIL, ModuleCypher.DISCOVER_ATTRIBUTES,
            ModuleCypher.EXISTING_MANDATORY_POLICIES, ModuleCypher.MODULE_OBJECT_IDS,
            ModuleCypher.MODULE_EXISTS, ModuleCypher.SET_SYSTEM_LEVEL, ModuleCypher.CLEAR_SYSTEM_LEVEL,
            ModuleCypher.ADD_MANDATORY_POLICIES, ModuleCypher.REMOVE_MANDATORY_POLICIES,
        )
        add(
            "ReviewCypher",
            ReviewCypher.MODULE_OBJECTS, ReviewCypher.MANDATORY_POLICIES, ReviewCypher.MODULE_NAMES,
            ReviewCypher.COUNT_MODULE_OBJECTS, ReviewCypher.ITEM_DETAIL, ReviewCypher.ITEM_TRACES_OUT,
            ReviewCypher.ITEM_TRACES_IN, ReviewCypher.UPSERT_COMMENTS, ReviewCypher.DELETE_COMMENTS,
            ReviewCypher.READ_COMMENTS, ReviewCypher.EXISTING_ATTRIBUTE_SETTINGS,
            ReviewCypher.UPSERT_ATTRIBUTE_SETTINGS, ReviewCypher.DELETE_ATTRIBUTE_SETTINGS,
        )
        add(
            "StatisticsCypher",
            StatisticsCypher.MODULES_IN_SCOPE, StatisticsCypher.MODULE_OBJECTS,
            StatisticsCypher.COUNT_MODULE_OBJECTS, StatisticsCypher.DANGLING_TARGET_MODULES,
            StatisticsCypher.ALL_TRACE_EDGES, StatisticsCypher.LOOP_MEMBERS,
        )
        add("SystemCypher", SystemCypher.PING)
        add("TableCypher", TableCypher.MODULE_TABLES, TableCypher.RESOLVE_TABLE)
        add(
            "JiraCypher",
            JiraCypher.UPSERT_ISSUE_TYPES, JiraCypher.DELETE_UNUSED_ISSUE_TYPES,
            JiraCypher.UPSERT_FIELDS, JiraCypher.DELETE_STALE_FIELDS,
            JiraCypher.COUNT_CATALOGUE,
            JiraCypher.UPSERT_ENTITIES, JiraCypher.UPSERT_ISSUES,
            JiraCypher.UPSERT_PROJECTIONS, JiraCypher.MERGE_PROMOTED,
            JiraCypher.PRUNE_PROMOTED, JiraCypher.COUNT_ISSUES,
            JiraCypher.MERGE_PLACEHOLDERS, JiraCypher.MERGE_LINKS,
            JiraCypher.DELETE_STALE_LINKS, JiraCypher.MERGE_SUB_TASKS,
            JiraCypher.DELETE_STALE_SUB_TASKS,
            JiraCypher.SWEEP_DELETED,
            JiraCypher.DELETE_ORPHANED_ENTITIES, JiraCypher.DELETE_ORPHANED_PLACEHOLDERS,
            JiraCypher.COUNT_PLACEHOLDERS,
            JiraCypher.LIST_ISSUES_ASC, JiraCypher.LIST_ISSUES_DESC,
            JiraCypher.COUNT_ISSUES_MATCHING,
            JiraCypher.LIST_FIELDS, JiraCypher.FIND_FIELDS,
            JiraCypher.LOAD_COLUMNS, JiraCypher.SAVE_COLUMNS,
            JiraCypher.LINK_NEIGHBOURS, JiraCypher.GRAPH_NODES,
            *JiraCypher.SCHEMA.toTypedArray(),
        )
        add(
            "WindchillCypher",
            WindchillCypher.UPSERT_DOCUMENTS, WindchillCypher.SWEEP_DELETED,
            WindchillCypher.COUNT_DOCUMENTS, WindchillCypher.LIST_DOCUMENTS,
            *WindchillCypher.SCHEMA.toTypedArray(),
        )
        add(
            "ImportRunCypher",
            ImportRunCypher.UPSERT, ImportRunCypher.LOAD, ImportRunCypher.HISTORY,
            ImportRunCypher.PRUNE,
            *ImportRunCypher.SCHEMA.toTypedArray(),
        )
        add("MetaSchema", *MetaSchema.statements.toTypedArray())
        // The reconciler's own writes, read straight from AccessContainment.all so a new source's
        // containment is checked — and, below, exempted with a reason — the moment it is added.
        add(
            "AccessCypher",
            AccessCypher.RESOLVE_GROUPS,
            *AccessContainment.all.filterNot { it.containerless }
                .flatMap { listOf(AccessCypher.propagate(it), AccessCypher.retract(it)) }
                .toTypedArray(),
            *AccessContainment.all.map { AccessCypher.seed(it) }.toTypedArray(),
            // Categories (phase 6, §10.2 screen 1) — appended, not inserted, so [1]-[10] above keep
            // their indices and their exemptions.
            AccessCypher.CATEGORIES_WITH_COUNTS,
            AccessCypher.CATEGORY_KEY_EXISTS,
            AccessCypher.CREATE_CATEGORY,
            AccessCypher.UPDATE_CATEGORY,
            AccessCypher.CATEGORY_USAGE_COUNTS,
            AccessCypher.DELETE_CATEGORY_IF_UNUSED,
            // Groups & Grants (phase 6, §10.2 screen 2) — indices 17-22, none of which touch a
            // filtered label (:__Group and :__AccessCategory are both outside filteredLabels).
            AccessCypher.GROUPS_WITH_GRANTS,
            AccessCypher.GROUP_WITH_GRANTS,
            AccessCypher.GROUP_EXISTS,
            AccessCypher.UNKNOWN_CATEGORY_IDS,
            AccessCypher.REPLACE_GRANTS,
            AccessCypher.SET_SEES_ALL,
            // Unassigned containers & direct categories (phase 6, §10.2 screen 3) — indices 23-27.
            // EXISTS_BY_ID, REPLACE_DIRECT_CATEGORIES and DIRECT_CATEGORIES_OF match their anchor
            // unlabeled, so none of them touch a filtered label either; the two
            // unassignedContainers(...) calls (one per distinct containerLabel group — DOORSModule,
            // JiraProject) are exempt for a real reason, given below.
            AccessCypher.EXISTS_BY_ID,
            AccessCypher.REPLACE_DIRECT_CATEGORIES,
            AccessCypher.DIRECT_CATEGORIES_OF,
            *AccessContainment.all.filterNot { it.containerless }
                .groupBy { it.containerLabel }
                .map { (label, containments) ->
                    AccessCypher.unassignedContainers(label, containments.map { it.memberMatch })
                }
                .toTypedArray(),
        )
    }

    // -- the exemptions: every statement above that touches a type label and does not filter ----

    /**
     * Every statement touching a filtered label that legitimately does not carry the predicate.
     *
     * Each reason is stated per entry rather than once, so a reader who greps one statement's name
     * gets the answer without cross-referencing the build order. None is a deferral now: phase 4
     * filtered every read and phase 5 filtered every Tier-2 write, **including the ones whose
     * Kotlin caller already checks the anchor** — a filter that survives its caller being reordered
     * is worth more than one that does not.
     */
    private val exemptions: Map<String, String> = mapOf(
        "JiraCypher[0]" to "importer write path — issue-type catalogue upsert (ADR 0013)",
        "JiraCypher[1]" to "importer write path — unused-issue-type sweep",
        "JiraCypher[2]" to "importer write path — field catalogue upsert",
        "JiraCypher[3]" to "importer write path — stale-field sweep",
        "JiraCypher[4]" to "importer read path — catalogue count, importer progress reporting",
        "JiraCypher[5]" to "importer write path — JIRA entity upsert (ADR 0013); imports are unfiltered by design",
        "JiraCypher[6]" to "importer write path — JIRA issue upsert (ADR 0013)",
        "JiraCypher[7]" to "importer write path — JIRA issue-shape projection",
        "JiraCypher[8]" to "importer write path — promoted-field merge",
        "JiraCypher[9]" to "importer write path — promoted-field prune",
        "JiraCypher[10]" to "importer read path — issue count, used by the importer's own progress reporting",
        "JiraCypher[11]" to "importer write path — placeholder merge for unresolved link targets",
        "JiraCypher[12]" to "importer write path — issue-link merge",
        "JiraCypher[13]" to "importer write path — stale-link sweep",
        "JiraCypher[14]" to "importer write path — sub-task merge",
        "JiraCypher[15]" to "importer write path — stale-sub-task sweep",
        "JiraCypher[16]" to "importer write path — deleted-issue sweep (ADR 0018)",
        "JiraCypher[17]" to "importer write path — orphaned-entity cleanup",
        "JiraCypher[18]" to "importer write path — orphaned-placeholder cleanup",
        "JiraCypher[19]" to "importer read path — placeholder count, importer progress reporting",
        // Permanently exempt, not deferred: a :JiraField is a field *definition* — `summary`,
        // `customfield_18201` — describing the JIRA instance's own schema. It names no project
        // content and has no container to inherit a category from, so there is nothing for the
        // predicate to compare against; filtering it would empty the column picker for every user
        // until somebody categorised 1 171 definitions by hand.
        "JiraCypher[23]" to "schema, not project content — the JIRA field catalogue describes the " +
            "instance, carries no issue data, and has no container to inherit from",
        "JiraCypher[24]" to "schema, not project content — field catalogue lookup by id, same as above",
        "JiraCypher[25]" to "per-user preference node (:JiraColumnConfig), not an item",
        "JiraCypher[26]" to "per-user preference node (:JiraColumnConfig) — same as the load above",
        "JiraCypher[29]" to "schema (index/constraint), not a data read — same exemption class as MetaSchema",
        "JiraCypher[30]" to "schema (index/constraint), not a data read",
        "JiraCypher[31]" to "schema (index/constraint), not a data read",
        "JiraCypher[32]" to "schema (index/constraint), not a data read",
        "JiraCypher[33]" to "schema (index/constraint), not a data read",
        "JiraCypher[34]" to "schema (index/constraint), not a data read",
        "JiraCypher[35]" to "schema (index/constraint), not a data read",
        "JiraCypher[36]" to "schema (index/constraint), not a data read",
        "WindchillCypher[0]" to "importer write path — Windchill document upsert (ADR 0015)",
        "WindchillCypher[1]" to "importer write path — deleted-document sweep (ADR 0015)",
        "WindchillCypher[2]" to "importer read path — the document count WindchillGraphWriter " +
            "reads either side of its own sweep, for the mass-deletion warning (ADR 0015 §7). " +
            "Not the Documents view's count, which comes from the filtered listing below",
        "WindchillCypher[4]" to "schema (index/constraint), not a data read",
        "WindchillCypher[5]" to "schema (index/constraint), not a data read",
        "WindchillCypher[6]" to "schema (index/constraint), not a data read",
        // Indexed by position in AccessContainment.all — [doors, doorsPlaceholders, jira,
        // windchill] — so the propagate/retract pairs run [1]..[6] and the seeds [7]..[10].
        // Adding a containment renumbers every entry after it; that is what this comment is for.
        "AccessCypher[1]" to "AccessReconciler's own write — propagates a DOORS module's direct " +
            "category to its objects; builds __inAccessCategory itself, not a read subject to it",
        "AccessCypher[2]" to "AccessReconciler's own write — retracts a DOORS object's inherited " +
            "category once its module no longer carries it directly",
        "AccessCypher[3]" to "AccessReconciler's own write — propagates a DOORS module's direct " +
            "category to the :__UNDEFINED placeholders that name it in __moduleUrl (§16.1a)",
        "AccessCypher[4]" to "AccessReconciler's own write — retracts a DOORS placeholder's " +
            "inherited category once its module no longer carries it directly (§16.1a)",
        "AccessCypher[5]" to "AccessReconciler's own write — propagates a JIRA project's direct " +
            "category to its issues",
        "AccessCypher[6]" to "AccessReconciler's own write — retracts a JIRA issue's inherited " +
            "category once its project no longer carries it directly",
        "AccessCypher[7]" to "AccessReconciler's own write — seeds a never-categorised DOORS " +
            "module from its source default (§8.3)",
        "AccessCypher[8]" to "AccessReconciler's own write — the placeholder containment's own " +
            "seed pass; a no-op in practice, since it seeds the :DOORSModule container [7] already " +
            "seeded, but generated for every containment and so exempted for every containment",
        "AccessCypher[9]" to "AccessReconciler's own write — seeds a never-categorised JIRA " +
            "project from its source default (§8.3)",
        "AccessCypher[10]" to "AccessReconciler's own write — seeds an uncategorised " +
            "WindchillDocument directly from its source default; containerless (§8.2)",
        // Categories (phase 6, §10.2 screen 1) — indices 11-16, in AccessCypher.kt's own
        // declaration order. CATEGORY_KEY_EXISTS, CREATE_CATEGORY and DELETE_CATEGORY_IF_UNUSED
        // touch no filtered label at all and need no entry here — only the two that count into
        // :SEItem do.
        "AccessCypher[11]" to "the Categories screen's own object/group counts — access-control.md " +
            "§13: \"no read path may start from a category node… the Access view's object counts " +
            "are the exception… never on a page a normal user loads,\" which this page is",
        "AccessCypher[14]" to "same §13 exemption as [11] — UPDATE_CATEGORY re-reads the fresh " +
            "counts after a rename rather than assuming them unchanged",
        "AccessCypher[15]" to "same §13 exemption as [11] — the delete confirmation's pre-empt " +
            "counts, and the 409 message's counts if the frontend's check is ever stale",
        // Unassigned containers (§10.2 screen 3) — one entry per distinct containerLabel group,
        // in the same order AccessAdminService.listUnassignedContainers groups them: DOORSModule
        // first (doors + doorsPlaceholders share it), then JiraProject.
        "AccessCypher[26]" to "access-control.md §16.2a — the unassigned queue is deliberately " +
            "exempt from visible(): an access manager who cannot yet grant themselves a category " +
            "could otherwise never find the container to grant one to. Container-level metadata " +
            "only (name, source, an invisible-item count), never a contained item; already " +
            "sec-access-manager-gated (Routes.kt), same shape as POST /access/reconcile",
        "AccessCypher[27]" to "same §16.2a exemption as [26], for the JiraProject group",
    )

    // -- the checks -----------------------------------------------------------------------------

    @Test
    fun `every statement touching a type label carries the ACL marker or a declared exemption`() {
        val violations = statements.mapNotNull { (owner, query) ->
            val touchesFilteredLabel = LABEL_OR_TYPE.findAll(query)
                .any { it.groupValues[1] in filteredLabels }
            when {
                !touchesFilteredLabel -> null
                MARKER in query -> null
                owner in exemptions -> null
                else -> owner
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Statement touches :SEItem or a type label with no /*ACL*/ marker and no declared " +
                "exemption (docs/features/access-control.md §6.2): ${violations.joinToString()}",
        )
    }

    /**
     * The inverse of the check above: an exemption that no longer describes reality — because the
     * statement it names gained a real `/*ACL*/` marker, or stopped touching a filtered label
     * altogether — is a stale entry hiding a filter someone already added. This is what would have
     * caught it, exactly the way [everyExemptionNamesARealStatement] catches a typo in the key.
     */
    @Test
    fun `every declared exemption still needs one`() {
        val byOwner = statements.toMap()
        val staleExemptions = exemptions.keys.filter { owner ->
            val query = byOwner[owner] ?: return@filter false
            val touchesFilteredLabel = LABEL_OR_TYPE.findAll(query)
                .any { it.groupValues[1] in filteredLabels }
            !touchesFilteredLabel || MARKER in query
        }

        assertTrue(
            staleExemptions.isEmpty(),
            "Exemption no longer needed — remove it from AccessGuardTest: ${staleExemptions.joinToString()}",
        )
    }

    @Test
    fun everyExemptionNamesARealStatement() {
        val known = statements.map { it.first }.toSet()
        val unknown = exemptions.keys - known

        assertTrue(unknown.isEmpty(), "Exemption for a statement that does not exist: ${unknown.joinToString()}")
    }

    /** Same purpose as `GraphNamesTest`'s own — a whole new Cypher file cannot slip past uncovered. */
    @Test
    fun `every Cypher file is covered`() {
        val dir = Path.of("src", "main", "kotlin", "com", "sec", "graph", "cypher")
        assertTrue(dir.exists(), "Cypher package moved to $dir — this test would pass vacuously")

        val onDisk = dir.listDirectoryEntries("*.kt").map { it.nameWithoutExtension }.toSet()
        val covered = statements.map { (owner, _) -> owner.substringBefore('[') }.toSet()

        assertTrue(
            (onDisk - covered).isEmpty(),
            "A Cypher file exists that AccessGuardTest does not read: ${(onDisk - covered).joinToString()}",
        )
    }

    private companion object {
        /** Same pattern `GraphNamesTest` extracts labels with — `:Name` in a pattern. */
        val LABEL_OR_TYPE = Regex(""":([A-Za-z_][A-Za-z0-9_]*)""")

        const val MARKER = "/*ACL*/"
    }
}

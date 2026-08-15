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
 * This is the reason phase 2 leaves nearly every statement in [exemptions]: `access-control.md`
 * §15 filters exactly one endpoint per phase, and every other statement's exemption reason names
 * the phase that pays it off — phase 4 for reads, phase 5 for writes. An entry leaving this map
 * without the statement gaining a real `/*ACL*/` marker is a regression, and
 * [`every declared exemption still needs one`] is what catches that the moment it happens.
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
            JiraCypher.SWEEP_DELETED, JiraCypher.SWEEP_DECONFIGURED,
            JiraCypher.DELETE_ORPHANED_ENTITIES, JiraCypher.DELETE_ORPHANED_PLACEHOLDERS,
            JiraCypher.COUNT_PLACEHOLDERS,
            JiraCypher.LIST_ISSUES_ASC, JiraCypher.LIST_ISSUES_DESC,
            JiraCypher.COUNT_ISSUES_MATCHING,
            JiraCypher.LOAD_SETTINGS, JiraCypher.SAVE_SETTINGS,
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
        add("AccessCypher", AccessCypher.RESOLVE_GROUPS)
    }

    // -- the exemptions: every statement above that touches a type label and is not yet filtered --

    /**
     * `docs/features/access-control.md` §15's build order, not an oversight: phase 2 filters
     * `/modules/{ref}/objects` alone. Every read here is filtered in phase 4; every write is
     * anchor-checked in phase 5. Both reasons are stated per entry rather than once, so a reader
     * who greps one statement's name gets the answer without cross-referencing the build order.
     */
    private val exemptions: Map<String, String> = mapOf(
        "DependencyGraphCypher[0]" to "phase 4 read path — dependency graph, out-neighbours",
        "DependencyGraphCypher[1]" to "phase 4 read path — dependency graph, in-neighbours",
        "DependencyGraphCypher[2]" to "phase 4 read path — dependency graph, seed resolution",
        "RequirementCardCypher[0]" to "phase 4 read path — requirement cards (breakdown + graph)",
        "RequirementCardCypher[1]" to "phase 4 read path — verification-attribute lookup",
        "BreakdownCypher[0]" to "phase 4 read path — breakdown tree, upward edges",
        "BreakdownCypher[1]" to "phase 4 read path — breakdown tree, downward edges",
        "ItemCypher[0]" to "phase 4 read path — item lookup by id",
        "ModuleCypher[0]" to "phase 4 read path — Modules table listing",
        "ModuleCypher[1]" to "phase 4 read path — module detail dialog",
        "ModuleCypher[2]" to "phase 4 read path — attribute discovery scans a module's own objects",
        "ModuleCypher[3]" to "phase 4 read path — a module's own mandatory-policy list",
        "ModuleCypher[4]" to "phase 4 read path — resolves ids for the comment write's own guard",
        "ModuleCypher[5]" to "phase 4 read path — module-exists check",
        "ModuleCypher[6]" to "phase 5 write path — system-level classification; anchors a :DOORSModule",
        "ModuleCypher[7]" to "phase 5 write path — clears the system-level classification",
        "ModuleCypher[8]" to "phase 5 write path — mandatory-policy upsert; anchors a :DOORSModule",
        "ModuleCypher[9]" to "phase 5 write path — mandatory-policy removal",
        "ReviewCypher[1]" to "phase 4 read path — a module's own mandatory-policy list (review table copy)",
        "ReviewCypher[2]" to "phase 4 read path — names the modules a page's references point into",
        "ReviewCypher[4]" to "phase 4 read path — item detail panel",
        "ReviewCypher[5]" to "phase 4 read path — outgoing traces",
        "ReviewCypher[6]" to "phase 4 read path — incoming traces",
        "ReviewCypher[7]" to "phase 5 write path — comment upsert; anchor-visibility check, not a read",
        "ReviewCypher[8]" to "phase 5 write path — comment delete; anchor-visibility check, not a read",
        "ReviewCypher[9]" to "phase 5 write path — comment read-back after save",
        "ReviewCypher[10]" to "phase 4 read path — a module's own attribute-setting list",
        "ReviewCypher[11]" to "phase 5 write path — attribute-setting upsert; anchors a :DOORSModule, " +
            "not an :SEItem instance, but MERGEs through the module's own label",
        "ReviewCypher[12]" to "phase 5 write path — attribute-setting removal",
        "StatisticsCypher[0]" to "phase 4 read path — Statistics view, modules in scope",
        "StatisticsCypher[1]" to "phase 4 read path — Statistics view, module census",
        "StatisticsCypher[2]" to "phase 4 read path — Statistics view, module census count",
        "StatisticsCypher[3]" to "phase 4 read path — Statistics view, dangling-link module names",
        "StatisticsCypher[4]" to "phase 4 read path — Statistics view, trace-cycle detection",
        "StatisticsCypher[5]" to "phase 4 read path — Statistics view, loop membership",
        "TableCypher[0]" to "phase 4 read path — reconstructed DOORS tables",
        "TableCypher[1]" to "phase 4 read path — table resolution for an item/row/cell",
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
        "JiraCypher[16]" to "importer write path — deleted-issue sweep",
        "JiraCypher[17]" to "importer write path — deconfigured-project sweep",
        "JiraCypher[18]" to "importer write path — orphaned-entity cleanup",
        "JiraCypher[19]" to "importer write path — orphaned-placeholder cleanup",
        "JiraCypher[20]" to "importer read path — placeholder count, importer progress reporting",
        "JiraCypher[21]" to "phase 4 read path — Issues table, ascending page",
        "JiraCypher[22]" to "phase 4 read path — Issues table, descending page",
        "JiraCypher[23]" to "phase 4 read path — Issues table, matching count",
        "JiraCypher[24]" to "application configuration node (:JiraSettings), not an item — gated by " +
            "role in phase 5, not by category",
        "JiraCypher[25]" to "application configuration node (:JiraSettings) — same as the load above",
        "JiraCypher[26]" to "phase 4 read path — field catalogue, for the column picker",
        "JiraCypher[27]" to "phase 4 read path — field catalogue lookup by id",
        "JiraCypher[28]" to "per-user preference node (:JiraColumnConfig), not an item",
        "JiraCypher[29]" to "per-user preference node (:JiraColumnConfig) — same as the load above",
        "JiraCypher[30]" to "phase 4 read path — related-issues link graph, neighbours",
        "JiraCypher[31]" to "phase 4 read path — related-issues link graph, nodes",
        "JiraCypher[32]" to "schema (index/constraint), not a data read — same exemption class as MetaSchema",
        "JiraCypher[33]" to "schema (index/constraint), not a data read",
        "JiraCypher[34]" to "schema (index/constraint), not a data read",
        "JiraCypher[35]" to "schema (index/constraint), not a data read",
        "JiraCypher[36]" to "schema (index/constraint), not a data read",
        "JiraCypher[37]" to "schema (index/constraint), not a data read",
        "JiraCypher[38]" to "schema (index/constraint), not a data read",
        "JiraCypher[39]" to "schema (index/constraint), not a data read",
        "WindchillCypher[0]" to "importer write path — Windchill document upsert (ADR 0015)",
        "WindchillCypher[1]" to "importer write path — deleted-document sweep (ADR 0015)",
        "WindchillCypher[2]" to "phase 4 read path — Windchill Documents view, count",
        "WindchillCypher[3]" to "phase 4 read path — Windchill Documents view, listing",
        "WindchillCypher[4]" to "schema (index/constraint), not a data read",
        "WindchillCypher[5]" to "schema (index/constraint), not a data read",
        "WindchillCypher[6]" to "schema (index/constraint), not a data read",
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

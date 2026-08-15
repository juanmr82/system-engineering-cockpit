package com.sec.domain

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
import com.sec.source.doors.DoorsProp
import com.sec.source.doors.DoorsRel
import com.sec.source.jira.JiraLabel
import com.sec.source.jira.JiraProp
import com.sec.source.jira.JiraRel
import com.sec.source.windchill.WindchillLabel
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What keeps "one declaration per graph name" true (ADR 0010).
 *
 * `domain/GraphNames.kt` and `source/doors/DoorsNames.kt` are the single source of truth for every
 * name the backend uses to address the graph, and every statement in `graph/cypher/` plus
 * `MetaSchema` interpolates them rather than spelling them out — so renaming one is one edit.
 *
 * Two checks, pointing in opposite directions, and both are needed:
 *
 *  - **Forward, over the compiled statements.** Every label, relationship type and `__` name that
 *    actually reaches the driver is declared. This is what catches a name assembled some other way
 *    — a helper that concatenates, a statement built outside `graph/cypher/`.
 *  - **Inverse, over the statement *source*.** No graph name is written out as a literal. This is
 *    the one that matters now: interpolation is only worth having if it cannot be undone one
 *    statement at a time, and a literal `__id` passes the forward check perfectly.
 *
 * Neither checks that a declared name is *used* — half the `:__Meta` catalogue is written for views
 * that have not arrived yet.
 */
class GraphNamesTest {

    // -- what the Cypher is allowed to say -------------------------------------------------

    // Each source contributes its own names file and nothing edits another's (R3), so adding a
    // source is one term in each of these three sets.
    private val declaredLabels: Set<String> =
        setOf(NodeLabel.SE_ITEM, NodeLabel.UNDEFINED, NodeLabel.DELETED, NodeLabel.IMPORT_RUN, NodeLabel.GROUP) +
            NodeLabel.meta + DoorsLabel.all + JiraLabel.all + WindchillLabel.all

    private val declaredRelationships: Set<String> = setOf(
        Rel.CHILD, Rel.NOTE_ON, Rel.TAGGED_AS, Rel.REVIEW_OF, Rel.FLAG_ON, Rel.CLASSIFIED_AS,
        Rel.POLICY_FOR, Rel.ATTRIBUTE_SETTING_FOR, Rel.LINK_FROM, Rel.LINK_TO,
        Rel.IN_ACCESS_CATEGORY, Rel.MAY_READ,
        DoorsRel.REFERS_TO,
    ) + JiraRel.all

    private val declaredNamespaceNames: Set<String> = setOf(
        Prop.ID, Prop.NAME, Prop.VERSION, Prop.SORT_KEY, Prop.MODULE_URL, Prop.OBJECT_URL,
        Prop.TYPE_RAW, Prop.META_ID, Prop.META_KIND, Prop.SCHEMA_VERSION,
        Prop.CREATED_BY, Prop.CREATED_AT, Prop.UPDATED_BY, Prop.UPDATED_AT,
        DoorsProp.TABLE_ROW_INDEX, DoorsProp.TABLE_COLUMN_INDEX, DoorsProp.TABLE_URL,
    ) + JiraProp.namespaced +
        declaredLabels.filter { it.startsWith(Prop.NAMESPACE) } +
        declaredRelationships.filter { it.startsWith(Prop.NAMESPACE) }

    // -- the statements ---------------------------------------------------------------------

    /**
     * Every object holding Cypher, named rather than discovered: there is no classpath scanner in
     * this project and adding one for a test would be a dependency (§4). [everyCypherFileIsCovered]
     * is what stops the list going stale.
     */
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
        // visible("o") stands in for every alias the predicate is actually called with — the name
        // extraction below only cares which graph names appear in the produced text, not which
        // alias they were bound to.
        add("AccessCypher", AccessCypher.RESOLVE_GROUPS, AccessCypher.visible("o"))
    }

    // -- the checks -------------------------------------------------------------------------

    @Test
    fun `every label and relationship type in Cypher is declared`() {
        val known = declaredLabels + declaredRelationships
        val undeclared = statements.flatMap { (owner, query) ->
            LABEL_OR_TYPE.findAll(query).map { owner to it.groupValues[1] }
        }.filterNot { (_, name) -> name in known }

        assertTrue(
            undeclared.isEmpty(),
            "Cypher names a label or relationship type that no constant declares. Add it to " +
                "domain/GraphNames.kt or source/doors/DoorsNames.kt: " +
                undeclared.joinToString { (owner, name) -> "$name in $owner" },
        )
    }

    @Test
    fun `every __-prefixed name in Cypher is declared`() {
        val undeclared = statements.flatMap { (owner, query) ->
            NAMESPACE_NAME.findAll(query).map { owner to it.value }
        }.filterNot { (_, name) -> name in declaredNamespaceNames }

        assertTrue(
            undeclared.isEmpty(),
            "Cypher uses a `__` name that no constant declares — a typo here matches nothing " +
                "and reports zero rows rather than failing: " +
                undeclared.joinToString { (owner, name) -> "$name in $owner" },
        )
    }

    /**
     * A label written as a string rather than as a pattern — `appliesToLabels = ['DOORSRequirement']`
     * is the one that exists today, and it is exactly the kind the pattern check cannot see.
     */
    @Test
    fun `every quoted source label in Cypher is declared`() {
        val undeclared = statements.flatMap { (owner, query) ->
            QUOTED_SOURCE_LABEL.findAll(query).map { owner to it.groupValues[1] }
        }.filterNot { (_, name) -> name in declaredLabels }

        assertTrue(
            undeclared.isEmpty(),
            "Cypher carries a label as a string literal that no constant declares: " +
                undeclared.joinToString { (owner, name) -> "$name in $owner" },
        )
    }

    // -- the inverse check: nothing is spelled out ------------------------------------------

    /**
     * The check that makes the interpolation stick.
     *
     * Every statement reads its names from a constant, which is only worth anything if it stays
     * that way — and a hand-written `__id` is invisible to every other test here, because the
     * compiled string is identical either way. So this one reads the **source**, strips the
     * comments (which is where these names *should* appear, and do, at length), and fails on any
     * graph name left in the code.
     *
     * Deliberately limited to labels, relationship types and the `__` namespace. The meta payload
     * keys are ordinary words — `text`, `visible`, `code` — and half of them are also the *result
     * column* names the statements return, which are wire names and not graph names at all.
     */
    @Test
    fun `no graph name is spelled out in a Cypher source file`() {
        val spelledOut = cypherSources().flatMap { (file, source) ->
            val code = stripComments(source)
            val namespaced = NAMESPACE_NAME.findAll(code).map { it.value }
            val plain = (declaredLabels + declaredRelationships)
                .filterNot { it.startsWith(Prop.NAMESPACE) }
                .filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(code) }
            (namespaced + plain).map { file to it }
        }

        assertTrue(
            spelledOut.isEmpty(),
            "A graph name is written out in Cypher source instead of interpolated from a " +
                "constant, which puts its spelling back in two places (ADR 0010). Import the " +
                "constant and use it: " +
                spelledOut.distinct().joinToString { (file, name) -> "$name in $file" },
        )
    }

    /** Sanity for the check above: the stripper must not simply blank the file. */
    @Test
    fun `stripping comments leaves the statements behind`() {
        val code = cypherSources().joinToString("\n") { (_, source) -> stripComments(source) }
        assertTrue("CYPHER 25" in code, "comment stripping ate the statements — the check is vacuous")
        assertTrue("OPTIONAL MATCH" in code, "comment stripping ate the statements")
    }

    /**
     * The list above is hand-maintained, so this is what stops it silently covering seven files out
     * of eight. It compares file names only — nothing is parsed — so a renamed statement inside a
     * covered file is still on the author, but a whole new Cypher file cannot slip past.
     */
    @Test
    fun `every Cypher file is covered`() {
        val dir = cypherDir()
        assertTrue(dir.exists(), "Cypher package moved to $dir — this test would pass vacuously")

        val onDisk = dir.listDirectoryEntries("*.kt").map { it.nameWithoutExtension }.toSet()
        val covered = statements.map { (owner, _) -> owner.substringBefore('[') }.toSet()

        assertEquals(
            emptySet(), onDisk - covered,
            "A Cypher file exists that GraphNamesTest does not read. Add its statements to " +
                "`statements` so its labels are checked too.",
        )
    }

    /** Sanity: the extraction actually finds things, so a broken regex cannot make the suite pass. */
    @Test
    fun `the extraction finds the names it is supposed to`() {
        val labels = statements.flatMap { (_, q) -> LABEL_OR_TYPE.findAll(q).map { it.groupValues[1] } }
        assertTrue(NodeLabel.SE_ITEM in labels, "no :SEItem found — the label regex is broken")
        assertTrue(DoorsRel.REFERS_TO in labels, "no :refersTo found — the label regex is broken")

        val namespaced = statements.flatMap { (_, q) -> NAMESPACE_NAME.findAll(q).map { it.value } }
        assertTrue(Prop.ID in namespaced, "no __id found — the namespace regex is broken")
        assertTrue(Rel.CHILD in namespaced, "no __child found — the namespace regex is broken")
    }

    // -- reading the source ------------------------------------------------------------------

    private fun cypherDir(): Path = Path.of("src", "main", "kotlin", "com", "sec", "graph", "cypher")

    /** Every file holding Cypher, as text. `MetaSchema` lives in `meta/` and counts all the same. */
    private fun cypherSources(): List<Pair<String, String>> {
        val dir = cypherDir()
        assertTrue(dir.exists(), "Cypher package moved to $dir — this check would pass vacuously")

        val metaSchema = Path.of("src", "main", "kotlin", "com", "sec", "meta", "MetaSchema.kt")
        assertTrue(metaSchema.exists(), "MetaSchema moved — its statements would stop being read")

        // listOf(metaSchema), never a bare `+ metaSchema`: Path implements Iterable<Path> over its
        // own name elements, so `List<Path> + Path` picks the Iterable overload and appends
        // "src", "main", "kotlin", … instead of the file.
        return (dir.listDirectoryEntries("*.kt") + listOf(metaSchema))
            .map { it.fileName.toString() to it.readText() }
    }

    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, " ")

    private companion object {
        /**
         * `:Name` in a pattern — a node label or a relationship type. A map key is `name:`, with
         * the colon on the other side, so requiring an identifier character immediately after the
         * colon is what tells the two apart.
         */
        val LABEL_OR_TYPE = Regex(""":([A-Za-z_][A-Za-z0-9_]*)""")

        /** KDoc and block comments — where these names are explained, and belong. */
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")

        /** Any `__` name, wherever it appears: property access, map key, label or type. */
        val NAMESPACE_NAME = Regex("""__[A-Za-z][A-Za-z0-9_]*""")

        /** A source label carried as a Cypher string, which the pattern regex cannot see. */
        val QUOTED_SOURCE_LABEL = Regex("""'(DOORS[A-Za-z0-9_]*)'""")
    }
}

package com.sec.source.jira

import com.sec.config.JiraDeployment
import com.sec.config.JiraSettings
import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase
import com.sec.source.jira.mapping.IssueLink
import com.sec.source.jira.mapping.IssueMapper
import com.sec.source.jira.mapping.IssueRef
import com.sec.source.jira.mapping.JiraFieldCatalogue
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The JIRA import, as a job the generic framework runs (spec §12).
 *
 * ## The phases
 *
 * All six of the spec's, with its own weights: preflight, issue types, fields, issues, links,
 * sweep. Phase 6 in the spec is the report, which is not a progress phase — the framework writes
 * the counters and the outcome once [run] returns.
 *
 * The order is not a convenience. Links run after *every* page, because a link's target may live on
 * the last one, and the sweep runs after the links, because a stub created for a target this run
 * could not see is a node the sweep must already know about.
 *
 * ## What this class is not allowed to do
 *
 * Talk to Neo4j directly. Every write goes through [JiraGraphWriter], which is the only thing in the
 * process that writes imported JIRA data — the structural guarantee that stands in for R1's "the
 * application never writes imported data", which every other importer gets for free by being a
 * separate process (ADR 0013).
 */
public class JiraImporter(
    private val settings: JiraSettings,
    private val client: JiraHttpClient,
    private val writer: JiraGraphWriter,
    private val settingsStore: JiraSettingsStore,
) : ImportJob {

    override val importerId: String = ID

    override val displayName: String = "JIRA"

    override val phases: List<ImportPhase> = listOf(
        ImportPhase(PREFLIGHT, "Checking configuration and connectivity", weight = 2),
        ImportPhase(ISSUE_TYPES, "Importing issue types", weight = 3),
        ImportPhase(FIELDS, "Importing field definitions", weight = 5),
        ImportPhase(ISSUES, "Importing issues", weight = 70),
        ImportPhase(LINKS, "Linking issues", weight = 12),
        ImportPhase(SWEEP, "Removing deleted issues", weight = 8),
    )

    override suspend fun run(context: ImportContext) {
        val state = RunState()

        preflight(context, state)
        importIssueTypes(context)
        importFieldDefinitions(context, state)
        importIssues(context, state)
        importLinks(context, state)
        sweep(context, state)
    }

    /**
     * What one run learns in an early phase and needs in a later one.
     *
     * A local in [run] rather than a field on this class, and that is not fussiness: the importer is
     * registered once and lives for the process, so a field would be shared by every run of it. The
     * service's per-importer mutex means two JIRA runs cannot overlap *today*, which makes a field
     * work by coincidence — and coincidences are what break when the mutex is later scoped per
     * project, or when a second instance is registered.
     */
    private class RunState {
        /** The **JIRA server's** zone, from `/myself`. The JQL's snapshot bound is read in it. */
        var zone: ZoneId = ZoneOffset.UTC

        /** Phase 2's catalogue, kept for phase 3 — see [JiraFieldCatalogue] on why it is advisory. */
        var catalogue: JiraFieldCatalogue = JiraFieldCatalogue.EMPTY

        /** Read once in preflight, used by phases 3 and 5. The sweep's scope *is* this list. */
        var projectKeys: List<String> = emptyList()

        /** How many stubs stood before this run touched anything — half of `unresolvedResolved`. */
        var unresolvedAtStart: Int = 0

        /**
         * Every issue `__id` phase 3 wrote.
         *
         * The most consequential value in the run: phase 4 stubs out what is missing from it, and
         * phase 5 deletes what is missing from it. An incomplete set is indistinguishable from an
         * emptied project, which is why [issuesComplete] exists beside it.
         */
        val seenIds: MutableSet<String> = LinkedHashSet()

        /**
         * Every link seen this run, keyed by JIRA's link id.
         *
         * The key is what collapses the two reports of one link — both ends report it — into one
         * edge. Accumulated across pages rather than written per page because a link's other end may
         * be on any page, including a later one (spec §12 phase 4). The reference instance's 784
         * issues carry ~550 links, so this is tens of kilobytes.
         */
        val links: MutableMap<String, IssueLink> = LinkedHashMap()

        /** Issues with a `fields.parent`, by child `__id`. Usually a small fraction of the run. */
        val parents: MutableMap<String, IssueRef> = LinkedHashMap()

        /**
         * Whether phase 3 finished every page.
         *
         * Read by the sweep, and it is the guard spec §12 calls the highest-consequence one in the
         * feature: a partial seen set would delete every issue the failed pages would have carried.
         * Today the control flow in [run] already guarantees it — a failing phase throws and the
         * sweep is never reached — and it is still stated, because "the sweep is safe as long as
         * nobody catches an exception" is a guarantee that lasts until somebody does.
         */
        var issuesComplete: Boolean = false
    }

    /**
     * Phase 0 — prove the configuration before touching anything.
     *
     * `/myself` first, and it is not only a connectivity check: it is where the JQL's `created <=`
     * bound will get its time zone when phase 3 arrives. Taking that from the JVM default would
     * shift the snapshot boundary by hours whenever the server and the service sit in different
     * zones (spec §8).
     *
     * Project keys are checked here now that phase 3 exists, and **before** anything is written:
     * spec §8 refuses an unbounded import outright, so finding out after the schema and two
     * catalogues have been written would be finding out late.
     */
    private suspend fun preflight(context: ImportContext, state: RunState) {
        context.phase(PREFLIGHT)

        if (!settings.isConfigured) throw JiraFailure.NotConfigured()

        val me = client.myself().getOrElse { throw it }
        context.log("Connected to ${settings.host} as ${me.displayName.ifBlank { me.name }}")

        warnOnDeploymentMismatch(context, me)

        // Not the JVM's zone. The JQL bound is compared against JIRA's own clock, so a service in
        // another zone would move the snapshot boundary by hours and silently admit or miss issues.
        // An unparseable zone is JIRA's problem to have, not a reason to fail: UTC and a warning.
        state.zone = runCatching { ZoneId.of(me.timeZone) }.getOrElse {
            context.warn("JIRA reported a time zone this server does not recognise (${me.timeZone}); using UTC.")
            ZoneOffset.UTC
        }

        state.projectKeys = settingsStore.projectKeys()
        JiraJql.validate(state.projectKeys).getOrElse { throw it }
        context.log("Configured projects: ${state.projectKeys.joinToString(", ")}")

        context.params(
            mapOf(
                "host" to settings.host,
                // The server's zone, recorded because a run that cannot say which midnight it
                // meant is a run nobody can reproduce. Never the token, in any form.
                "timeZone" to me.timeZone,
                "deployment" to settings.deployment.name,
                "pageSize" to settings.pageSize.toString(),
            ),
        )

        // Idempotent, and applied on every run rather than at boot: schema for imported labels
        // belongs to whatever imports them, and a deployment with no JIRA should not be creating
        // JIRA indexes (JiraCypher.SCHEMA).
        writer.applySchema()
        context.log("Applied the JIRA graph schema")

        // Counted before anything is written, because "how many stubs did this run resolve" is a
        // difference and there is no statement that can observe the event itself: a stub is
        // resolved by phase 3 writing the real issue over it, which looks like an ordinary upsert.
        state.unresolvedAtStart = writer.placeholderCount()

        context.progress(1, 1)
    }

    /** Phase 1 — issue types. Tens of rows, one batch, then the unused-type sweep (spec §9.1). */
    private suspend fun importIssueTypes(context: ImportContext) {
        context.phase(ISSUE_TYPES)
        context.ensureActive()

        val types = client.issueTypes().getOrElse { throw it }
        val written = writer.upsertIssueTypes(types)
        context.setCount(Counter.ISSUE_TYPES_SEEN, written.toLong())
        context.progress(written, written)

        writer.deleteUnusedIssueTypes(types.map { it.self })
        context.log("$written issue types")
    }

    /**
     * Phase 2 — the field catalogue: 1 171 definitions on the reference instance, in one response.
     *
     * The two schemaless pseudo-fields are counted and reported rather than silently dropped. They
     * are excluded from the column picker (spec §13.3) and that exclusion is a *read-path* decision;
     * the catalogue stores what JIRA returned.
     */
    private suspend fun importFieldDefinitions(context: ImportContext, state: RunState) {
        context.phase(FIELDS)
        context.ensureActive()

        val fields = client.fieldDefinitions().getOrElse { throw it }
        // Kept in memory for phase 3. The alternative — a lookup per field per issue — is ~900 000
        // round trips to answer a question whose answer did not change during the run.
        state.catalogue = JiraFieldCatalogue(fields)
        val written = writer.upsertFieldDefinitions(fields)
        context.setCount(Counter.FIELDS_SEEN, written.toLong())
        context.progress(written, written)

        writer.deleteStaleFields(fields.map { JiraId.field(settings.host, it.id) })

        val custom = fields.count { it.custom }
        context.log("$written field definitions ($custom custom)")

        // Not a failure and not silence: a name shared by two fields makes the column picker
        // ambiguous, and the picker's answer is to show the id beside it. Saying how many are
        // affected is what lets somebody decide whether that is a nuisance or a problem.
        val ambiguous = fields.groupBy { it.name }.count { (_, group) -> group.size > 1 }
        if (ambiguous > 0) {
            context.log("$ambiguous field names are used by more than one field")
        }
    }

    /**
     * Phase 3 — the issues. Seventy of the hundred units of work, and every hard case in the design.
     *
     * ## What it does per page
     *
     * Map, then write, then forget. A page is ~7 MB on a real instance, so nothing accumulates
     * except counters and a set of warnings — the issues themselves are never all in memory at once,
     * which is what lets this scale past the reference instance's 784.
     *
     * ## Progress, and why the denominator may be absent
     *
     * Data Center reports `total` on every page. Cloud reports nothing at all and its approximate
     * count is fetched once, up front, best-effort. So the denominator here is *whatever the product
     * would say*, and when it says nothing the phase still reports a rising count against a total
     * that equals it — a bar that advances honestly rather than one that pretends to know how far
     * it has to go.
     *
     * ## Unknown fields are collapsed
     *
     * The mapper reports a field the catalogue has never heard of once per *issue*; a field added
     * between the `/field` call and the search would otherwise produce 784 identical warnings. They
     * are collected into a set and reported once per field id at the end, which is the difference
     * between a finding and a wall.
     */
    private suspend fun importIssues(context: ImportContext, state: RunState) {
        context.phase(ISSUES)
        context.ensureActive()

        val jql = JiraJql.build(state.projectKeys, Instant.now(), state.zone).getOrElse { throw it }

        // On the run record, because when an import returns something unexpected the first question
        // is always what was actually asked for (spec §8).
        context.params(mapOf("jql" to jql))
        context.log("Searching: $jql")

        val mapper = IssueMapper(state.catalogue)
        val unknownFields = sortedSetOf<String>()
        var issuesSeen = 0

        val summary = client.searchAll(jql) { page ->
            // Between pages, not inside one: a page that has been mapped and written is the
            // smallest unit that leaves the graph in a state the next run can complete.
            context.ensureActive()

            val mapped = page.issues.map(mapper::map)
            writer.writeIssues(mapped)

            mapped.forEach { issue ->
                unknownFields += issue.warnings
                state.seenIds += issue.id
                // Keyed by link id, so the two reports of one link — one from each end — collapse
                // to a single entry however many pages apart they arrive.
                issue.links.forEach { link -> state.links[link.linkId] = link }
                issue.parent?.let { parent -> state.parents[issue.id] = parent }
            }
            issuesSeen += mapped.size

            context.setCount(Counter.ISSUES_SEEN, issuesSeen.toLong())
            context.progress(issuesSeen, page.estimatedTotal ?: issuesSeen)
        }.getOrElse { throw it }

        // JIRA's own warnings about the query — a clause that matched nothing, most often. Passed
        // through in JIRA's words because it knows which clause it disliked and this does not.
        summary.warnings.forEach { context.warn(it) }

        if (unknownFields.isNotEmpty()) {
            context.warn(
                "${unknownFields.size} field(s) were not in the catalogue and were imported anyway: " +
                    unknownFields.joinToString(", ", limit = UNKNOWN_FIELDS_LISTED),
            )
        }

        val counts = writer.issueCounts()
        if (counts.issues != counts.projections) {
            context.warn(
                "${counts.issues} issues but ${counts.projections} display projections — " +
                    "some issues have no projection, and their sortable columns will be empty.",
            )
        }

        // Set once rather than only inside the page callback: a search that matched nothing never
        // reaches the callback, and a run that reports no counter at all reads as a run that failed
        // to count rather than one that found nothing.
        context.setCount(Counter.ISSUES_SEEN, issuesSeen.toLong())

        // Set only here, and only after every page has been written. Everything the sweep is
        // allowed to delete rests on this line being reached.
        state.issuesComplete = true

        context.log("$issuesSeen issues over ${summary.pages} page(s)")
    }

    /**
     * Phase 4 — the links, once every page is in (spec §12 phase 4).
     *
     * ## Why it cannot be done per page
     *
     * A link names an issue that may be on any page, including the last. Writing links as each page
     * arrives would stub out an issue that was three minutes away from being imported for real, and
     * every one of those stubs would then have to be un-made. Waiting costs a few hundred kilobytes
     * of link records and removes the problem entirely.
     *
     * ## The two counters
     *
     * `unresolvedResolved` and `unresolvedCreated` are differences, not events. A stub is resolved
     * by phase 3 writing the real issue over it — an ordinary upsert, indistinguishable from any
     * other from inside the statement — so the only way to count it is to look at how many stubs
     * stood before the run and how many stand now. Three cheap counts, and they are what let the run
     * summary say "9 links now resolve, 4 still point outside the configured projects".
     */
    private suspend fun importLinks(context: ImportContext, state: RunState) {
        context.phase(LINKS)
        context.ensureActive()

        val standing = writer.placeholderCount()
        val resolved = (state.unresolvedAtStart - standing).coerceAtLeast(0)

        writer.writeLinks(state.links.values, state.parents, state.seenIds)

        val created = (writer.placeholderCount() - standing).coerceAtLeast(0)

        context.setCount(Counter.LINKS_SEEN, state.links.size.toLong())
        context.setCount(Counter.UNRESOLVED_CREATED, created.toLong())
        context.setCount(Counter.UNRESOLVED_RESOLVED, resolved.toLong())
        context.progress(1, 1)

        context.log(
            "${state.links.size} link(s) and ${state.parents.size} sub-task(s); " +
                "$created placeholder(s) created, $resolved resolved",
        )
    }

    /**
     * Phase 5 — remove what JIRA no longer has, and what the configuration no longer asks for.
     *
     * ## The guard
     *
     * This is the one phase that deletes imported data, and the seen set it deletes against is only
     * complete if phase 3 read every page. A run that failed on page 9 has seen 8 pages, and to this
     * statement that is indistinguishable from an instance whose issues were deleted. Hence the
     * check on [RunState.issuesComplete] — belt as well as braces, since a failing phase already
     * throws before reaching here.
     *
     * ## The mass-deletion warning
     *
     * Measured after the fact rather than before, and that is a deliberate limitation: refusing
     * would need a dry-run count of the same statement, and spec §12 asks for a warning rather than
     * a refusal ("a confirm-before-delete dialog is a natural extension; not required now"). What is
     * built is the honest version of what it asked for — the run ends `SUCCEEDED_WITH_WARNINGS` and
     * says how much went.
     */
    private suspend fun sweep(context: ImportContext, state: RunState) {
        context.phase(SWEEP)
        context.ensureActive()

        if (!state.issuesComplete) {
            context.warn("Did not remove deleted issues: the issue phase did not finish, so what is missing from this run is unknown.")
            return
        }

        val before = writer.issueCounts().issues
        val counts = writer.sweep(state.projectKeys, state.seenIds)

        context.setCount(Counter.DELETED, counts.deleted.toLong())
        context.setCount(Counter.DELETED_BY_CONFIG, counts.deletedByConfig.toLong())
        context.progress(1, 1)

        if (counts.deleted > 0) context.log("${counts.deleted} issue(s) no longer in JIRA were removed")
        if (counts.deletedByConfig > 0) {
            context.log(
                "${counts.deletedByConfig} issue(s) removed because their project is no longer selected",
            )
        }

        val removed = counts.deleted + counts.deletedByConfig
        if (before > 0 && removed * PERCENT > before * MASS_DELETE_PERCENT) {
            context.warn(
                "This import removed $removed of $before issues — more than $MASS_DELETE_PERCENT %. " +
                    "Check that the selected projects and the JIRA instance are the ones you meant.",
            )
        }
    }

    /**
     * Say so when the configured product disagrees with the one that answered.
     *
     * A warning rather than a failure, and it runs in preflight rather than at the first search,
     * because the failure it prevents is otherwise a 410 or a 404 arriving after the schema and two
     * catalogues have been written — at the start of the phase that takes all the time.
     *
     * The signal is exact rather than heuristic: Cloud identifies a user by `accountId` and sends no
     * `name`, Data Center does precisely the reverse. Verified against one of each.
     */
    private suspend fun warnOnDeploymentMismatch(context: ImportContext, me: JiraMyself) {
        val looksLikeCloud = me.accountId.isNotBlank()
        val looksLikeDataCenter = me.name.isNotBlank() || me.key.isNotBlank()

        when {
            settings.deployment == JiraDeployment.DATA_CENTER && looksLikeCloud && !looksLikeDataCenter ->
                context.warn(
                    "This host answers like JIRA Cloud, but jira.deployment is datacenter. " +
                        "Importing issues will fail — set jira.deployment: cloud.",
                )

            settings.deployment == JiraDeployment.CLOUD && looksLikeDataCenter && !looksLikeCloud ->
                context.warn(
                    "This host answers like JIRA Data Center, but jira.deployment is cloud. " +
                        "Importing issues will fail — set jira.deployment: datacenter.",
                )
        }
    }

    /** Counter names for this importer. Not graph names — they are keys inside a JSON text blob. */
    public object Counter {
        public const val ISSUE_TYPES_SEEN: String = "issueTypesSeen"
        public const val FIELDS_SEEN: String = "fieldsSeen"
        public const val ISSUES_SEEN: String = "issuesSeen"
        public const val LINKS_SEEN: String = "linksSeen"
        public const val UNRESOLVED_CREATED: String = "unresolvedCreated"
        public const val UNRESOLVED_RESOLVED: String = "unresolvedResolved"

        /** Issues JIRA no longer returns. Kept apart from [DELETED_BY_CONFIG] — different news. */
        public const val DELETED: String = "deleted"
        public const val DELETED_BY_CONFIG: String = "deletedByConfig"
    }

    public companion object {
        /** The importer id: a URL segment and an `:__ImportRun` property. Never renamed. */
        public const val ID: String = "jira"

        public const val PREFLIGHT: String = "preflight"
        public const val ISSUE_TYPES: String = "issuetypes"
        public const val FIELDS: String = "fields"
        public const val ISSUES: String = "issues"
        public const val LINKS: String = "links"
        public const val SWEEP: String = "sweep"

        /** How many unknown field ids a warning names before it says "and n more". */
        private const val UNKNOWN_FIELDS_LISTED = 10

        /** The share of the existing issues a single import may remove before it says so (spec §12). */
        private const val MASS_DELETE_PERCENT = 20
        private const val PERCENT = 100
    }
}

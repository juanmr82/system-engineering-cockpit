package com.sec.source.jira

import com.sec.config.JiraDeployment
import com.sec.config.JiraSettings
import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase
import com.sec.source.jira.mapping.IssueMapper
import com.sec.source.jira.mapping.JiraFieldCatalogue
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The JIRA import, as a job the generic framework runs (spec §12).
 *
 * ## Which phases exist
 *
 * Three of the six, and the list is deliberately honest about it: `preflight`, `issuetypes` and
 * `fields` are built, so those are what is declared. `issues`, `links` and `sweep` join
 * [phases] when they are written.
 *
 * Declaring the missing three now as no-ops would draw a stepper with three steps that flash past,
 * and an aggregate bar that reaches 10 % and stops — a progress display that lies about what the
 * product does. The weights are the spec's own, and because [com.sec.importer.percentComplete]
 * normalises by their sum, adding the remaining phases needs no rebalancing here: 2/3/5 reads as
 * 20/30/50 today and as 2/3/5 of 100 the day the other three arrive.
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
    )

    override suspend fun run(context: ImportContext) {
        val state = RunState()

        preflight(context, state)
        importIssueTypes(context)
        importFieldDefinitions(context, state)
        importIssues(context, state)
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

        val projectKeys = settingsStore.projectKeys()
        JiraJql.validate(projectKeys).getOrElse { throw it }
        context.log("Configured projects: ${projectKeys.joinToString(", ")}")

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

        val projectKeys = settingsStore.projectKeys()
        val jql = JiraJql.build(projectKeys, Instant.now(), state.zone).getOrElse { throw it }

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

            mapped.forEach { unknownFields += it.warnings }
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

        context.log("$issuesSeen issues over ${summary.pages} page(s)")
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
    }

    public companion object {
        /** The importer id: a URL segment and an `:__ImportRun` property. Never renamed. */
        public const val ID: String = "jira"

        public const val PREFLIGHT: String = "preflight"
        public const val ISSUE_TYPES: String = "issuetypes"
        public const val FIELDS: String = "fields"
        public const val ISSUES: String = "issues"

        /** How many unknown field ids a warning names before it says "and n more". */
        private const val UNKNOWN_FIELDS_LISTED = 10
    }
}

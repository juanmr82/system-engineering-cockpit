package com.sec.source.jira

import com.sec.config.JiraSettings
import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase

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
) : ImportJob {

    override val importerId: String = ID

    override val displayName: String = "JIRA"

    override val phases: List<ImportPhase> = listOf(
        ImportPhase(PREFLIGHT, "Checking configuration and connectivity", weight = 2),
        ImportPhase(ISSUE_TYPES, "Importing issue types", weight = 3),
        ImportPhase(FIELDS, "Importing field definitions", weight = 5),
    )

    override suspend fun run(context: ImportContext) {
        preflight(context)
        importIssueTypes(context)
        importFieldDefinitions(context)
    }

    /**
     * Phase 0 — prove the configuration before touching anything.
     *
     * `/myself` first, and it is not only a connectivity check: it is where the JQL's `created <=`
     * bound will get its time zone when phase 3 arrives. Taking that from the JVM default would
     * shift the snapshot boundary by hours whenever the server and the service sit in different
     * zones (spec §8).
     *
     * The project-key check spec §12 puts here is **not** here yet, and that is correct rather than
     * an omission: nothing in these three phases queries issues, so nothing needs a project list.
     * It joins this phase with the phase that needs it.
     */
    private suspend fun preflight(context: ImportContext) {
        context.phase(PREFLIGHT)

        if (!settings.isConfigured) throw JiraFailure.NotConfigured()

        val me = client.myself().getOrElse { throw it }
        context.log("Connected to ${settings.host} as ${me.displayName.ifBlank { me.name }}")

        context.params(
            mapOf(
                "host" to settings.host,
                // The server's zone, recorded because a run that cannot say which midnight it
                // meant is a run nobody can reproduce. Never the token, in any form.
                "timeZone" to me.timeZone,
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
    private suspend fun importFieldDefinitions(context: ImportContext) {
        context.phase(FIELDS)
        context.ensureActive()

        val fields = client.fieldDefinitions().getOrElse { throw it }
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

    /** Counter names for this importer. Not graph names — they are keys inside a JSON text blob. */
    public object Counter {
        public const val ISSUE_TYPES_SEEN: String = "issueTypesSeen"
        public const val FIELDS_SEEN: String = "fieldsSeen"
    }

    public companion object {
        /** The importer id: a URL segment and an `:__ImportRun` property. Never renamed. */
        public const val ID: String = "jira"

        public const val PREFLIGHT: String = "preflight"
        public const val ISSUE_TYPES: String = "issuetypes"
        public const val FIELDS: String = "fields"
    }
}

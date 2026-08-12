package com.sec.source.windchill

import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase

/**
 * The Windchill import, as a job the generic framework runs.
 *
 * ## The one thing that makes it unlike JIRA's
 *
 * It is **fed**, not connected. There is no host to reach and no credential to hold: an exporter
 * outside this process produces an OData `Documents` response and a person uploads it, so the file
 * arrives with the request that starts the run and is handed to it as the run's
 * [com.sec.importer.ImportRequest]. That is why this importer refuses to start without one — a run
 * with no file is not an empty import, it is a wiring error, and the run that reported it as an
 * empty import would delete every document in the graph on its way past.
 *
 * When the OData service is reachable directly this class gains a phase that fetches pages and
 * loses nothing else: the parse, the write and the sweep are already independent of where the bytes
 * came from.
 *
 * ## What this class is not allowed to do
 *
 * Talk to Neo4j. Every write goes through [WindchillGraphWriter], which is the only thing in the
 * process that writes imported Windchill data — the structural stand-in for R1's "the application
 * never writes imported data", which an out-of-process importer gets for free (ADR 0013).
 */
public class WindchillImporter(
    private val writer: WindchillGraphWriter,
) : ImportJob {

    override val importerId: String = ID

    override val displayName: String = "Windchill"

    /**
     * Three phases, weighted by where the time actually goes.
     *
     * Reading is already done — the route parses the file before the run exists, so that a broken
     * file is a `400` on the upload rather than a failed run somebody has to go and read about — so
     * [PREFLIGHT] is where the file's *findings* are reported and the schema is applied, not where
     * it is decoded.
     */
    override val phases: List<ImportPhase> = listOf(
        ImportPhase(PREFLIGHT, "Checking the export", weight = 5),
        ImportPhase(DOCUMENTS, "Importing documents", weight = 70),
        ImportPhase(SWEEP, "Removing deleted documents", weight = 25),
    )

    override suspend fun run(context: ImportContext) {
        val export = context.request as? WindchillExport
            ?: throw IllegalStateException(
                "The Windchill importer was started without an export file. It is fed by an upload, " +
                    "so a run can only be started by POSTing one.",
            )

        preflight(context, export)
        val seenIds = importDocuments(context, export)
        sweep(context, seenIds)
    }

    /**
     * Phase 0 — say what the file was, before anything is written.
     *
     * ## The `@odata.nextLink` warning
     *
     * A file carrying one is a **page**, not a collection: Windchill is saying there are more
     * documents than these. The import proceeds anyway — that is the user's explicit decision, and a
     * partial import of real documents is worth more than a refusal — but it is said twice, once as
     * a warning that turns the run amber and once in the log, because phase 2 is about to treat this
     * file as the complete truth and delete everything it does not mention.
     */
    private suspend fun preflight(context: ImportContext, export: WindchillExport) {
        context.phase(PREFLIGHT)

        context.params(
            mapOf(
                "documents" to export.records.size.toString(),
                // Recorded because "why did that import delete 400 documents" is answered by
                // whether the file said it was one page of several.
                "paged" to (export.nextLink != null).toString(),
            ),
        )
        context.log("Read ${export.records.size} document(s) from the uploaded export")

        // The parser's own findings — rows skipped, ids repeated, versions it could not order.
        // Raised here rather than at the upload, so they land on the run where they are read.
        export.warnings.forEach { context.warn(it) }

        if (export.nextLink != null) {
            context.warn(
                "This export is one page of several — it carries an @odata.nextLink, so Windchill " +
                    "has more documents than these. Documents on the pages this file does not " +
                    "contain will be removed as though they had been deleted.",
            )
        }

        // Idempotent, and applied per run rather than at boot: schema for imported labels belongs to
        // whatever imports them, and a deployment with no Windchill should not create its indexes.
        writer.applySchema()
        context.log("Applied the Windchill graph schema")

        context.progress(1, 1)
    }

    /**
     * Phase 1 — write the documents, and return every `__id` the file asserted.
     *
     * That returned set is the most consequential value in the run: the sweep deletes against it,
     * and an incomplete one is indistinguishable from documents Windchill lost. Here it cannot be
     * incomplete — the whole file was parsed before the run started, so there is no page that can
     * fail halfway — which is the one simplification being fed a file buys over being connected.
     */
    private suspend fun importDocuments(
        context: ImportContext,
        export: WindchillExport,
    ): Set<String> {
        context.phase(DOCUMENTS)
        context.ensureActive()

        val written = writer.upsertDocuments(export.records)
        context.setCount(Counter.DOCUMENTS_SEEN, written.toLong())
        context.progress(written, written)

        val versioned = export.records.groupBy { it.number }.count { (_, group) -> group.size > 1 }
        context.log(
            "$written document(s) written" +
                if (versioned > 0) "; $versioned of them have more than one version" else "",
        )

        return export.records.mapTo(LinkedHashSet()) { it.id }
    }

    /**
     * Phase 2 — remove what the export no longer contains.
     *
     * ## The scope, and the warning that stands in for a narrower one
     *
     * The file is the whole truth: anything in the graph and not in the file is deleted, whatever
     * folder it sits in. That is what was asked for, and its failure mode is exact — uploading an
     * export that covers one Windchill context removes every other context's documents. Nothing here
     * can tell that apart from a genuine deletion, because the file carries no statement of what it
     * was *meant* to cover that this could check against.
     *
     * So the guard is a report rather than a refusal, and it is measured after the fact: the run
     * ends `SUCCEEDED_WITH_WARNINGS` and says how much went and out of how many. A refusal would
     * need a dry-run count of the same statement and would block the legitimate case — a genuinely
     * large clean-up — behind a flag nobody would find.
     */
    private suspend fun sweep(context: ImportContext, seenIds: Set<String>) {
        context.phase(SWEEP)
        context.ensureActive()

        val before = writer.documentCount()
        val deleted = writer.sweep(seenIds)

        context.setCount(Counter.DELETED, deleted.toLong())
        context.progress(1, 1)

        if (deleted > 0) {
            context.log("$deleted document(s) no longer in the export were removed")
        }

        if (before > 0 && deleted * PERCENT > before * MASS_DELETE_PERCENT) {
            context.warn(
                "This import removed $deleted of $before documents — more than $MASS_DELETE_PERCENT %. " +
                    "Check that the export covers everything you meant it to: a file covering one " +
                    "folder or one page removes every document it does not mention.",
            )
        }
    }

    /** Counter names for this importer. Not graph names — they are keys inside a JSON text blob. */
    public object Counter {
        public const val DOCUMENTS_SEEN: String = "documentsSeen"

        /** Documents the export no longer contains. */
        public const val DELETED: String = "deleted"
    }

    public companion object {
        /** The importer id: a URL segment and an `:__ImportRun` property. Never renamed. */
        public const val ID: String = "windchill"

        public const val PREFLIGHT: String = "preflight"
        public const val DOCUMENTS: String = "documents"
        public const val SWEEP: String = "sweep"

        /** The share of the standing documents one import may remove before it says so. */
        private const val MASS_DELETE_PERCENT = 20
        private const val PERCENT = 100
    }
}

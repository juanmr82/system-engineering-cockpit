package com.sec.source.doors

import com.sec.importer.ImportContext
import com.sec.importer.ImportJob
import com.sec.importer.ImportPhase
import java.time.Clock
import java.time.Instant

/**
 * The DOORS-from-an-upload import, as a job the generic framework runs (ADR 0019 §1).
 *
 * Ported from `importers/src/sec_import/doors/importer.py`'s `run_import`, phase for phase — the
 * row-building this class does (child pairs, `refersTo` and incoming-link rows) is exactly that
 * function's own, translated; the Cypher it drives is [com.sec.graph.cypher.DoorsImportCypher] and
 * the writes are [DoorsGraphWriter]'s alone (R1's structural stand-in, ADR 0013).
 *
 * Like [com.sec.source.windchill.WindchillImporter], this is **fed, not connected**: the export
 * arrives with the request that starts the run. Unlike Windchill, the gate that decides *whether*
 * to start one — does the module already exist, is it visible to this caller, has this exact file
 * already been imported — runs in the route, before [com.sec.importer.ImportRunService.start] is
 * ever called (ADR 0019 §3, §4); by the time this class runs, that decision is already made.
 *
 * **One run stamp for the whole run.** [Instant.now] is read exactly once, in [run], and threaded
 * through every write phase and the reconciliation that follows them. Reconciliation's whole
 * mechanism is a property comparison against that one value (ADR 0012) — a phase that stamped its
 * own time would make every object it wrote look stale the instant reconciliation ran.
 */
public class DoorsImporter(
    private val writer: DoorsGraphWriter,
    private val clock: Clock = Clock.systemUTC(),
) : ImportJob {

    override val importerId: String = ID
    override val displayName: String = "DOORS"

    override val phases: List<ImportPhase> = listOf(
        ImportPhase(PREFLIGHT, "Checking the export", weight = 5),
        ImportPhase(MODULE, "Writing the module", weight = 5),
        ImportPhase(OBJECTS, "Importing objects", weight = 45),
        ImportPhase(HIERARCHY, "Building the hierarchy", weight = 10),
        ImportPhase(LINKS, "Importing traceability links", weight = 15),
        ImportPhase(RECONCILE, "Reconciling against the export", weight = 20),
    )

    override suspend fun run(context: ImportContext) {
        val export = context.request as? DoorsExport
            ?: throw IllegalStateException(
                "The DOORS importer was started without an export. It is fed by an upload, so a " +
                    "run can only be started by POSTing one.",
            )

        val importedAt = Instant.now(clock).toString()

        preflight(context, export)
        writeModule(context, export)
        writeObjects(context, export, importedAt)
        writeHierarchy(context, export, importedAt)
        writeLinks(context, export, importedAt)
        reconcile(context, export, importedAt)

        // Only once every earlier phase has succeeded (ADR 0019 §3) — a run that throws before
        // this line leaves no checksum a retry of the same file would mistake for a no-op.
        writer.stampChecksum(export.moduleId, export.checksum)
    }

    private suspend fun preflight(context: ImportContext, export: DoorsExport) {
        context.phase(PREFLIGHT)
        context.params(
            mapOf(
                "module" to export.moduleName,
                "objects" to export.objects.size.toString(),
            ),
        )
        context.log(
            "Read ${export.objects.size} object(s) from '${export.moduleName}' " +
                "(${export.moduleVersion})",
        )
        export.warnings.forEach { context.warn(it) }

        writer.applySchema()
        context.log("Applied the DOORS graph schema")
        context.progress(1, 1)
    }

    private suspend fun writeModule(context: ImportContext, export: DoorsExport) {
        context.phase(MODULE)
        context.ensureActive()
        writer.upsertModule(export.moduleId, export.moduleProps)
        context.progress(1, 1)
    }

    private suspend fun writeObjects(context: ImportContext, export: DoorsExport, importedAt: String) {
        context.phase(OBJECTS)
        context.ensureActive()
        val written = writer.upsertObjects(export.objects, importedAt)
        context.setCount(Counter.OBJECTS_SEEN, written.toLong())
        context.progress(written, written)
        context.log("$written object(s) written")
    }

    /**
     * `__child` for every object — root objects hang off the module node, everything else off its
     * parent's `objectNumber`. A parent number the export does not contain is silently skipped, the
     * same as `importer.py`'s own `num_to_url.get(p)` miss.
     */
    private suspend fun writeHierarchy(context: ImportContext, export: DoorsExport, importedAt: String) {
        context.phase(HIERARCHY)
        context.ensureActive()

        val numberToUrl: Map<String, String> = export.objects
            .filter { it.objectNumber.isNotEmpty() }
            .associate { it.objectNumber to it.objectUrl }

        val pairs = export.objects.mapNotNull { row ->
            val parentNumber = DoorsDerivations.parentNumber(row.objectNumber)
            val parentId = if (parentNumber == null) export.moduleId else numberToUrl[parentNumber]
            parentId?.let { it to row.objectUrl }
        }

        val written = writer.upsertChild(pairs, importedAt)
        context.setCount(Counter.CHILD_RELS_SEEN, written.toLong())
        context.progress(written, written)
    }

    /**
     * `refersTo`, both directions — `__outputLinks` (what this module asserts) and `__inputLinks`
     * (what other modules assert about it, visible whether or not they have been imported yet).
     */
    private suspend fun writeLinks(context: ImportContext, export: DoorsExport, importedAt: String) {
        context.phase(LINKS)
        context.ensureActive()

        val outgoing = export.objects.flatMap { row ->
            row.outputLinks.mapNotNull { link ->
                outgoingLinkRow(context, export.moduleId, row, link, importedAt)
            }
        }
        val incoming = export.objects.flatMap { row ->
            row.inputLinks.mapNotNull { link ->
                incomingLinkRow(context, row, link, importedAt)
            }
        }

        val writtenOut = writer.upsertRefersTo(outgoing)
        val writtenIn = writer.upsertIncoming(incoming)
        context.setCount(Counter.REFERS_TO_SEEN, writtenOut.toLong())
        context.setCount(Counter.INCOMING_LINKS_SEEN, writtenIn.toLong())
        context.progress(1, 1)
    }

    /** One `__outputLinks` entry, or `null`, warned, if it names no target or a malformed URL. */
    private suspend fun outgoingLinkRow(
        context: ImportContext,
        sourceModuleUrl: String,
        owner: DoorsObjectRow,
        link: DoorsLinkRef,
        importedAt: String,
    ): Map<String, Any?>? {
        val end = resolveLinkEnd(context, owner, link, incoming = false) ?: return null
        return mapOf(
            "sourceId" to owner.objectUrl,
            "targetId" to end.objectUrl,
            "targetModuleUrl" to end.reqDocumentUrl,
            "targetVersion" to end.version,
            "absoluteNumber" to end.absoluteNumber,
            "targetName" to "<unresolved ${end.objectUrl}>",
            "sourceModuleUrl" to sourceModuleUrl,
            "importedAt" to importedAt,
        )
    }

    /** One `__inputLinks` entry — the mirror of [outgoingLinkRow] with the ends swapped. */
    private suspend fun incomingLinkRow(
        context: ImportContext,
        owner: DoorsObjectRow,
        link: DoorsLinkRef,
        importedAt: String,
    ): Map<String, Any?>? {
        val end = resolveLinkEnd(context, owner, link, incoming = true) ?: return null
        return mapOf(
            "sourceId" to end.objectUrl,
            "targetId" to owner.objectUrl,
            "sourceModuleUrl" to end.reqDocumentUrl,
            "sourceVersion" to end.version,
            "absoluteNumber" to end.absoluteNumber,
            "sourceName" to "<unresolved ${end.objectUrl}>",
            "importedAt" to importedAt,
        )
    }

    private data class LinkEnd(
        val objectUrl: String,
        val reqDocumentUrl: String,
        val version: String,
        val absoluteNumber: Int?,
    )

    private suspend fun resolveLinkEnd(
        context: ImportContext,
        owner: DoorsObjectRow,
        link: DoorsLinkRef,
        incoming: Boolean,
    ): LinkEnd? {
        val objectId = owner.props["id"] as? String ?: owner.objectUrl
        val direction = if (incoming) "incoming" else "outgoing"
        val reqDocumentUrl = link.reqDocumentUrl.trim()
        val absoluteNumber = link.absoluteNumber.trim()

        if (reqDocumentUrl.isEmpty() || absoluteNumber.isEmpty()) {
            context.warn(
                "Object $objectId has an $direction link with no document URL or Absolute " +
                    "Number; it was skipped.",
            )
            return null
        }

        return try {
            LinkEnd(
                objectUrl = DoorsDerivations.targetObjectUrl(reqDocumentUrl, absoluteNumber),
                reqDocumentUrl = reqDocumentUrl,
                version = DoorsDerivations.targetVersion(reqDocumentUrl),
                absoluteNumber = absoluteNumber.toIntOrNull(),
            )
        } catch (cause: MalformedUrlError) {
            context.warn(
                "Object $objectId has an $direction link whose document URL could not be read " +
                    "(${cause.message}); it was skipped.",
            )
            null
        }
    }

    private suspend fun reconcile(context: ImportContext, export: DoorsExport, importedAt: String) {
        context.phase(RECONCILE)
        context.ensureActive()

        val counts = writer.reconcile(export.moduleId, importedAt)
        context.setCount(Counter.OBJECTS_DELETED_IN_SOURCE, counts.ghosts.toLong())
        context.setCount(Counter.OBJECTS_NEWLY_DELETED, counts.newlyDeleted.toLong())
        context.setCount(Counter.GHOST_META_DELETED, counts.ghostMetaDeleted.toLong())
        context.setCount(Counter.GHOSTS_COLLECTED, counts.ghostsCollected.toLong())
        context.setCount(Counter.PLACEHOLDERS_REMOVED, counts.placeholdersRemoved.toLong())
        context.progress(1, 1)

        if (counts.newlyDeleted > 0) {
            context.log(
                "${counts.newlyDeleted} object(s) no longer in the export were marked deleted in DOORS",
            )
        }
    }

    public object Counter {
        public const val OBJECTS_SEEN: String = "objectsSeen"
        public const val CHILD_RELS_SEEN: String = "childRelsSeen"
        public const val REFERS_TO_SEEN: String = "refersToSeen"
        public const val INCOMING_LINKS_SEEN: String = "incomingLinksSeen"
        public const val OBJECTS_DELETED_IN_SOURCE: String = "objectsDeletedInSource"
        public const val OBJECTS_NEWLY_DELETED: String = "objectsNewlyDeleted"
        public const val GHOST_META_DELETED: String = "ghostMetaDeleted"
        public const val GHOSTS_COLLECTED: String = "ghostsCollected"
        public const val PLACEHOLDERS_REMOVED: String = "placeholdersRemoved"
    }

    public companion object {
        /** The importer id: a URL segment and an `:__ImportRun` property. Never renamed. */
        public const val ID: String = "doors"

        public const val PREFLIGHT: String = "preflight"
        public const val MODULE: String = "module"
        public const val OBJECTS: String = "objects"
        public const val HIERARCHY: String = "hierarchy"
        public const val LINKS: String = "links"
        public const val RECONCILE: String = "reconcile"
    }
}

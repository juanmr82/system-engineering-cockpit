package com.sec.source.doors

import com.sec.importer.ImportRequest

/** One entry of an object's `__outputLinks` or `__inputLinks` — DOORS's own untyped traceability. */
public data class DoorsLinkRef(
    public val reqDocumentUrl: String,
    public val absoluteNumber: String,
)

/**
 * One DOORS object, as a `__contents` entry becomes it — the object-level counterpart of
 * [WindchillRecord][com.sec.source.windchill.WindchillRecord]: a value [DoorsExportParser]
 * produces and [DoorsGraphWriter] writes, with nothing between them that talks to the graph.
 *
 * @property objectUrl `__objectUrl` — the node's `__id` (R6). Never a DOORS-local `id`.
 * @property labels the full derived label set, [com.sec.domain.NodeLabel.SE_ITEM] and
 *   [DoorsLabel.OBJECT] always included.
 * @property props everything `SET` onto the node, already Tier-1-derived and meta-key-filtered —
 *   see `DoorsExportParser.buildObjectProps`. Values are `String`, `Int` or `Boolean` only, which is
 *   everything the Neo4j driver accepts as a map parameter without a converter.
 */
public data class DoorsObjectRow(
    public val objectUrl: String,
    public val objectNumber: String,
    public val labels: Set<String>,
    public val props: Map<String, Any?>,
    public val outputLinks: List<DoorsLinkRef>,
    public val inputLinks: List<DoorsLinkRef>,
)

/**
 * A parsed DOORS module export, and everything the importer needs to know about how it read.
 *
 * It is the run's [ImportRequest] (ADR 0019 §1): the file arrives with the request that starts the
 * import and cannot be fetched again, so it travels to the run rather than being left in a slot on
 * the importer — the exact seam ADR 0015 built for Windchill and named DOORS as the next to use.
 *
 * @property moduleId the module's own `url`, trimmed — its node's `__id`, and what every object in
 *   [objects] carries as `__moduleUrl`.
 * @property checksum SHA-256 of the raw uploaded bytes, hex-encoded — computed once, here, from the
 *   bytes themselves rather than from [moduleProps] or any parsed value, so it is never affected by
 *   how a field was interpreted (ADR 0019 §3).
 * @property warnings findings from the parse itself — duplicate keys, a `__moduleUrl` that disagrees
 *   with the module's own, a file large enough to suspect truncation. Carried rather than logged, so
 *   they land on the run where they are read, the same convention [WindchillExport] uses.
 */
public data class DoorsExport(
    public val moduleId: String,
    public val moduleName: String,
    public val moduleVersion: String,
    public val moduleProps: Map<String, Any?>,
    public val objects: List<DoorsObjectRow>,
    public val checksum: String,
    public val warnings: List<String> = emptyList(),
) : ImportRequest

/** Why a file could not be read at all, as opposed to one defect inside it being reported. */
public sealed interface DoorsExportProblem {
    /** The bytes are not JSON. [detail] is the parser's own message. */
    public data class NotJson(public val detail: String) : DoorsExportProblem

    /** JSON, but not a DOORS module export: no top-level object, or no usable `__contents` array. */
    public data class NotAnExport(public val detail: String) : DoorsExportProblem

    /** Read as JSON, but failed a structural check: a required key is missing, or the module's
     *  `url` and `__version` disagree about whether this is the current module or a baseline. */
    public data class Invalid(public val detail: String) : DoorsExportProblem
}

/** Carries a [DoorsExportProblem] through `Result`. Never thrown out of the parse. */
public class DoorsExportFailure(public val problem: DoorsExportProblem) : Exception(problem.toString())

/**
 * What the pre-run gate (ADR 0019 §4) found for one module id, before the caller's own visibility is
 * weighed against it.
 *
 * @property exists whether a `:DOORSModule` with this `__id` is already in the graph, regardless of
 *   whether the caller may see it.
 * @property visible whether the caller's own [com.sec.security.AccessSet] can see it. Always `false`
 *   when [exists] is `false` — there is nothing to see, not "nobody may see it".
 * @property storedChecksum the module's current `__exportChecksum`, or null if it has none yet (a
 *   module imported before this feature existed, or [exists] is `false`).
 */
public data class DoorsImportGate(
    public val exists: Boolean,
    public val visible: Boolean,
    public val storedChecksum: String?,
)

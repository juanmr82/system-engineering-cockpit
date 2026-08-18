package com.sec.api.dto

import kotlinx.serialization.Serializable

/**
 * What an upload to `/doors/import` answered with (ADR 0019).
 *
 * One shape for both outcomes a successful request can have — `"started"` (`202`, a run to watch)
 * or `"skipped"` (`200`, this exact file was already imported) — because the settings page has one
 * thing to say either way: what the file turned out to be. [runId] is the only field that tells them
 * apart on the wire.
 */
@Serializable
public data class DoorsImportResultDto(
    public val status: String,
    /** Present only when [status] is `"started"` — the run to watch over SSE. */
    public val runId: String? = null,
    /** Base64url over the module's `__id` (R5) — never the raw id. */
    public val moduleRef: String,
    public val moduleName: String,
    public val objects: Int,
    public val checksum: String,
    /** The parser's findings. Also raised as warnings on the run when [status] is `"started"`. */
    public val warnings: List<String> = emptyList(),
)

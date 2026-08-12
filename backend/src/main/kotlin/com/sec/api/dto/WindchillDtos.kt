package com.sec.api.dto

import kotlinx.serialization.Serializable

/**
 * One Windchill document, as the Documents table draws it.
 *
 * **Windchill's own `ID` is deliberately not here.** It is stored on the node — the info-page link
 * is built from it — and it is never sent, because it is never shown and the row is addressed by
 * [ref] like every other item in this application (R5, R6).
 *
 * The five value fields are the export's own, and they carry Windchill's wording rather than a
 * translation of it: `State` reaches this as its `Display` string, because that is the word
 * Windchill itself puts on a screen.
 *
 * Called `WindchillDocumentRow` and not `WindchillDocument`: a Kotlin type sharing a name with a
 * graph label breaks `GraphNamesTest`'s inverse check the moment a statement imports from it. See
 * `WindchillNames.kt`.
 */
@Serializable
public data class WindchillDocumentRow(
    /** Base64url over `__id` — the row key and the future route parameter. Never the raw id. */
    public val ref: String,
    public val folderLocation: String,
    public val name: String,
    /** Shared by every version of one document, and what the view groups on. */
    public val number: String,
    public val version: String,
    public val state: String,
    /**
     * Windchill's info page for this document, derived on every read and never stored (R2).
     *
     * Null when no host is configured, which the table renders as an absent control rather than a
     * link that goes nowhere.
     */
    public val browseUrl: String? = null,
)

/**
 * Every Windchill document, in one response.
 *
 * Unpaged on purpose — see `WindchillProjection` — so the fields that would describe a page describe
 * the whole set instead.
 */
@Serializable
public data class WindchillDocumentsDto(
    /** In `__sortKey` order: by number ascending, then newest version first. */
    public val rows: List<WindchillDocumentRow>,
    public val total: Int,
    /**
     * Whether the server's row cap was reached.
     *
     * A cap hit silently is a table that is quietly wrong, so it travels with the data rather than
     * being inferred from a count the client would have to know the ceiling of.
     */
    public val truncated: Boolean = false,
    /**
     * Whether this deployment knows where Windchill is.
     *
     * Sent so the empty state can tell "nothing imported yet" from "no Windchill configured" —
     * different sentences, different next actions, and the row count is zero in both.
     */
    public val hostConfigured: Boolean = false,
)

/**
 * What an upload did, answered synchronously by the upload itself.
 *
 * The file is parsed **before** the run is started, so a broken file is a `400` on this request
 * rather than a run that fails somewhere a person has to go and look. What comes back is the run to
 * watch, plus the two things a user wants confirmed before the console has said anything:
 * how many documents were read, and whether the file admits to being one page of several.
 */
@Serializable
public data class WindchillImportStartedDto(
    public val runId: String,
    public val documents: Int,
    /**
     * True when the file carried an `@odata.nextLink`.
     *
     * The import is allowed and proceeds; this is what lets the page say so at the moment of upload,
     * before the run's own warning arrives over the event stream.
     */
    public val paged: Boolean = false,
    /** The parser's findings — rows skipped, ids repeated. Also raised as warnings on the run. */
    public val warnings: List<String> = emptyList(),
)

/** Whether Windchill is configured, for the settings page. Never carries a credential — there is none. */
@Serializable
public data class WindchillHealthDto(
    public val configured: Boolean,
    /** The configured host, which is not a secret: it is in every document link on the page. */
    public val host: String,
)

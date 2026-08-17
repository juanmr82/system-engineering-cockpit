package com.sec.source.windchill

import com.sec.api.dto.WindchillDocumentRow
import com.sec.api.dto.WindchillDocumentsDto
import com.sec.config.WindchillSettings
import com.sec.domain.Ref
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.WindchillCypher
import com.sec.graph.executeRead
import com.sec.security.AccessSet
import org.neo4j.driver.Query
import org.neo4j.driver.Record

/**
 * The Documents view's read path.
 *
 * Windchill-specific and confined to this package, exactly as the DOORS and JIRA projections are.
 * It reads and never writes: everything here is `executeRead`, which on Community is the only
 * server-side write protection there is.
 *
 * ## Why it returns everything at once
 *
 * Because the view groups versions of one document under a header, and a group is only drawable
 * when every version of a `Number` is in hand. Paging would put that decision on the server and
 * still not answer it — a page boundary falling inside a group leaves the header on one page and
 * two of its versions on the next. At ~1 500 documents the whole set is a few hundred kilobytes,
 * searching it in the browser costs no round trip at all, and the ceiling is stated rather than
 * discovered: [DOCUMENT_CAP], reported when it is hit.
 *
 * ## What it is responsible for that the Cypher is not
 *
 * Two things, and both are the difference between a graph row and something a browser may hold:
 *
 *  - **`ref`, never `__id`.** The row key is the base64url handle, so no internal id reaches the
 *    address bar or a DOM attribute (R5).
 *  - **`browseUrl`.** The document's OData resource URL is an API URL — opening one shows raw JSON.
 *    The link a person clicks is Windchill's info page, built from the object id, and it is
 *    *derived*, so it is computed on every read and never stored (R2).
 *
 * @param settings the Windchill host, for [WindchillSettings.infoPageUrl]. Unconfigured makes the
 *   link absent rather than broken, which the table renders as an absent control.
 */
public class WindchillProjection(
    private val graphDriver: GraphDriver,
    private val settings: WindchillSettings,
) {

    /**
     * Every document, in `__sortKey` order: by number, then newest version first.
     *
     * The order is the server's and the view keeps it as the order *inside* a group. Re-sorting the
     * table by a column reorders the groups, never the versions within one — see the Documents view.
     */
    public suspend fun listDocuments(access: AccessSet): WindchillDocumentsDto =
        graphDriver.executeRead(
            WindchillCypher.LIST_DOCUMENTS, mapOf("limit" to DOCUMENT_CAP), access,
        ) { records ->
            val rows = records.map(::row)
            WindchillDocumentsDto(
                rows = rows,
                // Not a page count: it says "there are at least this many and you are seeing all of
                // them", unless [truncated] says otherwise.
                total = rows.size,
                // A cap reached silently is a table that is quietly wrong. The view says so.
                truncated = rows.size >= DOCUMENT_CAP,
                // So the empty state can tell "nothing imported yet" from "no Windchill configured",
                // which need different sentences and different next actions.
                hostConfigured = settings.isConfigured,
            )
        }

    private fun row(record: Record): WindchillDocumentRow {
        val id = record["id"].asString("")
        val oid = record["oid"].asString("")
        return WindchillDocumentRow(
            ref = Ref.encode(id),
            folderLocation = record["folderLocation"].asString(""),
            name = record["name"].asString(""),
            number = record["number"].asString(""),
            version = record["version"].asString(""),
            state = record["state"].asString(""),
            // Derived on every read, never stored. Null when no host is configured, which is an
            // absent link rather than one that goes nowhere.
            browseUrl = settings.infoPageUrl(oid),
        )
    }

    private companion object {
        /**
         * The most rows one response carries.
         *
         * Community has no query governor (§7), so this is the only thing between a grown data set
         * and a response no browser can render. Set well above the ~1 500 documents production
         * starts with and well below the point ag-grid's client-side model stops being comfortable;
         * crossing it is the signal to move this endpoint to server-side paging, which is a design
         * change and not a bigger number.
         */
        const val DOCUMENT_CAP = 20_000
    }
}

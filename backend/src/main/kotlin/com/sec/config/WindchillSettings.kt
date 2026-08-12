package com.sec.config

import io.ktor.server.config.ApplicationConfig

/**
 * Everything the backend needs to know about one PTC Windchill instance — which today is one thing:
 * where it lives.
 *
 * ## Why there is no credential here
 *
 * Windchill's importer is fed by an **uploaded export file**, not by a connection. The export is
 * produced outside this process and posted to it, so the backend never authenticates against
 * Windchill and has no token to hold. That is expected to change — the file is a stand-in for the
 * OData service the exporter is already talking to — and when it does, this is where the connection
 * settings arrive, beside [JiraSettings] and shaped like it.
 *
 * ## What the host is for
 *
 * One thing, and it is a read-path concern: every document row carries a link into Windchill's own
 * UI, and that link is **derived on every read** rather than stored. The export gives an OData
 * resource URL, and opening one shows raw JSON; a person wants the info page. Deriving it means a
 * deployment that moves Windchill fixes every link by editing one line, and it keeps a computed
 * value out of the graph (R2).
 *
 * An empty host is not a failure. It means the link column has nothing to point at and says so,
 * exactly as an unconfigured JIRA does — importing a file still works, because the file is the
 * source and the host is not.
 */
public data class WindchillSettings(
    /**
     * Scheme, host and the Windchill context path — `https://windchill.example.com/Windchill`.
     * Not a bare origin: Windchill is served under a context path in every deployment we have seen,
     * and code that assumes otherwise builds links that 404. Normalised through [normaliseHost].
     */
    public val host: String,
) {
    public val isConfigured: Boolean get() = host.isNotBlank()

    /**
     * The info page for one document, from Windchill's own object id.
     *
     * The `#` makes the oid a **fragment**, which never reaches the server — so this is a URL for a
     * browser to open and not one anything here could fetch. The oid is percent-encoded because it
     * is source data: `OR:wt.doc.WTDocument:905344148` is safe today and nothing guarantees the next
     * object type is.
     */
    public fun infoPageUrl(oid: String): String? =
        if (!isConfigured || oid.isBlank()) null else "$host$INFO_PAGE${encodeOid(oid)}"

    public companion object {
        /** Windchill's own info-page route, up to the object id. A constant, like `/rest/api/2/`. */
        public const val INFO_PAGE: String = "/app/#ptc1/tcomp/infoPage?oid="

        /**
         * Percent-encodes the characters an oid could carry that a query string gives a meaning to.
         *
         * Deliberately not `URLEncoder`: it encodes a space as `+`, which is right for a form body
         * and wrong inside a URL fragment, and it escapes `:` — which every Windchill oid contains
         * and which is legal in a fragment. Encoding it would produce a link Windchill rejects.
         */
        private fun encodeOid(oid: String): String = buildString(oid.length) {
            oid.forEach { c ->
                when (c) {
                    in 'A'..'Z', in 'a'..'z', in '0'..'9', ':', '-', '_', '.', '~' -> append(c)
                    else -> c.toString().toByteArray(Charsets.UTF_8)
                        .forEach { b -> append('%').append("%02X".format(b)) }
                }
            }
        }

        /** A trailing slash is the commonest way to configure this wrongly, and it is harmless. */
        public fun normaliseHost(raw: String): String = raw.trim().trimEnd('/')
    }
}

/**
 * Reads the `windchill` block, which may be absent entirely.
 *
 * Absent means unconfigured, not broken — the same contract [loadJiraSettings] has, and for the
 * same reason: a deployment with no Windchill must start, and every other feature must work.
 */
public fun loadWindchillSettings(config: ApplicationConfig): WindchillSettings =
    WindchillSettings(
        host = WindchillSettings.normaliseHost(
            config.propertyOrNull("windchill.host")?.getString().orEmpty(),
        ),
    )

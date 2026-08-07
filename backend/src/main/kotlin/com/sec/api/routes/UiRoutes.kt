package com.sec.api.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import java.net.URL

// The packaged single-page application.
//
// `mvn -Pui package` copies frontend/dist/frontend/browser into the jar under `static/`, which
// leaves one artifact carrying both the API and the UI: `java -jar`, one process, one port, no
// dev server and no /api proxy. The frontend already addresses the API with root-relative URLs
// ("/api/v1/modules"), so being served from the same origin is all it needs.
//
// When the UI has NOT been packaged - every test that does not supply one, and development
// against `ng serve` - everything here is a no-op and the server behaves exactly as before. The
// dev server on :4200 with its hot reload stays the development story; this file is deployment.
//
// WHY THIS IS HAND-ROLLED RATHER THAN staticResources("/", "static"):
//
// Ktor's static routing mounted at "/" installs its own catch-all, and that catch-all answers
// 404 itself for anything it cannot find. It therefore wins over the tail-card fallback and
// takes two things with it - the client-side routes, which must return index.html, and every
// unknown /api path, which must stay an RFC 9457 problem detail. Both regressions were real and
// are covered by PackagedUiTest. Serving the tree from one function keeps every one of those
// decisions in a single readable place.

private object UiResources

private const val UI_ROOT = "static"
private const val INDEX_RESOURCE = "$UI_ROOT/index.html"

private val loader: ClassLoader = UiResources::class.java.classLoader

// Read once. The file never changes for the life of the process, and re-reading it would put a
// classpath lookup on the path of every client-side route.
private val indexHtml: String? by lazy { loader.getResource(INDEX_RESOURCE)?.readText() }

internal val uiIsPackaged: Boolean
    get() = indexHtml != null

/**
 * Serves the packaged UI for one request, returning true when it did.
 *
 * The order of the decisions is the contract:
 *
 *  1. no packaged UI, or not a GET  -> not ours; the caller answers.
 *  2. under `/api/`                 -> not ours, ALWAYS. An unknown endpoint stays a problem
 *                                      detail (CLAUDE.md section 5); a 200 with a page in it is
 *                                      the least useful possible answer to a mistyped API call.
 *  3. a real asset in the jar       -> that file.
 *  4. anything else                 -> `index.html`, because it is an Angular route.
 *
 * Step 4 is what makes `/requirements/modules` survive a reload, a bookmark or a pasted link:
 * the server has no route of its own for it, and the client router resolves it once loaded.
 */
internal suspend fun respondPackagedUi(call: ApplicationCall): Boolean {
    val index = indexHtml ?: return false
    if (call.request.httpMethod != HttpMethod.Get) return false

    val path = call.request.path()
    if (path.startsWith("/api/")) return false

    // A path whose last segment carries a dot is asking for a FILE, and is answered only by that
    // file. Returning index.html for a missing one - which is what a naive SPA fallback does -
    // means a browser asking for a stale `main-A1B2C3.js` after a redeploy receives an HTML
    // document with status 200 and reports a syntax error in it. A 404 is the honest answer, and
    // returning false here produces the same RFC 9457 problem detail as any other missing thing.
    //
    // This application emits no favicon, so /favicon.ico takes exactly that path.
    if (looksLikeAsset(path)) {
        val asset = assetFor(path) ?: return false
        call.respondBytes(asset.readBytes(), ContentType.defaultForFilePath(path))
        return true
    }

    // Everything left is a client-side route: /requirements/modules and its like. The server has
    // no route of its own for it, and the client router resolves it once the page has loaded.
    call.respondText(index, ContentType.Text.Html)
    return true
}

/**
 * Does this path name a file rather than a client-side route?
 *
 * Every file Angular emits has an extension (`main-A1B2C3.js`, `styles.css`) and no route in this
 * application has one, which makes the dot in the last segment a reliable divider. It also keeps
 * "/" out: a directory inside a jar resolves to a URL whose bytes are meaningless.
 */
private fun looksLikeAsset(path: String): Boolean =
    path.substringAfterLast('/').contains('.')

/** The packaged file for a request path, or null when there is no such file. */
private fun assetFor(path: String): URL? {
    val relative = path.removePrefix("/")
    if (relative.isEmpty()) return null

    // Path traversal. getResource does not normalise, so "../.." style input could otherwise
    // reach outside static/ and read anything on the classpath.
    if (relative.contains("..") || relative.contains('\\')) return null

    return loader.getResource("$UI_ROOT/$relative")
}

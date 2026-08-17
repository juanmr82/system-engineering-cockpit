package com.sec.api

/**
 * The HTTP surface's fixed path segments.
 *
 * [PREFIX] and [V1] were spelled out in seven route files and in `UiRoutes`, which is one place
 * too many for a decision that has to hold in all of them at once: the SPA fallback answers any
 * non-`/api` path with `index.html`, so a route mounted under a prefix the fallback does not
 * recognise stops being a 404 and starts being an HTML page with status 200 — the least useful
 * possible answer to a mistyped API call. Stating the prefix once is what keeps the two in step.
 *
 * Per-feature segments stay in their own route file. This is the shared spine, not a registry of
 * every path in the application — a constant for `"/modules"`, used once, would be indirection
 * without a reader.
 */
public object ApiPaths {
    /**
     * Everything the API owns. `UiRoutes` tests against this to decide what may *not* fall back to
     * the single-page application, so it is deliberately unversioned: a future `/api/v2` must be
     * excluded from the fallback on the day it is mounted, not on the day someone remembers.
     */
    public const val PREFIX: String = "/api"

    /** The current version. Every route below is mounted under it. */
    public const val V1: String = "$PREFIX/v1"

    public const val HEALTH: String = "$V1/health"
    public const val READY: String = "$V1/ready"

    /**
     * The session (ADR 0017). [AUTH_LOGIN] and [AUTH_CALLBACK] are the only two routes reachable
     * with no session — declared here, beside the paths themselves, for the same reason every
     * other fixed segment is (CLAUDE.md §5, ADR 0010). [AUTH_ME] and [AUTH_LOGOUT] require one.
     */
    public const val AUTH: String = "$V1/auth"
    public const val AUTH_LOGIN: String = "$AUTH/login"
    public const val AUTH_CALLBACK: String = "$AUTH/callback"
    public const val AUTH_LOGOUT: String = "$AUTH/logout"
    public const val AUTH_ME: String = "$AUTH/me"

    public const val CONFIG: String = "$V1/config"
    public const val MODULES: String = "$V1/modules"
    public const val ITEMS: String = "$V1/items"
    public const val STATISTICS: String = "$V1/statistics"

    /**
     * Comment threads (`docs/req-review-comment-threads.md` §4): `GET`/`POST $ITEMS/{ref}/annotations`
     * for a thread, `PATCH`/`DELETE $ANNOTATIONS/{ref}` for one note in it — `{ref}` there is the
     * note's own `__metaId`, not the item's, so the route needs no item context to resolve or
     * delete a thread.
     */
    public const val ANNOTATIONS: String = "$V1/annotations"

    /**
     * The JIRA integration. Everything under it needs a configured host and token — except
     * [JIRA_HEALTH], whose entire job is to report whether they are there.
     */
    public const val JIRA: String = "$V1/jira"

    public const val JIRA_HEALTH: String = "$JIRA/health"

    /**
     * The Issues table's rows (spec §14.4).
     *
     * Paged, filtered and sorted **server-side**, because the set is 784 issues on the reference
     * instance and tens of thousands on a real one. A client that asks for more than the cap gets
     * the cap, not an error: the size is a request, and the ceiling is the server's.
     */
    public const val JIRA_ISSUES: String = "$JIRA/issues"


    /**
     * The offerable field catalogue, for the column picker (spec §13.3).
     *
     * Read from the graph, not from JIRA: it is what the last import brought in, which is exactly
     * the set the table can render. Asking JIRA live would offer columns whose values no issue in
     * this database carries.
     */
    public const val JIRA_FIELDS: String = "$JIRA/fields"

    /** The chosen columns and their order (spec §10.2). `GET` is public, `PUT` is the picker's. */
    public const val JIRA_COLUMNS: String = "$JIRA/columns"

    /**
     * The columns a deployment starts with, resolved the same way any other set is.
     *
     * Its own endpoint because *Reset to defaults* has to mean the server's defaults. A client that
     * held its own copy of that list would be a second declaration of it, and the two would part
     * company the first time one was edited — the same argument that keeps every graph name in one
     * place (ADR 0010).
     */
    public const val JIRA_COLUMN_DEFAULTS: String = "$JIRA_COLUMNS/defaults"

    /**
     * The live project list, proxied from JIRA — a read-only diagnostic (ADR 0018).
     *
     * The one JIRA route that is a proxy rather than a read of our own graph. There is no picker
     * behind it any more: the importer brings in everything the token can see, and this is how the
     * settings page still answers "what will that actually be" without a second copy of the answer.
     */
    public const val JIRA_PROJECTS: String = "$JIRA/projects"

    /**
     * The Windchill integration.
     *
     * Two endpoints and no credential: this source is fed by an uploaded export, so the backend
     * never authenticates against Windchill and has nothing to hold. [WINDCHILL_HEALTH] reports
     * whether a host is configured, which decides only whether a document row can link back.
     */
    public const val WINDCHILL: String = "$V1/windchill"

    public const val WINDCHILL_HEALTH: String = "$WINDCHILL/health"

    /**
     * Every imported document, in one response.
     *
     * Deliberately unpaged, unlike [JIRA_ISSUES]: the view groups versions of one document under a
     * header, and a group is only drawable with every version of a `Number` in hand. The set is
     * ~1 500 rows and the server caps it — see `WindchillProjection`.
     */
    public const val WINDCHILL_DOCUMENTS: String = "$WINDCHILL/documents"

    /**
     * Upload an export and import it — one gesture, one request (R7).
     *
     * The file is parsed here and the run is started with it, so a malformed file is a `400` on this
     * call rather than a run that fails out of sight. It is **not** under [IMPORT] because the
     * framework's `POST /import/{importerId}/runs` takes no body: an importer that is fed needs a
     * door of its own, and the door belongs to the source, not to the framework.
     */
    public const val WINDCHILL_IMPORT: String = "$WINDCHILL/import"

    /**
     * The import framework, source-agnostic: `{importerId}` is the only place a path says which
     * source, and it says it as a string the importer chose.
     *
     * Deliberately not under [JIRA] — DOORS and Windchill get the same endpoints for free, and a
     * per-source import API would be three copies of one contract (spec §11.4).
     */
    public const val IMPORT: String = "$V1/import"

    /** Run resources, addressed by the `run-<uuid>` this application minted. */
    public const val IMPORT_RUNS: String = "$IMPORT/runs"

    /**
     * The Access views (spec §9, §10.2). `AccessAdminService` (phase 6) builds this one screen at
     * a time — [ACCESS_CATEGORIES] first; groups/grants, containers and defaults follow.
     */
    public const val ACCESS: String = "$V1/access"

    /**
     * Runs [com.sec.security.AccessReconciler] (§8.3): `?scope=all` (every registered source) or
     * `?scope=source&source=<id>` (one — the import-pipeline hook's own scope, exposed here too so
     * `sec-import-doors.ps1` can ask for exactly what its run touched). Synchronous today — it
     * returns the counts directly rather than a run id on the SSE stream, unlike the spec's own
     * sketch, because a reconcile pass is index-driven and batched, not the minutes-long kind of
     * work `ImportRunService` exists for. Revisit if a deployment's pass is slow enough to want one.
     */
    public const val ACCESS_RECONCILE: String = "$ACCESS/reconcile"

    /**
     * The Categories screen (spec §10.2 screen 1): `GET`/`POST` here, `PATCH`/`DELETE` at
     * `$ACCESS_CATEGORIES/{ref}`. `DELETE` is `409` while any object or grant still references the
     * category, per [com.sec.api.ProblemType.ACCESS_CATEGORY_IN_USE].
     */
    public const val ACCESS_CATEGORIES: String = "$ACCESS/categories"

    /**
     * The Grants screen (spec §10.2 screen 2): `GET` here, `PUT $ACCESS_GROUPS/{ref}/grants` (the
     * whole grant set, one group, one transaction — R7) and `PATCH $ACCESS_GROUPS/{ref}` (`seesAll`
     * only). `{ref}` is a group's `key`, not an `__id` — groups have no `__metaId` either.
     */
    public const val ACCESS_GROUPS: String = "$ACCESS/groups"

    /**
     * The Unassigned queue (spec §10.2 screen 3): `GET ?state=unassigned&source=&q=` here,
     * `PUT $ACCESS_CONTAINERS/{ref}/categories` for the whole direct set (R7). Deliberately exempt
     * from the visibility predicate (`docs/features/access-control.md` §16.2a) — the one screen an
     * access manager needs before they can grant themselves anything else.
     */
    public const val ACCESS_CONTAINERS: String = "$ACCESS/containers"

    /**
     * The single-item escape hatch (spec §8.1): `PUT $ACCESS_ITEMS/{ref}/categories`, the exact
     * same write [ACCESS_CONTAINERS]'s categories route makes — see
     * [com.sec.graph.cypher.AccessCypher.REPLACE_DIRECT_CATEGORIES]'s own doc comment for why one
     * statement serves both anchor shapes.
     */
    public const val ACCESS_ITEMS: String = "$ACCESS/items"

    /**
     * The Import defaults screen (spec §10.2 screen 4): `GET`/`PUT`, per `(sourceId,
     * containerLabel)`. Empty is a legitimate, and the default, answer — a pair with no
     * `:__AccessDefault` node yet is still a row here, `categoryRef: null`.
     */
    public const val ACCESS_DEFAULTS: String = "$ACCESS/defaults"

    /** Counts for the Access dashboard (spec §9), computed on read (R2) — never stored. */
    public const val ACCESS_SUMMARY: String = "$ACCESS/summary"

    /**
     * `{ref}` is the base64url encoding of `__id` (R5) — an opaque handle, never the raw id, and
     * decoded in exactly one place by the route parameter converter.
     */
    public const val REF: String = "{ref}"

    /**
     * One issue's related-issues graph — the links it has, and the links those have.
     *
     * `{ref}` is the base64url handle over `__id`, like every other item route (R5). The depth is a
     * query parameter and is clamped server-side, because it is a *cost* rather than a preference.
     */
    public const val JIRA_ISSUE_GRAPH: String = "$JIRA_ISSUES/$REF/graph"
}

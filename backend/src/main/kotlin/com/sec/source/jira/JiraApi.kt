package com.sec.source.jira

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The JIRA REST surface this application reads, and the wire types it reads it into.
 *
 * **Read-only, and that is a property of the interface rather than a convention.** Nothing here
 * has a write shape: there is no create, no transition, no comment, no field update. The
 * application imports from JIRA and never writes to it, and the way to keep that true as this
 * grows is for the only door to JIRA to have no handle on the inside.
 *
 * An interface rather than a class so the importer can be tested against a scripted JIRA — paging,
 * a mid-run 429, a project that vanishes between the catalogue call and the search — none of which
 * a live instance can be made to do on demand.
 */
public interface JiraApi {

    /**
     * `GET /rest/api/2/field` — every field, system and custom, with its declared schema.
     *
     * Cheap, unpaginated, and needs no permission at all. It is the only place a field's type is
     * stated independently of any one issue's data, which is what the selection dialog needs
     * (design doc §3).
     */
    public suspend fun fieldCatalog(): List<JiraFieldDef>

    /** `GET /rest/api/2/issuetype`. Browse-level permission. */
    public suspend fun issueTypes(): List<JiraIssueTypeDef>

    /** `GET /rest/api/2/project` — the projects this token can browse. */
    public suspend fun projects(): List<JiraProjectDef>

    /** `GET /rest/api/2/project/{key}`, or null when the key is unknown or unreadable. */
    public suspend fun project(key: String): JiraProjectDef?

    /**
     * Page through a JQL search, handing each page to [onPage] as it arrives.
     *
     * A callback rather than a returned list, and a `Flow` would do as well: an import of twenty
     * thousand issues with their whole `fields` block is hundreds of megabytes, and materialising
     * it before the first write means holding all of it while the graph holds none of it. The
     * importer writes each page as it lands.
     *
     * Returns how many issues were seen, which is not a `total` the server reported — Cloud's
     * cursor-paginated search does not report one (design doc §1) — but a count of what arrived.
     */
    public suspend fun searchIssues(
        jql: String,
        maxIssues: Int,
        onPage: suspend (List<JiraIssueDto>) -> Unit,
    ): Int
}

/** Fixed path segments. Callers pass a segment, never a full path (design doc §2, point 4). */
public object JiraApiConstants {
    public const val API_BASE: String = "/rest/api/2/"

    /** Cloud's cursor-paginated replacement for [SEARCH_CLASSIC]. */
    public const val SEARCH_JQL: String = "search/jql"

    /** Data Center's offset-paginated search. Cloud is removing it. */
    public const val SEARCH_CLASSIC: String = "search"

    public const val FIELD: String = "field"
    public const val ISSUE_TYPE: String = "issuetype"
    public const val PROJECT: String = "project"

    /** Ask for every field. The flattener decides what is worth keeping, not the request. */
    public const val ALL_FIELDS: String = "*all"
}

@Serializable
public data class JiraFieldDef(
    public val id: String,
    public val name: String = "",
    public val custom: Boolean = false,
    public val navigable: Boolean = true,
    public val schema: JiraFieldSchema? = null,
)

@Serializable
public data class JiraFieldSchema(
    public val type: String = "",
    public val custom: String? = null,
    public val items: String? = null,
)

@Serializable
public data class JiraIssueTypeDef(
    public val id: String,
    public val name: String = "",
    public val description: String = "",
    public val subtask: Boolean = false,
)

@Serializable
public data class JiraProjectDef(
    public val id: String = "",
    public val key: String,
    public val name: String = "",
    public val projectTypeKey: String = "",
)

/**
 * One issue.
 *
 * `fields` stays a [JsonObject] rather than becoming a data class, because two projects can define
 * the same custom field as a string and as a user-picker object, so no single typed shape is
 * correct for both (design doc §3). Everything structural the importer needs — the type, the
 * project, the links — is read out of it by name through [JiraFieldId].
 */
@Serializable
public data class JiraIssueDto(
    public val id: String = "",
    public val key: String,
    public val self: String = "",
    public val fields: JsonObject = JsonObject(emptyMap()),
)

/**
 * Both search response shapes in one type, because they differ only in how they say "there is
 * more".
 *
 * Cloud returns [nextPageToken] and [isLast] and no total; Data Center returns [startAt],
 * [maxResults] and [total]. [JiraHttpClient] normalises the two into one loop.
 */
@Serializable
public data class JiraSearchResponse(
    public val issues: List<JiraIssueDto> = emptyList(),
    @SerialName("nextPageToken") public val nextPageToken: String? = null,
    @SerialName("isLast") public val isLast: Boolean? = null,
    public val startAt: Int? = null,
    public val maxResults: Int? = null,
    public val total: Int? = null,
)

/** What went wrong talking to JIRA, in terms a route can turn into a problem detail. */
public sealed interface JiraFailure {
    /** No host or no token — the JIRA section of the configuration was never filled in. */
    public data object NotConfigured : JiraFailure

    /** JIRA answered 401 or 403: the token is wrong, expired, or lacks Browse Projects. */
    public data class Unauthorised(val status: Int) : JiraFailure

    /** JIRA answered, and said no. [detail] is JIRA's own message where it gave one. */
    public data class Rejected(val status: Int, val detail: String) : JiraFailure

    /** JIRA could not be reached at all, or stopped answering mid-run. */
    public data class Unreachable(val detail: String) : JiraFailure
}

/** Raised inside the client and translated at the route boundary. Never reaches the wire. */
public class JiraException(public val failure: JiraFailure, message: String) : RuntimeException(message)

package com.sec.source.jira

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The JIRA wire types — and there are deliberately very few of them.
 *
 * **Nothing below deserializes an issue's fields into a typed class** (spec §1, R8). Two projects
 * can define the same custom field id as a string in one and a user object in the other, the
 * reference instance defines 1 171 fields of which 1 129 are custom, and the set changes without
 * notice. A new custom field appearing in JIRA must never break an import, which is only true if
 * the importer never claims to know the shape.
 *
 * So the envelope is typed — it is stable and documented — and `fields` is a [JsonObject] carried
 * to the mapper as-is. Everything the mapper decides, it decides from the field *catalogue* plus
 * the observed JSON shape, never from a compile-time type.
 */

/**
 * The one `Json` the JIRA side parses with.
 *
 * `ignoreUnknownKeys`: an instance with a plugin we have never heard of adds keys to responses we
 * do type, and none of them are our business. `explicitNulls = false` so a DTO's absent field and
 * its null field arrive the same way — which is exactly the equivalence spec §3.4 insists on for
 * issue fields, and it would be strange for the envelope to disagree with the payload about it.
 */
public val jiraJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
}

/**
 * `GET /myself`.
 *
 * Called first in every run for two reasons, and the second is the one that is easy to miss: it
 * proves the token before anything is written, **and** it is where the JQL's `created <=` bound
 * gets its time zone. Taking that from the JVM default instead would silently shift the snapshot
 * boundary by hours whenever the server and the service sit in different zones (spec §8).
 */
@Serializable
public data class JiraMyself(
    public val name: String = "",
    public val key: String = "",
    public val displayName: String = "",
    public val emailAddress: String = "",
    public val timeZone: String = "",
    public val active: Boolean = true,
)

/** `GET /project` — the picker's list. Never persisted; a project deleted in JIRA just stops appearing. */
@Serializable
public data class JiraProjectSummary(
    public val id: String = "",
    public val key: String = "",
    public val name: String = "",
    public val self: String = "",
    public val projectTypeKey: String = "",
)

/**
 * `GET /issuetype`.
 *
 * `avatarId` is absent on some types — `Epic` uses a static SVG icon rather than an avatar — so it
 * is nullable rather than defaulted: absent and zero are different facts.
 */
@Serializable
public data class JiraIssueTypeDefinition(
    public val id: String = "",
    public val name: String = "",
    public val self: String = "",
    public val description: String = "",
    public val iconUrl: String = "",
    public val subtask: Boolean = false,
    public val avatarId: Long? = null,
)

/**
 * `GET /field` — the field catalogue, and the only reliable source of field metadata.
 *
 * Two properties of this list drive design decisions elsewhere and are worth stating here:
 *
 *  - **`name` is not unique.** In the committed export, 16 display names cover 38 different fields
 *    — two `Work Package`s, two `DOORS-ID`s, three `Classification`s. (Spec §5 says 15 over 33; it
 *    was counted from an earlier export. `JiraSampleExportTest` asserts the property, not the
 *    tally, for exactly that reason.) Never key anything on the name; the column picker shows the
 *    id beside an ambiguous one (spec §13.3).
 *  - **`schema` is absent on two of them**, `issuekey` and `thumbnail`, which are navigable
 *    pseudo-fields rather than data. They are excluded from the picker entirely.
 */
@Serializable
public data class JiraFieldDefinition(
    public val id: String = "",
    public val name: String = "",
    public val custom: Boolean = false,
    public val orderable: Boolean = false,
    public val navigable: Boolean = false,
    public val searchable: Boolean = false,
    public val clauseNames: List<String> = emptyList(),
    public val schema: JiraFieldSchema? = null,
)

/**
 * The declared type of a field, flattened onto the node rather than stored as JSON text.
 *
 * A structural flattening with verbatim values, which does not violate R1 — no value is altered —
 * and it is done because the column picker filters and sorts on the type constantly, which a JSON
 * blob would make a parse per row (spec §9.2).
 */
@Serializable
public data class JiraFieldSchema(
    public val type: String = "",
    public val items: String? = null,
    public val custom: String? = null,
    public val customId: Long? = null,
    public val system: String? = null,
)

/**
 * One page of `GET /search`, Data Center's offset-paginated shape.
 *
 * **[maxResults] is the response's, and it is the stride.** The server silently clamps whatever
 * was requested to `jira.search.views.default.max`, so paging by the requested size skips whole
 * pages without any error — the single most common way to lose issues (spec §3.3). The loop reads
 * it from here, every page, never from configuration.
 *
 * [total] is an estimate under concurrent modification, so an empty [issues] wins over it.
 */
@Serializable
public data class JiraSearchPage(
    public val startAt: Int = 0,
    public val maxResults: Int = 0,
    public val total: Int = 0,
    public val issues: List<JiraIssueEnvelope> = emptyList(),
    public val warningMessages: List<String>? = null,
)

/**
 * One issue, as far as this code is willing to commit to its shape.
 *
 * [fields] stays a [JsonObject] — see the file note. `expand` and `renderedFields` are read off the
 * wire and discarded: the first describes what *could* have been expanded, and the second is null
 * on every issue of the reference export because it only populates with `expand=renderedFields`,
 * which this design does not ask for (spec §9.3).
 */
@Serializable
public data class JiraIssueEnvelope(
    public val id: String = "",
    public val key: String = "",
    public val self: String = "",
    public val fields: JsonObject = JsonObject(emptyMap()),
)

/** JIRA's own error envelope, returned with a 400 and worth more than any paraphrase of it. */
@Serializable
public data class JiraErrorResponse(
    public val errorMessages: List<String> = emptyList(),
    public val errors: Map<String, JsonElement> = emptyMap(),
)

/**
 * The declared type, or `""` for the two pseudo-fields that have no schema.
 *
 * Reached through this rather than `schema?.type.orEmpty()` at each call site so that "no schema"
 * and "schema with an empty type" stay one answer, in one place.
 */
public val JiraFieldDefinition.schemaType: String
    get() = schema?.type.orEmpty()

/**
 * Whether the column picker may offer this field.
 *
 * False for the two fields with no `schema` (`issuekey` duplicates the fixed Key column,
 * `thumbnail` is not data) and for `any`-typed fields, whose shape is genuinely unconstrained.
 *
 * **Derived, so it is computed here and never stored** (R2, spec §9.2): recomputing costs nothing
 * and is always right, while a stored copy would go stale the first time this rule changes.
 */
public val JiraFieldDefinition.isDisplayable: Boolean
    get() = schema != null && schemaType != JiraSchemaType.ANY

/** The `schema.type` values this code branches on. Others are handled generically, by shape. */
public object JiraSchemaType {
    public const val ANY: String = "any"
    public const val ARRAY: String = "array"
    public const val STRING: String = "string"
    public const val NUMBER: String = "number"
    public const val DATE: String = "date"
    public const val DATETIME: String = "datetime"
    public const val OPTION: String = "option"
    public const val OPTION_WITH_CHILD: String = "option-with-child"
    public const val USER: String = "user"
    public const val PROGRESS: String = "progress"
}

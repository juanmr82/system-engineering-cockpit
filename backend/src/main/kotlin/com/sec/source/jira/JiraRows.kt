package com.sec.source.jira

import com.sec.domain.Prop
import com.sec.domain.PropValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The second pure half of the importer: a JIRA wire object in, one `UNWIND` row out.
 *
 * Nothing here touches a driver or a socket, so every Tier-1 derivation the JIRA source makes is
 * unit-testable against a JSON fixture — which matters more here than it did for DOORS, because a
 * JIRA instance cannot be checked into `tests/fixtures/` and asked the same question twice.
 */
public object JiraRows {

    /**
     * One issue as the row [JiraCypher.UPSERT_ISSUES][com.sec.graph.cypher.JiraCypher.UPSERT_ISSUES]
     * expects: an `__id` to MERGE on, and a property map to `+=`.
     *
     * The Tier-1 four — `__id`, `__name`, `__version`, `__sortKey` — are written *after* the
     * flattened fields, so a JIRA field that happened to be called `__name` could not overwrite
     * one. It cannot happen today, because a JIRA field id is `summary` or `customfield_10032`
     * and never carries the prefix; it costs one map ordering to make sure it stays impossible.
     */
    public fun issueRow(issue: JiraIssueDto, storeRawFields: Boolean): Map<String, Any?> {
        val projectKey = projectKeyOf(issue)
        val props = LinkedHashMap<String, Any?>(JiraFields.flatten(issue.fields))

        if (storeRawFields) {
            props[JiraProp.RAW_FIELDS] = issue.fields.toString()
        }

        props[JiraFieldId.KEY] = issue.key
        props[JiraFieldId.ID] = issue.id
        props[JiraFieldId.SELF] = issue.self
        props[JiraProp.PROJECT_KEY] = projectKey
        props[Prop.NAME] = JiraFields.deriveName(issue.fields, issue.key)
        props[Prop.VERSION] = PropValue.CURRENT_VERSION
        props[Prop.SORT_KEY] = JiraFields.deriveSortKey(issue.key)

        return mapOf("id" to JiraId.issue(issue.key), "props" to props)
    }

    /**
     * The project an issue belongs to.
     *
     * `fields.project.key` is authoritative and the key prefix is the fallback, not the other way
     * round: an issue **moved** between projects keeps its original key, so `SEG-42` can genuinely
     * live in project `AVI`, and trusting the prefix would file it under a project that has not
     * contained it for years.
     */
    public fun projectKeyOf(issue: JiraIssueDto): String {
        val fromField = (issue.fields[JiraFieldId.PROJECT] as? JsonObject)
            ?.get(JiraProjectAttr.KEY)
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
        return fromField?.takeIf { it.isNotBlank() } ?: JiraFields.projectKeyOf(issue.key)
    }

    public fun projectRow(project: JiraProjectDef): Map<String, Any?> = mapOf(
        "id" to JiraId.project(project.key),
        "props" to mapOf(
            JiraProjectAttr.KEY to project.key,
            JiraProjectAttr.ID to project.id,
            JiraProjectAttr.NAME to project.name,
            JiraProjectAttr.PROJECT_TYPE_KEY to project.projectTypeKey,
            Prop.NAME to project.name.ifBlank { project.key },
            Prop.VERSION to PropValue.CURRENT_VERSION,
            Prop.SORT_KEY to project.key,
        ),
    )

    public fun issueTypeRow(type: JiraIssueTypeDef): Map<String, Any?> = mapOf(
        "id" to JiraId.issueType(type.id),
        "props" to mapOf(
            JiraProjectAttr.ID to type.id,
            JiraProjectAttr.NAME to type.name,
            JiraProjectAttr.DESCRIPTION to type.description,
            JiraProjectAttr.SUBTASK to type.subtask,
            Prop.NAME to type.name.ifBlank { type.id },
            Prop.VERSION to PropValue.CURRENT_VERSION,
            Prop.SORT_KEY to type.name,
        ),
    )

    public fun fieldRow(field: JiraFieldDef): Map<String, Any?> = mapOf(
        "id" to JiraId.field(field.id),
        "props" to mapOf(
            JiraProjectAttr.ID to field.id,
            JiraProjectAttr.NAME to field.name.ifBlank { field.id },
            JiraProjectAttr.SCHEMA_TYPE to (field.schema?.type ?: ""),
            JiraProjectAttr.SCHEMA_CUSTOM to (field.schema?.custom ?: ""),
            JiraProjectAttr.SCHEMA_ITEMS to (field.schema?.items ?: ""),
            JiraProjectAttr.NAVIGABLE to field.navigable,
            Prop.NAME to field.name.ifBlank { field.id },
            Prop.VERSION to PropValue.CURRENT_VERSION,
            Prop.SORT_KEY to field.name.ifBlank { field.id },
        ),
    )

    /**
     * The issue type as the issue itself states it, for types `GET /issuetype` did not list.
     *
     * Some instances scope an issue type to one project and leave it out of the global catalogue.
     * An issue of such a type would then find no `:JiraIssueType` node to link to and lose its
     * type edge silently — so the copy JIRA embeds in every issue is used to create it. Only `id`
     * and `name` are there, which is all the link and the column need.
     */
    public fun embeddedIssueType(issue: JiraIssueDto): JiraIssueTypeDef? {
        val type = issue.fields[JiraFieldId.ISSUE_TYPE] as? JsonObject ?: return null
        val id = (type[JiraProjectAttr.ID] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return null
        return JiraIssueTypeDef(
            id = id,
            name = (type[JiraProjectAttr.NAME] as? JsonPrimitive)?.content.orEmpty(),
            description = "",
            subtask = (type[JiraProjectAttr.SUBTASK] as? JsonPrimitive)?.content?.toBoolean() ?: false,
        )
    }

    /** The issue type id an issue claims, or null when the response carried no `issuetype`. */
    public fun issueTypeIdOf(issue: JiraIssueDto): String? =
        (issue.fields[JiraFieldId.ISSUE_TYPE] as? JsonObject)
            ?.get(JiraProjectAttr.ID)
            ?.let { it as? JsonPrimitive }
            ?.content
            ?.takeIf { it.isNotBlank() }

    /**
     * The key of the issue that contains this one, or null for a top-level issue.
     *
     * `fields.parent` covers both the sub-task case and — on JIRA instances where it is
     * populated — the epic-child case, so one field answers both and no special case is needed
     * for the second.
     */
    public fun parentKeyOf(issue: JiraIssueDto): String? =
        (issue.fields[JiraFieldId.PARENT] as? JsonObject)
            ?.get(JiraFieldId.KEY)
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }

    /**
     * Every link this issue asserts, as rows for
     * [JiraCypher.LINK_ISSUES][com.sec.graph.cypher.JiraCypher.LINK_ISSUES].
     *
     * **Only the outward half is emitted.** JIRA states each link on both of its issues — once
     * with `outwardIssue` and once with `inwardIssue` — so taking both would draw every link
     * twice, in opposite directions, and make every relationship look bidirectional. Reading the
     * outward side only means the edge runs the way JIRA's own `outward` phrase reads, and the
     * other end still finds it because a graph edge is traversable from both.
     *
     * A link whose other end was never imported is *not* dropped: the stub payload travels with
     * the row so [JiraCypher.LINK_ISSUES] can create a placeholder for it.
     */
    public fun linkRows(issue: JiraIssueDto): List<Map<String, Any?>> {
        val links = issue.fields[JiraFieldId.ISSUE_LINKS] ?: return emptyList()
        val array = runCatching { links.jsonArray }.getOrNull() ?: return emptyList()

        return array.mapNotNull { element ->
            val link = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val target = (link["outwardIssue"] as? JsonObject) ?: return@mapNotNull null
            val targetKey = (target[JiraFieldId.KEY] as? JsonPrimitive)?.content
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val type = link["type"] as? JsonObject
            val typeId = (type?.get(JiraProjectAttr.ID) as? JsonPrimitive)?.content.orEmpty()

            mapOf(
                "fromId" to JiraId.issue(issue.key),
                "toId" to JiraId.issue(targetKey),
                "linkTypeId" to typeId,
                "props" to mapOf(
                    JiraLinkProp.TYPE_ID to typeId,
                    JiraLinkProp.TYPE_NAME to (type?.get(JiraProjectAttr.NAME) as? JsonPrimitive)
                        ?.content.orEmpty(),
                    JiraLinkProp.INWARD to (type?.get(JiraLinkProp.INWARD) as? JsonPrimitive)
                        ?.content.orEmpty(),
                    JiraLinkProp.OUTWARD to (type?.get(JiraLinkProp.OUTWARD) as? JsonPrimitive)
                        ?.content.orEmpty(),
                ),
                "stub" to stubProps(targetKey),
            )
        }
    }

    /**
     * What a placeholder for an unimported issue knows about itself: its key, and what can be
     * derived from it. No fields, because nothing has read them.
     *
     * It carries `__sortKey` and `__projectKey` like a real issue so that the day its project
     * enters the scope, the node the importer MERGEs is this one — promoted in place, rather than
     * a second node for the same issue.
     */
    public fun stubProps(issueKey: String): Map<String, Any?> = mapOf(
        JiraFieldId.KEY to issueKey,
        JiraProp.PROJECT_KEY to JiraFields.projectKeyOf(issueKey),
        Prop.NAME to issueKey,
        Prop.VERSION to PropValue.CURRENT_VERSION,
        Prop.SORT_KEY to JiraFields.deriveSortKey(issueKey),
    )
}

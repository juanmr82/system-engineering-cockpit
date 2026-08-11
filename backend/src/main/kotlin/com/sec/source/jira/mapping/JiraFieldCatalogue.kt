package com.sec.source.jira.mapping

import com.sec.source.jira.JiraFieldDefinition
import com.sec.source.jira.schemaType

/**
 * What phase 2 learned about one field, kept in memory for phase 3 (spec §12).
 *
 * In memory and not re-queried: the alternative is a lookup per field per issue, which at
 * 1 171 fields × 784 issues is roughly 900 000 round trips to answer a question whose answer did
 * not change during the run.
 */
public data class JiraFieldMeta(
    public val id: String,
    public val name: String,
    public val custom: Boolean,
    public val schemaType: String,
    public val schemaItems: String?,
)

/**
 * The field catalogue as the mapper sees it.
 *
 * ## It is advisory, and that is the important part
 *
 * Nothing in the storage rules consults this. A value's shape decides where it lands
 * ([ValueClassifier]), because the declared type list is open — the reference instance uses
 * `securitylevel`, `comments-page` and `sd-approvals`, none of which the spec's table names — and a
 * `when` over known types would drop a plugin's data silently.
 *
 * So what is it for? Two things a shape cannot tell you: the field's **display name**, and whether
 * the catalogue has **heard of the field at all**. The second is a real case — a field can be added
 * to a project between the `/field` call and the `/search` call — and §16.1 requires that it not
 * crash. It does not: [meta] returns null, the value is still stored under its own id, and the run
 * reports it once as a warning.
 */
public class JiraFieldCatalogue(definitions: List<JiraFieldDefinition>) {

    private val byId: Map<String, JiraFieldMeta> = definitions.associate { definition ->
        definition.id to JiraFieldMeta(
            id = definition.id,
            name = definition.name,
            custom = definition.custom,
            schemaType = definition.schemaType,
            schemaItems = definition.schema?.items,
        )
    }

    public val size: Int get() = byId.size

    /** Null for a field the catalogue has never heard of, which is a warning and never a failure. */
    public fun meta(fieldId: String): JiraFieldMeta? = byId[fieldId]

    public operator fun contains(fieldId: String): Boolean = fieldId in byId

    public companion object {
        public val EMPTY: JiraFieldCatalogue = JiraFieldCatalogue(emptyList())
    }
}

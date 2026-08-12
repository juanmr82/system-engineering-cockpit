package com.sec.source.jira.mapping

import com.sec.domain.ItemVersion
import com.sec.domain.Prop
import com.sec.source.jira.JiraFieldId
import com.sec.source.jira.JiraIssueEnvelope
import com.sec.source.jira.JiraLabel
import com.sec.source.jira.JiraLinkProp
import com.sec.source.jira.JiraProp
import com.sec.source.jira.JiraRel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One shared node an issue points at — a project, a status, a user (spec §6.4).
 *
 * Shared: one node per distinct entity, `MERGE`d on `__id`, referenced by many issues. That is
 * where the graph earns its keep — "every open issue assigned to X across all projects" is one
 * traversal rather than a scan of 784 property maps.
 */
public data class PromotedEntity(
    public val label: String,
    public val id: String,
    public val relationship: String,
    public val props: Map<String, Any?>,
)

/**
 * The other end of a link, or a parent — as much of an issue as JIRA embeds in a reference.
 *
 * Enough to build a placeholder from and nothing more: JIRA embeds `id`, `key`, `self` and a small
 * `fields` object in every reference, so an issue outside the configured projects can be stood up
 * as a stub without a second API call (spec §9.4). [self] is its `__id`, exactly the value the node
 * will carry when the real issue is imported — which is what lets phase 3 fill the stub in rather
 * than create a duplicate beside it.
 */
public data class IssueRef(
    public val id: String,
    public val key: String,
    public val self: String,
    public val summary: String,
)

/**
 * One JIRA issue link, normalised to the direction JIRA itself states it in.
 *
 * Both ends of a link report it, with the same [linkId]. Storing it as JIRA's *outward* direction
 * is what collapses `A outward→ B` and `B inward→ A` into a single edge instead of two edges that
 * disagree about which way round they are.
 *
 * [toId] may be an issue this run never saw — a link can point outside the configured projects —
 * which is what phase 4's placeholder is for. The mapper records the fact and takes no view.
 */
public data class IssueLink(
    public val linkId: String,
    public val fromId: String,
    public val toId: String,
    public val typeId: String,
    public val typeName: String,
    public val inward: String,
    public val outward: String,
    /** The end that is *not* this issue — what a placeholder is built from when it is unknown. */
    public val other: IssueRef,
)

/**
 * Everything one issue contributes to the graph, as values. Nothing here has touched a database.
 */
public data class MappedIssue(
    public val id: String,
    public val key: String,
    public val props: Map<String, Any?>,
    /**
     * The field ids this issue currently has a value for.
     *
     * Phase 3's property-removal statement needs it: `SET i += $props` only adds and overwrites, so
     * a field that became `null` in JIRA would keep its stale value forever without a list of what
     * *should* be there (spec §12 phase 3). It carries the envelope's own keys too — `key`, `id`,
     * `self` are properties of the node and must not be swept off it.
     */
    public val presentKeys: List<String>,
    /** Display scalars for complex values, destined for the `:__JiraProjection` companion. */
    public val projection: Map<String, Any?>,
    public val entities: List<PromotedEntity>,
    public val links: List<IssueLink>,
    /** `fields.parent`, when present — the target of a `subTaskOf` edge (spec §9.5). */
    public val parent: IssueRef? = null,
    public val warnings: List<String> = emptyList(),
)

/**
 * Turns one `/search` issue into the rows that phase 3 writes (spec §7, §9.3).
 *
 * ## Pure, and that is the design
 *
 * No Neo4j, no HTTP, no clock, no configuration. This is where the complexity of the whole importer
 * lives — ~1 040 keys in, ~145 properties out, thirteen of them additionally becoming edges — and
 * being a function of its input is what makes that complexity testable against the committed export
 * rather than against a running instance (spec §14.2).
 *
 * ## What it does not do
 *
 * Decide anything about *resolution*. A link whose target is not in this run, a parent that has not
 * been imported, an entity seen on fifty issues in one page — all of those are facts this records
 * and phases 3 and 4 act on. Deduplicating shared entities across a page, for instance, is the
 * writer's job: doing it here would mean this function needed memory of the issues before it, and
 * a pure function with memory is neither.
 */
public class IssueMapper(private val catalogue: JiraFieldCatalogue = JiraFieldCatalogue.EMPTY) {

    public fun map(envelope: JiraIssueEnvelope): MappedIssue {
        val fields = envelope.fields
        val props = LinkedHashMap<String, Any?>()
        val projection = LinkedHashMap<String, Any?>()
        val presentKeys = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // The envelope's own three. They are node properties, not fields, and `presentKeys` has to
        // carry them or phase 3's stale-key sweep would remove the issue's own key on every run.
        // Verified against the export: no field id is called `key`, `id` or `self`.
        props[JiraProp.KEY] = envelope.key
        props[JiraProp.ID] = envelope.id
        props[JiraProp.SELF] = envelope.self
        presentKeys += listOf(JiraProp.KEY, JiraProp.ID, JiraProp.SELF)

        fields.forEach { (fieldId, value) ->
            // A field the catalogue has never heard of — added between the /field call and the
            // /search call — is reported and still imported (spec §16.1). The run collapses these
            // to one line per field id rather than one per issue.
            //
            // Guarded on a non-empty catalogue: with none supplied, every field is unknown, and a
            // caller that passed no catalogue was not asking to be told so 1 041 times.
            if (catalogue.size > 0 && fieldId !in catalogue) warnings += fieldId

            when (val stored = ValueClassifier.classify(value)) {
                is StoredValue.Skip -> Unit

                is StoredValue.Scalar -> {
                    props[fieldId] = stored.value
                    presentKeys += fieldId
                }

                is StoredValue.ListOfScalars -> {
                    props[fieldId] = stored.values
                    presentKeys += fieldId
                }

                is StoredValue.JsonText -> {
                    props[fieldId] = stored.json
                    presentKeys += fieldId
                    // Only complex values get a projection: a scalar on the issue node is already
                    // sortable, and a second copy of it would be a derived value with no purpose
                    // and one more thing to go stale.
                    DisplayProjector.project(value)?.let { projection[fieldId] = it }
                }
            }
        }

        // The three SEItem properties, written last so nothing above can shadow them.
        props[Prop.ID] = envelope.self
        props[Prop.NAME] = displayName(envelope)
        props[Prop.VERSION] = fields.stringOrNull(JiraFieldId.UPDATED) ?: UNKNOWN_VERSION

        // The one denormalisation this design allows, and it is a copy of imported data rather
        // than a derivation — so a re-import reproduces it exactly, which is the Tier-1 test. It
        // exists because phase 5's sweep must scope by project without a traversal per issue.
        fields.objectOrNull(JiraFieldId.PROJECT)?.stringOrNull(JiraProp.KEY)?.let {
            props[JiraProp.PROJECT_KEY] = it
            presentKeys += JiraProp.PROJECT_KEY
        }

        return MappedIssue(
            id = envelope.self,
            key = envelope.key,
            props = props,
            presentKeys = presentKeys,
            projection = projection,
            entities = promotedEntities(fields),
            links = issueLinks(envelope),
            parent = fields.objectOrNull(JiraFieldId.PARENT)?.let(::issueRef),
            warnings = warnings,
        )
    }

    /**
     * `"<key>: <summary>"`, capped at 200 characters, falling back to the key alone.
     *
     * The key is first so a truncated name still identifies the issue. A summary can be missing —
     * a field-level permission can hide it — and `"SCRUM-7"` is a worse name than
     * `"SCRUM-7: Something"` but an entirely useful one, whereas an empty `__name` is not.
     */
    private fun displayName(envelope: JiraIssueEnvelope): String {
        val summary = envelope.fields.stringOrNull(JiraFieldId.SUMMARY)?.takeIf { it.isNotBlank() }
            ?: return envelope.key
        return "${envelope.key}: $summary".take(MAX_NAME)
    }

    /**
     * The thirteen fields that become edges as well as verbatim properties (spec §7.3).
     *
     * The duplication is deliberate: R1 keeps the raw copy so nothing is lost, and the edge gives
     * traversal. **Custom fields are never promoted**, whatever their declared type — there are
     * 1 129 of them and the set changes without notice, so promoting by type would make the shape
     * of the graph a function of somebody else's admin screen.
     *
     * `labels` is deliberately absent: it is already an `array<string>` living happily as a list
     * property, and a node per label would buy no traversal anybody wants.
     */
    private fun promotedEntities(fields: JsonObject): List<PromotedEntity> = buildList {
        entity(fields, JiraFieldId.PROJECT, JiraLabel.PROJECT, JiraRel.IN_PROJECT)
        entity(fields, JiraFieldId.ISSUE_TYPE, JiraLabel.ISSUE_TYPE, JiraRel.HAS_ISSUE_TYPE)
        entity(fields, JiraFieldId.STATUS, JiraLabel.STATUS, JiraRel.HAS_STATUS)
        entity(fields, JiraFieldId.PRIORITY, JiraLabel.PRIORITY, JiraRel.HAS_PRIORITY)
        // Absent on an unresolved issue, which is the ordinary state rather than a gap.
        entity(fields, JiraFieldId.RESOLUTION, JiraLabel.RESOLUTION, JiraRel.HAS_RESOLUTION)

        entity(fields, JiraFieldId.ASSIGNEE, JiraLabel.USER, JiraRel.ASSIGNED_TO)
        entity(fields, JiraFieldId.REPORTER, JiraLabel.USER, JiraRel.REPORTED_BY)
        entity(fields, JiraFieldId.CREATOR, JiraLabel.USER, JiraRel.CREATED_BY)

        entities(fields, JiraFieldId.COMPONENTS, JiraLabel.COMPONENT, JiraRel.HAS_COMPONENT)
        entities(fields, JiraFieldId.VERSIONS, JiraLabel.VERSION, JiraRel.AFFECTS_VERSION)
        entities(fields, JiraFieldId.FIX_VERSIONS, JiraLabel.VERSION, JiraRel.FIX_VERSION)
    }

    private fun MutableList<PromotedEntity>.entity(
        fields: JsonObject,
        fieldId: String,
        label: String,
        relationship: String,
    ) {
        fields.objectOrNull(fieldId)?.let { entityOf(it, label, relationship)?.let(::add) }
    }

    private fun MutableList<PromotedEntity>.entities(
        fields: JsonObject,
        fieldId: String,
        label: String,
        relationship: String,
    ) {
        (fields[fieldId] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { entityOf(it, label, relationship)?.let(::add) }
        }
    }

    /**
     * A shared node from the object JIRA embedded in the issue.
     *
     * Identity is `self`, exactly as it is for the issue and for a DOORS object — an entity with no
     * `self` is not one this can key, and inventing an id for it would create a node that the next
     * run cannot recognise as the same one. Skipped instead, silently: the raw JSON is still on the
     * issue, so nothing is lost, only untraversable.
     */
    private fun entityOf(value: JsonObject, label: String, relationship: String): PromotedEntity? {
        val self = value.stringOrNull(JiraProp.SELF)?.takeIf { it.isNotBlank() } ?: return null

        // A user is named by displayName; everything else by name. The user's `name` is a login,
        // and showing logins where a person's name belongs is the sort of thing R5 exists to stop.
        val displayName = if (label == JiraLabel.USER) {
            value.stringOrNull(DISPLAY_NAME) ?: value.stringOrNull(JiraProp.NAME)
        } else {
            value.stringOrNull(JiraProp.NAME)
        }

        return PromotedEntity(
            label = label,
            id = self,
            relationship = relationship,
            props = buildMap {
                put(Prop.ID, self)
                put(Prop.NAME, displayName.orEmpty())
                put(Prop.VERSION, ItemVersion.CURRENT)
                put(JiraProp.SELF, self)
                value.stringOrNull(JiraProp.ID)?.let { put(JiraProp.ID, it) }
                value.stringOrNull(JiraProp.KEY)?.let { put(JiraProp.KEY, it) }
                value.stringOrNull(JiraProp.NAME)?.let { put(JiraProp.NAME, it) }
                value.stringOrNull(DISPLAY_NAME)?.let { put(DISPLAY_NAME, it) }
            },
        )
    }

    /**
     * `issuelinks`, one [IssueLink] per entry, normalised to JIRA's outward direction (spec §9.4).
     *
     * An entry names exactly one of `outwardIssue` or `inwardIssue` — the *other* end. Which one it
     * is decides the edge's direction, and it is the only thing that can: `IsRelated` has identical
     * inward and outward phrases, so the words cannot be read as a direction.
     */
    private fun issueLinks(envelope: JiraIssueEnvelope): List<IssueLink> {
        val entries = envelope.fields[JiraFieldId.ISSUE_LINKS] as? JsonArray ?: return emptyList()

        return entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val linkId = entry.stringOrNull(JiraProp.ID) ?: return@mapNotNull null
            val type = entry.objectOrNull(TYPE)

            val outward = entry.objectOrNull(OUTWARD_ISSUE)
            val inward = entry.objectOrNull(INWARD_ISSUE)
            val other = outward ?: inward ?: return@mapNotNull null
            val otherSelf = other.stringOrNull(JiraProp.SELF) ?: return@mapNotNull null

            IssueLink(
                linkId = linkId,
                // The one asymmetry: when JIRA gave us the *inward* issue, this issue is the target.
                fromId = if (outward != null) envelope.self else otherSelf,
                toId = if (outward != null) otherSelf else envelope.self,
                typeId = type?.stringOrNull(JiraProp.ID).orEmpty(),
                typeName = type?.stringOrNull(JiraProp.NAME).orEmpty(),
                inward = type?.stringOrNull(JiraLinkProp.INWARD).orEmpty(),
                outward = type?.stringOrNull(JiraLinkProp.OUTWARD).orEmpty(),
                other = issueRef(other),
            )
        }
    }

    /**
     * An embedded issue reference — the shape JIRA uses for a link's other end and for `parent`.
     *
     * `summary` lives one level down in the reference's own `fields`, and it is the only thing in
     * here that makes a placeholder readable: `<unresolved SCRUM-7>` names the issue, the summary
     * says what it is. Absent values become empty strings rather than nulls, because these go
     * straight into a property map and `SET n += {summary: null}` *removes* the property, which
     * would make "JIRA sent no summary" and "the summary was cleared" the same event.
     */
    private fun issueRef(value: JsonObject): IssueRef = IssueRef(
        id = value.stringOrNull(JiraProp.ID).orEmpty(),
        key = value.stringOrNull(JiraProp.KEY).orEmpty(),
        self = value.stringOrNull(JiraProp.SELF).orEmpty(),
        summary = value.objectOrNull(FIELDS)?.stringOrNull(JiraFieldId.SUMMARY).orEmpty(),
    )

    private companion object {
        const val MAX_NAME = 200
        const val UNKNOWN_VERSION = "unknown"
        const val DISPLAY_NAME = "displayName"
        const val TYPE = "type"
        const val FIELDS = "fields"
        const val OUTWARD_ISSUE = "outwardIssue"
        const val INWARD_ISSUE = "inwardIssue"
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

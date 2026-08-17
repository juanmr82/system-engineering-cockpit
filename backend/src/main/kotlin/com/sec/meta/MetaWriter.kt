package com.sec.meta

import com.sec.domain.SaveCommentsOutcome
import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.domain.SaveSystemLevelsOutcome
import com.sec.domain.SavedComment
import com.sec.domain.SavedSystemLevel
import com.sec.domain.SystemLevel
import com.sec.domain.SystemLevelChange
import com.sec.domain.UuidV7
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ModuleCypher
import com.sec.graph.cypher.ReviewCypher
import com.sec.graph.executeRead
import com.sec.graph.executeWrite
import com.sec.security.AccessSet
import com.sec.security.CurrentUser
import com.sec.source.doors.DoorsProjection
import org.neo4j.driver.Query
import java.time.Instant

// The single guarded write path for Tier-2 data (CLAUDE.md R2). Every write that touches
// :__Meta nodes and their __-prefixed relationships goes through here, and nothing else does —
// enforced in this one place, not per route. Expected failures are a sealed result, not an
// exception (CLAUDE.md §11).
//
// Several feature-shaped endpoints reach this class (module settings, review settings, the batch
// comment save). That is deliberate and is what CLAUDE.md §5 allows: a dialog or a table save is
// one transaction, not N annotation calls. One meta write path, however many endpoints reach it.
//
// Every public method takes an AccessSet, and it is not decoration. Each one already answers
// "does this module exist" through DoorsProjection.moduleExists before it writes, and that read is
// filtered — so a module this caller cannot see is ModuleNotFound here for the same reason it is a
// 404 on the read side. Without it the container would 404 on read and accept a write, which is
// worse than either behaviour on its own. This is a deliberate, bounded pull-forward of phase 5's
// anchor guard (docs/features/access-control.md §15): it closes the *container* case only. Phase 5
// widens it to each individual anchor an edit names.
//
// The same set is threaded into `discoverAttributeNames` and `objectsOfModule`, the two reads that
// decide whether a request's attribute names and item ids are ones the caller was entitled to have
// seen — an unfiltered read there would answer a question about objects the caller cannot see.
public class MetaWriter(
    private val graphDriver: GraphDriver,
    private val doorsProjection: DoorsProjection,
) {
    // docs/features/requirements-modules.md §5: one transaction covers the classification, the
    // mandatory-attribute policies and the review dialog's attribute settings, because each
    // dialog's Save button is one gesture (R7).
    public suspend fun saveModuleSettings(
        moduleId: String,
        systemLevel: SystemLevelChange,
        access: AccessSet,
        addAttributes: List<String> = emptyList(),
        removeAttributes: List<String> = emptyList(),
        attributeSettings: List<AttributeSettingInput>? = null,
        user: String = CurrentUser.PLACEHOLDER,
    ): SaveModuleSettingsOutcome {
        if (!doorsProjection.moduleExists(moduleId, access)) {
            return SaveModuleSettingsOutcome.ModuleNotFound
        }

        if (systemLevel is SystemLevelChange.Set && SystemLevel.fromCode(systemLevel.code) == null) {
            return SaveModuleSettingsOutcome.InvalidSystemLevel(systemLevel.code)
        }

        // A client may not invent an attribute to add; a client may always remove one, even a
        // stale name no longer discovered on this module's objects — un-marking is always safe.
        // The review dialog's absolute list is checked the same way, for the names it turns on.
        val proposed = addAttributes + attributeSettings.orEmpty()
            .filter { it.mandatory || it.visible || it.verification || it.excludedFromOpenPoints }
            .map { it.name }
        if (proposed.isNotEmpty()) {
            val discovered = doorsProjection.discoverAttributeNames(moduleId, access).toSet()
            val unknown = proposed.filterNot { it in discovered }.distinct()
            if (unknown.isNotEmpty()) {
                return SaveModuleSettingsOutcome.UnknownAttributes(unknown)
            }
        }

        val now = Instant.now().toString()
        val queries = buildList {
            systemLevelQuery(moduleId, systemLevel, user, now)?.let(::add)
            if (addAttributes.isNotEmpty()) {
                add(addMandatoryPoliciesQuery(moduleId, addAttributes, user, now))
            }
            if (removeAttributes.isNotEmpty()) {
                add(Query(ModuleCypher.REMOVE_MANDATORY_POLICIES, mapOf("moduleId" to moduleId, "remove" to removeAttributes)))
            }
            attributeSettings?.let { addAll(attributeSettingQueries(moduleId, it, user, now)) }
        }

        graphDriver.executeWrite(queries, access)
        return SaveModuleSettingsOutcome.Saved
    }

    /**
     * The Modules table's save icon: every changed system level, one transaction
     * (`docs/features/requirements-modules.md`).
     *
     * Spans modules rather than the objects of one module, which is the only structural difference
     * from `saveComments`. Everything else matches deliberately: one gesture, one request, one
     * transaction, and the stored values echoed back so the table clears its dirty marks without a
     * reload.
     *
     * A client may only classify a module the list actually returned. Without that check an
     * arbitrary `__id` in the body would attach a classification to any node in the graph — the
     * same hole `saveComments` closes for items.
     */
    public suspend fun saveSystemLevels(
        edits: List<SystemLevelEditInput>,
        access: AccessSet,
        user: String = CurrentUser.PLACEHOLDER,
    ): SaveSystemLevelsOutcome {
        val invalid = edits.mapNotNull { it.code }.firstOrNull { SystemLevel.fromCode(it) == null }
        if (invalid != null) {
            return SaveSystemLevelsOutcome.InvalidSystemLevel(invalid)
        }

        val unknown = edits.map { it.moduleId }.distinct()
            .filterNot { doorsProjection.moduleExists(it, access) }
        if (unknown.isNotEmpty()) {
            return SaveSystemLevelsOutcome.UnknownModules(unknown)
        }

        val now = Instant.now().toString()
        val queries = edits.map { edit ->
            val change = edit.code?.let(SystemLevelChange::Set) ?: SystemLevelChange.Clear
            // Reuses the *same* per-module query the settings dialog writes, so a level set from
            // the table and one set from the dialog are one stored shape, not two.
            systemLevelQuery(edit.moduleId, change, user, now)
                ?: error("A system-level edit always changes something")
        }

        graphDriver.executeWrite(queries, access)
        return SaveSystemLevelsOutcome.Saved(
            edits.map { SavedSystemLevel(moduleId = it.moduleId, code = it.code) },
        )
    }

    /**
     * The review table's save icon: every dirty comment for one module, one transaction
     * (docs/REQ_REVIEW.md §5.2). Partial success is impossible — either all of them are written
     * or none is and the reviewer's edits stay on screen.
     */
    public suspend fun saveComments(
        moduleId: String,
        edits: List<CommentEditInput>,
        access: AccessSet,
        user: String = CurrentUser.PLACEHOLDER,
    ): SaveCommentsOutcome {
        if (!doorsProjection.moduleExists(moduleId, access)) {
            return SaveCommentsOutcome.ModuleNotFound
        }

        // A client may only comment on objects of the module it loaded. Without this an arbitrary
        // __id in the body would attach a note to any node in the graph.
        val itemIds = edits.map { it.itemId }
        val known = itemIds.takeIf { it.isNotEmpty() }
            ?.let { objectsOfModule(moduleId, it, access) } ?: emptySet()
        val unknown = itemIds.filterNot { it in known }.distinct()
        if (unknown.isNotEmpty()) {
            return SaveCommentsOutcome.UnknownItems(unknown)
        }

        val (cleared, written) = edits.partition { it.text.isBlank() }
        val now = Instant.now().toString()

        val queries = buildList {
            if (written.isNotEmpty()) {
                val rows = written.map {
                    mapOf("itemId" to it.itemId, "text" to it.text, "metaId" to UuidV7.generate())
                }
                add(Query(ReviewCypher.UPSERT_COMMENTS, mapOf("comments" to rows, "user" to user, "now" to now)))
            }
            if (cleared.isNotEmpty()) {
                add(Query(ReviewCypher.DELETE_COMMENTS, mapOf("itemIds" to cleared.map { it.itemId })))
            }
        }

        if (queries.isNotEmpty()) {
            graphDriver.executeWrite(queries, access)
        }

        // Read back what was stored rather than echoing what was asked for: the server decides,
        // and this is what lets the table clear its dirty marks without reloading (§8).
        val stored = readComments(written.map { it.itemId }, access)
        return SaveCommentsOutcome.Saved(
            comments = edits.map { edit ->
                stored[edit.itemId] ?: SavedComment(edit.itemId, metaId = null, text = null, updatedAt = null)
            },
        )
    }

    // --- Internals -------------------------------------------------------------------------------

    private suspend fun objectsOfModule(
        moduleId: String,
        itemIds: List<String>,
        access: AccessSet,
    ): Set<String> =
        graphDriver.executeRead(
            ModuleCypher.MODULE_OBJECT_IDS,
            mapOf("moduleUrl" to moduleId, "itemIds" to itemIds),
            access,
        ) { records -> records.map { it.get("id").asString() }.toSet() }

    private suspend fun readComments(
        itemIds: List<String>,
        access: AccessSet,
    ): Map<String, SavedComment> {
        if (itemIds.isEmpty()) {
            return emptyMap()
        }
        return graphDriver.executeRead(
            ReviewCypher.READ_COMMENTS, mapOf("itemIds" to itemIds), access,
        ) { records ->
            records.associate { record ->
                val itemId = record.get("ref").asString()
                itemId to SavedComment(
                    itemId = itemId,
                    metaId = record.get("metaId").asString(null),
                    text = record.get("text").asString(null),
                    updatedAt = record.get("updatedAt").asString(null),
                )
            }
        }
    }

    // Unchanged means the request said nothing about system level, so no statement is emitted at
    // all — a dialog that does not show the field must not be able to clear it (SystemLevelChange).
    private fun systemLevelQuery(moduleId: String, change: SystemLevelChange, user: String, now: String): Query? =
        when (change) {
            is SystemLevelChange.Unchanged -> null

            is SystemLevelChange.Clear ->
                Query(ModuleCypher.CLEAR_SYSTEM_LEVEL, mapOf("moduleId" to moduleId))

            is SystemLevelChange.Set ->
                Query(
                    ModuleCypher.SET_SYSTEM_LEVEL,
                    mapOf(
                        "moduleId" to moduleId,
                        "code" to change.code,
                        "metaId" to UuidV7.generate(),
                        "user" to user,
                        "now" to now,
                    ),
                )
        }

    private fun addMandatoryPoliciesQuery(moduleId: String, names: List<String>, user: String, now: String): Query {
        val rows = names.map { name -> mapOf("attributeName" to name, "metaId" to UuidV7.generate()) }
        return Query(
            ModuleCypher.ADD_MANDATORY_POLICIES,
            mapOf("moduleId" to moduleId, "add" to rows, "user" to user, "now" to now),
        )
    }

    /**
     * The review dialog sends the absolute state of every attribute row it displayed, so this
     * translates that into the same stored shapes the Modules dialog writes.
     *
     * `mandatory` is deliberately routed to :__Policy and not into the :__AttributeSetting node —
     * it is the *same stored value* the Modules dialog writes, so setting it in either dialog is
     * visible in the other (REQ_REVIEW.md §6, acceptance criterion 4). There is one policy shape
     * and one write path, not two.
     */
    private fun attributeSettingQueries(
        moduleId: String,
        settings: List<AttributeSettingInput>,
        user: String,
        now: String,
    ): List<Query> {
        val mandatoryOn = settings.filter { it.mandatory }.map { it.name }
        val mandatoryOff = settings.filterNot { it.mandatory }.map { it.name }

        // A row with every flag off carries no information; its node goes rather than lingering
        // as a row of `false`, so MATCH (m:__Meta) stays an inventory of decisions actually made.
        // `mandatory` is deliberately not in this test: it lives on a :__Policy, not on this node.
        val (keep, drop) = settings.partition {
            it.visible || it.verification || it.excludedFromOpenPoints
        }

        return buildList {
            if (mandatoryOn.isNotEmpty()) {
                add(addMandatoryPoliciesQuery(moduleId, mandatoryOn, user, now))
            }
            if (mandatoryOff.isNotEmpty()) {
                add(Query(ModuleCypher.REMOVE_MANDATORY_POLICIES, mapOf("moduleId" to moduleId, "remove" to mandatoryOff)))
            }
            if (keep.isNotEmpty()) {
                val rows = keep.map {
                    mapOf(
                        "attributeName" to it.name,
                        "visible" to it.visible,
                        "verification" to it.verification,
                        "excludedFromOpenPoints" to it.excludedFromOpenPoints,
                        "metaId" to UuidV7.generate(),
                    )
                }
                add(
                    Query(
                        ReviewCypher.UPSERT_ATTRIBUTE_SETTINGS,
                        mapOf("moduleId" to moduleId, "settings" to rows, "user" to user, "now" to now),
                    ),
                )
            }
            if (drop.isNotEmpty()) {
                add(
                    Query(
                        ReviewCypher.DELETE_ATTRIBUTE_SETTINGS,
                        mapOf("moduleId" to moduleId, "names" to drop.map { it.name }),
                    ),
                )
            }
        }
    }

    /** One attribute's flags, decoded from the wire and validated by the caller. */
    public data class AttributeSettingInput(
        public val name: String,
        public val mandatory: Boolean,
        public val visible: Boolean,
        public val verification: Boolean,
        public val excludedFromOpenPoints: Boolean,
    )

    /** One comment edit. A blank `text` means the reviewer cleared the box: delete the node. */
    public data class CommentEditInput(
        public val itemId: String,
        public val text: String,
    )

    /** One system-level change. A null `code` means the user chose "Not set": delete the node. */
    public data class SystemLevelEditInput(
        public val moduleId: String,
        public val code: String?,
    )
}

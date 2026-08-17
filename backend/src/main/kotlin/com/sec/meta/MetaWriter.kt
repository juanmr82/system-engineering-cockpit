package com.sec.meta

import com.sec.domain.DeleteThreadOutcome
import com.sec.domain.PostNoteOutcome
import com.sec.domain.ResolveThreadOutcome
import com.sec.domain.SaveModuleSettingsOutcome
import com.sec.domain.SaveSystemLevelsOutcome
import com.sec.domain.SavedSystemLevel
import com.sec.domain.SystemLevel
import com.sec.domain.SystemLevelChange
import com.sec.domain.ThreadNote
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
import org.neo4j.driver.Record
import java.time.Instant

// The single guarded write path for Tier-2 data (CLAUDE.md R2). Every write that touches
// :__Meta nodes and their __-prefixed relationships goes through here, and nothing else does —
// enforced in this one place, not per route. Expected failures are a sealed result, not an
// exception (CLAUDE.md §11).
//
// Several feature-shaped endpoints reach this class (module settings, review settings, comment
// threads). That is deliberate and is what CLAUDE.md §5 allows: a dialog save is one transaction,
// not N annotation calls, and a thread reply is its own one-gesture-one-request write (R7's
// ordinary rule, now that docs/req-review-comment-threads.md has retired the old batch exception).
// One meta write path, however many endpoints reach it.
//
// Every public method takes an AccessSet, and it is not decoration. Each one already answers
// "does this module (or item) exist" through a filtered read before it writes — so an anchor this
// caller cannot see is NotFound here for the same reason it is a 404 on the read side. Without it
// the anchor would 404 on read and accept a write, which is worse than either behaviour on its
// own. This is a deliberate, bounded pull-forward of phase 5's anchor guard
// (docs/features/access-control.md §15): it closes the *container* case only. Phase 5 widens it to
// each individual anchor an edit names.
//
// The same set is threaded into `discoverAttributeNames`, the read that decides whether a
// request's attribute names are ones the caller was entitled to have seen — an unfiltered read
// there would answer a question about objects the caller cannot see.
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
     * Spans modules rather than one dialog's own anchor, which is the only structural difference
     * from `saveModuleSettings`. Everything else matches deliberately: one gesture, one request,
     * one transaction, and the stored values echoed back so the table clears its dirty marks
     * without a reload.
     *
     * A client may only classify a module the list actually returned. Without that check an
     * arbitrary `__id` in the body would attach a classification to any node in the graph.
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
     * Post one message to an item's thread — its own request, its own transaction, the R7
     * *ordinary* rule now that every reply saves itself (`docs/req-review-comment-threads.md` §1).
     *
     * Server-decided, not client-decided, whether this becomes the root or a reply: the first post
     * on an item creates its thread's root, and this is what enforces "one thread per item" the way
     * the old single-note write enforced "one note per item" — a `MERGE` on the relationship there,
     * a read-then-branch here, because there are two different shapes to create depending on the
     * answer rather than one node to reuse.
     */
    public suspend fun postNote(
        itemId: String,
        text: String,
        access: AccessSet,
        authorSub: String,
    ): PostNoteOutcome {
        val rootRows = graphDriver.executeRead(
            ReviewCypher.READ_THREAD_ROOT, mapOf("itemId" to itemId), access,
        ) { records -> records.map { it.get("rootMetaId").asString(null) } }
        // Empty means the item itself does not match — not found, or not visible, which R8 asks to
        // look the same either way. A single row with a null value means the item is fine and simply
        // has no thread yet, which is not the same outcome at all.
        if (rootRows.isEmpty()) {
            return PostNoteOutcome.ItemNotFound
        }
        val existingRoot = rootRows.single()
        val metaId = UuidV7.generate()

        val note = graphDriver.executeWrite(
            ReviewCypher.CREATE_NOTE,
            mapOf(
                "itemId" to itemId,
                "metaId" to metaId,
                "text" to text,
                "user" to authorSub,
                "now" to Instant.now().toString(),
                "replyTo" to existingRoot,
                "extra" to if (existingRoot == null) mapOf("resolved" to false) else emptyMap<String, Any>(),
            ),
            access,
        ) { records -> records.single().toThreadNote() }
        return PostNoteOutcome.Posted(note)
    }

    /** Root only (`docs/req-review-comment-threads.md` §2.1) — resolving a reply's ref is a 404. */
    public suspend fun resolveThread(
        metaId: String,
        resolved: Boolean,
        access: AccessSet,
        authorSub: String,
    ): ResolveThreadOutcome {
        val note = graphDriver.executeWrite(
            ReviewCypher.RESOLVE_NOTE,
            mapOf("metaId" to metaId, "resolved" to resolved, "user" to authorSub, "now" to Instant.now().toString()),
            access,
        ) { records -> records.singleOrNull()?.toThreadNote() }
        return note?.let(ResolveThreadOutcome::Resolved) ?: ResolveThreadOutcome.NotFound
    }

    /**
     * Deletes one thread (its root and every reply) or one lone reply — [ReviewCypher.DELETE_NOTE]
     * decides which by whether `metaId` names a root, so this needs no branch of its own.
     */
    public suspend fun deleteThread(metaId: String, access: AccessSet): DeleteThreadOutcome {
        val deleted = graphDriver.executeWrite(
            ReviewCypher.DELETE_NOTE, mapOf("metaId" to metaId), access,
        ) { records -> records.isNotEmpty() }
        return if (deleted) DeleteThreadOutcome.Deleted else DeleteThreadOutcome.NotFound
    }

    /** Every note on one item, root first then chronologically (`ReviewCypher.READ_ANNOTATIONS`). */
    public suspend fun listAnnotations(itemId: String, access: AccessSet): List<ThreadNote> =
        graphDriver.executeRead(
            ReviewCypher.READ_ANNOTATIONS, mapOf("itemId" to itemId), access,
        ) { records -> records.map { it.toThreadNote() } }

    // --- Internals -------------------------------------------------------------------------------

    /** Shared by [postNote], [resolveThread], [listAnnotations] — every `ReviewCypher` note read
     *  returns the same column shape. */
    private fun Record.toThreadNote(): ThreadNote =
        ThreadNote(
            metaId = get("metaId").asString(),
            text = get("text").asString(),
            replyTo = get("replyTo").asString(null),
            resolved = get("resolved").let { if (it.isNull) null else it.asBoolean() },
            authorName = get("authorName").asString(),
            createdAt = get("createdAt").asString(),
            updatedAt = get("updatedAt").asString(),
        )

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

    /** One system-level change. A null `code` means the user chose "Not set": delete the node. */
    public data class SystemLevelEditInput(
        public val moduleId: String,
        public val code: String?,
    )
}

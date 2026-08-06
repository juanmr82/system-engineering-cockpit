package com.sec.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire shapes for docs/REQ_REVIEW.md §8. `ref` is always the base64url encoding of __id (R5) —
// never __id itself. No __-prefixed name appears in any field name or value here.

// One linked object in the References column. `resolved` false means the target is a placeholder
// the importer created for an object that has not been imported: the UI renders it as
// "Not yet imported", names the owning module, and does not link it (§5.1).
//
// `id` is null for an unresolved target, and that is deliberate rather than incidental. A
// placeholder has no DOORS id — the importer names it `<unresolved doors://…>`, which is its
// __id spelled out — so sending it would put a raw __id in the field the References column
// displays (R5). There is no display id for something that has not been imported; the wording
// and the module name are the whole of what the UI can honestly show.
@Serializable
public data class ReferenceDto(
    public val ref: String,
    public val id: String? = null,
    public val resolved: Boolean,
    public val moduleRef: String? = null,
    public val moduleName: String? = null,
)

// Incoming links are incomplete by construction — importers ingest out-links only, so an incoming
// link exists only where the referencing module has itself been imported. `incomingComplete` is
// always false today and is carried explicitly rather than left for the UI to remember, so no
// consumer can present an empty incoming list as "orphan requirement" (SE_ITEM_SCHEMA §8.2).
@Serializable
public data class ReferencesDto(
    public val outgoing: List<ReferenceDto> = emptyList(),
    public val incoming: List<ReferenceDto> = emptyList(),
    public val incomingComplete: Boolean = false,
)

// One comment on one object. Exactly one per object (§5.2) — this is a single value, never a list.
// The author is recorded on the node (__createdBy/__updatedBy, R2 requires the audit fields) but
// is deliberately not carried here: the review table shows no author.
@Serializable
public data class CommentDto(
    public val metaId: String,
    public val text: String,
    public val updatedAt: String? = null,
)

// One table row. `attributes` is the dynamic DOORS attribute bag as Map<String, JsonElement> —
// never a per-module DTO, because attribute sets differ per module by design (CLAUDE.md §5).
//
// `labels` is the one place raw label strings cross the wire, as a state channel rather than
// display text; `type` beside it is the wording the UI shows (Aliases.renderType).
@Serializable
public data class ReviewRowDto(
    public val ref: String,
    public val id: String,
    public val name: String,
    // DOORS's outline number, e.g. "4.3.2-1". Display data: it is the first half of a heading's
    // Description (REQ_REVIEW.md §5). It is *not* a sort key — it does not order correctly as a
    // string, which is why rows arrive in `__sortKey` order and the client keeps that order
    // (CLAUDE.md §11, R3).
    public val objectNumber: String,
    public val type: String?,
    public val labels: List<String>,
    public val level: Int,
    public val requirementLike: Boolean,
    /**
     * Everything the consistency checks found wrong with this object, as the text to show
     * (`REQ_REVIEW.md` §5.3). Two kinds arrive in one list, most fundamental first:
     *
     *  - **fixed rules**, which always run and cannot be turned off — currently "Object Type shall
     *    not be TBD";
     *  - **mandatory attributes** with no value, named individually. These are raw DOORS attribute
     *    names, which is correct under R5: they are *content*, the names the user chose in DOORS
     *    and ticked in the dialog, not internal identifiers.
     *
     * Computed on read, never stored. The mandatory half depends on user-editable configuration,
     * so the verdict is not a property of the import (R2) — and the fixed half is free anyway,
     * being a test over labels already in hand.
     */
    public val issues: List<String> = emptyList(),
    public val attributes: Map<String, JsonElement>,
    public val references: ReferencesDto,
    public val comment: CommentDto? = null,
)

// `truncated` follows the pattern attribute-policy-checks.md §4 already establishes: a page that
// hit its cap says so rather than silently returning less than the client asked for.
@Serializable
public data class ModuleObjectsResponseDto(
    public val rows: List<ReviewRowDto>,
    public val total: Int,
    public val truncated: Boolean,
)

// The detail panel (§7). `moduleName` is what __moduleUrl renders as, per the R5 alias map.
@Serializable
public data class ItemDetailDto(
    public val ref: String,
    public val name: String,
    public val type: String?,
    public val labels: List<String>,
    public val moduleRef: String? = null,
    public val moduleName: String? = null,
    public val properties: List<ModulePropertyDto>,
    public val attributes: Map<String, JsonElement>,
)

@Serializable
public data class TracesResponseDto(
    public val references: List<ReferenceDto>,
    public val complete: Boolean,
)

// --- Comment save -------------------------------------------------------------------------------

// An empty `text` means delete (§8). That is the wire contract, so the client never needs a
// separate delete call for the ordinary "reviewer cleared the box" case.
@Serializable
public data class CommentEditDto(
    public val ref: String,
    public val text: String,
)

@Serializable
public data class SaveCommentsRequestDto(
    public val comments: List<CommentEditDto> = emptyList(),
)

@Serializable
public data class SavedCommentDto(
    public val ref: String,
    public val comment: CommentDto?,
)

@Serializable
public data class SaveCommentsResponseDto(
    public val saved: List<SavedCommentDto>,
)

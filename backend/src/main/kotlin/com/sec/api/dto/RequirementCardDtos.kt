package com.sec.api.dto

import kotlinx.serialization.Serializable

// The card payload: one requirement as every view that draws one shows it.
//
// Shared, deliberately and by contract. The Breakdown tab draws it as a row and the dependency
// graph draws it as a node (docs/REQ_BREAKDOWN_GRAPH_VIEW.md §5.1), and both read *this* shape — so a
// column added here appears in both with no second change, and the two can never drift into
// showing different facts about the same requirement.
//
// `ref` is always the base64url encoding of `__id` (R5) — never `__id` itself, and never the DOORS
// `id`, which is unique inside one module only (R6).

/** One attribute of a node's module, name and value. */
@Serializable
public data class RequirementAttributeDto(
    public val name: String,
    public val value: String,
)

/**
 * One requirement, as a card.
 *
 * `resolved` is false for a placeholder the importer created for an object no import has reached.
 * Such a node carries no DOORS `id` — its `__name` is its `__id` spelled out, which R5 keeps off
 * the wire — so `id` is null and the wording plus the owning module's name is all the UI can
 * honestly show.
 *
 * `deletedInSource` is a different fact and the two are independent. It means a module that *has*
 * been imported no longer contains this object: DOORS deleted it, and DOORS kept the links
 * pointing at it. Such a card is fully `resolved` — the object was really imported once and still
 * carries its id, its statement and its type — and what it adds is that nothing here can be
 * repaired by importing anything. The link is the defect, and it only exists in DOORS.
 *
 * A card is never both. A placeholder was never imported, so no export can have stopped
 * mentioning it (ADR 0012).
 *
 * `level` is the module's system-level classification resolved to code **and** label, reusing the
 * shape the Modules table already speaks, so the client never maps a stored code to wording of its
 * own (R5). Null when the owning module has no classification: the card renders an outlined empty
 * badge rather than none, because dropping it un-aligns every id beside it.
 */
@Serializable
public data class RequirementCardDto(
    public val ref: String,
    public val id: String? = null,
    public val level: SystemLevelOptionDto? = null,
    public val description: String,
    public val resolved: Boolean = true,
    public val deletedInSource: Boolean = false,
    public val moduleRef: String? = null,
    public val moduleName: String? = null,
    public val verificationAttributes: List<RequirementAttributeDto> = emptyList(),
)

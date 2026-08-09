package com.sec.api.dto

import kotlinx.serialization.Serializable

// Wire shapes for docs/features/requirements-statistics.md §9. `ref` is always the base64url
// encoding of __id (R5, com.sec.domain.Ref) — never __id itself. Nothing here is stored: every
// number is computed on read, because a stored derivation goes stale silently (R2).

/**
 * Band 1 — the census (§4).
 *
 * The loop count is **not** here. Band 4 loads from its own endpoint so the other three bands
 * paint without waiting on the edge scan (§7.4), and folding its result into this response would
 * undo that. The view composes the sixth tile from the cycles resource.
 */
@Serializable
public data class CensusDto(
    public val modules: Int,
    public val items: Int,
    public val requirements: Int,
    /** Items carrying a literal TBD/TBC marker in some attribute value (§3.3). */
    public val openPoints: Int,
    public val links: Int,
    /**
     * Links whose far end is an object DOORS deleted while keeping the link (ADR 0012).
     *
     * A subset of [links], and deliberately reported next to it rather than subtracted from it:
     * the edge is really there and really imported. What is wrong is the requirements data.
     */
    public val deletedLinks: Int,
)

/**
 * One entry of the "which attribute is unfilled" ranking (§5).
 *
 * `attribute` is a source attribute name — displayed as-is, because source data is content (R5).
 */
@Serializable
public data class AttributeCountDto(
    public val attribute: String,
    /** (item × attribute) pairs — the violation count, not the item count. */
    public val violations: Int,
)

/**
 * Band 2 for one module, or for the whole scope.
 *
 * `mandatoryConfigured` and `verificationConfigured` exist so the view can tell *not configured*
 * from *clean*: both produce zero violations and they mean opposite things (§3.4, §3.5).
 */
@Serializable
public data class CompletenessDto(
    public val items: Int,
    public val itemsWithOpenPoints: Int,
    public val mandatoryConfigured: Boolean,
    public val mandatoryViolations: Int,
    public val itemsMissingMandatory: Int,
    public val verificationConfigured: Boolean,
    public val verificationViolations: Int,
    public val itemsMissingVerification: Int,
    /** Items with no finding of any kind — the clean segment of the stacked bar. */
    public val itemsClean: Int,
)

/**
 * Band 3's three-way split for one module (§6.1).
 *
 * Populated only when the module carries a system level above L0. `applicable` is false when the
 * module is L0 (it has nothing above it to refine) or has no level set at all — in which case the
 * counts are zero and the view must exclude the module from the ratio rather than read the zeros
 * as a clean result.
 */
@Serializable
public data class ParentageDto(
    public val applicable: Boolean,
    public val hasParent: Int,
    public val parentNotImported: Int,
    public val orphans: Int,
)

/** A module referred to but not imported (§6.2). `name` is null when its node does not exist. */
@Serializable
public data class DanglingTargetDto(
    public val ref: String,
    public val name: String?,
)

/** Everything the view shows for one module, in both scopes. */
@Serializable
public data class ModuleStatisticsDto(
    public val ref: String,
    public val name: String,
    public val systemLevel: SystemLevelOptionDto?,
    public val completeness: CompletenessDto,
    public val parentage: ParentageDto,
    public val mandatoryByAttribute: List<AttributeCountDto>,
    public val openPointsByAttribute: List<AttributeCountDto>,
    public val links: Int,
    public val danglingLinks: Int,
    /** Links this module still asserts to or from objects DOORS deleted (ADR 0012). */
    public val deletedLinks: Int,
    /** True when the object scan hit its cap. Truncation is reported, never silent (§9). */
    public val truncated: Boolean,
)

@Serializable
public data class RequirementStatisticsDto(
    public val census: CensusDto,
    public val modules: List<ModuleStatisticsDto>,
    /** Scope-wide rollups, so the view never re-adds per-module numbers itself. */
    public val completeness: CompletenessDto,
    public val parentage: ParentageDto,
    public val mandatoryByAttribute: List<AttributeCountDto>,
    public val openPointsByAttribute: List<AttributeCountDto>,
    public val danglingTargets: List<DanglingTargetDto>,
    /** Modules excluded from the orphan ratio because they carry no system level (§6.1). */
    public val modulesWithoutSystemLevel: List<String>,
    public val truncated: Boolean,
)

/** One member of a loop, with enough to render the row and link into its Breakdown tab (§7.3). */
@Serializable
public data class LoopMemberDto(
    public val ref: String,
    /** DOORS's own module-local id: display only, never a key (R6). Null on a placeholder. */
    public val id: String?,
    public val name: String,
    public val moduleRef: String?,
    public val moduleName: String?,
    public val systemLevel: SystemLevelOptionDto?,
)

/**
 * One closed loop (§7).
 *
 * `ring` is a concrete cycle in order — the last member refines the first, which is what closes it.
 * `others` are further members of the same knot; several interlocking loops are one finding,
 * because fixing them is one conversation.
 */
@Serializable
public data class LoopDto(
    public val ring: List<LoopMemberDto>,
    public val others: List<LoopMemberDto>,
)

@Serializable
public data class CyclesResponseDto(
    public val loops: List<LoopDto>,
    /** Edges examined — the honest denominator behind "no circular references found". */
    public val edgesExamined: Int,
    public val truncated: Boolean,
)

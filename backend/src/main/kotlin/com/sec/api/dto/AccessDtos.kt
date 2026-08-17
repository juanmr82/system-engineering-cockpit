package com.sec.api.dto

import kotlinx.serialization.Serializable

/** One source's outcome from one `POST /access/reconcile` call (spec §9, §8.3). */
@Serializable
public data class AccessReconcileSourceDto(
    public val sourceId: String,
    public val propagated: Long,
    public val retracted: Long,
    public val seeded: Long,
)

/** The whole call's outcome — one entry per source reconciled. */
@Serializable
public data class AccessReconcileResponseDto(
    public val sources: List<AccessReconcileSourceDto>,
)

/** One row of the Categories screen's table (spec §10.2 screen 1). `ref` is `__metaId`'s handle. */
@Serializable
public data class AccessCategoryDto(
    public val ref: String,
    public val key: String,
    public val name: String,
    public val description: String,
    public val everyGroup: Boolean,
    public val objectCount: Long,
    public val groupCount: Long,
)

@Serializable
public data class AccessCategoryListResponseDto(
    public val categories: List<AccessCategoryDto>,
)

/** `key` is chosen once and never changes (spec §9's phase-6 plan §6.1) — no field for it here
 *  because there is nothing to rename it to; [description] defaults to empty, never absent. */
@Serializable
public data class CreateAccessCategoryRequestDto(
    public val key: String,
    public val name: String,
    public val description: String? = null,
    public val everyGroup: Boolean = false,
)

/** Every field optional: an absent one means "leave unchanged," not "clear it" — `PATCH`, not a
 *  whole-resource `PUT` (categories are edited one field at a time from the table's own dialog). */
@Serializable
public data class UpdateAccessCategoryRequestDto(
    public val name: String? = null,
    public val description: String? = null,
    public val everyGroup: Boolean? = null,
)

/** One row of the Grants screen's matrix (spec §10.2 screen 2). `ref` is `key`'s handle — a group
 *  has no `__metaId`, so its natural key is what [com.sec.domain.Ref] encodes for it. */
@Serializable
public data class GroupWithGrantsDto(
    public val ref: String,
    public val key: String,
    public val name: String,
    public val seesAll: Boolean,
    public val categoryRefs: List<String>,
    public val firstSeenAt: String,
    public val lastSeenAt: String,
)

@Serializable
public data class GroupListResponseDto(
    public val groups: List<GroupWithGrantsDto>,
)

/** The WHOLE grant set for one group, one transaction (R7) — never a delta. */
@Serializable
public data class SaveGrantsRequestDto(
    public val categoryRefs: List<String>,
)

@Serializable
public data class SetSeesAllRequestDto(
    public val seesAll: Boolean,
)

/** One row of the Unassigned queue (spec §10.2 screen 3). `ref` is the container's `__id` handle —
 *  the same kind of reference every other item/module route already uses. */
@Serializable
public data class UnassignedContainerDto(
    public val ref: String,
    public val sourceId: String,
    public val name: String,
    public val invisibleItemCount: Long,
)

@Serializable
public data class UnassignedContainersResponseDto(
    public val containers: List<UnassignedContainerDto>,
)

/** Shared by `PUT /access/containers/{ref}/categories` and `PUT /access/items/{ref}/categories`
 *  (spec §8.1's escape hatch) — the WHOLE direct set, one transaction (R7). */
@Serializable
public data class SaveDirectCategoriesRequestDto(
    public val categoryRefs: List<String>,
)

@Serializable
public data class SaveDirectCategoriesResponseDto(
    public val categoryRefs: List<String>,
)

/** One row of the Import defaults screen (spec §10.2 screen 4) — "new ‹source› ‹containers› are
 *  visible to …". A null [categoryRef] is empty, the legitimate and default answer. */
@Serializable
public data class AccessDefaultDto(
    public val sourceId: String,
    public val containerLabel: String,
    public val categoryRef: String?,
)

@Serializable
public data class AccessDefaultsResponseDto(
    public val defaults: List<AccessDefaultDto>,
)

@Serializable
public data class SaveAccessDefaultsRequestDto(
    public val defaults: List<AccessDefaultDto>,
)

/** Counts for the Access dashboard (spec §9), computed on read (R2). */
@Serializable
public data class AccessSummaryDto(
    public val categoryCount: Long,
    public val groupCount: Long,
    public val unassignedContainerCount: Long,
)

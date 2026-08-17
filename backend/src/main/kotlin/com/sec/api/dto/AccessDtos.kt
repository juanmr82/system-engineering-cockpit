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

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

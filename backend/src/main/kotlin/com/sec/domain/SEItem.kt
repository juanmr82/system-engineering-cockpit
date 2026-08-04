package com.sec.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Source-agnostic envelope for one node in the knowledge tree (CLAUDE.md §5 "API shape",
// docs/SE_ITEM_SCHEMA.md). __id becomes `ref` (base64url) before it reaches the wire.
// `labels` is the one place raw label strings cross the wire deliberately — it is a state
// channel the UI maps to language (e.g. __UNDEFINED -> "Not yet imported"), not display text.
@Serializable
public data class SEItemDto(
    public val ref: String,
    public val name: String,
    public val version: String,
    public val labels: List<String>,
    public val attributes: Map<String, JsonElement>,
)

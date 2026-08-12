package com.sec.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire shapes for docs/features/requirements-modules.md §6. `ref` is always the base64url
// encoding of __id (R5, com.sec.domain.Ref) — never __id itself.

@Serializable
public data class SystemLevelOptionDto(
    public val code: String,
    public val label: String,
)

@Serializable
public data class SystemLevelsResponseDto(
    public val levels: List<SystemLevelOptionDto>,
)

// The Word-export title and number are :DOORSModule properties, not object attributes, so they
// are read by name rather than discovered. Both default to "" — a module that was never exported
// to Word simply does not carry them, which is an absence and not a fault.
@Serializable
public data class ModuleRowDto(
    public val ref: String,
    public val name: String,
    public val lastModified: String,
    public val path: String,
    public val wordExportTitle: String,
    public val wordExportNumber: String,
    public val systemLevel: SystemLevelOptionDto?,
)

@Serializable
public data class ModuleListResponseDto(
    public val rows: List<ModuleRowDto>,
)

@Serializable
public data class ModulePropertyDto(
    public val label: String,
    public val value: String,
)

@Serializable
public data class ModuleDetailDto(
    public val ref: String,
    public val name: String,
    public val systemLevel: String?,
    public val properties: List<ModulePropertyDto>,
)

// The three per-module attribute flags (REQ_REVIEW.md §6). `mandatory` is the :__Policy rule the
// Modules dialog already writes — the same stored value, read through the same path, so setting it
// in either dialog shows in the other. `visible` and `verification` are :__AttributeSetting.
//
// `fixed` marks a column the review table always shows: its Visible checkbox renders checked and
// disabled. It is derived per request, never stored — which column set is fixed is a decision in
// code, not data (CLAUDE.md §2, "anything derivable" is not :__Meta).
@Serializable
public data class ModuleAttributeDto(
    public val name: String,
    public val mandatory: Boolean,
    public val visible: Boolean = false,
    public val verification: Boolean = false,
    /** The TBD / TBC scan skips this attribute's value (`requirements-statistics.md` §3.3). */
    public val excludedFromOpenPoints: Boolean = false,
    public val fixed: Boolean = false,
)

@Serializable
public data class ModuleAttributesResponseDto(
    public val attributes: List<ModuleAttributeDto>,
)

@Serializable
public data class MandatoryAttributesDiffDto(
    public val add: List<String> = emptyList(),
    public val remove: List<String> = emptyList(),
)

// One attribute's three flags as the Req review settings dialog submits them (REQ_REVIEW.md §6).
// Absolute, not a diff: the dialog holds the module's whole attribute list on screen, so sending
// the state of every row it showed is both simpler and unambiguous about what was unticked.
@Serializable
public data class AttributeSettingDto(
    public val name: String,
    public val mandatory: Boolean = false,
    public val visible: Boolean = false,
    public val verification: Boolean = false,
    public val excludedFromOpenPoints: Boolean = false,
)

// Two shapes reach this endpoint, and both are one request and one transaction (R7):
//   - the Modules dialog sends `systemLevel` + `mandatoryAttributes` (a diff over one tab);
//   - the Req review dialog sends `attributeSettings` (the absolute state of every row).
// A request may carry either or both. `attributeSettings` also carries `mandatory`, so a review
// dialog save writes :__Policy and :__AttributeSetting in the same transaction (§9.2).
//
// `systemLevel` is JsonElement? and not String? on purpose. The Modules dialog clears a
// classification by sending an explicit `"systemLevel": null`, while the review dialog does not
// show system level at all and omits the field — and with String? those two are the same value,
// so the review dialog would silently wipe the classification on every save. JsonNull is an
// object rather than Kotlin null, so absent (null) and explicit null (JsonNull) stay
// distinguishable. Read it through SystemLevelChange.from(), never directly.
@Serializable
public data class ModuleSettingsRequestDto(
    public val systemLevel: JsonElement? = null,
    public val mandatoryAttributes: MandatoryAttributesDiffDto = MandatoryAttributesDiffDto(),
    public val attributeSettings: List<AttributeSettingDto>? = null,
)

/**
 * The Modules table's batch system-level save.
 *
 * `code` is a plain nullable string here, unlike `ModuleSettingsRequestDto.systemLevel`, and the
 * difference is deliberate: there, absent and explicit-null had to stay distinguishable because
 * two dialogs post to that endpoint and one of them does not show system level at all. Here every
 * entry in the list *is* a change the user made, so a null unambiguously means "cleared".
 */
@Serializable
public data class SystemLevelEditDto(
    public val ref: String,
    public val code: String? = null,
)

@Serializable
public data class SaveSystemLevelsRequestDto(
    public val levels: List<SystemLevelEditDto> = emptyList(),
)

// Echoed back so the table can clear its dirty marks without reloading — the server decides what
// was stored, exactly as with comments (REQ_REVIEW.md §5.2). The label is resolved here so the
// client never maps a code to wording of its own (R5).
@Serializable
public data class SavedSystemLevelDto(
    public val ref: String,
    public val systemLevel: SystemLevelOptionDto?,
)

@Serializable
public data class SaveSystemLevelsResponseDto(
    public val saved: List<SavedSystemLevelDto>,
)

// RFC 9457. `instance` is the request's CallId, so a reported failure can be found in the logs.
@Serializable
public data class ProblemDetailDto(
    public val type: String,
    public val title: String,
    public val status: Int,
    public val detail: String,
    public val instance: String? = null,
)

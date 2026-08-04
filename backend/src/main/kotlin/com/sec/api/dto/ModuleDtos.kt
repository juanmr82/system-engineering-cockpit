package com.sec.api.dto

import kotlinx.serialization.Serializable

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

@Serializable
public data class ModuleRowDto(
    public val ref: String,
    public val name: String,
    public val lastModified: String,
    public val path: String,
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

@Serializable
public data class ModuleAttributeDto(
    public val name: String,
    public val mandatory: Boolean,
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

@Serializable
public data class ModuleSettingsRequestDto(
    public val systemLevel: String? = null,
    public val mandatoryAttributes: MandatoryAttributesDiffDto = MandatoryAttributesDiffDto(),
)

@Serializable
public data class ProblemDetailDto(
    public val type: String,
    public val title: String,
    public val status: Int,
    public val detail: String,
)

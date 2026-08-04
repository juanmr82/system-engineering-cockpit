// Wire shapes for docs/features/requirements-modules.md §6. `ref` is always the opaque route
// handle (R5) — never a raw __id.

export interface SystemLevelOption {
  readonly code: string;
  readonly label: string;
}

export interface SystemLevelsResponse {
  readonly levels: SystemLevelOption[];
}

export interface ModuleRow {
  readonly ref: string;
  readonly name: string;
  readonly lastModified: string;
  readonly path: string;
  readonly systemLevel: SystemLevelOption | null;
}

export interface ModuleListResponse {
  readonly rows: ModuleRow[];
}

export interface ModuleProperty {
  readonly label: string;
  readonly value: string;
}

export interface ModuleDetail {
  readonly ref: string;
  readonly name: string;
  readonly systemLevel: string | null;
  readonly properties: ModuleProperty[];
}

export interface ModuleAttribute {
  readonly name: string;
  readonly mandatory: boolean;
}

export interface ModuleAttributesResponse {
  readonly attributes: ModuleAttribute[];
}

export interface MandatoryAttributesDiff {
  readonly add: string[];
  readonly remove: string[];
}

export interface ModuleSettingsRequest {
  readonly systemLevel: string | null;
  readonly mandatoryAttributes: MandatoryAttributesDiff;
}

// Row model carries a lowercase, accent-stripped, pre-joined copy of every rendered column so
// the search box can filter without re-normalising on every keystroke
// (requirements-modules.md §3).
export interface SearchableModuleRow extends ModuleRow {
  readonly searchText: string;
}

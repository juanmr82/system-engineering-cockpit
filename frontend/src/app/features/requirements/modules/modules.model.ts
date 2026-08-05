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

// The three per-module attribute flags (REQ_REVIEW.md §6). `fixed` marks a column the review
// table always shows: its Visible checkbox renders checked and disabled. It is derived per
// request, never stored.
export interface ModuleAttribute {
  readonly name: string;
  readonly mandatory: boolean;
  readonly visible: boolean;
  readonly verification: boolean;
  readonly fixed: boolean;
}

export interface ModuleAttributesResponse {
  readonly attributes: ModuleAttribute[];
}

export interface MandatoryAttributesDiff {
  readonly add: string[];
  readonly remove: string[];
}

export interface AttributeSetting {
  readonly name: string;
  readonly mandatory: boolean;
  readonly visible: boolean;
  readonly verification: boolean;
}

// Two dialogs post this, and both are one request in one transaction (R7): the Modules dialog
// sends `systemLevel` plus a mandatory diff, the Req review dialog sends the absolute state of
// every attribute row it showed. `systemLevel` is optional and must stay so — the review dialog
// does not show system level, and sending an explicit null would clear the classification.
export interface ModuleSettingsRequest {
  readonly systemLevel?: string | null;
  readonly mandatoryAttributes?: MandatoryAttributesDiff;
  readonly attributeSettings?: AttributeSetting[];
}

// Row model carries a lowercase, accent-stripped, pre-joined copy of every rendered column so
// the search box can filter without re-normalising on every keystroke
// (requirements-modules.md §3).
export interface SearchableModuleRow extends ModuleRow {
  readonly searchText: string;
}

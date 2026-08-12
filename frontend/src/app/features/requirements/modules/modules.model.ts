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
  // Module properties, not object attributes: a module that was never exported to Word carries
  // neither, and the server sends "" for it — an absence, not a fault.
  readonly wordExportTitle: string;
  readonly wordExportNumber: string;
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

// The per-module attribute flags (REQ_REVIEW.md §6). `fixed` marks a column the review table
// always shows: its Visible checkbox renders checked and disabled. It is derived per request,
// never stored. `excludedFromOpenPoints` takes the attribute out of the TBD/TBC scan the
// Statistics view runs, and out of nothing else.
export interface ModuleAttribute {
  readonly name: string;
  readonly mandatory: boolean;
  readonly visible: boolean;
  readonly verification: boolean;
  readonly excludedFromOpenPoints: boolean;
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
  readonly excludedFromOpenPoints: boolean;
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

/**
 * What a Modules cell renderer is allowed to ask the view for, passed as the grid's `context`.
 *
 * One function, because one is all a cell needs. Handing the component to ag-grid would work and
 * would put the search box, the reload and the snackbar within reach of a table cell.
 */
export interface ModulesCellContext {
  readonly openSettings: (row: ModuleRow) => void;
  /** The controlled vocabulary, so a cell never invents an option or its wording (R5). */
  readonly systemLevels: () => readonly SystemLevelOption[];
  /** What the level select shows: the pending edit if there is one, else what was stored. */
  readonly levelCode: (row: ModuleRow) => string | null;
  readonly isLevelDirty: (row: ModuleRow) => boolean;
  /** `null` is the "Not set" option — clear the classification, not "leave it alone". */
  readonly editLevel: (row: ModuleRow, code: string | null) => void;
}

// The Modules table's batch save (requirements-modules.md). Same shape as the review table's
// comment save: every changed row in one request, one transaction, and the server echoes back
// what it stored so the table clears its dirty marks without reloading.
export interface SystemLevelEdit {
  readonly ref: string;
  readonly code: string | null;
}

export interface SaveSystemLevelsRequest {
  readonly levels: SystemLevelEdit[];
}

export interface SavedSystemLevel {
  readonly ref: string;
  readonly systemLevel: SystemLevelOption | null;
}

export interface SaveSystemLevelsResponse {
  readonly saved: SavedSystemLevel[];
}

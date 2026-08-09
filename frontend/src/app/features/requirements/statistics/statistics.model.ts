import type { SystemLevelOption } from '../modules/modules.model';

// Wire shapes for docs/features/requirements-statistics.md §9. `ref` is always the opaque route
// handle (R5) — never a raw __id. Every number here is computed on read and stored nowhere.

/**
 * Band 1. The loop count is absent on purpose: Band 4 loads from its own endpoint so the other
 * three bands paint without waiting on the edge scan (§7.4), and the view composes that tile from
 * the cycles resource.
 */
export interface Census {
  readonly modules: number;
  readonly items: number;
  readonly requirements: number;
  readonly openPoints: number;
  readonly links: number;
  /**
   * Links whose far end is an object DOORS deleted while keeping the link (ADR 0012).
   *
   * A subset of `links`, not a separate population: the edge really was imported. What is wrong
   * is the requirements data, and the fix is in DOORS.
   */
  readonly deletedLinks: number;
}

export interface AttributeCount {
  readonly attribute: string;
  readonly violations: number;
}

/**
 * `mandatoryConfigured` / `verificationConfigured` are what let the view tell *not configured*
 * from *clean*: both produce zero violations and they mean opposite things (§3.4, §3.5).
 */
export interface Completeness {
  readonly items: number;
  readonly itemsWithOpenPoints: number;
  readonly mandatoryConfigured: boolean;
  readonly mandatoryViolations: number;
  readonly itemsMissingMandatory: number;
  readonly verificationConfigured: boolean;
  readonly verificationViolations: number;
  readonly itemsMissingVerification: number;
  readonly itemsClean: number;
}

/**
 * `applicable` is false for an L0 module (nothing above it to refine) and for one with no system
 * level at all. The counts are then zero and must **not** be read as a clean result (§6.1).
 */
export interface Parentage {
  readonly applicable: boolean;
  readonly hasParent: number;
  readonly parentNotImported: number;
  readonly orphans: number;
}

export interface DanglingTarget {
  readonly ref: string;
  readonly name: string | null;
}

export interface ModuleStatistics {
  readonly ref: string;
  readonly name: string;
  readonly systemLevel: SystemLevelOption | null;
  readonly completeness: Completeness;
  readonly parentage: Parentage;
  readonly mandatoryByAttribute: AttributeCount[];
  readonly openPointsByAttribute: AttributeCount[];
  readonly links: number;
  readonly danglingLinks: number;
  readonly deletedLinks: number;
  readonly truncated: boolean;
}

export interface RequirementStatistics {
  readonly census: Census;
  readonly modules: ModuleStatistics[];
  readonly completeness: Completeness;
  readonly parentage: Parentage;
  readonly mandatoryByAttribute: AttributeCount[];
  readonly openPointsByAttribute: AttributeCount[];
  readonly danglingTargets: DanglingTarget[];
  readonly modulesWithoutSystemLevel: string[];
  readonly truncated: boolean;
}

export interface LoopMember {
  readonly ref: string;
  /** DOORS's own id: display only, never a key (R6). Null on a not-yet-imported placeholder. */
  readonly id: string | null;
  readonly name: string;
  readonly moduleRef: string | null;
  readonly moduleName: string | null;
  readonly systemLevel: SystemLevelOption | null;
}

/** `ring` is a concrete cycle in order — the last member refines the first, closing the loop. */
export interface Loop {
  readonly ring: LoopMember[];
  readonly others: LoopMember[];
}

export interface CyclesResponse {
  readonly loops: Loop[];
  readonly edgesExamined: number;
  readonly truncated: boolean;
}

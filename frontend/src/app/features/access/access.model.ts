// Wire shapes for the Access views (docs/features/access-control.md §9, §10.2). Extended screen
// by screen as each one is built. `ref` is always the opaque route handle (R5) — never a raw
// __metaId or __id.

/** `GET /api/v1/access/summary` — counts for the Access dashboard, computed on read (R2). */
export interface AccessSummary {
  readonly categoryCount: number;
  readonly groupCount: number;
  readonly unassignedContainerCount: number;
}

/** One row of the Categories screen's table (spec §10.2 screen 1). */
export interface AccessCategory {
  readonly ref: string;
  readonly key: string;
  readonly name: string;
  readonly description: string;
  readonly everyGroup: boolean;
  readonly objectCount: number;
  readonly groupCount: number;
}

export interface AccessCategoryListResponse {
  readonly categories: AccessCategory[];
}

/** `key` is chosen once and never changes — there is no field to rename it to on the update
 *  request below; `description` defaults to empty, never absent. */
export interface CreateAccessCategoryRequest {
  readonly key: string;
  readonly name: string;
  readonly description?: string;
  readonly everyGroup?: boolean;
}

/** Every field optional: an absent one means "leave unchanged," not "clear it" — `PATCH`, not a
 *  whole-resource `PUT`. No `key` field; a category's key is immutable after creation. */
export interface UpdateAccessCategoryRequest {
  readonly name?: string;
  readonly description?: string;
  readonly everyGroup?: boolean;
}

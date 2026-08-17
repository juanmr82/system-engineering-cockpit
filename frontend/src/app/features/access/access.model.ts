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

/** One row of the Grants screen's matrix (spec §10.2 screen 2) — every `:__Group` ever seen,
 *  with its own grants and `seesAll`. The matrix itself is built client-side from this plus
 *  every category, never server-shaped, per spec §9's "saving is per row" wording. */
export interface GroupWithGrants {
  readonly ref: string;
  readonly key: string;
  readonly name: string;
  readonly seesAll: boolean;
  readonly categoryRefs: string[];
  readonly firstSeenAt: string;
  readonly lastSeenAt: string;
}

export interface GroupListResponse {
  readonly groups: GroupWithGrants[];
}

/** The WHOLE grant set for one group, one transaction (R7) — never a delta. */
export interface SaveGrantsRequest {
  readonly categoryRefs: string[];
}

/** `seesAll` only — a deliberately separate write from the grant set (spec §9: "audited
 *  loudly"), never batched into a row's pending-grants buffer. */
export interface SetSeesAllRequest {
  readonly seesAll: boolean;
}

/** One row of the Unassigned queue (spec §10.2 screen 3) — a container with no direct category,
 *  and how many of its members carry none at all. Deliberately exempt from the visibility
 *  predicate on the backend (§16.2a): an access manager who cannot yet grant themselves a
 *  category could otherwise never find the container to grant one to. */
export interface UnassignedContainer {
  readonly ref: string;
  readonly sourceId: string;
  readonly name: string;
  readonly invisibleItemCount: number;
}

export interface UnassignedContainersResponse {
  readonly containers: UnassignedContainer[];
}

/** Shared by the container and the single-item escape-hatch write (spec §8.1) — the WHOLE direct
 *  set, one transaction (R7). */
export interface SaveDirectCategoriesRequest {
  readonly categoryRefs: string[];
}

export interface SaveDirectCategoriesResponse {
  readonly categoryRefs: string[];
}

/** One source's outcome from `POST /access/reconcile` (spec §8.3). */
export interface AccessReconcileSource {
  readonly sourceId: string;
  readonly propagated: number;
  readonly retracted: number;
  readonly seeded: number;
}

export interface AccessReconcileResponse {
  readonly sources: AccessReconcileSource[];
}

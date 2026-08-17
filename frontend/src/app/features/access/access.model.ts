// Wire shapes for the Access views (docs/features/access-control.md §9, §10.2). Extended screen
// by screen as each one is built; today only what the sidenav badge needs.

/** `GET /api/v1/access/summary` — counts for the Access dashboard, computed on read (R2). */
export interface AccessSummary {
  readonly categoryCount: number;
  readonly groupCount: number;
  readonly unassignedContainerCount: number;
}

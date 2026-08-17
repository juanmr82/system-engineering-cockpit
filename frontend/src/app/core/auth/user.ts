// Wire shape of GET /api/v1/auth/me (ADR 0017). Mirrors AuthMeDto exactly — the backend's DTO
// names are already presentable, so no alias mapping is needed here (R5's map is for `__`-prefixed
// graph names, and nothing about identity carries the namespace).
export interface AuthenticatedUser {
  readonly userId: string;
  readonly displayName: string;
  readonly email: string;
  readonly roles: readonly string[];
  readonly groups: readonly string[];
  readonly csrfToken: string;
  readonly seesAll: boolean;
  /**
   * Explicit category grants only — **not** how many categories this caller can actually see.
   * Reads `0` for a `seesAll` user by design: `AccessSet.SEES_ALL` carries no `categoryIds`
   * because the visibility predicate never reads them once `seesAll` short-circuits it. Do not
   * treat this as "0 means nothing visible."
   */
  readonly categoryCount: number;
}

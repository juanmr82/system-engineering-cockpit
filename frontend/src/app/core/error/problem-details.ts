// Shape of the RFC 9457 problem-details body the backend's StatusPages plugin returns.
// Errors and empty states get human sentences in the UI — never a raw `title` string rendered
// verbatim if it could contain an internal label (CLAUDE.md R5).
export interface ProblemDetails {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail?: string;
  readonly instance?: string;
}

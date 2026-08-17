import { HttpErrorResponse } from '@angular/common/http';

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

/**
 * The problem detail's own sentence, or `fallback` when the failure never reached the API (a
 * network error, a CORS failure, anything whose `error` is not a parsed JSON body).
 *
 * One implementation, used everywhere a save catches an `HttpErrorResponse` — before this there
 * were five near-identical copies (`modules.ts`, `module-settings-dialog.ts`,
 * `review-settings-dialog.ts`, `requirement-review.ts`, and a structurally different
 * `windchill-settings-api.service.ts` that skipped the `instanceof` check). `fallback` is a
 * parameter rather than baked in because every call site's fallback names what it was saving.
 */
export function detailOf(cause: unknown, fallback: string): string {
  if (cause instanceof HttpErrorResponse && cause.error) {
    const problem = cause.error as Partial<ProblemDetails>;
    if (problem.detail) {
      return problem.detail;
    }
  }
  return fallback;
}

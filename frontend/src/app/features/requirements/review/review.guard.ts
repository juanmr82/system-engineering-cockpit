import type { CanDeactivateFn } from '@angular/router';
import type { RequirementReview } from './requirement-review';

/**
 * The route half of the Req review view's exit guard (REQ_REVIEW.md §9.1, CLAUDE.md R7).
 *
 * It reads the component instance it is leaving and nothing else — there is no store to consult,
 * and this is the only guard in the application. A table with pending comments is the one place
 * unsaved work can exist outside a modal, which is why it exists at all; a reviewer who has typed
 * twelve comments and clicks a different view has done real work.
 */
export const canLeaveReview: CanDeactivateFn<RequirementReview> = (component) =>
  component.confirmDiscard();

import type { CanDeactivateFn } from '@angular/router';
import type { Modules } from './modules';

/**
 * The route half of the Modules view's exit guard (CLAUDE.md R7).
 *
 * Same shape and same reason as the Req review view's: the System level column is editable and its
 * pending changes live in the component, so a table is again the one place unsaved work can exist
 * outside a modal. It reads the component instance it is leaving and nothing else — there is no
 * store to consult.
 */
export const canLeaveModules: CanDeactivateFn<Modules> = (component) => component.confirmDiscard();

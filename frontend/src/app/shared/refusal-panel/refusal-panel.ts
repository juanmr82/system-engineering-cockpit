import { Component, input } from '@angular/core';
import { EmptyState } from '../empty-state/empty-state';

/**
 * What a denied `/access/*` route renders in place of its real content (frontend/CLAUDE.md §8:
 * "never a redirect" — the URL stays exactly where the user typed it). Each of the four Access
 * screens self-checks `authStore.hasRole(Role.ACCESS_MANAGER)` and swaps to this rather than
 * navigating anywhere, the same way `layout/shell/not-found.ts` composes `EmptyState` inline
 * rather than duplicating its template.
 */
@Component({
  selector: 'sec-refusal-panel',
  imports: [EmptyState],
  template: ` <sec-empty-state [title]="title()" [description]="description()" /> `,
})
export class RefusalPanel {
  readonly title = input.required<string>();
  readonly description = input(
    'You need the Access manager role to see this page. Ask an administrator to grant it.',
  );
}

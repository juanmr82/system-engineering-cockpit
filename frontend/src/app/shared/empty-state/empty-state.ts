import { Component, input } from '@angular/core';

// Every feature route renders this until its real view lands. Empty states are an invitation
// to act, not an apology (CLAUDE.md §9).
@Component({
  selector: 'sec-empty-state',
  template: `
    <div class="sec-empty-state">
      <h2>{{ title() }}</h2>
      @if (description()) {
        <p>{{ description() }}</p>
      }
    </div>
  `,
  styles: `
    .sec-empty-state {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 8px;
      padding: 48px;
      color: var(--sec-blue);
    }
  `,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly description = input('');
}

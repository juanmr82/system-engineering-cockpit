import { Component, input } from '@angular/core';

// Every feature route renders this until its real view lands, and views reuse it for their own
// empty results. Empty states are an invitation to act, not an apology (CLAUDE.md §9).
@Component({
  selector: 'sec-empty-state',
  templateUrl: './empty-state.html',
  styleUrl: './empty-state.scss',
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly description = input('');
}

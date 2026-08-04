import { Component } from '@angular/core';
import { EmptyState } from '../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-not-found',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="Page not found"
      description="Check the address, or pick a section from the sidenav."
    />
  `,
})
export class NotFound {}

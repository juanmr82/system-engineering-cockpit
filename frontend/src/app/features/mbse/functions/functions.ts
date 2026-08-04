import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-functions',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="Functions"
      description="Functional decomposition elements from Cameo will live here."
    />
  `,
})
export class Functions {}

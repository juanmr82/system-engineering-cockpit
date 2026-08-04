import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-requirements-statistics',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="Statistics"
      description="Coverage and health metrics across imported requirements modules will live here."
    />
  `,
})
export class RequirementsStatistics {}

import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-requirement-review',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="Req review"
      description="Review campaigns and their verdicts will be managed here."
    />
  `,
})
export class RequirementReview {}

import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-jira-issues',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="Issues"
      description="Issues JIRA will be browsable here, not imported ."
    />
  `,
})
export class JiraIssues {}

import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-jira-kids',
  imports: [EmptyState],
  template: `
    <sec-empty-state title="KIDS" description="KIDS imported from JIRA will be browsable here." />
  `,
})
export class JiraKids {}

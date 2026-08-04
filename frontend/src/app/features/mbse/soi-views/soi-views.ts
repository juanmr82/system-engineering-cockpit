import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-soi-views',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="SOI views"
      description="System-of-interest views from Cameo Systems Modeler will live here."
    />
  `,
})
export class SoiViews {}

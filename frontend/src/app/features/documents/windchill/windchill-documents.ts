import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-windchill-documents',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="Windchill"
      description="Document metadata imported from PTC Windchill will be browsable here."
    />
  `,
})
export class WindchillDocuments {}

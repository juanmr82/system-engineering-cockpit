import { Component } from '@angular/core';
import { EmptyState } from '../../../shared/empty-state/empty-state';

@Component({
  selector: 'sec-modules',
  imports: [EmptyState],
  template: `
    <sec-empty-state
      title="Modules"
      description="The DOORS module tree and their objects will be browsable here."
    />
  `,
})
export class Modules {}

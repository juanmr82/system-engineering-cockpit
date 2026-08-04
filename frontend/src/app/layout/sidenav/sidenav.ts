import { Component, inject } from '@angular/core';
import { MatListModule } from '@angular/material/list';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { NavigationService } from '../../core/navigation/navigation.service';
import { LogoComponent } from './logo.component';

// Rendered from NavGroup[] (config, never hand-written markup, never the graph — CLAUDE.md §9).
// Collapse-to-64px and per-user width are a later pass; this is the expanded, always-open shape.
@Component({
  selector: 'sec-sidenav',
  imports: [RouterLink, RouterLinkActive, MatListModule, LogoComponent],
  template: `
    <nav class="sec-sidenav" aria-label="Primary">
      <sec-logo />
      @for (group of navigation.groups(); track group.key) {
        <div class="sec-sidenav__group-label">{{ group.label }}</div>
        <mat-nav-list>
          @for (item of group.items; track item.key) {
            <a
              mat-list-item
              [routerLink]="item.route"
              routerLinkActive="sec-sidenav__item--active"
            >
              {{ item.label }}
            </a>
          }
        </mat-nav-list>
      }
    </nav>
  `,
  styles: `
    .sec-sidenav {
      width: 280px;
      height: 100%;
      background: white;
    }
    .sec-sidenav__group-label {
      padding: 8px 16px;
      color: var(--sec-blue);
      opacity: 0.7;
      font-size: 0.75rem;
      text-transform: uppercase;
    }
    .sec-sidenav__item--active {
      border-left: 3px solid var(--sec-blue-mid);
      background: var(--sec-blue-pale);
    }
  `,
})
export class Sidenav {
  protected readonly navigation = inject(NavigationService);
}

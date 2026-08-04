import { Component, inject } from '@angular/core';
import { MatListModule } from '@angular/material/list';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { NavigationService } from '../../core/navigation/navigation.service';
import { Logo } from './logo';

// Rendered from NavGroup[] (config, never hand-written markup, never the graph — CLAUDE.md §9).
// Collapse-to-64px and per-user width are a later pass; this is the expanded, always-open shape.
@Component({
  selector: 'sec-sidenav',
  imports: [RouterLink, RouterLinkActive, MatListModule, Logo],
  templateUrl: './sidenav.html',
  styleUrl: './sidenav.scss',
})
export class Sidenav {
  protected readonly navigation = inject(NavigationService);
}

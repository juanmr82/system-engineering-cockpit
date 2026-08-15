import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth/auth-store';

// Two toolbar actions, and neither is a save — there is no global save (CLAUDE.md §9, R7). Every
// dialog and editable table commits its own changes. The settings menu routes into /settings/*,
// which is the subtree one guard will cover when RBAC arrives (spec §13.1, §14.1).
//
// The user menu is the only place identity reaches the UI (ADR 0017): display name, email, roles
// and groups all come straight from AuthStore's /auth/me signal, decoded nowhere in the browser.
// "Connected graph/database name" from CLAUDE.md §9's sketch is not here yet — nothing in the API
// reports it today, and adding it is its own small piece of work, not part of this feature.
@Component({
  selector: 'sec-toolbar',
  imports: [
    MatToolbarModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
    MatTooltipModule,
    RouterLink,
  ],
  templateUrl: './toolbar.html',
  styleUrl: './toolbar.scss',
})
export class Toolbar {
  private readonly authStore = inject(AuthStore);

  protected readonly user = this.authStore.user;

  protected signOut(): void {
    void this.authStore.signOut();
  }
}

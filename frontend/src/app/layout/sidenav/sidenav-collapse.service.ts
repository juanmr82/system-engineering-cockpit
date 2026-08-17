import { Injectable, signal } from '@angular/core';

// Per-user browser preference, in-memory only (frontend/CLAUDE.md §9): never persisted, never
// sent to the backend, and reset to expanded on every reload — a `sec-shell`/`sec-sidenav`-shared
// service is what lets the shell resize the drawer and the sidenav swap its own template off the
// same signal without a parent/child input-output chain across two unrelated components.
@Injectable({ providedIn: 'root' })
export class SidenavCollapseService {
  private readonly _collapsed = signal(false);

  readonly collapsed = this._collapsed.asReadonly();

  toggle(): void {
    this._collapsed.update((collapsed) => !collapsed);
  }
}

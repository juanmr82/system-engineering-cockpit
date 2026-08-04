import { Component, inject } from '@angular/core';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { AnnotationStore } from '../../core/meta/annotation-store';

// Save icon commits pending Tier-2 annotations only — navigation order is config and sidenav
// state is a browser preference, neither is ever "unsaved" (CLAUDE.md §9).
@Component({
  selector: 'sec-toolbar',
  imports: [MatToolbarModule, MatIconModule, MatButtonModule, MatMenuModule, MatBadgeModule],
  template: `
    <mat-toolbar class="sec-toolbar">
      <span class="sec-toolbar__spacer"></span>

      <button
        mat-icon-button
        [matBadge]="annotationStore.pendingCount()"
        [matBadgeHidden]="!annotationStore.isDirty()"
        [disabled]="!annotationStore.isDirty()"
        aria-label="Save pending annotations"
      >
        <mat-icon>save</mat-icon>
      </button>

      <button mat-icon-button [matMenuTriggerFor]="userMenu" aria-label="Account menu">
        <mat-icon>account_circle</mat-icon>
      </button>
      <mat-menu #userMenu="matMenu">
        <button mat-menu-item disabled>Signed in</button>
        <button mat-menu-item>Sign out</button>
      </mat-menu>
    </mat-toolbar>
  `,
  styles: `
    .sec-toolbar {
      background: var(--sec-blue);
      color: white;
      height: 56px;
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      z-index: 10;
    }
    .sec-toolbar__spacer {
      flex: 1 1 auto;
    }
  `,
})
export class Toolbar {
  protected readonly annotationStore = inject(AnnotationStore);
}

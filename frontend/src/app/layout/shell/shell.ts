import { Component } from '@angular/core';
import { MatSidenavModule } from '@angular/material/sidenav';
import { RouterOutlet } from '@angular/router';
import { Sidenav } from '../sidenav/sidenav';
import { Toolbar } from '../toolbar/toolbar';

// The shell skeleton (CLAUDE.md §9): fixed toolbar, side="side" sidenav, routed content.
// Responsive collapse (over/side breakpoint, 64px rail) is not wired yet.
@Component({
  selector: 'sec-shell',
  imports: [RouterOutlet, MatSidenavModule, Toolbar, Sidenav],
  template: `
    <sec-toolbar />
    <mat-sidenav-container class="sec-shell">
      <mat-sidenav mode="side" [opened]="true">
        <sec-sidenav />
      </mat-sidenav>
      <mat-sidenav-content>
        <router-outlet />
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styles: `
    .sec-shell {
      position: absolute;
      top: 56px;
      bottom: 0;
      left: 0;
      right: 0;
    }
  `,
})
export class Shell {}

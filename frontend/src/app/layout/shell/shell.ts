import { Component, inject } from '@angular/core';
import { MatSidenavModule } from '@angular/material/sidenav';
import { RouterOutlet } from '@angular/router';
import { Sidenav } from '../sidenav/sidenav';
import { SidenavCollapseService } from '../sidenav/sidenav-collapse.service';
import { Toolbar } from '../toolbar/toolbar';

// The shell skeleton (CLAUDE.md §9): fixed toolbar, side="side" sidenav, routed content.
// The 64px rail collapse is wired through SidenavCollapseService, shared with Sidenav so the
// drawer's own width and its template (full list vs. icon rail) move together. The responsive
// over/side breakpoint below 960px is a separate concern and is still not wired.
@Component({
  selector: 'sec-shell',
  imports: [RouterOutlet, MatSidenavModule, Toolbar, Sidenav],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected readonly collapse = inject(SidenavCollapseService);
}

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
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {}

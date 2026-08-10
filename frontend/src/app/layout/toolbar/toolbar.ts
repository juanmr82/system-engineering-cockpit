import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

// Two toolbar actions: settings, and the user menu.
//
// CLAUDE.md §9 used to say the user menu was the *only* one, and the reason it gave is the reason
// the gear is allowed: "there is no global save (R7)". That rule is about a control that writes
// across views, and a link to a settings route writes nothing. The sidenav was the other candidate
// and is wrong for a different reason — its groups are source families, and administration is not
// one (ADR 0013).
@Component({
  selector: 'sec-toolbar',
  imports: [
    MatToolbarModule,
    MatIconModule,
    MatButtonModule,
    MatMenuModule,
    MatTooltipModule,
    RouterLink,
    RouterLinkActive,
  ],
  templateUrl: './toolbar.html',
  styleUrl: './toolbar.scss',
})
export class Toolbar {}

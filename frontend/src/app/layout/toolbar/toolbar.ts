import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';

// Two toolbar actions, and neither is a save — there is no global save (CLAUDE.md §9, R7). Every
// dialog and editable table commits its own changes. The settings menu routes into /settings/*,
// which is the subtree one guard will cover when RBAC arrives (spec §13.1, §14.1).
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
export class Toolbar {}

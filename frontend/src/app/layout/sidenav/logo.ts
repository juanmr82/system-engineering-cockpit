import { Component } from '@angular/core';

// Single swappable wordmark block. Do not ship the Airbus logo here — it is a trademark this
// product does not hold a licence to use (CLAUDE.md §8). Inter, sentence case, --sec-blue on
// white, sized to the 64px sidenav header. A real mark can drop in later by editing only this
// file. The template stays inline: it is one element, and a separate file would add noise, not
// clarity (see the standard in CLAUDE.md §6).
@Component({
  selector: 'sec-logo',
  template: `<span class="sec-logo">System Engineering Cockpit</span>`,
  styleUrl: './logo.scss',
})
export class Logo {}

import { Component } from '@angular/core';

// Single swappable wordmark block. Do not ship the Airbus logo here — it is a trademark this
// product does not hold a licence to use (CLAUDE.md §8). Inter, sentence case, --sec-blue on
// white, sized to the 64px sidenav header. A real mark can drop in later by editing only this
// file.
@Component({
  selector: 'sec-logo',
  template: `<span class="sec-logo">System Engineering Cockpit</span>`,
  styles: `
    .sec-logo {
      display: flex;
      align-items: center;
      height: 64px;
      padding: 0 16px;
      font-weight: 600;
      color: var(--sec-blue);
    }
  `,
})
export class LogoComponent {}

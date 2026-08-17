import { EnvironmentProviders, inject, provideAppInitializer } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { MatIconRegistry } from '@angular/material/icon';

// Every custom icon in the app, registered once. Icons are real .svg assets under public/icons/
// rather than literals in a component: an SVG stays previewable, diffable and replaceable without
// touching TypeScript, and a component never carries a path blob.
//
// This also sidesteps the Material icon *font*, which CLAUDE.md §8 requires be self-hosted (no
// Google Fonts CDN, GDPR) and which is not shipped yet — a `<mat-icon>ligature</mat-icon>` renders
// as raw text until it is. Anything registered here renders correctly today.
//
// Paths are root-absolute on purpose. public/** is emitted at the output root, and a relative
// 'icons/x.svg' would resolve against the current route — breaking on /requirements/modules.
const SEC_ICONS = {
  alert: '/icons/alert.svg',
  gearbox: '/icons/gearbox.svg',
  'account-circle': '/icons/account-circle.svg',
  'check-circle': '/icons/check-circle.svg',
  'chevron-down': '/icons/chevron-down.svg',
  close: '/icons/close.svg',
  collapse: '/icons/collapse.svg',
  // A comment thread's own indicator (docs/req-review-comment-threads.md) — the review table's
  // Comment column and the thread panel's own header.
  comment: '/icons/comment.svg',
  expand: '/icons/expand.svg',
  // A hub with three spokes. Deliberately not a tree glyph: the breakdown tab next to it *is* a
  // tree, and the point of this view is that it is not one.
  graph: '/icons/graph.svg',
  info: '/icons/info.svg',
  minus: '/icons/minus.svg',
  // A box with an arrow leaving it, the near-universal "opens elsewhere" glyph. The Issues table's
  // last column is nothing but this icon, so it carries the whole meaning of the control.
  'open-in-new': '/icons/open-in-new.svg',
  plus: '/icons/plus.svg',
  save: '/icons/save.svg',
  search: '/icons/search.svg',
  trash: '/icons/trash.svg',
  // Collapsed sidenav rail glyphs, one per source family (frontend/CLAUDE.md §9). Stroke-drawn
  // originals from docs/*.svg, recoloured from a hardcoded white to currentColor so they pick up
  // the same ink/blue treatment as every other nav glyph instead of a bespoke colour.
  doors: '/icons/doors.svg',
  jira: '/icons/jira.svg',
  windchill: '/icons/windchill.svg',
  cameo: '/icons/cameo.svg',
  // No source icon was supplied for Access; the Material "shield" glyph stands in for it.
  shield: '/icons/shield.svg',
} as const;

// Use this in a template as <mat-icon svgIcon="gearbox" /> — the name is checked against the map.
export type SecIconName = keyof typeof SEC_ICONS;

export function provideSecIcons(): EnvironmentProviders {
  return provideAppInitializer(() => {
    const registry = inject(MatIconRegistry);
    const sanitizer = inject(DomSanitizer);

    // addSvgIcon only records the URL; the fetch happens the first time an icon is rendered, so
    // this adds nothing to startup.
    for (const [name, path] of Object.entries(SEC_ICONS)) {
      registry.addSvgIcon(name, sanitizer.bypassSecurityTrustResourceUrl(path));
    }
  });
}

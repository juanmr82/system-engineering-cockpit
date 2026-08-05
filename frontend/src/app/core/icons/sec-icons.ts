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
  gearbox: '/icons/gearbox.svg',
  'account-circle': '/icons/account-circle.svg',
  close: '/icons/close.svg',
  info: '/icons/info.svg',
  save: '/icons/save.svg',
  search: '/icons/search.svg',
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

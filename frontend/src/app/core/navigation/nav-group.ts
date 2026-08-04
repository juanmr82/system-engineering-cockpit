export interface NavItem {
  readonly key: string;
  readonly label: string;
  readonly route: string;
}

export interface NavGroup {
  readonly key: string;
  readonly label: string;
  readonly items: NavItem[];
}

// Ship a hardcoded default so a broken config endpoint never produces an app with no
// navigation (CLAUDE.md §9). The backend's application.yaml owns real ordering; this is only
// the last-resort fallback and must be kept in sync with it by hand.
export const DEFAULT_NAV_GROUPS: NavGroup[] = [
  {
    key: 'requirements',
    label: 'Requirements',
    items: [
      { key: 'requirements-statistics', label: 'Statistics', route: '/requirements/statistics' },
      { key: 'requirements-modules', label: 'Modules', route: '/requirements/modules' },
      { key: 'requirements-review', label: 'Req review', route: '/requirements/review' },
    ],
  },
  {
    key: 'documents',
    label: 'Documents',
    items: [{ key: 'documents-windchill', label: 'Windchill', route: '/documents/windchill' }],
  },
  {
    key: 'cameo',
    label: 'CAMEO',
    items: [
      { key: 'cameo-soi-views', label: 'SOI views', route: '/mbse/soi-views' },
      { key: 'cameo-functions', label: 'Functions', route: '/mbse/functions' },
    ],
  },
];

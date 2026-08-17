export interface NavItem {
  readonly key: string;
  readonly label: string;
  readonly route: string;
  /** Overlaid client-side, never carried by the config itself — see `access-badge.service.ts`. */
  readonly badge?: number;
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
    key: 'jira',
    label: 'JIRA',
    items: [
      { key: 'jira-issues', label: 'Issues', route: '/jira/issues' },
      { key: 'jira-kids', label: 'KIDS', route: '/jira/kids' },
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
  // sec-access-manager only (frontend/CLAUDE.md §9) — the sidenav role-filters this group client
  // side; the config itself carries no role field (root CLAUDE.md §9, backend step 9).
  {
    key: 'access',
    label: 'Access',
    items: [
      { key: 'access-categories', label: 'Categories', route: '/access/categories' },
      { key: 'access-grants', label: 'Grants', route: '/access/grants' },
      { key: 'access-containers', label: 'Containers', route: '/access/containers' },
      { key: 'access-unassigned', label: 'Not assigned', route: '/access/unassigned' },
      { key: 'access-defaults', label: 'Defaults', route: '/access/defaults' },
    ],
  },
];

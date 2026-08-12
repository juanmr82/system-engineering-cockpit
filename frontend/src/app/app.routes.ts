import { Routes } from '@angular/router';
import { canLeaveModules } from './features/requirements/modules/modules.guard';
import { canLeaveReview } from './features/requirements/review/review.guard';

// Every route is lazy (loadComponent) and renders inside the shell. Unknown paths render a
// not-found component inside the shell, not a bare page (CLAUDE.md §9).
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./layout/shell/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'requirements/statistics' },
      {
        path: 'requirements/statistics',
        loadComponent: () =>
          import('./features/requirements/statistics/requirements-statistics').then(
            (m) => m.RequirementsStatistics,
          ),
      },
      {
        path: 'requirements/modules',
        loadComponent: () =>
          import('./features/requirements/modules/modules').then((m) => m.Modules),
        // The System level column is editable, so this view owns a buffer that can be navigated
        // away from — the same situation the review table's comments created (R7). The guard file
        // imports the component as a type only, so this does not un-lazy the route.
        canDeactivate: [canLeaveModules],
      },
      {
        path: 'requirements/review',
        loadComponent: () =>
          import('./features/requirements/review/requirement-review').then(
            (m) => m.RequirementReview,
          ),
        // The only guard in the application, and it is scoped to the one view that owns an
        // editable table — never a router-wide guard reading a global store (CLAUDE.md R7). The
        // guard file imports the component as a type only, so this does not un-lazy the route.
        canDeactivate: [canLeaveReview],
      },
      {
        path: 'jira/issues',
        loadComponent: () => import('./features/jira/issues/jira-issues').then((m) => m.JiraIssues),
      },
      {
        path: 'jira/kids',
        loadComponent: () => import('./features/jira/kids/jira-kids').then((m) => m.JiraKids),
      },
      // The settings subtree. One place, so RBAC is one guard on one path rather than a
      // scattering of checks (spec §13.1) — there is no authorization anywhere yet (ADR 0014).
      { path: 'settings', pathMatch: 'full', redirectTo: 'settings/jira' },
      {
        path: 'settings/jira',
        loadComponent: () =>
          import('./features/settings/jira/jira-settings').then((m) => m.JiraSettings),
      },
      {
        path: 'settings/windchill',
        loadComponent: () =>
          import('./features/settings/windchill/windchill-settings').then(
            (m) => m.WindchillSettings,
          ),
      },
      {
        path: 'settings/importers',
        loadComponent: () =>
          import('./features/settings/importers/import-runs').then((m) => m.ImportRuns),
      },
      {
        path: 'documents/windchill',
        loadComponent: () =>
          import('./features/documents/windchill/windchill-documents').then(
            (m) => m.WindchillDocuments,
          ),
      },
      {
        path: 'mbse/soi-views',
        loadComponent: () =>
          import('./features/mbse/soi-views/soi-views').then((m) => m.SoiViews),
      },
      {
        path: 'mbse/functions',
        loadComponent: () =>
          import('./features/mbse/functions/functions').then((m) => m.Functions),
      },
      {
        path: '**',
        loadComponent: () => import('./layout/shell/not-found').then((m) => m.NotFound),
      },
    ],
  },
];

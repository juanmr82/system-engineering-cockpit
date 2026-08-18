import { Routes } from '@angular/router';
import { canLeaveModules } from './features/requirements/modules/modules.guard';

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
        // away from (R7). The guard file imports the component as a type only, so this does not
        // un-lazy the route.
        //
        // The only guard left in the application: the Req review table used to own one too
        // (the R7 batch exception `docs/REQ_REVIEW.md` §9.1 carved out for its Comment column),
        // but every reply now posts as its own request and there is nothing left there to guard
        // against losing (docs/req-review-comment-threads.md §1).
        canDeactivate: [canLeaveModules],
      },
      {
        path: 'requirements/review',
        loadComponent: () =>
          import('./features/requirements/review/requirement-review').then(
            (m) => m.RequirementReview,
          ),
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
        path: 'settings/doors',
        loadComponent: () =>
          import('./features/settings/doors/doors-settings').then((m) => m.DoorsSettings),
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
      // The Access views (spec §10.2). sec-access-manager only — each screen self-checks the
      // role and renders a refusal panel in place rather than redirecting (frontend/CLAUDE.md
      // §8), so there is no canActivate guard here; the sidenav already hides these links from
      // anyone without the role.
      { path: 'access', pathMatch: 'full', redirectTo: 'access/categories' },
      {
        path: 'access/categories',
        loadComponent: () =>
          import('./features/access/categories/access-categories').then((m) => m.AccessCategories),
      },
      {
        path: 'access/grants',
        loadComponent: () => import('./features/access/grants/access-grants').then((m) => m.AccessGrants),
      },
      {
        path: 'access/containers',
        loadComponent: () =>
          import('./features/access/containers/access-containers').then((m) => m.AccessContainers),
      },
      {
        path: 'access/unassigned',
        loadComponent: () =>
          import('./features/access/unassigned/access-unassigned').then((m) => m.AccessUnassigned),
      },
      {
        path: 'access/defaults',
        loadComponent: () => import('./features/access/defaults/access-defaults').then((m) => m.AccessDefaults),
      },
      {
        path: '**',
        loadComponent: () => import('./layout/shell/not-found').then((m) => m.NotFound),
      },
    ],
  },
];

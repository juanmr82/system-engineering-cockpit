import { Routes } from '@angular/router';

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
      },
      {
        path: 'requirements/review',
        loadComponent: () =>
          import('./features/requirements/review/requirement-review').then(
            (m) => m.RequirementReview,
          ),
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

import { Routes } from '@angular/router';
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

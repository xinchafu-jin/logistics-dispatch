import { Routes } from '@angular/router';
import { guestOnlyGuard, requireRole } from './core/auth/auth.guard';
import { Login } from './features/auth/pages/login/login';

export const routes: Routes = [
  {
    path: 'login',
    component: Login,
    canActivate: [guestOnlyGuard],
  },
  {
    path: 'dispatch',
    canActivate: [requireRole('DISPATCHER')],
    loadComponent: () =>
      import('./features/dispatch/layouts/dispatch-shell/dispatch-shell').then(
        (component) => component.DispatchShell,
      ),
    children: [
      {
        path: 'dashboard',
        loadComponent: () =>
          import('./features/dispatch/pages/dispatch-dashboard/dispatch-dashboard').then(
            (component) => component.DispatchDashboard,
          ),
      },
      {
        path: 'orders',
        loadComponent: () =>
          import('./features/dispatch/pages/order-review/order-review').then(
            (component) => component.OrderReview,
          ),
      },
      {
        path: 'resources',
        loadComponent: () =>
          import('./features/dispatch/pages/resource-overview/resource-overview').then(
            (component) => component.ResourceOverview,
          ),
      },
      {
        path: 'fleet',
        loadComponent: () =>
          import('./features/dispatch/pages/fleet-monitor/fleet-monitor').then(
            (component) => component.FleetMonitor,
          ),
      },
      {
        path: 'anomalies',
        loadComponent: () =>
          import('./features/dispatch/pages/anomaly-center/anomaly-center').then(
            (component) => component.AnomalyCenter,
          ),
      },
      {
        path: 'reports',
        loadComponent: () =>
          import('./features/dispatch/pages/operation-report/operation-report').then(
            (component) => component.OperationReport,
          ),
      },
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'dashboard',
      },
    ],
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'login',
  },
  {
    path: '**',
    redirectTo: 'login',
  },
];

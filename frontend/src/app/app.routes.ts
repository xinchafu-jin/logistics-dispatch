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
        data: { title: '今日配送總覽' },
        loadComponent: () =>
          import('./features/dispatch/pages/dispatch-dashboard/dispatch-dashboard').then(
            (component) => component.DispatchDashboard,
          ),
      },
      {
        path: 'orders',
        data: { title: '訂單審核' },
        loadComponent: () =>
          import('./features/dispatch/pages/order-review/order-review').then(
            (component) => component.OrderReview,
          ),
      },
      {
        path: 'resources',
        data: { title: '人車資源' },
        loadComponent: () =>
          import('./features/dispatch/pages/resource-overview/resource-overview').then(
            (component) => component.ResourceOverview,
          ),
      },
      {
        path: 'schedules',
        data: { title: '司機班表' },
        loadComponent: () =>
          import('./features/dispatch/pages/driver-schedule/driver-schedule').then(
            (component) => component.DriverSchedule,
          ),
      },
      {
        path: 'anomalies',
        data: { title: '異常中心' },
        loadComponent: () =>
          import('./features/dispatch/pages/anomaly-center/anomaly-center').then(
            (component) => component.AnomalyCenter,
          ),
      },
      {
        path: 'reports',
        data: { title: '營運報表' },
        loadComponent: () =>
          import('./features/dispatch/pages/operation-report/operation-report').then(
            (component) => component.OperationReport,
          ),
      },
      {
        path: 'history',
        data: { title: '歷史報表' },
        loadComponent: () =>
          import('./features/dispatch/pages/report-history/report-history').then(
            (component) => component.ReportHistory,
          ),
      },
      {
        path: 'profile',
        data: { title: '個人設定' },
        loadComponent: () =>
          import('./features/dispatch/pages/personal-profile/personal-profile').then(
            (component) => component.PersonalProfile,
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

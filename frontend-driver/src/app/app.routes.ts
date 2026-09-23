import { Routes } from '@angular/router';
import { driverOnlyGuard, guestOnlyGuard } from './core/auth/driver-auth.guard';
import { DriverLogin } from './features/login/driver-login';

export const routes: Routes = [
  {
    path: 'login',
    component: DriverLogin,
    canActivate: [guestOnlyGuard],
  },
  {
    path: 'dashboard',
    // 延後載入：dashboard 帶了 Material 月曆、表單等一大包，登入頁用不到，
    // 放在 initial bundle 會超過 angular.json 的 750kB 預算，production build 直接失敗
    loadComponent: () => import('./features/dashboard/driver-dashboard').then(m => m.DriverDashboard),
    canActivate: [driverOnlyGuard],
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

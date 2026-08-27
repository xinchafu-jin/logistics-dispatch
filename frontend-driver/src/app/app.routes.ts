import { Routes } from '@angular/router';
import { driverOnlyGuard, guestOnlyGuard } from './core/auth/driver-auth.guard';
import { DriverDashboard } from './features/dashboard/driver-dashboard';
import { DriverLogin } from './features/login/driver-login';

export const routes: Routes = [
  {
    path: 'login',
    component: DriverLogin,
    canActivate: [guestOnlyGuard],
  },
  {
    path: 'dashboard',
    component: DriverDashboard,
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

import { Routes } from '@angular/router';
import { roleGuard } from './auth/auth.guard';
import { DriverDashboard } from './driver-dashboard/driver-dashboard';
import { Login } from './login/login';
import { ManagerDashboard } from './manager-dashboard/manager-dashboard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'manager/login' },
  { path: 'manager/login', component: Login, data: { role: 'ADMIN' } },
  { path: 'driver/login', component: Login, data: { role: 'DRIVER' } },
  {
    path: 'manager',
    component: ManagerDashboard,
    canActivate: [roleGuard],
    data: { role: 'ADMIN' }
  },
  {
    path: 'driver',
    component: DriverDashboard,
    canActivate: [roleGuard],
    data: { role: 'DRIVER' }
  },
  { path: '**', redirectTo: 'manager/login' }
];

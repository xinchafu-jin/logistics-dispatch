import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { UserRole } from './auth.models';

export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const role = route.data['role'] as UserRole;

  if (auth.hasRole(role)) return true;
  return router.createUrlTree([role === 'DRIVER' ? '/driver/login' : '/manager/login']);
};

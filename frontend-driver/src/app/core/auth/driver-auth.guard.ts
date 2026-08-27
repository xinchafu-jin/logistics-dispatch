import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { DriverAuthService } from './driver-auth.service';

export const driverOnlyGuard: CanActivateFn = () => {
  const authService = inject(DriverAuthService);
  const router = inject(Router);

  if (authService.user()) {
    return true;
  }

  return authService.restoreSession().pipe(
    map((user) => (user ? true : router.createUrlTree(['/login']))),
  );
};

export const guestOnlyGuard: CanActivateFn = () => {
  const authService = inject(DriverAuthService);
  const router = inject(Router);

  return authService.user() ? router.createUrlTree(['/dashboard']) : true;
};

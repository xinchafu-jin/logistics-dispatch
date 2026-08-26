import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from './auth.service';
import { UserRole } from './auth.models';

export const requireRole = (...roles: UserRole[]): CanActivateFn => {
  return (_route, state) => {
    const authService = inject(AuthService);
    const router = inject(Router);
    const decide = (user = authService.user()) => {
      if (!user) {
        return router.createUrlTree(['/login'], {
          queryParams: { returnUrl: state.url },
        });
      }

      if (roles.includes(user.role)) {
        return true;
      }

      return router.createUrlTree([authService.dashboardPath()]);
    };

    return authService.user()
      ? decide()
      : authService.restoreSession().pipe(map((user) => decide(user)));
  };
};

export const guestOnlyGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const router = inject(Router);
  const user = authService.user();

  return user ? router.createUrlTree([authService.dashboardPath()]) : true;
};

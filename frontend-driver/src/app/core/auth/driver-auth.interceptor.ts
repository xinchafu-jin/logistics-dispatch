import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { DriverAuthService } from './driver-auth.service';

export const driverAuthInterceptor: HttpInterceptorFn = (request, next) => {
  const token = inject(DriverAuthService).accessToken();

  if (!token || !request.url.startsWith('/api/')) {
    return next(request);
  }

  return next(
    request.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`,
      },
    }),
  );
};

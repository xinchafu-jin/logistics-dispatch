import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { AuthUser } from './auth.models';
import { AuthService } from './auth.service';
import { requireRole } from './auth.guard';

class AuthServiceStub {
  readonly user = signal<AuthUser | null>(null);

  dashboardPath(role: AuthUser['role']): string {
    return role === 'DRIVER' ? '/driver/dashboard' : '/dispatch/dashboard';
  }
}

describe('requireRole', () => {
  let authService: AuthServiceStub;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useClass: AuthServiceStub }],
    });

    authService = TestBed.inject(AuthService) as unknown as AuthServiceStub;
    router = TestBed.inject(Router);
  });

  it('redirects unauthenticated users to login with the original URL', () => {
    const result = TestBed.runInInjectionContext(() =>
      requireRole('DISPATCHER')({} as never, { url: '/dispatch/reports' } as RouterStateSnapshot),
    );

    expect(result).toBeInstanceOf(UrlTree);
    expect((result as UrlTree).queryParams['returnUrl']).toBe('/dispatch/reports');
  });

  it('blocks a driver from entering the dispatcher workspace', () => {
    authService.user.set({ account: 'driver@jflow.tw', displayName: '司機', role: 'DRIVER' });

    const result = TestBed.runInInjectionContext(() =>
      requireRole('DISPATCHER')({} as never, { url: '/dispatch/dashboard' } as RouterStateSnapshot),
    );

    expect(router.serializeUrl(result as UrlTree)).toBe('/driver/dashboard');
  });
});

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { firstValueFrom, Observable, of } from 'rxjs';
import { AuthUser } from './auth.models';
import { AuthService } from './auth.service';
import { requireRole } from './auth.guard';

class AuthServiceStub {
  readonly user = signal<AuthUser | null>(null);
  readonly restoredUser = signal<AuthUser | null>(null);

  dashboardPath(): string {
    return '/dispatch/dashboard';
  }

  restoreSession() {
    return of(this.restoredUser());
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

  it('redirects unauthenticated users to login with the original URL', async () => {
    const result = TestBed.runInInjectionContext(() =>
      requireRole('DISPATCHER')({} as never, { url: '/dispatch/reports' } as RouterStateSnapshot),
    );
    const resolvedResult = await firstValueFrom(result as Observable<UrlTree | boolean>);

    expect(resolvedResult).toBeInstanceOf(UrlTree);
    expect((resolvedResult as UrlTree).queryParams['returnUrl']).toBe('/dispatch/reports');
  });

  it('allows an authenticated dispatcher into the dispatcher workspace', () => {
    authService.user.set({ account: 'manager', displayName: '物流主管', role: 'DISPATCHER' });

    const result = TestBed.runInInjectionContext(() =>
      requireRole('DISPATCHER')({} as never, { url: '/dispatch/dashboard' } as RouterStateSnapshot),
    );

    expect(result).toBe(true);
  });

  it('restores the stored session before deciding the route', async () => {
    authService.restoredUser.set({
      account: 'manager',
      displayName: '物流主管',
      role: 'DISPATCHER',
    });

    const result = TestBed.runInInjectionContext(() =>
      requireRole('DISPATCHER')({} as never, { url: '/dispatch/dashboard' } as RouterStateSnapshot),
    );

    await expect(firstValueFrom(result as Observable<UrlTree | boolean>)).resolves.toBe(true);
  });
});

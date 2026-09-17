import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(AuthService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    localStorage.removeItem('logistics-dispatch.access-token');
    httpTesting.verify();
  });

  it('logs in an administrator using the backend JWT contract', () => {
    service
      .login({
        account: ' manager ',
        password: 'Manager123!',
      })
      .subscribe();

    const request = httpTesting.expectOne('/api/auth/admin/login');

    expect(request.request.method).toBe('POST');
    expect(request.request.withCredentials).toBe(false);
    expect(request.request.body).toEqual({
      account: 'manager',
      password: 'Manager123!',
    });

    request.flush({
      accessToken: 'admin-token',
      tokenType: 'Bearer',
      expiresAt: '2026-08-19T20:00:00Z',
      role: 'ADMIN',
      userId: 1,
      account: 'manager',
      name: '物流主管',
    });

    expect(service.user()).toEqual({
      account: 'manager',
      displayName: '物流主管',
      role: 'DISPATCHER',
    });
    expect(service.accessToken()).toBe('admin-token');
  });

  it('does not call the backend when no in-memory token exists', () => {
    service.restoreSession().subscribe((user) => expect(user).toBeNull());
  });

  it('verifies an administrator identity before resetting the password', () => {
    service
      .verifyForgottenPassword({
        account: ' manager ',
        phone: ' 0912345678 ',
      })
      .subscribe();
    service
      .resetForgottenPassword({
        account: ' manager ',
        phone: ' 0912345678 ',
        newPassword: 'Password1',
      })
      .subscribe();

    const verifyRequest = httpTesting.expectOne('/api/auth/admin/forgot-password/verify');
    expect(verifyRequest.request.method).toBe('POST');
    expect(verifyRequest.request.body).toEqual({ account: 'manager', phone: '0912345678' });
    verifyRequest.flush(null);

    const resetRequest = httpTesting.expectOne('/api/auth/admin/forgot-password/reset');
    expect(resetRequest.request.method).toBe('POST');
    expect(resetRequest.request.body).toEqual({
      account: 'manager',
      phone: '0912345678',
      newPassword: 'Password1',
    });
    resetRequest.flush(null);
  });

  it('restores the current user with the in-memory token', () => {
    service
      .login({
        account: 'manager',
        password: 'Manager123!',
      })
      .subscribe();

    const loginRequest = httpTesting.expectOne('/api/auth/admin/login');
    loginRequest.flush({
      accessToken: 'admin-token',
      tokenType: 'Bearer',
      expiresAt: '2026-08-19T20:00:00Z',
      role: 'ADMIN',
      userId: 1,
      account: 'manager',
      name: '物流主管',
    });

    service.restoreSession().subscribe((user) => {
      expect(user).toEqual({
        account: 'manager',
        displayName: '物流主管',
        role: 'DISPATCHER',
      });
    });

    const sessionRequest = httpTesting.expectOne('/api/auth/me');
    expect(sessionRequest.request.method).toBe('GET');
    sessionRequest.flush({
      userId: 1,
      account: 'manager',
      name: '物流主管',
      role: 'ADMIN',
    });
  });

  it('clears the current session on logout', () => {
    service
      .login({
        account: 'manager',
        password: 'Manager123!',
      })
      .subscribe();

    httpTesting.expectOne('/api/auth/admin/login').flush({
      accessToken: 'admin-token',
      tokenType: 'Bearer',
      expiresAt: '2026-08-19T20:00:00Z',
      role: 'ADMIN',
      userId: 1,
      account: 'manager',
      name: '物流主管',
    });

    service.logout().subscribe();

    expect(service.user()).toBeNull();
    expect(service.accessToken()).toBeNull();
  });
});

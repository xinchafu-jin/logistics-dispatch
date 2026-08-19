import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
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

  afterEach(() => httpTesting.verify());

  it('logs in an administrator using the backend JWT contract', () => {
    service
      .login({
        account: ' manager ',
        password: 'Manager123!',
        portal: 'ADMIN',
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

  it('uses the driver login endpoint and maps the driver role', () => {
    service
      .login({
        account: 'driver01',
        password: 'Driver123!',
        portal: 'DRIVER',
      })
      .subscribe();

    const request = httpTesting.expectOne('/api/auth/driver/login');

    request.flush({
      accessToken: 'driver-token',
      tokenType: 'Bearer',
      expiresAt: '2026-08-19T20:00:00Z',
      role: 'DRIVER',
      userId: 2,
      account: 'driver01',
      name: '司機一號',
    });

    expect(service.user()).toEqual({
      account: 'driver01',
      displayName: '司機一號',
      role: 'DRIVER',
    });
  });

  it('does not call the backend when no in-memory token exists', () => {
    service.restoreSession().subscribe((user) => expect(user).toBeNull());
  });

  it('restores the current user with the in-memory token', () => {
    service
      .login({
        account: 'manager',
        password: 'Manager123!',
        portal: 'ADMIN',
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
        portal: 'ADMIN',
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

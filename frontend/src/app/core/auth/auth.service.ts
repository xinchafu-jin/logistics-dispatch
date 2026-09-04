import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, map, Observable, of } from 'rxjs';
import {
  AdminPasswordResetRequest,
  AdminPasswordResetVerification,
  AuthUser,
  LoginCredentials,
} from './auth.models';

const AUTH_API = '/api/auth';
const ACCESS_TOKEN_STORAGE_KEY = 'logistics-dispatch.access-token';

interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  role: 'ADMIN';
  userId: number;
  account: string;
  name: string;
}

interface CurrentUserResponse {
  userId: number;
  account: string;
  name: string;
  role: 'ADMIN';
}

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  readonly user = signal<AuthUser | null>(null);

  private readonly http = inject(HttpClient);
  private readonly accessTokenState = signal<string | null>(null);

  readonly accessToken = this.accessTokenState.asReadonly();

  login(credentials: LoginCredentials): Observable<AuthUser> {
    return this.http
      .post<LoginResponse>(`${AUTH_API}/admin/login`, {
        account: credentials.account.trim(),
        password: credentials.password,
      })
      .pipe(
        map((response) => {
          const user = this.parseLoginResponse(response);

          this.accessTokenState.set(response.accessToken);
          this.saveToken(response.accessToken);
          this.user.set(user);

          return user;
        }),
      );
  }

  verifyForgottenPassword(request: AdminPasswordResetVerification): Observable<void> {
    return this.http.post<void>(`${AUTH_API}/admin/forgot-password/verify`, {
      account: request.account.trim(),
      phone: request.phone.trim(),
    });
  }

  resetForgottenPassword(request: AdminPasswordResetRequest): Observable<void> {
    return this.http.post<void>(`${AUTH_API}/admin/forgot-password/reset`, {
      account: request.account.trim(),
      phone: request.phone.trim(),
      newPassword: request.newPassword,
    });
  }

  dashboardPath(): string {
    return '/dispatch/dashboard';
  }

  restoreSession(): Observable<AuthUser | null> {
    const token = this.accessTokenState() ?? this.readStoredToken();

    if (!token) {
      return of(null);
    }

    this.accessTokenState.set(token);

    return this.http.get<CurrentUserResponse>(`${AUTH_API}/me`).pipe(
      map((response) => {
        const user = this.parseCurrentUserResponse(response);
        this.user.set(user);
        return user;
      }),
      catchError(() => {
        this.clearSession();
        return of(null);
      }),
    );
  }

  logout(): Observable<void> {
    this.clearSession();
    return of(void 0);
  }

  private parseLoginResponse(response: LoginResponse): AuthUser {
    if (
      !response ||
      typeof response.accessToken !== 'string' ||
      !response.accessToken ||
      typeof response.account !== 'string' ||
      typeof response.name !== 'string' ||
      response.role !== 'ADMIN'
    ) {
      throw new Error('Invalid authentication response.');
    }

    return {
      account: response.account,
      displayName: response.name,
      role: this.toFrontendRole(),
    };
  }

  private parseCurrentUserResponse(response: CurrentUserResponse): AuthUser {
    if (
      !response ||
      typeof response.account !== 'string' ||
      typeof response.name !== 'string' ||
      response.role !== 'ADMIN'
    ) {
      throw new Error('Invalid current user response.');
    }

    return {
      account: response.account,
      displayName: response.name,
      role: this.toFrontendRole(),
    };
  }

  private toFrontendRole(): 'DISPATCHER' {
    return 'DISPATCHER';
  }

  private clearSession(): void {
    this.accessTokenState.set(null);
    this.user.set(null);
    this.removeStoredToken();
  }

  private saveToken(token: string): void {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, token);
    }
  }

  private readStoredToken(): string | null {
    if (typeof localStorage === 'undefined') {
      return null;
    }

    return localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY);
  }

  private removeStoredToken(): void {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    }
  }
}

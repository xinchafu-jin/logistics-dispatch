import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, map, Observable, of } from 'rxjs';
import { AuthUser, LoginCredentials, LoginPortal, UserRole } from './auth.models';

const AUTH_API = '/api/auth';
const ACCESS_TOKEN_STORAGE_KEY = 'logistics-dispatch.access-token';

interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  role: 'ADMIN' | 'DRIVER';
  userId: number;
  account: string;
  name: string;
}

interface CurrentUserResponse {
  userId: number;
  account: string;
  name: string;
  role: 'ADMIN' | 'DRIVER';
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
    const endpoint =
      credentials.portal === 'ADMIN' ? `${AUTH_API}/admin/login` : `${AUTH_API}/driver/login`;

    return this.http
      .post<LoginResponse>(endpoint, {
        account: credentials.account.trim(),
        password: credentials.password,
      })
      .pipe(
        map((response) => {
          const user = this.parseLoginResponse(response, credentials.portal);

          this.accessTokenState.set(response.accessToken);
          this.saveToken(response.accessToken);
          this.user.set(user);

          return user;
        }),
      );
  }

  dashboardPath(role: UserRole): string {
    const paths: Record<UserRole, string> = {
      DRIVER: '/driver/dashboard',
      DISPATCHER: '/dispatch/dashboard',
    };

    return paths[role];
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

  private parseLoginResponse(response: LoginResponse, portal: LoginPortal): AuthUser {
    const expectedRole = portal === 'ADMIN' ? 'ADMIN' : 'DRIVER';

    if (
      !response ||
      typeof response.accessToken !== 'string' ||
      !response.accessToken ||
      typeof response.account !== 'string' ||
      typeof response.name !== 'string' ||
      response.role !== expectedRole
    ) {
      throw new Error('Invalid authentication response.');
    }

    return {
      account: response.account,
      displayName: response.name,
      role: this.toFrontendRole(response.role),
    };
  }

  private parseCurrentUserResponse(response: CurrentUserResponse): AuthUser {
    if (
      !response ||
      typeof response.account !== 'string' ||
      typeof response.name !== 'string' ||
      (response.role !== 'ADMIN' && response.role !== 'DRIVER')
    ) {
      throw new Error('Invalid current user response.');
    }

    return {
      account: response.account,
      displayName: response.name,
      role: this.toFrontendRole(response.role),
    };
  }

  private toFrontendRole(role: 'ADMIN' | 'DRIVER'): UserRole {
    return role === 'ADMIN' ? 'DISPATCHER' : 'DRIVER';
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

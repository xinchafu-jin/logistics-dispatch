import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, map, Observable, of } from 'rxjs';
import {
  CurrentUserResponse,
  DriverUser,
  LoginResponse,
} from './driver-auth.models';

const ACCESS_TOKEN_STORAGE_KEY = 'logistics-dispatch.driver-access-token';

@Injectable({ providedIn: 'root' })
export class DriverAuthService {
  readonly user = signal<DriverUser | null>(null);

  private readonly http = inject(HttpClient);
  private readonly accessTokenState = signal<string | null>(null);

  readonly accessToken = this.accessTokenState.asReadonly();

  login(account: string, password: string): Observable<DriverUser> {
    return this.http
      .post<LoginResponse>('/api/auth/driver/login', {
        account: account.trim(),
        password,
      })
      .pipe(
        map((response) => {
          const user = this.toDriverUser(response);
          this.accessTokenState.set(response.accessToken);
          this.saveToken(response.accessToken);
          this.user.set(user);
          return user;
        }),
      );
  }

  restoreSession(): Observable<DriverUser | null> {
    const token = this.accessTokenState() ?? this.readStoredToken();
    if (!token) {
      return of(null);
    }

    this.accessTokenState.set(token);

    return this.http.get<CurrentUserResponse>('/api/auth/me').pipe(
      map((response) => {
        const user = this.toDriverUser(response);
        this.user.set(user);
        return user;
      }),
      catchError(() => {
        this.logout();
        return of(null);
      }),
    );
  }

  logout(): void {
    this.accessTokenState.set(null);
    this.user.set(null);

    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY);
    }
  }

  private toDriverUser(response: LoginResponse | CurrentUserResponse): DriverUser {
    if (
      !response ||
      response.role !== 'DRIVER' ||
      typeof response.userId !== 'number' ||
      typeof response.account !== 'string' ||
      typeof response.name !== 'string'
    ) {
      throw new Error('司機帳號驗證失敗。');
    }

    return {
      id: response.userId,
      account: response.account,
      name: response.name,
      role: 'DRIVER',
    };
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
}

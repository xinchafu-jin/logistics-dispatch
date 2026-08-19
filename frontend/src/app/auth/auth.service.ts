import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { AuthUser, LoginRequest, UserRole } from './auth.models';

export const API_BASE_URL = 'http://localhost:8080/api';
const STORAGE_KEY = 'logistics-dispatch-auth';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly userState = signal<AuthUser | null>(this.readStoredUser());

  readonly user = this.userState.asReadonly();
  readonly isLoggedIn = computed(() => this.userState() !== null);

  login(role: UserRole, credentials: LoginRequest): Observable<AuthUser> {
    const endpoint = role === 'ADMIN' ? 'admin/login' : 'driver/login';
    return this.http
      .post<AuthUser>(`${API_BASE_URL}/auth/${endpoint}`, credentials)
      .pipe(tap((user) => this.storeUser(user)));
  }

  token(): string | null {
    return this.userState()?.accessToken ?? null;
  }

  hasRole(role: UserRole): boolean {
    const user = this.userState();
    return user !== null && user.role === role && new Date(user.expiresAt).getTime() > Date.now();
  }

  logout(): void {
    const role = this.userState()?.role;
    this.userState.set(null);
    localStorage.removeItem(STORAGE_KEY);
    void this.router.navigate([role === 'DRIVER' ? '/driver/login' : '/manager/login']);
  }

  private storeUser(user: AuthUser): void {
    this.userState.set(user);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user));
  }

  private readStoredUser(): AuthUser | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      const user = JSON.parse(raw) as AuthUser;
      if (!user.accessToken || new Date(user.expiresAt).getTime() <= Date.now()) {
        localStorage.removeItem(STORAGE_KEY);
        return null;
      }
      return user;
    } catch {
      localStorage.removeItem(STORAGE_KEY);
      return null;
    }
  }
}

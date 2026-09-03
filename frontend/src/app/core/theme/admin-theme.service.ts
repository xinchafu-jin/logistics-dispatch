import { Injectable, signal } from '@angular/core';

type AdminTheme = 'light' | 'dark';

const THEME_STORAGE_KEY = 'logistics-dispatch.admin-theme';

@Injectable({ providedIn: 'root' })
export class AdminThemeService {
  readonly isLightTheme = signal(this.readSavedTheme() === 'light');

  toggle(): void {
    const nextTheme: AdminTheme = this.isLightTheme() ? 'dark' : 'light';
    this.isLightTheme.set(nextTheme === 'light');

    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(THEME_STORAGE_KEY, nextTheme);
    }
  }

  private readSavedTheme(): AdminTheme {
    if (typeof localStorage === 'undefined') {
      return 'dark';
    }

    return localStorage.getItem(THEME_STORAGE_KEY) === 'light' ? 'light' : 'dark';
  }
}

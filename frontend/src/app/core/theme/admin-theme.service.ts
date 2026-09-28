import { Injectable, signal } from '@angular/core';

type AdminTheme = 'light' | 'dark';

const THEME_STORAGE_KEY = 'logistics-dispatch.admin-theme';

@Injectable({ providedIn: 'root' })
export class AdminThemeService {
  readonly isLightTheme = signal(this.readSavedTheme() === 'light');

  /**
   * MatDialog 開在 body 底下、不在 .dispatch-layout 裡，繼承不到後台的深淺色。
   * 開對話框時把這組 class 當 panelClass 帶上，styles.scss 的 .dispatch-dialog 才能替它套同一套色票。
   * 開著時有遮罩擋住切換按鈕，所以開啟當下決定就好，不用跟著切換更新
   */
  dialogPanelClass(): string[] {
    return this.isLightTheme() ? ['dispatch-dialog', 'is-light-theme'] : ['dispatch-dialog'];
  }

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

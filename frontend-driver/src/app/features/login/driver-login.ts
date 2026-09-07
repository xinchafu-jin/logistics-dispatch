import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { LucideMoon, LucideSun, LucideUserRoundPlus, LucideX } from '@lucide/angular';
import { DriverAuthService } from '../../core/auth/driver-auth.service';
import { saveStoredMapLocation } from '../../core/location/driver-map-location.storage';
import { BrandLogo } from '../../shared/ui/brand-logo/brand-logo';

@Component({
  selector: 'app-driver-login',
  imports: [BrandLogo, LucideMoon, LucideSun, LucideUserRoundPlus, LucideX],
  templateUrl: './driver-login.html',
  styleUrl: './driver-login.scss',
})
export class DriverLogin {
  protected readonly account = signal('');
  protected readonly password = signal('');
  protected readonly errorMessage = signal('');
  protected readonly isSubmitting = signal(false);
  protected readonly isLocating = signal(false);
  protected readonly isAccountApplicationOpen = signal(false);
  protected readonly isDarkTheme = signal(this.readSavedTheme() === 'dark');

  private readonly authService = inject(DriverAuthService);
  private readonly router = inject(Router);

  protected submit(event: Event): void {
    event.preventDefault();
    this.errorMessage.set('');

    if (!this.account().trim() || !this.password()) {
      this.errorMessage.set('請輸入司機帳號與密碼。');
      return;
    }

    this.isSubmitting.set(true);
    this.authService.login(this.account(), this.password()).subscribe({
      next: () => {
        this.isLocating.set(true);
        void this.captureLocation().finally(() => {
          void this.router.navigateByUrl('/dashboard').finally(() => {
            this.isLocating.set(false);
            this.isSubmitting.set(false);
          });
        });
      },
      error: (error: unknown) => {
        this.isSubmitting.set(false);
        this.errorMessage.set(this.getErrorMessage(error));
      },
    });
  }

  private captureLocation(): Promise<void> {
    return new Promise((resolve) => {
      if (!navigator.geolocation) {
        resolve();
        return;
      }

      navigator.geolocation.getCurrentPosition(
        (position) => {
          saveStoredMapLocation({
            lat: position.coords.latitude,
            lng: position.coords.longitude,
          });
          resolve();
        },
        () => resolve(),
        { enableHighAccuracy: true, timeout: 8_000, maximumAge: 60_000 },
      );
    });
  }

  protected toggleTheme(): void {
    const nextTheme = this.isDarkTheme() ? 'light' : 'dark';
    this.isDarkTheme.set(nextTheme === 'dark');

    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('logistics-dispatch.driver-theme', nextTheme);
    }
  }

  protected openAccountApplication(): void {
    this.isAccountApplicationOpen.set(true);
  }

  protected closeAccountApplication(): void {
    this.isAccountApplicationOpen.set(false);
  }

  private getErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (typeof error.error?.message === 'string') {
        return error.error.message;
      }

      if (error.message) {
        return error.message;
      }
    }

    if (error instanceof Error && error.message) {
      return error.message;
    }

    return '無法登入，請確認後端服務與帳密後再試。';
  }

  private readSavedTheme(): 'light' | 'dark' {
    if (typeof localStorage === 'undefined') {
      return 'dark';
    }

    return localStorage.getItem('logistics-dispatch.driver-theme') === 'light' ? 'light' : 'dark';
  }
}

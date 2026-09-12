import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { LucideMoon, LucideSun, LucideUserRoundPlus, LucideX } from '@lucide/angular';
import { DriverAuthService } from '../../core/auth/driver-auth.service';
import { saveStoredMapLocation } from '../../core/location/driver-map-location.storage';
import { DriverOperationsService } from '../../core/services/driver-operations.service';
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
  protected readonly applicationAccount = signal('');
  protected readonly applicationName = signal('');
  protected readonly applicationPhone = signal('');
  protected readonly applicationNationalId = signal('');
  protected readonly applicationError = signal('');
  protected readonly applicationMessage = signal('');
  protected readonly isApplicationSubmitting = signal(false);
  protected readonly isDarkTheme = signal(this.readSavedTheme() === 'dark');

  private readonly authService = inject(DriverAuthService);
  private readonly operations = inject(DriverOperationsService);
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
    this.applicationError.set('');
    this.applicationMessage.set('');
    this.isAccountApplicationOpen.set(true);
  }

  protected closeAccountApplication(): void {
    if (!this.isApplicationSubmitting()) {
      this.isAccountApplicationOpen.set(false);
    }
  }

  protected submitAccountApplication(event: Event): void {
    event.preventDefault();
    const account = this.applicationAccount().trim();
    const name = this.applicationName().trim();
    const phone = this.applicationPhone().trim();
    const nationalId = this.applicationNationalId().trim().toUpperCase();

    this.applicationError.set('');
    this.applicationMessage.set('');

    if (!account || !name || !phone || !nationalId) {
      this.applicationError.set('請填寫帳號、姓名、手機與身分證字號。');
      return;
    }

    if (!/^09\d{8}$/.test(phone)) {
      this.applicationError.set('請輸入 09 開頭的 10 位數手機號碼。');
      return;
    }

    if (!/^[A-Z][12]\d{8}$/.test(nationalId)) {
      this.applicationError.set('請輸入正確格式的身分證字號。');
      return;
    }

    this.isApplicationSubmitting.set(true);
    this.operations.submitAccountApplication({ account, name, phone, nationalId }).subscribe({
      next: () => {
        this.applicationAccount.set('');
        this.applicationName.set('');
        this.applicationPhone.set('');
        this.applicationNationalId.set('');
        this.applicationMessage.set('申請已送出，待主管核准後即可使用此帳號登入。');
        this.isApplicationSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.applicationError.set(this.getErrorMessage(error));
        this.isApplicationSubmitting.set(false);
      },
    });
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

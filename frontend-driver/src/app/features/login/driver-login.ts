import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import {NonNullableFormBuilder, ReactiveFormsModule, Validators} from '@angular/forms';
import { Router } from '@angular/router';
import { DriverAuthService } from '../../core/auth/driver-auth.service';
import {DriverOperationsService} from '../../core/services/driver-operations.service';
import { saveStoredMapLocation } from '../../core/location/driver-map-location.storage';
import { BrandLogo } from '../../shared/ui/brand-logo/brand-logo';

@Component({
  selector: 'app-driver-login',
  imports: [BrandLogo, ReactiveFormsModule],
  templateUrl: './driver-login.html',
  styleUrl: './driver-login.scss',
})
export class DriverLogin {
  protected readonly account = signal('');
  protected readonly password = signal('');
  protected readonly errorMessage = signal('');
  protected readonly isSubmitting = signal(false);
  protected readonly isLocating = signal(false);
  protected readonly applicationOpen = signal(false);
  protected readonly applicationMessage = signal('');
  protected readonly applicationError = signal('');
  protected readonly applicationSubmitting = signal(false);

  private readonly authService = inject(DriverAuthService);
  private readonly operations = inject(DriverOperationsService);
  private readonly router = inject(Router);
  private readonly formBuilder = inject(NonNullableFormBuilder);
  protected readonly applicationForm = this.formBuilder.group({
    account: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(60)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    phone: ['', [Validators.required, Validators.maxLength(30)]],
    nationalId: ['', [Validators.required, Validators.minLength(6), Validators.maxLength(30)]],
  });

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

  protected toggleApplication(): void {
    this.applicationOpen.update((open) => !open);
    this.applicationMessage.set('');
    this.applicationError.set('');
  }

  protected submitApplication(): void {
    if (this.applicationSubmitting()) {
      return;
    }
    if (this.applicationForm.invalid) {
      this.applicationForm.markAllAsTouched();
      this.applicationError.set('請完整填寫申請資料。');
      return;
    }

    this.applicationSubmitting.set(true);
    this.applicationError.set('');
    const value = this.applicationForm.getRawValue();
    this.operations.submitAccountApplication(value).subscribe({
      next: () => {
        this.applicationForm.reset();
        this.applicationMessage.set('申請已送出，主管核准後即可使用帳號登入。');
        this.applicationSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.applicationError.set(this.getErrorMessage(error));
        this.applicationSubmitting.set(false);
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

}

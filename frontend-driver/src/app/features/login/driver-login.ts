import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { DriverAuthService } from '../../core/auth/driver-auth.service';
import { BrandLogo } from '../../shared/ui/brand-logo/brand-logo';

@Component({
  selector: 'app-driver-login',
  imports: [BrandLogo],
  templateUrl: './driver-login.html',
  styleUrl: './driver-login.scss',
})
export class DriverLogin {
  protected readonly account = signal('');
  protected readonly password = signal('');
  protected readonly errorMessage = signal('');
  protected readonly isSubmitting = signal(false);

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
        void this.router.navigateByUrl('/dashboard').finally(() => {
          this.isSubmitting.set(false);
        });
      },
      error: (error: unknown) => {
        this.isSubmitting.set(false);
        this.errorMessage.set(this.getErrorMessage(error));
      },
    });
  }

  private getErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse && typeof error.error?.message === 'string') {
      return error.error.message;
    }

    return '無法登入，請確認後端服務與帳密後再試。';
  }
}

import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ElectricGazeVisual } from '../../components/electric-gaze-visual/electric-gaze-visual';
import { BrandLogo } from '../../../../shared/ui/brand-logo/brand-logo';
import { CurrentWeather, WeatherService } from '../../../../core/services/weather.service';
import { AuthService } from '../../../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  imports: [BrandLogo, ElectricGazeVisual],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  protected readonly showLoginForm = signal(true);
  protected readonly account = signal('');
  protected readonly password = signal('');
  protected readonly loginError = signal('');
  protected readonly loginNotice = signal('');
  protected readonly resetStage = signal<'identity' | 'password' | null>(null);
  protected readonly resetAccount = signal('');
  protected readonly resetPhone = signal('');
  protected readonly resetPassword = signal('');
  protected readonly resetPasswordConfirmation = signal('');
  protected readonly resetError = signal('');
  protected readonly isSubmitting = signal(false);

  protected readonly weather = signal<CurrentWeather>({
    city: '高雄',
    condition: '今日配送天氣',
    icon: String.fromCodePoint(0x2601, 0xfe0e),
    temperature: 24,
  });

  private readonly weatherService = inject(WeatherService);
  private readonly authService = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  constructor() {
    this.weatherService.getCurrentWeather().subscribe((weather) => this.weather.set(weather));
  }

  protected openLogin(): void {
    this.showLoginForm.set(true);
  }

  protected openPasswordReset(): void {
    this.loginError.set('');
    this.loginNotice.set('');
    this.resetError.set('');
    this.resetAccount.set(this.account());
    this.resetPhone.set('');
    this.resetPassword.set('');
    this.resetPasswordConfirmation.set('');
    this.resetStage.set('identity');
  }

  protected cancelPasswordReset(): void {
    if (this.isSubmitting()) {
      return;
    }

    this.resetStage.set(null);
    this.resetError.set('');
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    this.loginError.set('');
    this.loginNotice.set('');

    const form = event.currentTarget as HTMLFormElement;
    const account = form.querySelector<HTMLInputElement>('#account')?.value.trim() ?? '';
    const password = form.querySelector<HTMLInputElement>('#password')?.value ?? '';

    this.account.set(account);
    this.password.set(password);

    if (!account || !password) {
      this.loginError.set('請輸入帳號與密碼。');
      return;
    }

    this.isSubmitting.set(true);

    this.authService
      .login({
        account,
        password,
      })
      .subscribe({
        next: () => {
          void this.router
            .navigateByUrl(this.destinationAfterLogin())
            .then((navigated) => {
              this.isSubmitting.set(false);

              if (!navigated) {
                this.loginError.set('無法前往工作區，請稍後再試。');
              }
            })
            .catch(() => {
              this.isSubmitting.set(false);
              this.loginError.set('無法前往工作區，請稍後再試。');
            });
        },
        error: () => {
          this.isSubmitting.set(false);
          this.loginError.set('帳號或密碼不正確，請再次確認。');
        },
      });
  }

  protected verifyPasswordReset(event: Event): void {
    event.preventDefault();
    const account = this.resetAccount().trim();
    const phone = this.resetPhone().trim();
    this.resetError.set('');

    if (!account || !phone) {
      this.resetError.set('請輸入帳號與手機號碼。');
      return;
    }

    this.isSubmitting.set(true);
    this.authService.verifyForgottenPassword({ account, phone }).subscribe({
      next: () => {
        this.resetAccount.set(account);
        this.resetPhone.set(phone);
        this.resetStage.set('password');
        this.isSubmitting.set(false);
      },
      error: () => {
        this.resetError.set('帳號或手機號碼不正確，請再次確認。');
        this.isSubmitting.set(false);
      },
    });
  }

  protected submitPasswordReset(event: Event): void {
    event.preventDefault();
    const newPassword = this.resetPassword();
    this.resetError.set('');

    if (newPassword.length < 8 || newPassword.length > 12) {
      this.resetError.set('新密碼長度必須介於 8 到 12 個字元。');
      return;
    }

    if (newPassword !== this.resetPasswordConfirmation()) {
      this.resetError.set('兩次輸入的新密碼不一致。');
      return;
    }

    this.isSubmitting.set(true);
    this.authService
      .resetForgottenPassword({
        account: this.resetAccount(),
        phone: this.resetPhone(),
        newPassword,
      })
      .subscribe({
        next: () => {
          this.password.set('');
          this.resetPassword.set('');
          this.resetPasswordConfirmation.set('');
          this.resetStage.set(null);
          this.loginNotice.set('密碼已重設，請使用新密碼登入。');
          this.isSubmitting.set(false);
        },
        error: () => {
          this.resetError.set('密碼重設失敗，請重新驗證帳號與手機號碼。');
          this.isSubmitting.set(false);
        },
      });
  }

  private destinationAfterLogin(): string {
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
    if (returnUrl?.startsWith('/') && !returnUrl.startsWith('//')) {
      return returnUrl;
    }

    return this.authService.dashboardPath();
  }
}

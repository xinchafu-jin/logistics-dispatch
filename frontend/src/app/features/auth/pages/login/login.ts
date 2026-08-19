import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ElectricGazeVisual } from '../../components/electric-gaze-visual/electric-gaze-visual';
import { BrandLogo } from '../../../../shared/ui/brand-logo/brand-logo';
import { CurrentWeather, WeatherService } from '../../../../core/services/weather.service';
import { AuthService } from '../../../../core/auth/auth.service';
import { LoginPortal } from '../../../../core/auth/auth.models';

@Component({
  selector: 'app-login',
  imports: [BrandLogo, ElectricGazeVisual],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  protected readonly showLoginForm = signal(false);
  protected readonly account = signal('');
  protected readonly password = signal('');
  protected readonly portal = signal<LoginPortal>('ADMIN');
  protected readonly loginError = signal('');
  protected readonly isSubmitting = signal(false);

  protected readonly weather = signal<CurrentWeather>({
    city: '台南',
    condition: '今日配送天氣',
    icon: String.fromCodePoint(0x2601, 0xfe0e),
    temperature: 24,
  });

  private readonly weatherService = inject(WeatherService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  constructor() {
    this.weatherService
      .getCurrentWeather()
      .subscribe((weather) => this.weather.set(weather));
  }

  protected openLogin(): void {
    this.showLoginForm.set(true);
  }

  protected setPortal(portal: LoginPortal): void {
    this.portal.set(portal);
    this.loginError.set('');
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    this.loginError.set('');

    if (!this.account().trim() || !this.password()) {
      this.loginError.set('請輸入帳號與密碼。');
      return;
    }

    this.isSubmitting.set(true);

    this.authService
      .login({
        account: this.account(),
        password: this.password(),
        portal: this.portal(),
      })
      .subscribe({
        next: (user) => {
          void this.router
            .navigateByUrl(this.authService.dashboardPath(user.role))
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
}

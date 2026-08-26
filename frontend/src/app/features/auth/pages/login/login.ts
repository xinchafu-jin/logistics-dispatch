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
  protected readonly isSubmitting = signal(false);

  protected readonly weather = signal<CurrentWeather>({
    city: '台南',
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

  private destinationAfterLogin(): string {
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
    if (returnUrl?.startsWith('/') && !returnUrl.startsWith('//')) {
      return returnUrl;
    }

    return this.authService.dashboardPath();
  }
}

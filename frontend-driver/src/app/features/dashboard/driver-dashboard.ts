import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { LucideMoon, LucideSun } from '@lucide/angular';
import { DriverAuthService } from '../../core/auth/driver-auth.service';
import { DriverWeather, DriverWeatherService } from '../../core/services/driver-weather.service';
import { BrandLogo } from '../../shared/ui/brand-logo/brand-logo';

@Component({
  selector: 'app-driver-dashboard',
  imports: [BrandLogo, LucideMoon, LucideSun],
  templateUrl: './driver-dashboard.html',
  styleUrl: './driver-dashboard.scss',
})
export class DriverDashboard {
  protected readonly user = inject(DriverAuthService).user;
  protected readonly weather = signal<DriverWeather | null>(null);
  protected readonly weatherUnavailable = signal(false);
  protected readonly isDarkTheme = signal(this.readSavedTheme() === 'dark');

  private readonly authService = inject(DriverAuthService);
  private readonly weatherService = inject(DriverWeatherService);
  private readonly router = inject(Router);

  constructor() {
    this.weatherService.getCurrentWeather().subscribe({
      next: (weather) => this.weather.set(weather),
      error: () => this.weatherUnavailable.set(true),
    });
  }

  protected signOut(): void {
    this.authService.logout();
    void this.router.navigateByUrl('/login');
  }

  protected toggleTheme(): void {
    const nextTheme = this.isDarkTheme() ? 'light' : 'dark';
    this.isDarkTheme.set(nextTheme === 'dark');

    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('logistics-dispatch.driver-theme', nextTheme);
    }
  }

  private readSavedTheme(): 'light' | 'dark' {
    if (typeof localStorage === 'undefined') {
      return 'light';
    }

    return localStorage.getItem('logistics-dispatch.driver-theme') === 'dark'
      ? 'dark'
      : 'light';
  }
}

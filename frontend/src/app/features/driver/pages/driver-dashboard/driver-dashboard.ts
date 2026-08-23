import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import {
  LucideKeyRound,
  LucideLogOut,
  LucideMapPinned,
  LucideNavigation,
  LucidePackageCheck,
  LucidePhone,
  LucideTruck,
} from '@lucide/angular';
import { AuthService } from '../../../../core/auth/auth.service';
import { BrandLogo } from '../../../../shared/ui/brand-logo/brand-logo';

@Component({
  selector: 'app-driver-dashboard',
  imports: [
    RouterLink,
    BrandLogo,
    LucideKeyRound,
    LucideLogOut,
    LucideMapPinned,
    LucideNavigation,
    LucidePackageCheck,
    LucidePhone,
    LucideTruck,
  ],
  templateUrl: './driver-dashboard.html',
  styleUrl: './driver-dashboard.scss',
})
export class DriverDashboard {
  protected readonly user = inject(AuthService).user;
  protected readonly isSigningOut = signal(false);
  protected readonly locationStatus = signal('GPS 功能尚未開放');

  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  signOut(): void {
    if (this.isSigningOut()) {
      return;
    }

    this.isSigningOut.set(true);
    this.authService.logout().subscribe({
      next: () => {
        void this.router.navigateByUrl('/login').finally(() => this.isSigningOut.set(false));
      },
      error: () => this.isSigningOut.set(false),
    });
  }
}

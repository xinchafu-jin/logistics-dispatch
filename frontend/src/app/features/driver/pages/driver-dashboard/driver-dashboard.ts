import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import {
  LucideCircleCheck,
  LucideClock3,
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
    LucideCircleCheck,
    LucideClock3,
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

  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly stops = [
    {
      sequence: '01',
      store: '永康生鮮超市',
      address: '台南市永康區中華路 318 號',
      window: '09:50 - 10:20',
      status: '配送中',
      active: true,
    },
    {
      sequence: '02',
      store: '正強路便利商店',
      address: '台南市永康區正強街 86 號',
      window: '10:30 - 10:50',
      status: '待配送',
      active: false,
    },
    {
      sequence: '03',
      store: '中正北路餐飲供應點',
      address: '台南市永康區中正北路 168 號',
      window: '11:05 - 11:30',
      status: '待配送',
      active: false,
    },
    {
      sequence: '04',
      store: '鹽行冷藏配送點',
      address: '台南市永康區中正南路 529 號',
      window: '11:40 - 12:10',
      status: '待配送',
      active: false,
    },
  ];

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

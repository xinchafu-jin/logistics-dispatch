import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  LucideChartNoAxesCombined,
  LucideClipboardCheck,
  LucideLogOut,
  LucideMapPinned,
  LucideMoon,
  LucideSun,
  LucideTriangleAlert,
  LucideTruck,
  LucideWorkflow,
} from '@lucide/angular';
import { BrandLogo } from '../../../../shared/ui/brand-logo/brand-logo';
import { AuthService } from '../../../../core/auth/auth.service';

@Component({
  selector: 'app-dispatch-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    LucideWorkflow,
    LucideClipboardCheck,
    LucideTruck,
    LucideMapPinned,
    LucideMoon,
    LucideSun,
    LucideLogOut,
    LucideTriangleAlert,
    LucideChartNoAxesCombined,
    BrandLogo,
  ],
  templateUrl: './dispatch-shell.html',
  styleUrl: './dispatch-shell.scss',
})
export class DispatchShell {
  protected readonly user = inject(AuthService).user;
  protected readonly isSigningOut = signal(false);
  protected readonly isLightTheme = signal(this.readSavedTheme() === 'light');

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

  protected toggleTheme(): void {
    const nextTheme = this.isLightTheme() ? 'dark' : 'light';
    this.isLightTheme.set(nextTheme === 'light');

    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('logistics-dispatch.admin-theme', nextTheme);
    }
  }

  private readSavedTheme(): 'light' | 'dark' {
    if (typeof localStorage === 'undefined') {
      return 'dark';
    }

    return localStorage.getItem('logistics-dispatch.admin-theme') === 'light'
      ? 'light'
      : 'dark';
  }
}

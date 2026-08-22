import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  LucideChartNoAxesCombined,
  LucideClipboardCheck,
  LucideMapPinned,
  LucideLogOut,
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

import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  ActivatedRouteSnapshot,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { filter, map } from 'rxjs';
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

  /**
   * 標頭顯示的分頁標題。來源是路由 data（app.routes.ts），各頁不再自己畫標題。
   *
   * 用 data.title 而不是 Angular 內建的 Route.title：後者會連帶改掉瀏覽器分頁標題，
   * 把 index.html 的品牌名「捷流智慧物流」蓋掉。
   */
  protected readonly pageTitle = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.readRouteTitle()),
    ),
    // shell 是在導覽過程中才建立的，第一次 NavigationEnd 會晚於這裡；
    // 但 routerState 在啟用子路由前就已更新，所以初始值直接讀得到，標題不會閃一下空白
    { initialValue: this.readRouteTitle() },
  );

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

  /** 沿著路由樹走到最深一層取標題：標題掛在子路由上，shell 這一層沒有 */
  private readRouteTitle(): string {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let title = '';

    while (route) {
      title = (route.data['title'] as string | undefined) ?? title;
      route = route.firstChild;
    }

    return title;
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

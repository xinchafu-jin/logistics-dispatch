import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import {provideAnimationsAsync} from '@angular/platform-browser/animations/async';
import {MAT_DATE_LOCALE, provideNativeDateAdapter} from '@angular/material/core';
import { firstValueFrom } from 'rxjs';

import { routes } from './app.routes';
import { driverAuthInterceptor } from './core/auth/driver-auth.interceptor';
import { DriverAuthService } from './core/auth/driver-auth.service';
import { provideServiceWorker } from '@angular/service-worker';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideAnimationsAsync(),
    provideHttpClient(withInterceptors([driverAuthInterceptor])),
    provideRouter(routes),
    // Material 月曆（班表）：跟後台一樣用瀏覽器內建 Date、zh-TW 顯示星期與月份
    provideNativeDateAdapter(),
    { provide: MAT_DATE_LOCALE, useValue: 'zh-TW' },
    provideAppInitializer(() => firstValueFrom(inject(DriverAuthService).restoreSession())),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};

import { Injectable, inject, signal } from '@angular/core';
import { DriverOperationsService } from './driver-operations.service';

const GPS_UPLOAD_INTERVAL_MS = 5 * 60 * 1000;

@Injectable({ providedIn: 'root' })
export class DriverGpsTrackingService {
  readonly isTracking = signal(false);
  readonly lastUploadedAt = signal<Date | null>(null);
  readonly locationError = signal<string | null>(null);

  private readonly operations = inject(DriverOperationsService);
  private uploadTimer: ReturnType<typeof setInterval> | null = null;

  start(): void {
    if (this.uploadTimer !== null) {
      return;
    }

    this.isTracking.set(true);
    this.locationError.set(null);
    this.uploadCurrentLocation();
    this.uploadTimer = setInterval(() => this.uploadCurrentLocation(), GPS_UPLOAD_INTERVAL_MS);
  }

  stop(): void {
    if (this.uploadTimer !== null) {
      clearInterval(this.uploadTimer);
      this.uploadTimer = null;
    }

    this.isTracking.set(false);
  }

  private uploadCurrentLocation(): void {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      this.locationError.set('此裝置無法取得定位資訊。');
      return;
    }

    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.operations
          .uploadGps({
            lat: position.coords.latitude,
            lng: position.coords.longitude,
          })
          .subscribe({
            next: () => {
              this.lastUploadedAt.set(new Date());
              this.locationError.set(null);
            },
            error: () => this.locationError.set('定位尚未上傳成功，將於下一輪自動再試。'),
          });
      },
      () => this.locationError.set('請允許瀏覽器存取定位，才能回報目前位置。'),
      {
        enableHighAccuracy: true,
        timeout: 15_000,
        maximumAge: 0,
      },
    );
  }
}

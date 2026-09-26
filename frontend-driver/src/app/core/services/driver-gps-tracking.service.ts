import { Injectable, inject, signal } from '@angular/core';
import { DriverOperationsService } from './driver-operations.service';

/**
 * 回報後端的間隔。後端用「收到的時間」當這筆 GPS 的時間。
 *
 * 不用每秒：後端算 GPS 里程時，每一對相鄰點都要打一次 OSRM，資料也要保留 5 年；
 * 每秒一筆＝每位司機每小時 3,600 筆。10 秒在市區約 110 公尺一段，沿道路累加的里程已經夠準，
 * 後台地圖上的位置也夠新。司機端自己地圖上的藍點不受影響，那邊另外每秒更新。
 */
const GPS_UPLOAD_INTERVAL_MS = 10 * 1000;

/**
 * watch 最新一筆超過這麼久沒更新，上傳前自己再問一次定位。
 * 停著不動時有些瀏覽器不會再通知新位置；直接送舊的那筆，後端會把十幾秒前的位置配上現在的時間。
 */
const STALE_FIX_MS = 15 * 1000;

const LOCATION_DENIED_MESSAGE = '請允許瀏覽器存取定位，才能回報目前位置。';

@Injectable({ providedIn: 'root' })
export class DriverGpsTrackingService {
  readonly isTracking = signal(false);
  readonly lastUploadedAt = signal<Date | null>(null);
  readonly locationError = signal<string | null>(null);

  private readonly operations = inject(DriverOperationsService);
  private uploadTimer: ReturnType<typeof setInterval> | null = null;
  private watchId: number | null = null;
  private latestFix: GeolocationPosition | null = null;
  // 用收到的當下時間判斷新舊，不用 position.timestamp：少數瀏覽器給的時間基準不可靠
  private latestFixReceivedAt = 0;
  // 同一時間只問一次定位、只送一個上傳請求。
  // 以前每秒呼叫一次 getCurrentPosition、逾時 15 秒，GPS 慢的時候請求會一個一個疊起來
  private isRequestingFix = false;
  private isUploading = false;

  start(): void {
    if (this.uploadTimer !== null) {
      return;
    }
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      this.locationError.set('此裝置無法取得定位資訊。');
      return;
    }

    this.isTracking.set(true);
    this.locationError.set(null);
    this.latestFix = null;

    // 持續接收位置、只記最新一筆，上傳交給計時器。
    // 同一頁的多個 watchPosition 由瀏覽器共用同一個定位來源，跟地圖自己的 watch 同時開不會讓 GPS 多做一份工
    this.watchId = navigator.geolocation.watchPosition(
      (position) => {
        const isFirstFix = this.latestFix === null;
        this.keepFix(position);
        // 開始追蹤後的第一筆立刻送，後台不用等 10 秒才看得到司機在哪
        if (isFirstFix) {
          this.upload(position);
        }
      },
      () => this.locationError.set(LOCATION_DENIED_MESSAGE),
      { enableHighAccuracy: true, maximumAge: 0 },
    );
    this.uploadTimer = setInterval(() => this.uploadLatestFix(), GPS_UPLOAD_INTERVAL_MS);
  }

  stop(): void {
    if (this.uploadTimer !== null) {
      clearInterval(this.uploadTimer);
      this.uploadTimer = null;
    }
    if (this.watchId !== null && typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.clearWatch(this.watchId);
    }
    this.watchId = null;
    this.latestFix = null;
    this.isTracking.set(false);
  }

  private keepFix(position: GeolocationPosition): void {
    this.latestFix = position;
    this.latestFixReceivedAt = Date.now();
    this.locationError.set(null);
  }

  /** 計時器每 10 秒呼叫：有夠新的位置就送；太舊（停著不動、瀏覽器沒再通知）就自己問一次再送 */
  private uploadLatestFix(): void {
    const fix = this.latestFix;
    if (fix && Date.now() - this.latestFixReceivedAt <= STALE_FIX_MS) {
      this.upload(fix);
      return;
    }
    if (this.isRequestingFix) {
      return;
    }

    this.isRequestingFix = true;
    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.isRequestingFix = false;
        // 問的期間司機可能已經下班（stop），這時不能再送
        if (!this.isTracking()) {
          return;
        }
        this.keepFix(position);
        this.upload(position);
      },
      () => {
        this.isRequestingFix = false;
        this.locationError.set(LOCATION_DENIED_MESSAGE);
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    );
  }

  private upload(position: GeolocationPosition): void {
    // 上一筆還沒送完（網路慢）就跳過這一輪，下一輪會送更新的位置，不必排隊送過時的
    if (this.isUploading) {
      return;
    }

    this.isUploading = true;
    this.operations
      .uploadGps({
        lat: position.coords.latitude,
        lng: position.coords.longitude,
      })
      .subscribe({
        next: () => {
          this.isUploading = false;
          this.lastUploadedAt.set(new Date());
          this.locationError.set(null);
        },
        error: () => {
          this.isUploading = false;
          this.locationError.set('定位尚未上傳成功，將於下一輪自動再試。');
        },
      });
  }
}

import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { DriverGpsTrackingService } from './driver-gps-tracking.service';
import { DriverOperationsService } from './driver-operations.service';

/** 假的瀏覽器定位：測試自己決定什麼時候給位置 */
class FakeGeolocation {
  watchSuccess: ((position: GeolocationPosition) => void) | null = null;
  pendingCurrent: ((position: GeolocationPosition) => void) | null = null;
  getCurrentPositionCalls = 0;
  clearedWatchIds: number[] = [];

  watchPosition(success: (position: GeolocationPosition) => void): number {
    this.watchSuccess = success;
    return 7;
  }

  clearWatch(id: number): void {
    this.clearedWatchIds.push(id);
  }

  getCurrentPosition(success: (position: GeolocationPosition) => void): void {
    this.getCurrentPositionCalls++;
    this.pendingCurrent = success;
  }
}

function fix(lat: number, lng: number): GeolocationPosition {
  return { coords: { latitude: lat, longitude: lng }, timestamp: Date.now() } as unknown as GeolocationPosition;
}

describe('DriverGpsTrackingService', () => {
  let service: DriverGpsTrackingService;
  let geolocation: FakeGeolocation;
  let uploadGps: ReturnType<typeof vi.fn>;
  let originalGeolocation: PropertyDescriptor | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    geolocation = new FakeGeolocation();
    originalGeolocation = Object.getOwnPropertyDescriptor(navigator, 'geolocation');
    Object.defineProperty(navigator, 'geolocation', { value: geolocation, configurable: true });
    uploadGps = vi.fn(() => of(undefined));

    TestBed.configureTestingModule({
      providers: [{ provide: DriverOperationsService, useValue: { uploadGps } }],
    });
    service = TestBed.inject(DriverGpsTrackingService);
  });

  afterEach(() => {
    service.stop();
    vi.useRealTimers();
    if (originalGeolocation) {
      Object.defineProperty(navigator, 'geolocation', originalGeolocation);
    } else {
      delete (navigator as { geolocation?: unknown }).geolocation;
    }
  });

  it('第一筆位置立刻上傳；之後不是每筆都送，每 10 秒送一次最新的那筆', () => {
    service.start();
    geolocation.watchSuccess!(fix(22.6270, 120.3010));
    expect(uploadGps).toHaveBeenCalledTimes(1);

    // 10 秒內又收到兩筆：只記下來，不送
    vi.advanceTimersByTime(3_000);
    geolocation.watchSuccess!(fix(22.6271, 120.3011));
    vi.advanceTimersByTime(3_000);
    geolocation.watchSuccess!(fix(22.6272, 120.3012));
    expect(uploadGps).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(4_000);
    expect(uploadGps).toHaveBeenCalledTimes(2);
    expect(uploadGps).toHaveBeenLastCalledWith({ lat: 22.6272, lng: 120.3012 });
  });

  it('太久沒有新位置時自己問一次定位；還沒回應前不會再問第二次', () => {
    service.start();
    geolocation.watchSuccess!(fix(22.6270, 120.3010));

    // 10 秒：位置 10 秒前收到，還算新，直接送（停著不動也要讓後台知道司機還在）
    vi.advanceTimersByTime(10_000);
    expect(geolocation.getCurrentPositionCalls).toBe(0);
    expect(uploadGps).toHaveBeenCalledTimes(2);

    // 20 秒、30 秒：位置已經超過 15 秒沒更新，改成自己問；第一次還沒回應，第二輪不能再問
    vi.advanceTimersByTime(20_000);
    expect(geolocation.getCurrentPositionCalls).toBe(1);

    geolocation.pendingCurrent!(fix(22.6280, 120.3020));
    expect(uploadGps).toHaveBeenLastCalledWith({ lat: 22.6280, lng: 120.3020 });
  });

  it('停止後關掉定位監聽，也不再上傳', () => {
    service.start();
    geolocation.watchSuccess!(fix(22.6270, 120.3010));
    service.stop();

    expect(geolocation.clearedWatchIds).toEqual([7]);
    vi.advanceTimersByTime(60_000);
    expect(uploadGps).toHaveBeenCalledTimes(1);
    expect(service.isTracking()).toBe(false);
  });
});

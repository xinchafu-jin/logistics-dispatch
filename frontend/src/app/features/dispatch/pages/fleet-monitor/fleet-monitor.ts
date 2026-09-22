import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import {MatIconModule} from '@angular/material/icon';
import { catchError, forkJoin, of } from 'rxjs';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverDto, GpsPingDto } from '../../../../core/services/dispatch-api.models';
import { LiveFleetMap, MapPoint } from '../../components/live-fleet-map/live-fleet-map';

type FleetFilter = 'all' | 'active' | 'inactive';

interface FleetDriver {
  id: number;
  displayId: string;
  name: string;
  state: string;
  isActive: boolean;
}

function toDateTimeInputValue(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');

  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

@Component({
  selector: 'app-fleet-monitor',
  imports: [MatIconModule, LiveFleetMap],
  templateUrl: './fleet-monitor.html',
  styleUrl: './fleet-monitor.scss',
})
export class FleetMonitor implements OnInit, OnDestroy {
  private readonly api = inject(DispatchApiService);

  readonly activeFilter = signal<FleetFilter>('all');
  readonly drivers = signal<FleetDriver[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly livePings = signal<GpsPingDto[]>([]);
  readonly gpsErrorMessage = signal('');
  readonly gpsUpdatedAt = signal('');
  readonly refreshing = signal(false);
  readonly selectedDriverId = signal<number | null>(null);
  readonly selectedLatestPing = signal<GpsPingDto | null>(null);
  readonly selectedCurrentPing = signal<GpsPingDto | null>(null);
  readonly selectedHistory = signal<GpsPingDto[]>([]);
  readonly detailLoading = signal(false);
  readonly historyLoading = signal(false);
  readonly detailMessage = signal('');
  readonly historyError = signal('');
  readonly hasSearchedHistory = signal(false);
  readonly historyFrom = signal(toDateTimeInputValue(new Date(Date.now() - 24 * 60 * 60 * 1000)));
  readonly historyTo = signal(toDateTimeInputValue(new Date()));
  private refreshTimer?: number;

  readonly visibleDrivers = computed(() => {
    const filter = this.activeFilter();
    return filter === 'all'
      ? this.drivers()
      : this.drivers().filter((driver) =>
          filter === 'active' ? driver.isActive : !driver.isActive,
        );
  });
  readonly activeCount = computed(() => this.drivers().filter((driver) => driver.isActive).length);
  readonly inactiveCount = computed(
    () => this.drivers().filter((driver) => !driver.isActive).length,
  );
  readonly mapDrivers = computed<MapPoint[]>(() => {
    const driversById = new Map(this.drivers().map((driver) => [driver.id, driver]));

    return this.livePings().map((ping) => ({
      id: ping.driverId,
      label: driversById.get(ping.driverId)?.name ?? `司機 #${ping.driverId}`,
      detail: `${this.formatTimestamp(ping.timestamp)} 回報`,
      lat: ping.lat,
      lng: ping.lng,
    }));
  });
  readonly gpsState = computed(() => {
    if (this.gpsErrorMessage()) {
      return this.gpsErrorMessage();
    }

    return this.livePings().length
      ? `${this.livePings().length} 位司機正在回傳定位`
      : '目前沒有有效定位回傳';
  });
  readonly selectedDriver = computed(
    () => this.drivers().find((driver) => driver.id === this.selectedDriverId()) ?? null,
  );

  readonly filters: { id: FleetFilter; label: string }[] = [
    { id: 'all', label: '全部司機' },
    { id: 'active', label: '可排班' },
    { id: 'inactive', label: '未啟用' },
  ];

  ngOnInit(): void {
    this.loadDrivers();
    this.loadLiveFleet();
    this.refreshTimer = window.setInterval(() => this.loadLiveFleet(), 30_000);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== undefined) {
      window.clearInterval(this.refreshTimer);
    }
  }

  setFilter(filter: FleetFilter): void {
    this.activeFilter.set(filter);
  }

  refresh(): void {
    this.refreshing.set(true);
    this.loadDrivers();
    this.loadLiveFleet(() => this.refreshing.set(false));
    this.loadSelectedDriverPositions();
  }

  selectDriver(driver: FleetDriver): void {
    this.selectedDriverId.set(driver.id);
    this.selectedLatestPing.set(null);
    this.selectedCurrentPing.set(null);
    this.selectedHistory.set([]);
    this.detailMessage.set('');
    this.historyError.set('');
    this.hasSearchedHistory.set(false);
    this.loadSelectedDriverPositions();
  }

  refreshSelectedDriver(): void {
    this.loadSelectedDriverPositions();
  }

  updateHistoryRange(bound: 'from' | 'to', event: Event): void {
    const value = (event.target as HTMLInputElement).value;

    if (bound === 'from') {
      this.historyFrom.set(value);
    } else {
      this.historyTo.set(value);
    }
  }

  searchHistory(): void {
    const driverId = this.selectedDriverId();
    const from = this.historyFrom();
    const to = this.historyTo();

    if (driverId === null || !from || !to) {
      this.historyError.set('請先選擇司機並設定完整的查詢區間。');
      return;
    }

    if (from > to) {
      this.historyError.set('開始時間不可晚於結束時間。');
      return;
    }

    this.historyLoading.set(true);
    this.historyError.set('');
    this.hasSearchedHistory.set(false);

    this.api.getFleetDriverHistory(driverId, from, to).subscribe({
      next: (pings) => {
        if (this.selectedDriverId() === driverId) {
          this.selectedHistory.set(pings);
          this.hasSearchedHistory.set(true);
          this.historyLoading.set(false);
        }
      },
      error: () => {
        if (this.selectedDriverId() === driverId) {
          this.selectedHistory.set([]);
          this.historyError.set('查詢軌跡失敗，請確認查詢區間與登入狀態。');
          this.historyLoading.set(false);
        }
      },
    });
  }

  private loadDrivers(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.api.getDrivers().subscribe({
      next: (drivers) => {
        const pingsByDriverId = new Map(this.livePings().map((ping) => [ping.driverId, ping]));
        this.drivers.set(drivers.map((driver) => this.toFleetDriver(driver, pingsByDriverId.get(driver.id ?? 0))));
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('暫時無法載入司機資料，請稍後再試。');
        this.loading.set(false);
      },
    });
  }

  private loadLiveFleet(onComplete?: () => void): void {
    this.gpsErrorMessage.set('');

    this.api.getLiveFleet().subscribe({
      next: (pings) => {
        this.livePings.set(pings);
        this.gpsUpdatedAt.set(this.currentTime());
        this.updateDriverGpsStates(pings);
        onComplete?.();
      },
      error: () => {
        this.livePings.set([]);
        this.gpsErrorMessage.set('暫時無法取得即時定位，請稍後再試。');
        onComplete?.();
      },
    });
  }

  private loadSelectedDriverPositions(): void {
    const driverId = this.selectedDriverId();

    if (driverId === null) {
      return;
    }

    this.detailLoading.set(true);
    this.detailMessage.set('');

    forkJoin({
      latest: this.api.getFleetDriverLatest(driverId).pipe(catchError(() => of(null))),
      current: this.api.getFleetDriverCurrent(driverId).pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ latest, current }) => {
        if (this.selectedDriverId() !== driverId) {
          return;
        }

        this.selectedLatestPing.set(latest);
        this.selectedCurrentPing.set(current);
        this.detailLoading.set(false);

        if (!latest && !current) {
          this.detailMessage.set('目前沒有這位司機的定位回傳資料。');
        } else if (!current) {
          this.detailMessage.set('已有最後回傳位置，但目前不符合有效定位條件。');
        }
      },
    });
  }

  private updateDriverGpsStates(pings: GpsPingDto[]): void {
    const pingsByDriverId = new Map(pings.map((ping) => [ping.driverId, ping]));
    this.drivers.update((drivers) =>
      drivers.map((driver) => this.withGpsState(driver, pingsByDriverId.get(driver.id))),
    );
  }

  private toFleetDriver(driver: DriverDto, ping?: GpsPingDto): FleetDriver {
    return {
      id: driver.id ?? 0,
      displayId: driver.id ? `DR-${String(driver.id).padStart(3, '0')}` : driver.account,
      name: driver.name,
      state: this.driverGpsState(driver.isActive, ping),
      isActive: driver.isActive,
    };
  }

  private withGpsState(driver: FleetDriver, ping?: GpsPingDto): FleetDriver {
    return {...driver, state: this.driverGpsState(driver.isActive, ping)};
  }

  private driverGpsState(isActive: boolean, ping?: GpsPingDto): string {
    if (!isActive) {
      return '帳號未啟用';
    }

    return ping
      ? `定位於 ${this.formatTimestamp(ping.timestamp)}`
      : '尚未上班或尚未取得有效定位';
  }

  formatTimestamp(value: string): string {
    return value.replace('T', ' ').slice(0, 16);
  }

  formatCoordinate(ping: GpsPingDto | null): string {
    return ping ? `${ping.lat.toFixed(5)}, ${ping.lng.toFixed(5)}` : '--';
  }

  private currentTime(): string {
    return new Intl.DateTimeFormat('zh-TW', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date());
  }
}

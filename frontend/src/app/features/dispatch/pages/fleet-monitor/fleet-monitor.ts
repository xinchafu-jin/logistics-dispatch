import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { LiveFleetMap } from '../../components/live-fleet-map/live-fleet-map';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverDto } from '../../../../core/services/dispatch-api.models';
import {
  LucideClock3,
  LucideMapPinned,
  LucideTriangleAlert,
  LucideUserRound,
} from '@lucide/angular';

type FleetFilter = 'all' | 'active' | 'inactive';

interface FleetVehicle {
  driver: string;
  driverId: string;
  vehicle: string;
  status: string;
  filter: Exclude<FleetFilter, 'all'>;
  locationStatus: string;
}

@Component({
  selector: 'app-fleet-monitor',
  imports: [LiveFleetMap, LucideClock3, LucideMapPinned, LucideTriangleAlert, LucideUserRound],
  templateUrl: './fleet-monitor.html',
  styleUrl: './fleet-monitor.scss',
})
export class FleetMonitor implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly activeFilter = signal<FleetFilter>('all');
  readonly selectedVehicleId = signal('');
  readonly vehicles = signal<FleetVehicle[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly updatedAt = signal('--:--');

  readonly filters: { id: FleetFilter; label: string }[] = [
    { id: 'all', label: '全部司機' },
    { id: 'active', label: '可排班' },
    { id: 'inactive', label: '未啟用' },
  ];

  readonly visibleVehicles = computed(() => {
    const filter = this.activeFilter();
    return filter === 'all'
      ? this.vehicles()
      : this.vehicles().filter((vehicle) => vehicle.filter === filter);
  });

  readonly selectedVehicle = computed(
    () =>
      this.vehicles().find((vehicle) => vehicle.driverId === this.selectedVehicleId()) ??
      this.vehicles()[0] ??
      null,
  );

  readonly activeDriverCount = computed(
    () => this.vehicles().filter((vehicle) => vehicle.filter === 'active').length,
  );
  readonly inactiveCount = computed(
    () => this.vehicles().filter((vehicle) => vehicle.filter === 'inactive').length,
  );

  ngOnInit(): void {
    this.loadFleet();
  }

  setFilter(filter: FleetFilter): void {
    this.activeFilter.set(filter);
    this.selectedVehicleId.set(this.visibleVehicles()[0]?.driverId ?? '');
  }

  selectVehicle(driverId: string): void {
    this.selectedVehicleId.set(driverId);
  }

  private loadFleet(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.api.getDrivers().subscribe({
      next: (drivers) => {
        this.vehicles.set(drivers.map((driver) => this.toFleetVehicle(driver)));
        this.selectedVehicleId.set(this.vehicles()[0]?.driverId ?? '');
        this.updatedAt.set(this.formatCurrentTime());
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('無法取得司機資料，請確認後端服務與登入狀態。');
        this.loading.set(false);
      },
    });
  }

  private toFleetVehicle(driver: DriverDto): FleetVehicle {
    const backendId = driver.id ?? 0;
    const driverId = backendId ? `DR-${String(backendId).padStart(3, '0')}` : driver.account;

    if (!driver.isActive) {
      return {
        driver: driver.name,
        driverId,
        vehicle: '尚未提供車輛',
        status: '司機未啟用',
        filter: 'inactive',
        locationStatus: 'GPS API 尚未提供',
      };
    }

    return {
      driver: driver.name,
      driverId,
      vehicle: '尚未提供車輛',
      status: '可排班',
      filter: 'active',
      locationStatus: 'GPS API 尚未提供',
    };
  }

  private formatCurrentTime(): string {
    return new Intl.DateTimeFormat('zh-TW', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date());
  }
}

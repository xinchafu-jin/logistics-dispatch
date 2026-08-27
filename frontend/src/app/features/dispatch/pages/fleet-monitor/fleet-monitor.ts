import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { LucideMapPinned, LucideTriangleAlert, LucideUserRound } from '@lucide/angular';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverDto } from '../../../../core/services/dispatch-api.models';
import { LiveFleetMap } from '../../components/live-fleet-map/live-fleet-map';

type FleetFilter = 'all' | 'active' | 'inactive';

interface FleetDriver {
  id: string;
  name: string;
  state: string;
  isActive: boolean;
}

@Component({
  selector: 'app-fleet-monitor',
  imports: [LiveFleetMap, LucideMapPinned, LucideTriangleAlert, LucideUserRound],
  templateUrl: './fleet-monitor.html',
  styleUrl: './fleet-monitor.scss',
})
export class FleetMonitor implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly activeFilter = signal<FleetFilter>('all');
  readonly drivers = signal<FleetDriver[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');

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

  readonly filters: { id: FleetFilter; label: string }[] = [
    { id: 'all', label: '全部司機' },
    { id: 'active', label: '可排班' },
    { id: 'inactive', label: '未啟用' },
  ];

  ngOnInit(): void {
    this.loadDrivers();
  }

  setFilter(filter: FleetFilter): void {
    this.activeFilter.set(filter);
  }

  private loadDrivers(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    this.api.getDrivers().subscribe({
      next: (drivers) => {
        this.drivers.set(drivers.map((driver) => this.toFleetDriver(driver)));
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('無法取得司機資料，請確認後端服務與登入狀態。');
        this.loading.set(false);
      },
    });
  }

  private toFleetDriver(driver: DriverDto): FleetDriver {
    const id = driver.id ? `DR-${String(driver.id).padStart(3, '0')}` : driver.account;
    return {
      id,
      name: driver.name,
      state: driver.isActive ? '可排班，等待 GPS API 回傳位置' : '帳號未啟用',
      isActive: driver.isActive,
    };
  }
}

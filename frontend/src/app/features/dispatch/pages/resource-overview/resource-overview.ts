import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { LucideSearch, LucideTriangleAlert, LucideTruck } from '@lucide/angular';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverDto, VehicleDto } from '../../../../core/services/dispatch-api.models';

type ResourceView = 'drivers' | 'vehicles';
type DriverResourceStatus = '可排班' | '停職';
type VehicleResourceStatus = '待派車' | '保養排程' | '已退役';

interface DriverResource {
  id: string;
  name: string;
  license: string;
  status: DriverResourceStatus;
  vehicle: string;
  assignment: string;
  hours: string;
}

interface VehicleResource {
  id: string;
  type: string;
  capacity: string;
  status: VehicleResourceStatus;
  driver: string;
  assignment: string;
  inspection: string;
}

@Component({
  selector: 'app-resource-overview',
  imports: [LucideSearch, LucideTriangleAlert, LucideTruck],
  templateUrl: './resource-overview.html',
  styleUrl: './resource-overview.scss',
})
export class ResourceOverview implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly activeView = signal<ResourceView>('drivers');
  readonly activeFilter = signal('all');
  readonly searchTerm = signal('');
  readonly drivers = signal<DriverDto[]>([]);
  readonly vehicles = signal<VehicleDto[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly updatedAt = signal('--:--');

  readonly driverFilters = ['all', '可排班', '停職'];
  readonly vehicleFilters = ['all', '待派車', '保養排程', '已退役'];

  readonly visibleDrivers = computed<DriverResource[]>(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.drivers()
      .map((driver) => this.toDriverResource(driver))
      .filter((driver) => {
        const matchesFilter = filter === 'all' || driver.status === filter;
        const source =
          `${driver.id} ${driver.name} ${driver.vehicle} ${driver.assignment}`.toLowerCase();
        return matchesFilter && (!term || source.includes(term));
      });
  });

  readonly visibleVehicles = computed<VehicleResource[]>(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.vehicles()
      .map((vehicle) => this.toVehicleResource(vehicle))
      .filter((vehicle) => {
        const matchesFilter = filter === 'all' || vehicle.status === filter;
        const source =
          `${vehicle.id} ${vehicle.type} ${vehicle.driver} ${vehicle.assignment}`.toLowerCase();
        return matchesFilter && (!term || source.includes(term));
      });
  });

  readonly currentFilters = computed(() =>
    this.activeView() === 'drivers' ? this.driverFilters : this.vehicleFilters,
  );

  readonly activeDriverCount = computed(
    () => this.drivers().filter((driver) => driver.isActive).length,
  );
  readonly availableVehicleCount = computed(
    () => this.vehicles().filter((vehicle) => vehicle.status === 'AVAILABLE').length,
  );
  readonly attentionResourceCount = computed(
    () =>
      this.drivers().filter((driver) => !driver.isActive).length +
      this.vehicles().filter((vehicle) => vehicle.status === 'MAINTENANCE').length,
  );

  ngOnInit(): void {
    this.loadResources();
  }

  setView(view: ResourceView): void {
    this.activeView.set(view);
    this.activeFilter.set('all');
    this.searchTerm.set('');
  }

  setFilter(filter: string): void {
    this.activeFilter.set(filter);
  }

  updateSearch(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
  }

  private loadResources(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    forkJoin({
      drivers: this.api.getDrivers(),
      vehicles: this.api.getVehicles(),
    }).subscribe({
      next: ({ drivers, vehicles }) => {
        this.drivers.set(drivers);
        this.vehicles.set(vehicles);
        this.updatedAt.set(this.formatCurrentTime());
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('無法取得司機與車輛資料，請確認後端服務是否正在執行。');
        this.loading.set(false);
      },
    });
  }

  private toDriverResource(driver: DriverDto): DriverResource {
    const id = driver.id === undefined ? driver.account : `DR-${String(driver.id).padStart(3, '0')}`;
    return {
      id,
      name: driver.name,
      license: `帳號 ${driver.account}`,
      status: driver.isActive ? '可排班' : '停職',
      vehicle: '未提供',
      assignment: '尚未提供配送任務',
      hours: `${this.formatTime(driver.workStart)} - ${this.formatTime(driver.workEnd)}`,
    };
  }

  private toVehicleResource(vehicle: VehicleDto): VehicleResource {
    return {
      id: vehicle.plateNumber,
      type: vehicle.vehicleType || '未設定車型',
      capacity: `${vehicle.capacity} 箱容量`,
      status: this.toVehicleStatus(vehicle.status),
      driver: '未提供',
      assignment: '尚未提供配送任務',
      inspection:
        vehicle.status === 'MAINTENANCE'
          ? '目前標記為保養'
          : vehicle.fuelConsumption === undefined
            ? '尚未提供油耗資料'
            : `平均油耗 ${vehicle.fuelConsumption}`,
    };
  }

  private toVehicleStatus(status: VehicleDto['status']): VehicleResourceStatus {
    if (status === 'MAINTENANCE') {
      return '保養排程';
    }
    if (status === 'RETIRED') {
      return '已退役';
    }
    return '待派車';
  }

  private formatTime(value: string): string {
    return value?.slice(0, 5) || '--:--';
  }

  private formatCurrentTime(): string {
    return new Intl.DateTimeFormat('zh-TW', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date());
  }
}

import { Component, computed, signal } from '@angular/core';
import { LucideClock3, LucideMapPinned, LucideRoute, LucideTriangleAlert } from '@lucide/angular';
import { LiveFleetMap } from '../../components/live-fleet-map/live-fleet-map';

type FleetFilter = 'all' | 'delivering' | 'stopped' | 'delayed';

interface FleetVehicle {
  driver: string;
  driverId: string;
  vehicle: string;
  route: string;
  nextStop: string;
  status: string;
  filter: Exclude<FleetFilter, 'all'>;
  lastSeen: string;
  eta: string;
}

@Component({
  selector: 'app-fleet-monitor',
  imports: [LiveFleetMap, LucideClock3, LucideMapPinned, LucideRoute, LucideTriangleAlert],
  templateUrl: './fleet-monitor.html',
  styleUrl: './fleet-monitor.scss',
})
export class FleetMonitor {
  readonly activeFilter = signal<FleetFilter>('all');
  readonly selectedVehicleId = signal('DR-017');

  readonly filters: { id: FleetFilter; label: string }[] = [
    { id: 'all', label: '全部車隊' },
    { id: 'delivering', label: '配送中' },
    { id: 'stopped', label: '短暫停靠' },
    { id: 'delayed', label: '定位延遲' },
  ];

  readonly vehicles: FleetVehicle[] = [
    {
      driver: '陳志明',
      driverId: 'DR-017',
      vehicle: 'KLD-205',
      route: '永康區 02 線',
      nextStop: '中正北路 168 號',
      status: '配送中',
      filter: 'delivering',
      lastSeen: '8 秒前',
      eta: '預計 09:42 抵達',
    },
    {
      driver: '林柏安',
      driverId: 'DR-024',
      vehicle: 'KLD-118',
      route: '東區 01 線',
      nextStop: '崇德路 721 號',
      status: '配送中',
      filter: 'delivering',
      lastSeen: '18 秒前',
      eta: '預計 09:55 抵達',
    },
    {
      driver: '王雅雯',
      driverId: 'DR-031',
      vehicle: 'KLD-308',
      route: '中西區 01 線',
      nextStop: '民生路二段 138 號',
      status: '短暫停靠',
      filter: 'stopped',
      lastSeen: '31 秒前',
      eta: '完成簽收中',
    },
    {
      driver: '黃信翔',
      driverId: 'DR-044',
      vehicle: 'KLD-412',
      route: '安平區 03 線',
      nextStop: '健康三街 221 號',
      status: '定位延遲',
      filter: 'delayed',
      lastSeen: '12 分鐘前',
      eta: '請確認車機狀態',
    },
  ];

  readonly visibleVehicles = computed(() => {
    const filter = this.activeFilter();
    return filter === 'all'
      ? this.vehicles
      : this.vehicles.filter((vehicle) => vehicle.filter === filter);
  });

  readonly selectedVehicle = computed(
    () =>
      this.vehicles.find((vehicle) => vehicle.driverId === this.selectedVehicleId()) ??
      this.vehicles[0],
  );

  setFilter(filter: FleetFilter): void {
    this.activeFilter.set(filter);
    const firstVisible = this.visibleVehicles()[0];
    if (firstVisible) {
      this.selectedVehicleId.set(firstVisible.driverId);
    }
  }

  selectVehicle(driverId: string): void {
    this.selectedVehicleId.set(driverId);
  }
}

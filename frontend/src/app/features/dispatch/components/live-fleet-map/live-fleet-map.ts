import {
  AfterViewInit,
  Component,
  computed,
  inject,
  OnDestroy,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import * as L from 'leaflet';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverDto } from '../../../../core/services/dispatch-api.models';

interface FleetDriver {
  backendId: number;
  id: string;
  name: string;
  vehicle: string;
  status: 'GPS API 尚未提供';
  lastSeen: string;
  tone: 'delayed';
}

@Component({
  selector: 'app-live-fleet-map',
  imports: [],
  templateUrl: './live-fleet-map.html',
  styleUrl: './live-fleet-map.scss',
  encapsulation: ViewEncapsulation.None,
})
export class LiveFleetMap implements AfterViewInit, OnDestroy {
  readonly drivers = signal<FleetDriver[]>([]);
  readonly selectedDriverId = signal<string | null>(null);
  readonly selectedDriver = computed(() =>
    this.drivers().find((driver) => driver.id === this.selectedDriverId()),
  );
  readonly liveDriverCount = computed(() => 0);
  readonly unreportedDriverCount = computed(() => this.drivers().length);

  private readonly api = inject(DispatchApiService);
  private map?: L.Map;

  ngAfterViewInit(): void {
    this.map = L.map('fleet-map-canvas', {
      attributionControl: false,
      zoomControl: false,
      preferCanvas: true,
    }).setView([23.006, 120.219], 13);

    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      attribution: '&copy; OpenStreetMap contributors &copy; CARTO',
      maxZoom: 19,
      subdomains: 'abcd',
    }).addTo(this.map);

    L.control.zoom({ position: 'bottomright' }).addTo(this.map);
    this.refreshFleet();

    requestAnimationFrame(() => this.map?.invalidateSize());
  }

  ngOnDestroy(): void {
    this.map?.remove();
  }

  selectDriver(id: string): void {
    this.selectedDriverId.set(id);
  }

  private refreshFleet(): void {
    this.api.getDrivers().subscribe({
      next: (drivers) => {
        this.drivers.set(drivers.map((driver) => this.toFleetDriver(driver)));
        this.selectedDriverId.set(this.drivers()[0]?.id ?? null);
      },
      error: () => {
        this.drivers.set([]);
        this.selectedDriverId.set(null);
      },
    });
  }

  private toFleetDriver(driver: DriverDto): FleetDriver {
    const backendId = driver.id ?? 0;
    return {
      backendId,
      id: backendId ? `DR-${String(backendId).padStart(3, '0')}` : driver.account,
      name: driver.name,
      vehicle: '尚未指派車輛',
      status: 'GPS API 尚未提供',
      lastSeen: '尚無後端定位資料',
      tone: 'delayed',
    };
  }
}

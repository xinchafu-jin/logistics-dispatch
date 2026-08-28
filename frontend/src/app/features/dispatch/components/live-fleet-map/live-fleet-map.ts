import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import * as L from 'leaflet';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverDto } from '../../../../core/services/dispatch-api.models';

interface FleetDriver {
  id: string;
  name: string;
  isActive: boolean;
}

@Component({
  selector: 'app-live-fleet-map',
  imports: [],
  templateUrl: './live-fleet-map.html',
  styleUrl: './live-fleet-map.scss',
})
export class LiveFleetMap implements AfterViewInit, OnDestroy {
  @ViewChild('mapCanvas') private readonly mapCanvas?: ElementRef<HTMLDivElement>;
  readonly drivers = signal<FleetDriver[]>([]);
  readonly selectedDriverId = signal<string | null>(null);
  readonly selectedDriver = computed(() =>
    this.drivers().find((driver) => driver.id === this.selectedDriverId()),
  );
  readonly activeDriverCount = computed(
    () => this.drivers().filter((driver) => driver.isActive).length,
  );

  private readonly api = inject(DispatchApiService);
  private map?: L.Map;
  private resizeObserver?: ResizeObserver;

  ngAfterViewInit(): void {
    const canvas = this.mapCanvas?.nativeElement;
    if (!canvas) {
      return;
    }

    this.map = L.map(canvas, {
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

    this.resizeObserver = new ResizeObserver(() => this.map?.invalidateSize());
    this.resizeObserver.observe(canvas);
    requestAnimationFrame(() => this.map?.invalidateSize());
    this.loadDrivers();
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.map?.remove();
  }

  selectDriver(id: string): void {
    this.selectedDriverId.set(id);
  }

  private loadDrivers(): void {
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
    const id = driver.id ? `DR-${String(driver.id).padStart(3, '0')}` : driver.account;
    return { id, name: driver.name, isActive: driver.isActive };
  }
}

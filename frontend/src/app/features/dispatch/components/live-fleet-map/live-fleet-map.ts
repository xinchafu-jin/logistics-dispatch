import { AfterViewInit, Component, OnDestroy, ViewEncapsulation } from '@angular/core';
import * as L from 'leaflet';

interface FleetDriver {
  id: string;
  name: string;
  vehicle: string;
  status: '配送中' | '短暫停靠' | '定位延遲';
  lastSeen: string;
  tone: 'active' | 'paused' | 'delayed';
  path: [number, number][];
}

@Component({
  selector: 'app-live-fleet-map',
  imports: [],
  templateUrl: './live-fleet-map.html',
  styleUrl: './live-fleet-map.scss',
  encapsulation: ViewEncapsulation.None,
})
export class LiveFleetMap implements AfterViewInit, OnDestroy {
  private readonly drivers: FleetDriver[] = [
    {
      id: 'DR-017',
      name: '陳志明',
      vehicle: 'KLD-205',
      status: '配送中',
      lastSeen: '8 秒前',
      tone: 'active',
      path: [
        [23.0043, 120.212],
        [23.0065, 120.217],
        [23.0102, 120.222],
        [23.0134, 120.228],
        [23.016, 120.233],
      ],
    },
    {
      id: 'DR-024',
      name: '林柏安',
      vehicle: 'KLD-118',
      status: '配送中',
      lastSeen: '18 秒前',
      tone: 'active',
      path: [
        [22.993, 120.1985],
        [22.9956, 120.204],
        [22.9985, 120.21],
        [23.0009, 120.215],
        [23.0037, 120.219],
      ],
    },
    {
      id: 'DR-031',
      name: '王雅雯',
      vehicle: 'KLD-308',
      status: '短暫停靠',
      lastSeen: '31 秒前',
      tone: 'paused',
      path: [
        [23.0252, 120.218],
        [23.0265, 120.222],
        [23.0275, 120.226],
        [23.0275, 120.226],
        [23.0275, 120.226],
      ],
    },
    {
      id: 'DR-044',
      name: '黃信翔',
      vehicle: 'KLD-412',
      status: '定位延遲',
      lastSeen: '12 分鐘前',
      tone: 'delayed',
      path: [
        [22.983, 120.22],
        [22.985, 120.224],
        [22.987, 120.228],
        [22.989, 120.232],
        [22.991, 120.236],
      ],
    },
  ];

  private readonly markers = new Map<string, L.Marker>();
  private map?: L.Map;
  private locationTimer?: number;
  private routeStep = 0;

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

    this.drivers.forEach((driver) => this.addDriver(driver));
    this.drivers.forEach((driver) => this.drawRoute(driver));

    requestAnimationFrame(() => this.map?.invalidateSize());
    this.locationTimer = window.setInterval(() => this.moveFleet(), 30000);
  }

  ngOnDestroy(): void {
    if (this.locationTimer) {
      window.clearInterval(this.locationTimer);
    }

    this.map?.remove();
  }

  private addDriver(driver: FleetDriver): void {
    if (!this.map) {
      return;
    }

    const marker = L.marker(driver.path[0], {
      alt: `${driver.id} ${driver.name}，${driver.status}`,
      icon: this.createDriverIcon(driver),
      title: `${driver.id} ${driver.name}`,
    }).addTo(this.map);

    marker.bindPopup(this.createPopup(driver), {
      closeButton: false,
      offset: [0, -15],
      minWidth: 196,
    });

    this.markers.set(driver.id, marker);
  }

  private drawRoute(driver: FleetDriver): void {
    if (!this.map) {
      return;
    }

    L.polyline(driver.path, {
      color: driver.tone === 'delayed' ? '#c7834d' : '#63d1c6',
      dashArray: driver.tone === 'paused' ? '2 8' : '5 8',
      opacity: driver.tone === 'delayed' ? 0.35 : 0.48,
      weight: 1.5,
    }).addTo(this.map);
  }

  private moveFleet(): void {
    this.routeStep = (this.routeStep + 1) % 5;

    this.drivers.forEach((driver, index) => {
      const marker = this.markers.get(driver.id);
      const nextPoint = driver.path[(this.routeStep + index) % driver.path.length];

      marker?.setLatLng(nextPoint);
    });
  }

  private createDriverIcon(driver: FleetDriver): L.DivIcon {
    const tone = ['active', 'paused', 'delayed'].includes(driver.tone) ? driver.tone : 'delayed';

    return L.divIcon({
      className: 'jflow-fleet-marker',
      html: `
        <span class="fleet-marker fleet-marker--${tone}">
          <span class="fleet-marker__pulse"></span>
          <span class="fleet-marker__core">${this.escapeHtml(driver.id.slice(-2))}</span>
        </span>
      `,
      iconAnchor: [19, 19],
      iconSize: [38, 38],
    });
  }

  private createPopup(driver: FleetDriver): string {
    return `
      <article class="fleet-popup">
        <p class="fleet-popup__eyebrow">${this.escapeHtml(driver.status)}</p>
        <strong>${this.escapeHtml(driver.id)} · ${this.escapeHtml(driver.vehicle)}</strong>
        <span>${this.escapeHtml(driver.name)} · 最後定位 ${this.escapeHtml(driver.lastSeen)}</span>
      </article>
    `;
  }

  private escapeHtml(value: string): string {
    return value.replace(/[&<>'"]/g, (character) => {
      const entities: Record<string, string> = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        "'": '&#39;',
        '"': '&quot;',
      };

      return entities[character];
    });
  }
}

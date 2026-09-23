import {Component, computed, signal, input} from '@angular/core';
import {MapPoint} from '../live-fleet-map/live-fleet-map';

@Component({
  selector: 'app-fleet-tracking-panel',
  templateUrl: './fleet-tracking-panel.html',
  styleUrl: './fleet-tracking-panel.scss',
})
export class FleetTrackingPanel {
  readonly driverPoints = input<MapPoint[]>([]);
  readonly selectedDriverId = signal<number | null>(null);
  readonly selectedDriverPoint = computed(() => {
    const points = this.driverPoints();
    const selected = points.find((point) => point.id === this.selectedDriverId());
    return selected ?? points[0] ?? null;
  });

  selectDriver(id: number): void {
    this.selectedDriverId.set(id);
  }

}

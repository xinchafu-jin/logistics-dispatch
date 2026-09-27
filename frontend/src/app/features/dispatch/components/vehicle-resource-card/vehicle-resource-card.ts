import { Component, input, output } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { VehicleMaintenanceSummary } from '../../../../core/services/dispatch-api.models';
import { VehicleMaintenancePanel } from '../vehicle-maintenance-panel/vehicle-maintenance-panel';
import { VehicleMaintenanceNotice } from '../vehicle-maintenance-notice/vehicle-maintenance-notice';

export interface VehicleResource {
  maintenance?: VehicleMaintenanceSummary | null;
  backendId?: number;
  id: string;
  type: string;
  capacity: string;
  status: '待派車' | '維修中' | '小保中' | '大保中' | '已退役';
  driver: string;
  assignment: string;
  inspection: string;
}

@Component({
  selector: 'app-vehicle-resource-card',
  imports: [DatePipe, DecimalPipe, MatIconModule, VehicleMaintenancePanel, VehicleMaintenanceNotice],
  templateUrl: './vehicle-resource-card.html',
  styleUrl: './vehicle-resource-card.scss',
})
export class VehicleResourceCard {
  readonly vehicle = input.required<VehicleResource>();
  readonly editVehicle = output<void>();
  readonly deleteVehicle = output<void>();
}

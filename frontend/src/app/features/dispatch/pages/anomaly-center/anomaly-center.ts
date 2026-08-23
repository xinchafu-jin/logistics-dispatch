import { Component } from '@angular/core';
import { LucideMapPinned, LucideTriangleAlert } from '@lucide/angular';

@Component({
  selector: 'app-anomaly-center',
  imports: [LucideMapPinned, LucideTriangleAlert],
  templateUrl: './anomaly-center.html',
  styleUrl: './anomaly-center.scss',
})
export class AnomalyCenter {}

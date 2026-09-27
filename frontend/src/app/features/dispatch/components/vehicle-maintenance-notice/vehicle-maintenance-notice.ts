import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { VehicleMaintenanceSummary } from '../../../../core/services/dispatch-api.models';

@Component({
  selector: 'app-vehicle-maintenance-notice',
  imports: [MatIconModule],
  template: `
    @if (summary(); as m) {
      @if (m.decision !== 'NORMAL') {
        <div class="notice" [class.compact]="compact()" [class.warning]="m.decision === 'WARNING'" role="status">
          <strong><mat-icon aria-hidden="true">{{ m.decision === 'WARNING' ? 'warning_amber' : 'block' }}</mat-icon>{{ m.decision === 'BLOCKED' ? '禁止出車' : m.decision === 'UNKNOWN' ? '資料不足，暫不可派車' : '提前保養提醒' }}</strong>
          <p>{{ m.reasons.join('；') }}</p>
        </div>
      }
    }
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .notice { padding: 11px 12px; color: var(--danger, #ee9797); background: color-mix(in srgb, var(--danger, #ee9797) 6%, transparent); border: 1px solid currentColor; border-radius: 6px; font-size: 12px; line-height: 1.65; }
    .warning { color: var(--warning, #e8b957); background: color-mix(in srgb, var(--warning, #e8b957) 6%, transparent); }
    strong { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 650; }
    mat-icon { flex: 0 0 auto; width: 16px; height: 16px; font-size: 16px; }
    p { margin: 5px 0 0; overflow-wrap: anywhere; }
    .compact { border: 0; border-left: 3px solid currentColor; border-radius: 4px; }
  `,
})
export class VehicleMaintenanceNotice {
  readonly summary = input<VehicleMaintenanceSummary | null | undefined>();
  readonly compact = input(false);
}

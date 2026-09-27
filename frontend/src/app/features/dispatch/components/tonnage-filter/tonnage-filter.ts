import { Component, inject, input, output } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatSelectModule } from '@angular/material/select';
import { AdminThemeService } from '../../../../core/theme/admin-theme.service';

export type TonnageSelection = 'all' | number;

@Component({
  selector: 'app-tonnage-filter',
  imports: [DecimalPipe, MatSelectModule],
  template: `
    <mat-select class="tonnage-filter" aria-label="車輛噸位"
      [value]="value()" (selectionChange)="valueChange.emit($event.value)"
      [panelClass]="['tonnage-filter-panel', theme.isLightTheme() ? 'tonnage-filter-panel-light' : 'tonnage-filter-panel-dark']">
      <mat-select-trigger>
        <span class="tonnage-trigger">
          <span class="tonnage-label">車輛噸位</span>
          <span class="tonnage-value">
            @if (value() === 'all') {全部噸位} @else { {{ value() | number:'1.0-2' }} 噸 }
          </span>
        </span>
      </mat-select-trigger>
      <mat-option value="all">全部噸位</mat-option>
      @for (tonnage of tonnages(); track tonnage) {
        <mat-option [value]="tonnage">{{ tonnage | number:'1.0-2' }} 噸</mat-option>
      }
    </mat-select>
  `,
  styles: `
    :host { display: inline-block; max-width: 100%; }
    .tonnage-filter {
      --mat-select-enabled-trigger-text-color: var(--text);
      --mat-select-enabled-arrow-color: var(--muted);
      --mat-select-focused-arrow-color: var(--accent-text);
      --mat-select-trigger-text-font: inherit;
      --mat-select-trigger-text-size: 12px;
      --mat-select-trigger-text-line-height: 20px;
      --mat-select-trigger-text-weight: 500;
      --mat-select-trigger-text-tracking: normal;
      display: inline-flex; align-items: center; width: 194px; max-width: 100%; min-height: 38px;
      background: var(--surface); border: 1px solid var(--border);
      border-radius: 6px; cursor: pointer;
    }
    .tonnage-filter:hover { border-color: color-mix(in srgb, var(--accent) 45%, var(--border)); }
    .tonnage-filter:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    .tonnage-trigger { display: flex; align-items: center; gap: 10px; }
    .tonnage-label { color: var(--muted); white-space: nowrap; }
    .tonnage-value { color: var(--text); font-weight: 600; white-space: nowrap; }
  `,
})
export class TonnageFilter {
  protected readonly theme = inject(AdminThemeService);
  readonly tonnages = input<number[]>([]);
  readonly value = input<TonnageSelection>('all');
  readonly valueChange = output<TonnageSelection>();
}

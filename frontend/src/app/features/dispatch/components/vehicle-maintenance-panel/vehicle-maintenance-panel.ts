import { Component, input } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { VehicleMaintenanceSummary } from '../../../../core/services/dispatch-api.models';
import { VehicleMaintenanceNotice } from '../vehicle-maintenance-notice/vehicle-maintenance-notice';

@Component({
  selector: 'app-vehicle-maintenance-panel',
  imports: [DecimalPipe, VehicleMaintenanceNotice],
  host: { '[class.resource-panel]': "layout() === 'resource'" },
  template: `
    @if (summary(); as m) {
      <section class="mileage" [class.resource-layout]="layout() === 'resource'" [class.warning]="m.decision === 'WARNING'" [class.blocked]="m.decision === 'BLOCKED' || m.decision === 'UNKNOWN'" aria-label="車輛保養里程">
        <section class="actual" aria-label="目前實際紀錄">
          <header class="section-heading">
            <h3>{{ layout() === 'resource' ? '保養與汰換剩餘里程' : '目前實際紀錄' }}</h3>
            @if (layout() !== 'resource') {
              <span class="source actual-source">已記錄</span>
            }
          </header>
          <dl class="values">
            @if (layout() !== 'resource') {
              <div><dt>實際總里程</dt><dd>{{ km(m.currentOdometerKm) }}</dd></div>
            }
            <div><dt>距離小保</dt><dd>{{ km(m.minorRemainingKm) }}</dd></div>
            <div><dt>距離大保</dt><dd>{{ km(m.majorRemainingKm) }}</dd></div>
            <div><dt>距離汰換上限</dt><dd>{{ km(m.retirementRemainingKm) }}</dd></div>
          </dl>
          @if (showCounts()) {
            <p class="history">已完成：小保 <b>{{ m.minorCount }}</b> 次 · 大保 <b>{{ m.majorCount }}</b> 次 · 維修 <b>{{ m.repairCount }}</b> 次</p>
          }
        </section>
        @if (m.plannedKm !== null) {
          <section class="projection" aria-label="本趟完成後預估">
            <header class="section-heading">
              <h3>本趟完成後（預估）</h3>
              <span class="source projected-source">OSRM</span>
            </header>
            <p class="trip-distance"><span>預估行駛（含回程）</span><b>{{ m.plannedKm | number:'1.1-1' }} km</b></p>
            <dl class="projected-values">
              <div><dt>距離小保</dt><dd>{{ km(m.projectedMinorKm, true) }}</dd></div>
              <div><dt>距離大保</dt><dd>{{ km(m.projectedMajorKm, true) }}</dd></div>
              <div><dt>距離汰換上限</dt><dd>{{ km(m.projectedRetirementKm, true) }}</dd></div>
            </dl>
            @if (showEstimateNote()) {
              <p class="estimate-note">僅供派車預檢；收車後以實際里程更新。</p>
            }
          </section>
        }
        @if (showNotice() && m.decision !== 'NORMAL') {
          <app-vehicle-maintenance-notice [summary]="m" />
        }
      </section>
    } @else {
      <p class="missing">保養資料待載入</p>
    }
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .mileage { padding: 12px 0 0; font-size: 12px; line-height: 1.5; border-top: 1px solid var(--border, #ffffff18); }
    .actual, .projection { min-width: 0; padding: 12px; border: 1px solid var(--border, #ffffff18); border-radius: 6px; }
    .actual { background: color-mix(in srgb, var(--accent, #61d4c3) 4%, transparent); }
    .projection { margin-top: 10px; background: color-mix(in srgb, #7fa9dd 6%, transparent); border-style: dashed; }
    .section-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
    h3 { margin: 0; color: var(--text, #e0e9e8); font-size: 12px; font-weight: 600; }
    .source { flex: 0 0 auto; padding: 2px 7px; border-radius: 4px; font-size: 10px; }
    .actual-source { color: var(--accent-text, var(--accent, #61d4c3)); background: color-mix(in srgb, var(--accent, #61d4c3) 12%, transparent); }
    .projected-source { color: var(--text, #e0e9e8); background: color-mix(in srgb, #7fa9dd 15%, transparent); }
    dl, dd, p { margin: 0; }
    dt, p { color: var(--muted, #99abab); }
    dd, b { color: var(--text, #e0e9e8); font-weight: 600; font-variant-numeric: tabular-nums; }
    dt, dd, p { overflow-wrap: anywhere; }
    .values { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; }
    .values dd { margin-top: 3px; font-size: 14px; }
    .history { margin-top: 10px; padding-top: 8px; border-top: 1px solid var(--border, #ffffff18); font-size: 11px; }
    .trip-distance, .projected-values > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, auto); align-items: baseline; gap: 8px; }
    .trip-distance { padding-bottom: 8px; border-bottom: 1px solid var(--border, #ffffff18); }
    .trip-distance b { font-size: 14px; text-align: right; }
    .projected-values { display: grid; gap: 6px; margin-top: 8px; }
    .projected-values dd { text-align: right; }
    .estimate-note { margin-top: 9px; font-size: 11px; }
    app-vehicle-maintenance-notice { margin-top: 10px; }
    .missing { color: var(--muted, #99abab); font-size: 12px; }
    :host(.resource-panel), .resource-layout, .resource-layout .actual { display: grid; grid-row: span 4; grid-template-rows: subgrid; }
    .resource-layout { padding: 0; border-top: 0; }
    .resource-layout .actual { padding: 0; border: 0; border-radius: 0; background: transparent; }
    .resource-layout .section-heading { align-items: start; align-content: start; margin: 0; }
    .resource-layout h3 { color: var(--text, #e0e9e8); font-size: 13px; line-height: 20px; font-weight: 600; }
    .resource-layout .values { grid-row: 2 / span 3; grid-template-columns: minmax(0, 1fr); grid-template-rows: subgrid; gap: 0; }
    .resource-layout .values > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, auto); align-content: start; align-items: baseline; gap: 10px; min-width: 0; padding: 12px 0; border-top: 1px solid var(--border, #ffffff18); }
    .resource-layout .values dt { font-size: 13px; line-height: 28px; }
    .resource-layout .values dd { margin: 0; text-align: right; font-size: 20px; font-weight: 650; line-height: 28px; letter-spacing: -0.02em; }
    @media (max-width: 480px) {
      :host(.resource-panel), .resource-layout, .resource-layout .actual { display: block; }
      .resource-layout .section-heading { margin-bottom: 12px; }
      .resource-layout .values { grid-template-rows: none; }
    }
  `,
})
export class VehicleMaintenancePanel {
  readonly summary = input<VehicleMaintenanceSummary | null | undefined>();
  readonly showCounts = input(true);
  readonly showEstimateNote = input(true);
  readonly showNotice = input(true);
  readonly layout = input<'card' | 'resource'>('card');
  km(value: number | null, projected = false): string {
    if (value === null) return '待補資料';
    return value < 0 ? `${projected ? '預估超過' : '已超過'} ${(-value).toLocaleString('zh-TW', { maximumFractionDigits: 1 })} km`
      : `${value.toLocaleString('zh-TW', { maximumFractionDigits: 1 })} km`;
  }
}

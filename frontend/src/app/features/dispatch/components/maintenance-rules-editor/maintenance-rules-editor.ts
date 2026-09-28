import { AfterViewInit, Component, ElementRef, inject, input, output, OnInit, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { VehicleMaintenanceRule, VehicleMaintenanceRules } from '../../../../core/services/dispatch-api.models';

@Component({
  selector: 'app-maintenance-rules-editor',
  imports: [FormsModule],
  template: `
    <dialog #rulesDialog aria-labelledby="maintenance-rules-title" (cancel)="$event.preventDefault(); close()">
    <div class="backdrop" (click)="close()">
      <section (click)="$event.stopPropagation()">
        <header><h2 id="maintenance-rules-title">保養與退役規則</h2><button type="button" (click)="close()" [disabled]="saving()" aria-label="關閉">×</button></header>
        <p>同噸位車輛共用規則，儲存後所有車輛與拖曳看板立即重算。退役是總里程終點，不會因保養重設。</p>
        @if (loading()) { <p>載入規則中…</p> } @else {
          <form (ngSubmit)="save()">
            <label class="warning">提前提醒公里數（全車共用）
              <input type="number" name="warningKm" min="0" step="1" required [(ngModel)]="rules.warningKm" />
            </label>
            <small>預估跑完後剩 0～{{ rules.warningKm }} km 提醒；任何一項剩負數即禁止出車，不是允許超過 {{ rules.warningKm }} km。可只填某個噸位，三項公里數全部留白的列不會套用。</small>
            <div class="table-wrap">
              <table><thead><tr><th>噸位</th><th>小保間隔 km</th><th>大保間隔 km</th><th>退役總里程 km</th></tr></thead>
              <tbody>@for (rule of rules.policies; track $index) {
                <tr>
                  <td><input type="number" [name]="'tonnage'+$index" min="0.01" max="9999.99" step="0.01" required [(ngModel)]="rule.tonnage" /></td>
                  <td><input type="number" [name]="'minor'+$index" min="1" step="1" required [(ngModel)]="rule.minorIntervalKm" /></td>
                  <td><input type="number" [name]="'major'+$index" min="1" step="1" required [(ngModel)]="rule.majorIntervalKm" /></td>
                  <td><input type="number" [name]="'retirement'+$index" min="1" step="1" required [(ngModel)]="rule.retirementKm" /></td>
                </tr>
              }</tbody></table>
            </div>
            <button type="button" (click)="add()" [disabled]="saving()">＋ 新增噸位規則</button>
            @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
            <footer><button type="button" (click)="close()" [disabled]="saving()">取消</button><button type="submit" class="primary" [disabled]="saving()">{{ saving() ? '儲存中…' : '儲存並同步全部同噸位車輛' }}</button></footer>
          </form>
        }
      </section>
    </div>
    </dialog>
  `,
  styles: `
    dialog { position: fixed; inset: 0; width: 100vw; height: 100dvh; max-width: none; max-height: none; margin: 0; padding: 0; border: 0; background: transparent; }
    dialog::backdrop { background: transparent; }
    .backdrop { position: fixed; inset: 0; z-index: 1000; background: #000a; display: grid; place-items: center; padding: 20px; }
    section { width: min(860px, 100%); max-height: 90vh; overflow: auto; background: #151a1b; color: var(--text); padding: 24px; border: 1px solid var(--border); border-radius: 14px; box-sizing: border-box; }
    :host-context(.dispatch-layout.is-light-theme) section { background: #fff; }
    header, footer { display: flex; gap: 12px; justify-content: space-between; align-items: center; } h2 { font-size: 21px; margin: 0; }
    p, small { color: var(--muted); line-height: 1.6; } small { display: block; margin: 12px 0; }
    .warning { display: grid; gap: 8px; max-width: 300px; color: var(--field-label); } input { width: 100%; box-sizing: border-box; color: var(--text); background: var(--input-bg); border: 1px solid var(--border); border-radius: 5px; padding: 10px; }
    input:focus-visible, button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    .table-wrap { overflow-x: auto; } table { width: 100%; min-width: 540px; border-collapse: collapse; margin: 14px 0; } th { text-align: left; font-size: 12px; color: var(--field-label); } td, th { padding: 7px; } td:first-child { width: 18%; }
    button { color: var(--text); background: transparent; border: 1px solid var(--border); border-radius: 6px; padding: 10px 14px; cursor: pointer; } button:disabled { opacity: .5; cursor: wait; }
    footer { justify-content: flex-end; margin-top: 20px; flex-wrap: wrap; } .primary { background: var(--accent); border-color: var(--accent); color: var(--button-text); } .primary:hover:not(:disabled) { background: var(--accent-hover); border-color: var(--accent-hover); } .error { color: var(--danger); }
  `,
})
export class MaintenanceRulesEditor implements OnInit, AfterViewInit {
  private readonly api = inject(DispatchApiService);
  readonly tonnages = input<number[]>([]);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('rulesDialog');
  readonly closed = output<void>();
  readonly saved = output<void>();
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  rules: VehicleMaintenanceRules = { warningKm: 500, policies: [] };
  ngAfterViewInit(): void { this.dialog().nativeElement.showModal(); }
  ngOnInit(): void {
    this.api.getMaintenanceRules().subscribe({
      next: rules => {
        this.rules = rules;
        for (const tonnage of this.tonnages()) {
          if (!this.rules.policies.some(p => p.tonnage === tonnage)) this.rules.policies.push(this.blank(tonnage));
        }
        this.loading.set(false);
      },
      error: () => { this.error.set('規則載入失敗，請關閉後重試。'); this.loading.set(false); },
    });
  }
  private blank(tonnage: number): VehicleMaintenanceRule {
    // 初始值留白；不擅自替主管設定各噸位的真實保養週期。
    return { tonnage, minorIntervalKm: null!, majorIntervalKm: null!, retirementKm: null! };
  }
  add(): void { this.rules.policies.push(this.blank(null!)); }
  close(): void { if (!this.saving()) this.closed.emit(); }
  save(): void {
    if (this.saving()) return;
    const configured = this.rules.policies.filter(p => p.minorIntervalKm != null || p.majorIntervalKm != null || p.retirementKm != null);
    if (!Number.isInteger(this.rules.warningKm) || this.rules.warningKm < 0 || configured.some(p =>
      !p.tonnage || !Number.isInteger(p.minorIntervalKm) || p.minorIntervalKm <= 0 ||
      !Number.isInteger(p.majorIntervalKm) || p.majorIntervalKm < p.minorIntervalKm ||
      !Number.isInteger(p.retirementKm) || p.retirementKm <= 0)) {
      this.error.set('請填完整規則；公里數須為正整數，大保間隔不可小於小保。'); return;
    }
    if (new Set(configured.map(p => p.tonnage)).size !== configured.length) {
      this.error.set('同一噸位不能重複設定。'); return;
    }
    this.saving.set(true); this.error.set('');
    this.api.saveMaintenanceRules({ warningKm: this.rules.warningKm, policies: configured }).subscribe({
      next: () => { this.saving.set(false); this.saved.emit(); },
      error: (e: HttpErrorResponse) => { this.error.set(e.error?.message || '規則儲存失敗。'); this.saving.set(false); },
    });
  }
}

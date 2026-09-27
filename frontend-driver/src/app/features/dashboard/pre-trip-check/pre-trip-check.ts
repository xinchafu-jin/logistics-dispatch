import {Component, OnDestroy, computed, effect, inject, input, output, signal, untracked} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {HttpErrorResponse} from '@angular/common/http';
import {Subscription} from 'rxjs';
import {DriverOperationsService} from '../../../core/services/driver-operations.service';
import {DriverRouteTask, PreTripCheckKey, PreTripInspectionResult} from '../../../core/services/driver-operations.models';

type PhotoKind = 'alcohol' | 'vehicle' | 'dashcam';
const emptyChecks = (): Record<PreTripCheckKey, boolean> => ({alcoholTested: false, headlights: false, taillights: false,
  turnSignals: false, brakeLights: false, frontLeftTire: false, frontRightTire: false, rearLeftTire: false, rearRightTire: false, dashcam: false});

@Component({selector: 'app-pre-trip-check', imports: [FormsModule], templateUrl: './pre-trip-check.html', styleUrl: './pre-trip-check.scss'})
export class PreTripCheck implements OnDestroy {
  readonly route = input.required<DriverRouteTask>();
  readonly passedChange = output<boolean>();
  private readonly api = inject(DriverOperationsService);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly result = signal<PreTripInspectionResult | null>(null);
  protected readonly error = signal('');
  protected readonly alcohol = signal('');
  protected readonly checks = signal(emptyChecks());
  protected readonly files = signal<Partial<Record<PhotoKind, File>>>({});
  protected readonly previews = signal<Partial<Record<PhotoKind, string>>>({});
  protected readonly lightChecks: {key: PreTripCheckKey; label: string}[] = [
    {key: 'headlights', label: '頭燈'}, {key: 'taillights', label: '尾燈'}, {key: 'turnSignals', label: '方向燈'}, {key: 'brakeLights', label: '煞車燈'}];
  protected readonly tireChecks: {key: PreTripCheckKey; label: string}[] = [
    {key: 'frontLeftTire', label: '左前輪'}, {key: 'frontRightTire', label: '右前輪'}, {key: 'rearLeftTire', label: '左後輪'}, {key: 'rearRightTire', label: '右後輪'}];
  protected readonly photoKinds: {key: PhotoKind; label: string}[] = [{key: 'alcohol', label: '酒測結果'}, {key: 'vehicle', label: '車輛檢點'}, {key: 'dashcam', label: '行車紀錄器'}];
  protected readonly canSubmit = computed(() => !this.loading() && !this.saving() && !this.error()
    && /^\d(?:\.\d{1,2})?$/.test(this.alcohol()) && Object.values(this.checks()).every(Boolean)
    && this.photoKinds.every((photo) => !!this.files()[photo.key]));
  private fetch?: Subscription;
  private save?: Subscription;
  private readonly photoRequests = new Subscription();

  constructor() {
    effect(() => {
      this.route();
      untracked(() => {
        this.fetch?.unsubscribe(); this.save?.unsubscribe(); this.clearPreviews();
        this.result.set(null); this.alcohol.set(''); this.checks.set(emptyChecks()); this.files.set({}); this.saving.set(false);
        this.passedChange.emit(false); this.load();
      });
    });
  }
  ngOnDestroy(): void { this.fetch?.unsubscribe(); this.save?.unsubscribe(); this.photoRequests.unsubscribe(); this.clearPreviews(); }
  protected load(): void {
    this.loading.set(true); this.error.set('');
    this.fetch = this.api.getPreTripInspection(this.route().routeId).subscribe({
      next: (result) => { this.result.set(result); this.passedChange.emit(result.passed); this.loading.set(false); },
      error: (error: unknown) => { this.error.set(this.message(error)); this.loading.set(false); this.passedChange.emit(false); },
    });
  }
  protected toggle(key: PreTripCheckKey, checked: boolean): void { this.checks.update((checks) => ({...checks, [key]: checked})); }
  protected updateAlcohol(value: number | null): void { this.alcohol.set(value === null ? '' : String(value)); }
  protected selectPhoto(kind: PhotoKind, event: Event): void {
    const input = event.target as HTMLInputElement, file = input.files?.[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024 || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
      input.value = ''; this.error.set('每張照片須為 JPG、PNG 或 WebP，且不得超過 5 MB。'); return;
    }
    const prior = this.previews()[kind]; if (prior) URL.revokeObjectURL(prior);
    this.files.update((files) => ({...files, [kind]: file}));
    this.previews.update((previews) => ({...previews, [kind]: URL.createObjectURL(file)})); this.error.set('');
  }
  protected submit(): void {
    if (!this.canSubmit()) return;
    const files = this.files(); this.saving.set(true); this.error.set('');
    this.save = this.api.submitPreTripInspection({routeId: this.route().routeId, alcoholMgL: Number(this.alcohol()), ...this.checks()},
      {alcohol: files.alcohol!, vehicle: files.vehicle!, dashcam: files.dashcam!}).subscribe({
      next: (result) => { this.result.set(result); this.saving.set(false); this.passedChange.emit(result.passed); },
      error: (error: unknown) => { this.saving.set(false); this.error.set(this.message(error)); this.passedChange.emit(false); },
    });
  }
  protected retry(): void { this.error.set(''); this.result.set(null); this.load(); }
  protected viewPhoto(kind: PhotoKind): void {
    const id = this.result()?.id; if (!id) return;
    this.photoRequests.add(this.api.getPreTripPhoto(id, kind).subscribe({next: (blob) => {
      const prior = this.previews()[kind]; if (prior) URL.revokeObjectURL(prior);
      this.previews.update((previews) => ({...previews, [kind]: URL.createObjectURL(blob)}));
    }, error: (error: unknown) => this.error.set(this.message(error))}));
  }
  private message(error: unknown): string {
    return error instanceof HttpErrorResponse && typeof error.error?.message === 'string' ? error.error.message
      : '安全檢查暫時無法讀取或提交，請重試；若任務已撤回，請重新整理今日任務。';
  }
  private clearPreviews(): void { Object.values(this.previews()).forEach((url) => url && URL.revokeObjectURL(url)); this.previews.set({}); }
}

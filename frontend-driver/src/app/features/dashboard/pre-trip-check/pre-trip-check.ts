import {DecimalPipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {Component, OnDestroy, computed, effect, inject, input, output, signal, untracked} from '@angular/core';
import {Subscription} from 'rxjs';
import {DriverOperationsService} from '../../../core/services/driver-operations.service';
import {
  DriverRouteTask,
  PreTripCheckKey,
  PreTripInspectionRequest,
  PreTripInspectionResult,
} from '../../../core/services/driver-operations.models';

/** 酒測器、行車紀錄器（同時拍到出車里程）、故障 */
type PhotoKind = 'alcohol' | 'dashcam' | 'fault';
/** 送出後可以再看的照片；行車紀錄器照片存成出車里程照片，不從這裡讀 */
type SavedPhotoKind = 'alcohol' | 'fault';
/** 每一項的答案：true＝正常、false＝異常、null＝還沒選 */
type CheckAnswers = Record<PreTripCheckKey, boolean | null>;

interface CheckGroup {
  title: string;
  hint: string | null;
  /** 這組底下要不要填出車時的行車紀錄器里程、拍照 */
  readsMileage: boolean;
  items: {key: PreTripCheckKey; label: string}[];
}

const MAX_PHOTO_SIZE = 5 * 1024 * 1024;
const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
// 0～9.99，最多到小數第二位；後端 @Digits(integer = 1, fraction = 2) 同一個範圍
const ALCOHOL_PATTERN = /^\d(\.\d{1,2})?$/;
// 行車紀錄器里程：整數公里
const ODOMETER_PATTERN = /^\d{1,7}$/;

/** 項目與順序跟後端 PreTripInspectionService.abnormalItems 一致；四燈依交通部行車前檢查 */
const CHECK_GROUPS: CheckGroup[] = [
  {title: '行車紀錄器', hint: null, readsMileage: true, items: [{key: 'dashcam', label: '開機且正常錄影'}]},
  {
    title: '五油',
    hint: '油量不足，補充後再選正常',
    readsMileage: false,
    items: [
      {key: 'engineOil', label: '引擎機油'},
      {key: 'brakeFluid', label: '煞車油'},
      {key: 'powerSteeringFluid', label: '動力方向盤油'},
      {key: 'transmissionOil', label: '變速箱油'},
      {key: 'fuel', label: '燃油'},
    ],
  },
  {
    title: '三水',
    hint: '水量不足，補充後再選正常',
    readsMileage: false,
    items: [
      {key: 'coolant', label: '冷卻水'},
      {key: 'batteryWater', label: '電瓶水'},
      {key: 'washerFluid', label: '雨刷水'},
    ],
  },
  {
    title: '二胎',
    hint: null,
    readsMileage: false,
    items: [
      {key: 'tirePressure', label: '胎壓'},
      {key: 'tireTread', label: '胎紋'},
    ],
  },
  {
    title: '四燈',
    hint: null,
    readsMileage: false,
    items: [
      {key: 'headlights', label: '頭燈'},
      {key: 'turnSignals', label: '方向燈'},
      {key: 'brakeLights', label: '煞車燈'},
      {key: 'dashboardLights', label: '儀表板燈（沒有警示燈亮）'},
    ],
  },
];

function emptyAnswers(): CheckAnswers {
  const answers = {} as CheckAnswers;
  for (const group of CHECK_GROUPS) {
    for (const item of group.items) {
      answers[item.key] = null;
    }
  }
  return answers;
}

/**
 * 今日任務路線卡片上的「出車前安全檢查」：酒測＋15 項，外加出車時的行車紀錄器里程與照片。
 * 通過時後端在同一個交易記下出車里程（DepartureService），之後才能點交。
 *
 * 能不能出車由後端判定，這裡只負責收資料、顯示結果，並用 passedChange 告訴外層開不開放點交。
 * 沒通過可以重新檢查（例如補完水）；已經出車就不再顯示表單。
 */
@Component({
  selector: 'app-pre-trip-check',
  imports: [DecimalPipe],
  templateUrl: './pre-trip-check.html',
  styleUrl: './pre-trip-check.scss',
})
export class PreTripCheck implements OnDestroy {
  readonly route = input.required<DriverRouteTask>();
  /** 最新結果有沒有通過；外層（今日任務）用它決定點交按鈕能不能按 */
  readonly passedChange = output<boolean>();

  private readonly api = inject(DriverOperationsService);
  protected readonly groups = CHECK_GROUPS;
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly result = signal<PreTripInspectionResult | null>(null);
  protected readonly error = signal('');
  /** 沒通過之後，按「重新檢查」才把表單再打開 */
  protected readonly retrying = signal(false);
  protected readonly alcohol = signal('');
  /** 出車時行車紀錄器上的里程 */
  protected readonly odometer = signal('');
  protected readonly answers = signal<CheckAnswers>(emptyAnswers());
  protected readonly note = signal('');
  protected readonly files = signal<Partial<Record<PhotoKind, File>>>({});
  protected readonly previews = signal<Partial<Record<PhotoKind, string>>>({});

  protected readonly hasAbnormal = computed(() => Object.values(this.answers()).some((answer) => answer === false));
  /** 行車紀錄器選了異常就讀不到里程，這時里程和行車紀錄器照片不用填（反正不會通過、不會出車） */
  protected readonly needsMileage = computed(() => this.answers().dashcam !== false);
  protected readonly unansweredCount = computed(
    () => Object.values(this.answers()).filter((answer) => answer === null).length,
  );
  /**
   * 顯示表單的情況：還沒送過；沒通過而且按了重新檢查；
   * 或改版前就通過、當時出車里程要另外記而還沒記到的（再送一次會一起記下里程）
   */
  protected readonly showForm = computed(() => {
    const current = this.result();
    if (this.loading() || current === null) {
      return false;
    }
    if (!current.completed) {
      return true;
    }
    if (current.passed) {
      return !current.departed;
    }
    return this.retrying();
  });
  protected readonly canSubmit = computed(() => {
    if (this.saving() || !ALCOHOL_PATTERN.test(this.alcohol())) {
      return false;
    }
    if (this.unansweredCount() > 0 || !this.files().alcohol) {
      return false;
    }
    if (this.needsMileage() && (!ODOMETER_PATTERN.test(this.odometer()) || !this.files().dashcam)) {
      return false;
    }
    // 有異常一定要寫說明，主管才知道是什麼問題；後端也會擋
    return !this.hasAbnormal() || this.note().trim() !== '';
  });

  /**
   * 換了路線或換了車才重來：任務清單每次重新整理都會給新的物件，
   * 用「路線＋車」當鑰匙，填到一半不會因為重新整理被清空
   */
  private readonly routeKey = computed(() => `${this.route().routeId}:${this.route().vehicle.id}`);
  private fetch?: Subscription;
  private save?: Subscription;
  private readonly photoRequests = new Subscription();

  constructor() {
    effect(() => {
      this.routeKey();
      untracked(() => this.restart());
    });
  }

  ngOnDestroy(): void {
    this.fetch?.unsubscribe();
    this.save?.unsubscribe();
    this.photoRequests.unsubscribe();
    this.clearPreviews();
  }

  protected reload(): void {
    this.error.set('');
    this.load();
  }

  protected answer(key: PreTripCheckKey, normal: boolean): void {
    this.answers.update((answers) => ({...answers, [key]: normal}));
  }

  protected updateAlcohol(event: Event): void {
    this.alcohol.set((event.target as HTMLInputElement).value.trim());
  }

  protected updateOdometer(event: Event): void {
    this.odometer.set((event.target as HTMLInputElement).value.trim());
  }

  protected updateNote(event: Event): void {
    this.note.set((event.target as HTMLTextAreaElement).value);
  }

  /** 前端先擋格式和大小，省得等上傳完才被後端退回；真正的判斷在後端（看檔頭，不看副檔名） */
  protected selectPhoto(kind: PhotoKind, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    if (file.size > MAX_PHOTO_SIZE || !PHOTO_TYPES.includes(file.type)) {
      input.value = '';
      this.error.set('照片要是 JPG、PNG 或 WebP，而且不能超過 5 MB。');
      return;
    }
    this.replacePreview(kind, URL.createObjectURL(file));
    this.files.update((files) => ({...files, [kind]: file}));
    this.error.set('');
  }

  protected startRetry(): void {
    this.retrying.set(true);
    this.error.set('');
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const alcoholPhoto = this.files().alcohol;
    if (!this.canSubmit() || !alcoholPhoto) {
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const dashcamPhoto = this.needsMileage() ? (this.files().dashcam ?? null) : null;
    this.save = this.api.submitPreTripInspection(this.buildRequest(), alcoholPhoto, dashcamPhoto, this.files().fault ?? null)
      .subscribe({
        next: (result) => {
          this.saving.set(false);
          this.result.set(result);
          this.retrying.set(false);
          this.passedChange.emit(result.passed);
        },
        error: (error: unknown) => {
          this.saving.set(false);
          this.error.set(this.message(error));
        },
      });
  }

  /** 照片要帶登入 token 才讀得到，拿回 Blob 轉成網址顯示 */
  protected viewPhoto(kind: SavedPhotoKind): void {
    const inspectionId = this.result()?.id;
    if (!inspectionId) {
      return;
    }
    this.photoRequests.add(this.api.getPreTripPhoto(inspectionId, kind).subscribe({
      next: (blob) => this.replacePreview(kind, URL.createObjectURL(blob)),
      error: (error: unknown) => this.error.set(this.message(error)),
    }));
  }

  protected formatTime(value: string | null): string {
    if (!value) {
      return '';
    }
    return value.replace('T', ' ').slice(0, 16);
  }

  /** 換路線或換車：清掉所有填到一半的東西，重新查這組人車的檢查 */
  private restart(): void {
    this.fetch?.unsubscribe();
    this.save?.unsubscribe();
    this.clearPreviews();
    this.result.set(null);
    this.retrying.set(false);
    this.alcohol.set('');
    this.odometer.set('');
    this.answers.set(emptyAnswers());
    this.note.set('');
    this.files.set({});
    this.saving.set(false);
    this.error.set('');
    this.passedChange.emit(false);
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.fetch = this.api.getPreTripInspection(this.route().routeId).subscribe({
      next: (result) => {
        this.result.set(result);
        this.loading.set(false);
        this.passedChange.emit(result.passed);
      },
      error: (error: unknown) => {
        // 查不到就當作沒通過，不能因為網路錯誤就放行點交
        this.error.set(this.message(error));
        this.loading.set(false);
        this.passedChange.emit(false);
      },
    });
  }

  private buildRequest(): PreTripInspectionRequest {
    const answers = this.answers();
    const note = this.note().trim();
    const request = {
      routeId: this.route().routeId,
      alcoholMgL: Number(this.alcohol()),
      odometer: this.needsMileage() ? Number(this.odometer()) : null,
      note: this.hasAbnormal() && note !== '' ? note : null,
    } as PreTripInspectionRequest;
    for (const group of CHECK_GROUPS) {
      for (const item of group.items) {
        // canSubmit 已確認每一項都選了，這裡不會是 null
        request[item.key] = answers[item.key] === true;
      }
    }
    return request;
  }

  private replacePreview(kind: PhotoKind, url: string): void {
    const previous = this.previews()[kind];
    if (previous) {
      URL.revokeObjectURL(previous);
    }
    this.previews.update((previews) => ({...previews, [kind]: url}));
  }

  private clearPreviews(): void {
    for (const url of Object.values(this.previews())) {
      if (url) {
        URL.revokeObjectURL(url);
      }
    }
    this.previews.set({});
  }

  private message(error: unknown): string {
    if (error instanceof HttpErrorResponse && typeof error.error?.message === 'string') {
      return error.error.message;
    }
    return '安全檢查暫時無法讀取或送出，請再試一次；如果任務已撤回，請重新整理今日任務。';
  }
}

import {Component, computed, inject, OnDestroy, OnInit, signal} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {catchError, forkJoin, Observable, of, Subscription, throwError} from 'rxjs';
import {MatIconModule} from '@angular/material/icon';
import {MatDatepickerModule} from '@angular/material/datepicker';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatInputModule} from '@angular/material/input';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {AdminThemeService} from '../../../../core/theme/admin-theme.service';
import {DriverDto, OrderDto, ReportCollectionDto, ReportQuery, ReportSummaryDto, ReportPerformanceDto, StoreDto, VehicleDto, WarehouseDto,
  ReportOutcomesDto, ReportOrderOutcomeDto} from '../../../../core/services/dispatch-api.models';
import {REPORT_CASE_METRICS, matchesReportCase} from '../../report-delivery-cases';
import {ORDER_PROGRESS_METRICS, matchesOrderProgress} from '../../report-unsettled-orders';
import {ReportLoadingItem, loadingItemStatusLabel, loadingMismatchSummary, reportLoadingItems} from '../../report-loading-items';
import {isOverdueUnsettledOrder} from '../../report-overdue-orders';

type PreviewSheet = 'overview' | 'orders' | 'routes' | 'attendance' | 'vehicles' | 'warehouses' | 'stores' | 'exceptions' | 'notes'
  | 'delivery-quality' | 'recovery' | 'loading-quality';
type ReportHistoryPeriod = 'year' | 'month' | 'week' | 'day' | 'custom';
interface ReportRow {
  [key: string]: unknown;
  actualKm?: unknown;
  actualMileageStatus?: unknown;
  assignedOrders?: unknown;
  assignedRoutes?: unknown;
  averageLoadRatePercent?: unknown;
  boxes?: unknown;
  clockInAt?: unknown;
  clockOutAt?: unknown;
  clockSpanMinutes?: unknown;
  completedOrders?: unknown;
  createdAt?: unknown;
  dataStatus?: unknown;
  date?: unknown;
  deliveryOrder?: unknown;
  description?: unknown;
  differenceKm?: unknown;
  distinctStores?: unknown;
  driverId?: unknown;
  driverName?: unknown;
  exceptionId?: unknown;
  loadingItems?: ReportLoadingItem[];
  loadingItemSummary?: string;
  failedOrders?: unknown;
  mileageComparisonStatus?: unknown;
  noSignatureAttempts?: unknown;
  orderId?: unknown;
  orderNumber?: unknown;
  orders?: unknown;
  plannedFuelCost?: unknown;
  plannedKm?: unknown;
  plannedLoadRatePercent?: unknown;
  plateNumber?: unknown;
  publishedPlannedKm?: unknown;
  publishedRoutes?: unknown;
  resolution?: unknown;
  resolutionMinutes?: unknown;
  routeId?: unknown;
  routes?: unknown;
  scheduledEndAt?: unknown;
  scheduledStartAt?: unknown;
  scheduledWorkDays?: unknown;
  sequence?: unknown;
  shiftId?: unknown;
  shiftType?: unknown;
  status?: unknown;
  storeId?: unknown;
  storeName?: unknown;
  startedTrips?: unknown;
  type?: unknown;
  unassignedConfirmedOrders?: unknown;
  vehicleId?: unknown;
  warehouseId?: unknown;
  warehouseName?: unknown;
  workDate?: unknown;
}
interface ReportPreview {
  performance: ReportPerformanceDto;
  summary: ReportSummaryDto;
  attendance: ReportCollectionDto;
  routes: ReportCollectionDto;
  drivers: ReportCollectionDto;
  vehicles: ReportCollectionDto;
  warehouses: ReportCollectionDto;
  stores: ReportCollectionDto;
  exceptions: ReportCollectionDto;
  orders: OrderDto[];
  storeDirectory: StoreDto[];
  outcomes: ReportOutcomesDto | null;
}

interface RouteLegReportRow {
  sequence: number | null;
  fromName: string | null;
  toName: string | null;
  orderId: number | null;
  startedAt: string | null;
  endedAt: string | null;
  durationMinutes: number | null;
  systemKm: number | null;
  calculationStatus: string | null;
}

const SHEETS: ReadonlyArray<{id: PreviewSheet; label: string; icon: string}> = [
  {id: 'overview', label: '營運總覽', icon: 'dashboard'},
  {id: 'orders', label: '訂單明細', icon: 'receipt_long'},
  {id: 'routes', label: '路線里程油費', icon: 'route'},
  {id: 'attendance', label: '司機打卡', icon: 'badge'},
  {id: 'recovery', label: '補送追蹤', icon: 'restart_alt'},
  {id: 'delivery-quality', label: '配送結果', icon: 'fact_check'},
  {id: 'loading-quality', label: '出貨點交', icon: 'inventory'},
  {id: 'vehicles', label: '車輛使用', icon: 'local_shipping'},
  {id: 'warehouses', label: '倉庫門市', icon: 'warehouse'},
  {id: 'stores', label: '門市表現', icon: 'storefront'},
  {id: 'exceptions', label: '異常案件', icon: 'warning_amber'},
  {id: 'notes', label: '欄位說明', icon: 'help_outline'},
];

const METRICS: Partial<Record<PreviewSheet, {id: string; label: string}[]>> = {
  orders: ORDER_PROGRESS_METRICS.map(({id, label}) => ({id, label})),
  attendance: [{id: 'clocked-in', label: '已打上班卡'}, {id: 'on-time', label: '準時上班'}, {id: 'late', label: '遲到'},
    {id: 'missing-clock-in', label: '缺上班卡'}, {id: 'overtime', label: '有加班'}],
  vehicles: [{id: 'distance-recorded', label: '有可核對實際里程'}],
  recovery: [{id: 'attempted', label: '已執行補送'}, {id: 'outstanding', label: '未確認補送交付'}, {id: 'delivered', label: '補送已交貨'}],
  'delivery-quality': [{id: 'full', label: '完整交付'}, {id: 'incomplete', label: '交貨不完整'},
    {id: 'outstanding', label: '未確認交付'}, {id: 'missing-quality', label: '箱數待核對'},
    {id: 'within-window', label: '收貨時段內抵達'}, {id: 'late-arrival', label: '逾時抵達'},
    {id: 'no-signature', label: '無人簽收'}, {id: 'overdue-unsettled', label: '未結訂單異常'}],
  'loading-quality': [{id: 'matched', label: '點交相符'}, {id: 'mismatched', label: '點交不符'},
    {id: 'missing-loading', label: '點交紀錄缺漏'}, {id: 'unassigned', label: '到期未排車'}],
  exceptions: REPORT_CASE_METRICS.map(({id, label}) => ({id, label})),
};

@Component({
  selector: 'app-report-history',
  imports: [MatIconModule, MatDatepickerModule, MatFormFieldModule, MatInputModule],
  templateUrl: './report-history.html',
  styleUrl: './report-history.scss',
})
export class ReportHistory implements OnInit, OnDestroy {
  private readonly api = inject(DispatchApiService);
  // 日期區間選擇器的面板開在 body 底下，吃不到後台深淺色：panelClass 要帶 theme.dialogPanelClass()
  protected readonly theme = inject(AdminThemeService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private previewRequest?: Subscription;
  private filterRequest?: Subscription;

  readonly sheets = SHEETS;
  readonly from = signal(this.today());
  readonly to = signal(this.today());
  readonly periods: {id: Exclude<ReportHistoryPeriod, 'custom'>; label: string}[] = [
    {id: 'year', label: '年度'}, {id: 'month', label: '月度'},
    {id: 'week', label: '週別'}, {id: 'day', label: '日別'},
  ];
  readonly activePeriod = signal<ReportHistoryPeriod>('day');
  private readonly selectedDate = signal(this.today());
  // 日期選擇器吃 Date；查詢、網址參數、Excel 檔名仍用 YYYY-MM-DD 字串，所以只在這裡轉換
  protected readonly fromDate = computed(() => this.parseIsoDate(this.from()));
  protected readonly toDate = computed(() => this.parseIsoDate(this.to()));
  readonly warehouseId = signal<number | null>(null);
  readonly storeId = signal<number | null>(null);
  readonly driverId = signal<number | null>(null);
  readonly tonnage = signal<string | null>(null);
  readonly warehouses = signal<WarehouseDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly drivers = signal<DriverDto[]>([]);
  readonly vehicles = signal<VehicleDto[]>([]);
  readonly tonnageOptions = computed(() => {
    const values = new Set(this.vehicles().map((vehicle) => this.vehicleTonnage(vehicle)));
    return [...values].sort((left, right) => left === null ? 1 : right === null ? -1 : left - right)
      .map((value) => ({value: value === null ? 'unknown' : String(value), label: value === null ? '未設定噸位' : `${value} 噸`}));
  });
  readonly preview = signal<ReportPreview | null>(null);
  readonly selectedSheet = signal<PreviewSheet>('overview');
  readonly loading = signal(false);
  readonly exporting = signal(false);
  readonly errorMessage = signal('');
  readonly detailError = signal('');
  readonly filterError = signal('');
  readonly metric = signal('');

  ngOnInit(): void {
    const query = this.route.snapshot.queryParamMap;
    this.from.set(query.get('from') ?? this.from());
    this.to.set(query.get('to') ?? this.to());
    this.activePeriod.set(this.inferPeriod(this.from(), this.to()));
    this.selectedDate.set(this.from() <= this.today() && this.today() <= this.to() ? this.today() : this.from());
    const sheet = query.get('sheet');
    if (SHEETS.some((item) => item.id === sheet)) this.selectedSheet.set(sheet as PreviewSheet);
    const metric = query.get('metric');
    if (this.metricOptions().some(item => item.id === metric)) this.metric.set(metric!);
    const warehouseId = Number(query.get('warehouseId'));
    if (query.has('warehouseId') && Number.isSafeInteger(warehouseId) && warehouseId > 0) {
      this.warehouseId.set(warehouseId);
    }
    const tonnage = query.get('tonnage');
    if (tonnage === 'unknown' || (tonnage !== null && /^\d+(?:\.\d+)?$/.test(tonnage))) {
      this.tonnage.set(tonnage === 'unknown' ? tonnage : String(Number(tonnage)));
    }
    this.loadFilterOptions();
    if (this.tonnage() === null) this.createPreview();
  }

  /** 起訖日期分別選取；空白或起日較晚的區間由 createPreview 擋下。 */
  protected updateDate(bound: 'from' | 'to', date: Date | null): void {
    const value = date ? this.toIsoDate(date) : '';
    if (bound === 'from') this.from.set(value);
    else this.to.set(value);
    this.activePeriod.set(this.inferPeriod(this.from(), this.to()));
    if (this.fromDate()) this.selectedDate.set(this.from());
    this.invalidatePreview();
  }

  protected setPeriod(period: Exclude<ReportHistoryPeriod, 'custom'>): void {
    this.activePeriod.set(period);
    this.applyPeriod(this.parseIsoDate(this.selectedDate()) ?? this.parseIsoDate(this.today())!);
  }

  protected stepPeriod(direction: -1 | 1): void {
    const period = this.activePeriod();
    if (period === 'custom') return;
    const anchor = this.parseIsoDate(this.selectedDate()) ?? this.parseIsoDate(this.today())!;
    let next: Date;
    if (period === 'day') next = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() + direction);
    else if (period === 'week') next = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() + direction * 7);
    else if (period === 'month') next = this.shiftCalendarMonth(anchor, direction);
    else next = this.shiftCalendarMonth(anchor, direction * 12);
    this.applyPeriod(next);
  }

  protected periodRangeLabel(): string {
    if (!this.from() || !this.to()) return '請選擇完整期間';
    const from = this.from().replaceAll('-', '/');
    return this.from() === this.to() ? from : `${from} — ${this.to().replaceAll('-', '/')}`;
  }

  private applyPeriod(anchor: Date): void {
    const period = this.activePeriod();
    let start = anchor;
    let end = anchor;
    if (period === 'year') {
      start = new Date(anchor.getFullYear(), 0, 1);
      end = new Date(anchor.getFullYear(), 11, 31);
    } else if (period === 'month') {
      start = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
      end = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0);
    } else if (period === 'week') {
      start = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() - (anchor.getDay() + 6) % 7);
      end = new Date(start.getFullYear(), start.getMonth(), start.getDate() + 6);
    }
    this.selectedDate.set(this.toIsoDate(anchor));
    this.from.set(this.toIsoDate(start));
    this.to.set(this.toIsoDate(end));
    this.invalidatePreview();
    this.createPreview();
  }

  private shiftCalendarMonth(date: Date, months: number): Date {
    const first = new Date(date.getFullYear(), date.getMonth() + months, 1);
    return new Date(first.getFullYear(), first.getMonth(), Math.min(date.getDate(),
      new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate()));
  }

  private inferPeriod(from: string, to: string): ReportHistoryPeriod {
    const start = this.parseIsoDate(from);
    const end = this.parseIsoDate(to);
    if (!start || !end || from > to) return 'custom';
    if (from === to) return 'day';
    if (start.getMonth() === 0 && start.getDate() === 1
      && end.getFullYear() === start.getFullYear() && end.getMonth() === 11 && end.getDate() === 31) return 'year';
    if (start.getDate() === 1 && end.getFullYear() === start.getFullYear() && end.getMonth() === start.getMonth()
      && end.getDate() === new Date(start.getFullYear(), start.getMonth() + 1, 0).getDate()) return 'month';
    const weekEnd = new Date(start.getFullYear(), start.getMonth(), start.getDate() + 6);
    if (start.getDay() === 1 && to === this.toIsoDate(weekEnd)) return 'week';
    return 'custom';
  }

  ngOnDestroy(): void { this.previewRequest?.unsubscribe(); this.filterRequest?.unsubscribe(); }

  private invalidatePreview(): void {
    this.previewRequest?.unsubscribe();
    this.preview.set(null);
    this.loading.set(false);
  }

  /** YYYY-MM-DD 轉成本地時間的 Date；不能用 new Date('2026-09-24')，那會被當成 UTC 午夜 */
  private parseIsoDate(value: string): Date | null {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    return match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null;
  }

  /** 用本地年月日組字串；toISOString 會先轉 UTC，台灣早上 8 點前會變成前一天 */
  private toIsoDate(date: Date): string {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  }

  protected updateFilter(kind: 'warehouse' | 'store' | 'driver' | 'tonnage', event: Event): void {
    const raw = (event.target as HTMLSelectElement).value;
    const value = raw ? Number(raw) : null;
    if (kind === 'warehouse') this.warehouseId.set(value);
    if (kind === 'store') this.storeId.set(value);
    if (kind === 'driver') this.driverId.set(value);
    if (kind === 'tonnage') this.tonnage.set(raw || null);
    this.invalidatePreview();
  }

  protected selectSheet(sheet: PreviewSheet): void {
    this.selectedSheet.set(sheet);
    this.metric.set('');
    void this.router.navigate([], {relativeTo: this.route, queryParams: {sheet, metric: null}, queryParamsHandling: 'merge'});
  }

  protected metricOptions(): {id: string; label: string}[] { return METRICS[this.selectedSheet()] ?? []; }
  protected updateMetric(event: Event): void {
    const id = (event.target as HTMLSelectElement).value;
    this.metric.set(this.metricOptions().some(item => item.id === id) ? id : '');
    void this.router.navigate([], {relativeTo: this.route, queryParams: {metric: this.metric() || null}, queryParamsHandling: 'merge'});
  }

  protected createPreview(): void {
    if (!this.validRange()) return;

    this.loading.set(true);
    this.preview.set(null);
    this.errorMessage.set('');
    this.detailError.set('');
    const query = this.reportQuery();
    void this.router.navigate([], {relativeTo: this.route, queryParams: {
      from: this.from(), to: this.to(), vehicleId: null, tonnage: this.tonnage(),
    }, queryParamsHandling: 'merge'});
    this.previewRequest?.unsubscribe();
    this.previewRequest = forkJoin({
      performance: this.previewSource('人員與出車統計', this.api.getReportPerformance(query)),
      summary: this.previewSource('營運總覽', this.api.getReportSummary(query)),
      attendance: this.previewSource('司機打卡', this.api.getReportAttendance(query)),
      routes: this.previewSource('路線里程', this.api.getReportRoutes(query)),
      drivers: this.previewSource('司機統計', this.api.getReportDrivers(query)),
      vehicles: this.previewSource('車輛使用', this.api.getReportVehicles(query)),
      warehouses: this.previewSource('倉庫統計', this.api.getReportWarehouses(query)),
      stores: this.previewSource('門市表現', this.api.getReportStores(query)),
      exceptions: this.previewSource('異常案件', this.api.getReportExceptions(query)),
      orders: this.previewSource('訂單明細', this.api.getOrders()),
      storeDirectory: this.previewSource('門市資料', this.api.getStores()),
      outcomes: this.api.getReportOutcomes({...query, includeDetails: true}).pipe(catchError((error: unknown) => {
        this.detailError.set(this.loadError('配送／補送／點交明細', error)); return of(null);
      })),
    }).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.preview.set(null);
        this.errorMessage.set(error instanceof Error ? error.message : '報表未載入，請重新預覽。');
        this.loading.set(false);
      },
    });
  }

  private previewSource<T>(label: string, source: Observable<T>): Observable<T> {
    return source.pipe(catchError((error: unknown) => throwError(() => new Error(this.loadError(label, error)))));
  }

  private loadError(label: string, error: unknown): string {
    const status = typeof error === 'object' && error !== null && 'status' in error ? Number(error.status) : NaN;
    if (status === 401) return `「${label}」未載入：登入已失效，請重新登入。`;
    if (status === 403) return `「${label}」未載入：目前帳號沒有查詢權限。`;
    if (status === 0) return `「${label}」未載入：無法連線到後端服務，請確認服務已啟動後重試。`;
    if (status >= 500) return `「${label}」未載入：後端查詢失敗（HTTP ${status}），請確認資料庫更新完成後重試。`;
    return `「${label}」未載入，請重新預覽。`;
  }

  protected async exportWorkbook(): Promise<void> {
    const preview = this.preview();
    if (!preview) return;

    this.exporting.set(true);
    this.errorMessage.set('');
    try {
      const xlsx = await import('xlsx');
      const workbook = xlsx.utils.book_new();
      this.appendSheet(xlsx, workbook, '匯出說明', [
        ['主管歷史報表'],
        ['查詢期間', `${preview.summary.from} 至 ${preview.summary.to}`],
        ['倉庫', this.selectedWarehouseLabel()],
        ['門市', this.selectedStoreLabel()],
        ['司機', this.selectedDriverLabel()],
        ['噸位', this.selectedTonnageLabel()],
        ['匯出時間', new Intl.DateTimeFormat('zh-TW', {dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Taipei'}).format(new Date())],
      ]);
      this.appendSheet(xlsx, workbook, '營運總覽', this.overviewExportRows(preview.summary));
      this.appendSheet(xlsx, workbook, '訂單明細', this.orderExportRows(preview));
      this.appendSheet(xlsx, workbook, '路線里程油費', this.routeExportRows(preview));
      this.appendSheet(xlsx, workbook, '司機出勤', this.attendanceExportRows(preview));
      this.appendSheet(xlsx, workbook, '出車收車明細', this.tripExportRows(preview));
      this.appendSheet(xlsx, workbook, '各倉人車比較', [
        ['倉庫', '已打上班卡', '應打上班卡', '打卡率', '準時班次', '準時上班率', '已完成打卡班次', '加班班次', '加班率', '加班分鐘', '出車趟次', '收車趟次', '已記錄實際公里'],
        ...preview.performance.warehouses.map(row => [row.warehouseName, row.workforce.attendedShifts, row.workforce.dueShifts,
          this.percent(row.workforce.attendanceRate), row.workforce.onTimeShifts, this.percent(row.workforce.onTimeRate),
          row.workforce.finishedShifts, row.workforce.overtimeShifts, this.percent(row.workforce.overtimeRate), row.workforce.overtimeMinutes,
          row.fleet.startedTrips, row.fleet.returnedTrips, row.fleet.actualKm]),
        ['口徑', '人員依目前倉庫歸屬，出車依記錄出貨倉庫；比率無分母不補0%；加班為打卡超過排定下班分鐘，非核准薪資加班。'],
      ]);
      this.appendSheet(xlsx, workbook, '車輛使用', this.vehicleExportRows(preview));
      this.appendSheet(xlsx, workbook, '倉庫門市', this.warehouseExportRows(preview));
      this.appendSheet(xlsx, workbook, '異常案件', this.exceptionExportRows(preview));
      const loadingExceptionItems = this.loadingExceptionExportRows(preview);
      if (loadingExceptionItems.length > 1) {
        this.appendSheet(xlsx, workbook, '點交異常商品', loadingExceptionItems);
      }
      if (preview.outcomes) {
        this.appendSheet(xlsx, workbook, '配送結果明細', this.deliveryOutcomeExportRows(preview));
        this.appendSheet(xlsx, workbook, '出貨點交明細', this.loadingOutcomeExportRows(preview));
      }
      if (preview.outcomes) {
        this.appendSheet(xlsx, workbook, '補送追蹤', this.recoveryExportRows(preview));
      }
      this.appendSheet(xlsx, workbook, '欄位說明', this.notesExportRows());
      const buffer = xlsx.write(workbook, {bookType: 'xlsx', type: 'array'}) as ArrayBuffer;
      const href = URL.createObjectURL(new Blob([buffer], {type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}));
      const link = document.createElement('a');
      link.href = href;
      link.download = `主管歷史報表_${preview.summary.from}_${preview.summary.to}.xlsx`;
      link.click();
      URL.revokeObjectURL(href);
    } catch {
      this.errorMessage.set('匯出失敗，請稍後再試。');
    } finally {
      this.exporting.set(false);
    }
  }

  protected rows(collection: ReportCollectionDto, key: string): ReportRow[] {
    const value = collection[key];
    return this.asRows(value);
  }

  protected exceptionRows(report: ReportPreview): ReportRow[] {
    const rows = this.rows(report.exceptions, 'cases');
    const metric = this.selectedSheet() === 'exceptions' ? this.metric() : '';
    const ordersById = new Map((report.orders ?? []).filter(order => order.id != null).map(order => [order.id!, order]));
    return (metric ? rows.filter(row => matchesReportCase(row, metric)) : rows).map(row => {
      if (row.type !== 'LOADING_MISMATCH') return row;
      // Use the case's original order, not the follow-up order or the report's delivery-date-filtered orders.
      const order = typeof row.orderId === 'number' ? ordersById.get(row.orderId) : undefined;
      const items = reportLoadingItems(order?.items);
      return {...row, loadingItems: items, loadingItemSummary: order ? loadingMismatchSummary(items)
        : typeof row.orderId === 'number' ? '原訂單商品明細未載入' : '案件未關聯原訂單'};
    });
  }

  protected loadingItemLabel(item: ReportLoadingItem): string { return loadingItemStatusLabel(item.status); }

  protected exceptionStatusCount(rows: ReportRow[], status: 'OPEN' | 'CLOSED'): number {
    return rows.filter(row => row.status === status).length;
  }

  protected exceptionTypeLabel(type: unknown): string {
    const labels: Record<string, string> = {NO_SIGNATURE: '無人簽收回報', DRIVER_REPORT: '司機現場通報',
      SHORTAGE: '交貨差異回報', DAMAGE: '交貨差異回報', SHORTAGE_AND_DAMAGE: '交貨差異回報',
      PHONE_HANDLED: '電話處理補登', LOADING_MISMATCH: '倉庫點交不符'};
    return typeof type === 'string' ? labels[type] ?? type : '--';
  }

  protected rowsFrom(value: unknown): ReportRow[] {
    return this.asRows(value);
  }

  protected attendanceRows(report: ReportPreview): Record<string, unknown>[] {
    return report.performance.shifts.filter(row => {
      switch (this.selectedSheet() === 'attendance' ? this.metric() : '') {
        case 'clocked-in': return row['onTime'] !== null && row['onTime'] !== undefined;
        case 'on-time': return row['onTime'] === true;
        case 'late': return row['onTime'] === false;
        case 'missing-clock-in': return row['attendanceStatus'] === 'MISSING_CLOCK_IN';
        case 'overtime': return typeof row['overtimeMinutes'] === 'number' && row['overtimeMinutes'] > 0;
        default: return true;
      }
    });
  }

  protected outcomeRows(report: ReportPreview, loading = false): ReportOrderOutcomeDto[] {
    const metric = this.selectedSheet() === (loading ? 'loading-quality' : 'delivery-quality') ? this.metric() : '';
    return (report.outcomes?.orders ?? [])
      .filter(row => (this.warehouseId() === null || row.warehouseId === this.warehouseId())
        && (this.storeId() === null || row.storeId === this.storeId())
        && (this.driverId() === null || row.driverId === this.driverId()))
      .filter(row => {
        if (loading) {
          switch (metric) {
            case 'matched': return row.loadingMatched;
            case 'mismatched': return row.loadingMismatch;
            case 'missing-loading': return row.missingLoading;
            case 'unassigned': return row.dueUnassigned;
            default: return row.loadingMatched || row.loadingMismatch || row.missingLoading || row.dueUnassigned;
          }
        }
        if (!row.due) return false;
        if (row.status === 'PENDING_CONFIRM' && metric !== 'overdue-unsettled') return false;
        switch (metric) {
          case 'overdue-unsettled': return isOverdueUnsettledOrder(row);
          case 'full': return row.full;
          case 'incomplete': return row.delivered && !row.full && !row.missingQuality;
          case 'outstanding': return !row.delivered;
          case 'missing-quality': return row.missingQuality;
          case 'within-window': return row.withinWindow;
          case 'late-arrival': return row.late;
          case 'no-signature': return row.noSignature;
          default: return true;
        }
      });
  }

  protected tripRows(report: ReportPreview): Record<string, unknown>[] {
    return report.performance.trips.filter(row => this.selectedSheet() !== 'vehicles' || this.metric() !== 'distance-recorded'
      || typeof row['actualKm'] === 'number');
  }
  protected recoveryRows(report: ReportPreview): ReportOrderOutcomeDto[] {
    return (report.outcomes?.orders ?? []).filter(row => row.recovery
      && (this.warehouseId() === null || row.warehouseId === this.warehouseId())
      && (this.storeId() === null || row.storeId === this.storeId())
      && (this.driverId() === null || row.driverId === this.driverId()))
      .filter(row => {
        const metric = this.selectedSheet() === 'recovery' ? this.metric() : '';
        return metric === 'attempted' ? row.attempted : metric === 'outstanding' ? !row.delivered
          : metric === 'delivered' ? row.delivered : true;
      });
  }
  private recoveryExportRows(report: ReportPreview): unknown[][] {
    return [['預定日期', '補送訂單', '原單 ID', '出貨倉庫', '門市', '已執行', '交貨結果', '抵達', '交貨', '應送箱數', '實送箱數', '補送原因'],
      ...this.recoveryRows(report).map(r => [r.date, r.orderNumber, r.parentOrderId, r.warehouseName, r.storeName,
        r.attempted ? '是' : '否', this.deliveryResult(r), r.arrivedAt, r.deliveredAt, this.expectedDeliveryBoxes(r), this.actualDeliveryBoxes(r),
        r.recoveryReason ?? '未記錄'])];
  }

  protected deliveryResult(row: ReportOrderOutcomeDto): string {
    return row.noSignature ? '無人簽收' : row.full ? '完整交付' : row.missingQuality ? '箱數待核對'
      : row.delivered ? '交貨不完整' : '未確認交付';
  }
  protected overdueResult(row: ReportOrderOutcomeDto): string | null {
    if (!isOverdueUnsettledOrder(row)) return null;
    return row.deliveredAt ? '逾期才交付' : '到期未交付';
  }
  protected expectedDeliveryBoxes(row: ReportOrderOutcomeDto): number | null {
    return row.expectedBoxCount ?? row.orderedBoxCount;
  }
  protected actualDeliveryBoxes(row: ReportOrderOutcomeDto): number | null {
    return row.noSignature ? 0 : row.deliveredBoxCount;
  }
  protected arrivalResult(row: ReportOrderOutcomeDto): string {
    return !row.windowStart || !row.windowEnd ? '收貨時段未記錄' : row.withinWindow ? '時段內抵達'
      : row.late ? '逾時抵達' : row.early ? '提早抵達' : '抵達未記錄';
  }
  protected loadingResult(row: ReportOrderOutcomeDto): string {
    return row.loadingMismatch ? '點交不符' : row.loadingMatched ? '點交相符' : row.dueUnassigned ? '到期未排車' : '點交紀錄缺漏';
  }

  protected loadingItemsForOutcome(row: ReportOrderOutcomeDto, report: ReportPreview): ReportLoadingItem[] {
    const source = report.orders?.find(order => order.id === row.orderId);
    return reportLoadingItems(source?.items?.length ? source.items
      : (row.items ?? []).map(item => ({...item, loadingNotes: item.notes})));
  }

  protected loadingRecordedAt(row: ReportOrderOutcomeDto, report: ReportPreview): string | null {
    // A failed handoff has no successful loadedAt; its recorded item-check time is still valid evidence.
    return row.loadedAt || this.loadingItemsForOutcome(row, report).map(item => item.checkedAt)
      .filter((time): time is string => typeof time === 'string' && time.length > 0).sort().at(-1) || null;
  }

  private deliveryOutcomeExportRows(preview: ReportPreview): unknown[][] {
    return [['日期', '訂單', '出貨倉庫', '門市', '收貨開始', '收貨截止', '實際抵達', '實際交貨', '應送箱數', '實送箱數', '交付結果', '抵達結果'],
      ...this.outcomeRows(preview).map(r => [r.date, r.orderNumber, r.warehouseName, r.storeName, r.windowStart, r.windowEnd,
        r.arrivedAt, r.deliveredAt, this.expectedDeliveryBoxes(r), this.actualDeliveryBoxes(r), this.deliveryResult(r), this.arrivalResult(r)])];
  }
  private loadingOutcomeExportRows(preview: ReportPreview): unknown[][] {
    const orders = this.outcomeRows(preview, true);
    return [['日期', '訂單', '出貨倉庫', '門市', '訂單箱數', '點交時間', '點交結果', '異常描述', '點交備註'],
      ...orders.map(r => [r.date, r.orderNumber, r.warehouseName, r.storeName, r.orderedBoxCount, this.loadingRecordedAt(r, preview), this.loadingResult(r), r.loadingIssue, r.loadingNotes ?? null]),
      [], ['訂單', '商品代碼', '品項', '應點數量', '實點數量', '缺少數量', '單位', '點交結果', '點交時間', '備註'],
      ...orders.flatMap(r => this.loadingItemsForOutcome(r, preview).map(i => [r.orderNumber, i.productCode, i.itemName, i.expectedQuantity,
        i.loadedQuantity, i.missingQuantity, i.unit, this.loadingItemLabel(i), i.checkedAt, i.notes]))];
  }
  protected orderRows(preview: ReportPreview): ReportRow[] {
    const selectedVehicleIds = this.selectedVehicleIds();
    const vehicleIds = selectedVehicleIds === undefined ? null : new Set(selectedVehicleIds);
    const storesById = new Map(preview.storeDirectory.filter((store) => store.id != null).map((store) => [store.id!, store]));
    const routeByOrderId = new Map<number, ReportRow>();
    for (const route of this.rows(preview.routes, 'routes')) {
      for (const routeOrder of this.asRows(route.deliveryOrder)) {
        const orderId = routeOrder.orderId;
        if (typeof orderId === 'number') routeByOrderId.set(orderId, route);
      }
    }
    return preview.orders
      .filter((order) => order.deliveryDate >= this.from() && order.deliveryDate <= this.to())
      .filter((order) => this.warehouseId() === null || order.warehouseId === this.warehouseId())
      .filter((order) => this.storeId() === null || order.storeId === this.storeId())
      .filter((order) => this.driverId() === null || order.assignedDriverId === this.driverId())
      .filter((order) => {
        if (vehicleIds === null) return true;
        const routeVehicleId = order.id == null ? undefined : routeByOrderId.get(order.id)?.vehicleId;
        return (order.assignedVehicleId != null && vehicleIds.has(order.assignedVehicleId))
          || (typeof routeVehicleId === 'number' && vehicleIds.has(routeVehicleId));
      })
      .filter((order) => this.selectedSheet() !== 'orders' || matchesOrderProgress(order, this.metric()))
      .sort((left, right) => left.deliveryDate.localeCompare(right.deliveryDate) || left.orderNumber.localeCompare(right.orderNumber))
      .map((order) => {
        const route = order.id == null ? undefined : routeByOrderId.get(order.id);
        return {
          date: order.deliveryDate,
          orderNumber: order.orderNumber,
          sequence: order.sequence,
          storeName: storesById.get(order.storeId)?.name ?? `門市 #${order.storeId}`,
          boxes: order.boxCount,
          status: this.orderStatusLabel(order.status),
          routeId: route?.routeId,
          driverName: route?.driverName,
          plateNumber: route?.plateNumber,
          warehouseName: route?.warehouseName,
        };
      });
  }

  protected value(value: unknown, fallback = '--'): string {
    if (value === null || value === undefined || value === '') return fallback;
    if (typeof value === 'number') return Number.isInteger(value) ? `${value}` : value.toFixed(1);
    if (typeof value === 'boolean') return value ? '是' : '否';
    return this.formatTimestamp(String(value));
  }

  private formatTimestamp(value: string): string {
    // API 的 LocalDateTime 已是本地營運時間；只統一顯示精度，不經 Date 轉換時區。
    const timestamp = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2})(?:\.\d+)?)?$/.exec(value);
    return timestamp ? `${timestamp[1]} ${timestamp[2]}:${timestamp[3] ?? '00'}` : value;
  }

  protected percent(value: unknown): string {
    return typeof value === 'number' ? `${value.toFixed(1)}%` : '--';
  }

  protected sheetTitle(): string {
    return this.sheets.find((sheet) => sheet.id === this.selectedSheet())?.label ?? '';
  }

  private loadFilterOptions(): void {
    this.filterError.set('');
    const read = <T>(label: string, source: Observable<T[]>): Observable<T[]> => source.pipe(catchError((error: unknown) => {
      this.filterError.update(message => [message, this.loadError(label, error)].filter(Boolean).join(' '));
      return of([]);
    }));
    this.filterRequest?.unsubscribe();
    this.filterRequest = forkJoin({
      warehouses: read('倉庫選項', this.api.getWarehouses()),
      stores: read('門市選項', this.api.getStores()),
      drivers: read('司機選項', this.api.getDrivers()),
      vehicles: read('車輛選項', this.api.getVehicles()),
    }).subscribe({
      next: ({warehouses, stores, drivers, vehicles}) => {
        this.warehouses.set(warehouses);
        this.stores.set(stores);
        this.drivers.set(drivers);
        this.vehicles.set(vehicles);
        if (this.tonnage() !== null) this.createPreview();
      },
    });
  }

  private vehicleTonnage(vehicle: VehicleDto): number | null {
    const match = /([0-9]+(?:\.[0-9]+)?)\s*噸/.exec(vehicle.vehicleType ?? '');
    return match ? Number(match[1]) : null;
  }

  private selectedVehicleIds(): number[] | undefined {
    const selected = this.tonnage();
    if (selected === null) return undefined;
    return this.vehicles().filter((vehicle) => {
      const tonnage = this.vehicleTonnage(vehicle);
      return selected === 'unknown' ? tonnage === null : tonnage === Number(selected);
    }).map((vehicle) => vehicle.id).filter((id): id is number => typeof id === 'number');
  }

  private validRange(): boolean {
    if (!this.from() || !this.to() || this.from() > this.to()) {
      this.errorMessage.set('請確認查詢起訖日期。');
      return false;
    }
    if (this.tonnage() !== null && this.selectedVehicleIds()?.length === 0) {
      this.errorMessage.set('所選噸位沒有可查詢的車輛，請重新選擇噸位。');
      return false;
    }
    return true;
  }

  private reportQuery(): ReportQuery {
    return {
      period: 'CUSTOM', from: this.from(), to: this.to(),
      warehouseId: this.warehouseId() ?? undefined,
      storeId: this.storeId() ?? undefined,
      driverId: this.driverId() ?? undefined,
      vehicleIds: this.selectedVehicleIds(),
    };
  }

  private appendSheet(xlsx: typeof import('xlsx'), workbook: ReturnType<typeof xlsx.utils.book_new>, name: string, rows: unknown[][]): void {
    const formattedRows = rows.map(row => row.map(cell => typeof cell === 'string' ? this.formatTimestamp(cell) : cell));
    const sheet = xlsx.utils.aoa_to_sheet(formattedRows);
    sheet['!freeze'] = {xSplit: 0, ySplit: formattedRows.length > 1 ? 1 : 0};
    const widthCount = Math.max(...formattedRows.map((row) => row.length), 1);
    sheet['!cols'] = Array.from({length: widthCount}, (_, column) => ({wch: Math.min(32, Math.max(12, ...formattedRows.map((row) => String(row[column] ?? '').length + 2)))}));
    xlsx.utils.book_append_sheet(workbook, sheet, name);
  }

  private overviewExportRows(summary: ReportSummaryDto): unknown[][] {
    return [
      ['查詢期間', '訂單數', '箱數', '已完成', '配送中', '配送失敗', '完成率', '已發布路線', '出車司機', '出車車輛'],
      [summary.from, summary.totalOrders, summary.totalBoxes, summary.completedOrders, summary.inDeliveryOrders, summary.failedOrders, this.percent(summary.completionRatePercent), summary.publishedRoutes, summary.dispatchedDrivers, summary.dispatchedVehicles],
      [], ['日期', '訂單數', '箱數', '已完成', '完成率'],
      ...summary.dailyTrend.map((day) => [day.date, day.totalOrders, day.totalBoxes, day.completedOrders, this.percent(day.completionRatePercent)]),
    ];
  }

  private orderExportRows(preview: ReportPreview): unknown[][] {
    const data = this.orderRows(preview).map((order) => [order.date, order.orderNumber, order.sequence, order.storeName, order.boxes, order.status, order.routeId, order.driverName, order.plateNumber, order.warehouseName]);
    return [['配送日期', '訂單編號', '順序', '門市', '箱數', '狀態', '路線', '司機', '車牌', '出貨倉庫'], ...data];
  }

  private routeExportRows(preview: ReportPreview): unknown[][] {
    const routes = this.rows(preview.routes, 'routes');
    const legs = routes.flatMap((route) => this.routeLegs(route).map((leg) => [
      route['date'], route['routeId'], leg.sequence, leg.fromName, leg.toName, leg.orderId,
      leg.startedAt, leg.endedAt, leg.durationMinutes, leg.systemKm, leg.calculationStatus,
    ]));
    return [
      ['日期', '路線', '倉庫', '司機', '車牌', '門市數', '訂單數', '箱數', '裝載率', '預估公里', '系統公里', '實際公里', '里程差異', '預估油費', '里程狀態'],
      ...routes.map((row) => [row['date'], row['routeId'], row['warehouseName'], row['driverName'], row['plateNumber'], row['distinctStores'], row['orders'], row['boxes'], this.percent(row['plannedLoadRatePercent']), row['plannedKm'], row['systemKm'], row['actualKm'], row['differenceKm'], row['plannedFuelCost'], row['mileageComparisonStatus']]),
      [],
      ['分段實際里程'],
      ['日期', '路線', '段次', '出發地', '抵達地', '訂單 ID', '開始時間', '結束時間', '分鐘', '系統公里', '計算狀態'],
      ...legs,
    ];
  }

  protected routeLegs(row: ReportRow): RouteLegReportRow[] {
    const legs = row['routeLegs'];
    return Array.isArray(legs) ? legs.filter((leg): leg is RouteLegReportRow => typeof leg === 'object' && leg !== null) : [];
  }

  private attendanceExportRows(preview: ReportPreview): unknown[][] {
    return [
      ['日期', '司機', '目前所屬倉庫', '應上班時間（含核准部分請假）', '打卡上班', '排定下班', '打卡下班', '加班分鐘', '打卡判定'],
      ...this.attendanceRows(preview).map(row => [row['workDate'], row['driverName'], row['warehouseName'], row['expectedStartAt'], row['clockInAt'],
        row['scheduledEndAt'], row['clockOutAt'], row['overtimeMinutes'], this.performanceLabel(row['attendanceStatus'])]),
      [],
      ['司機工作摘要'],
      ['司機', '排班天數', '出車趟數', '已發布路線', '訂單數', '已完成', '失敗', '預估公里', '實際公里'],
      ...this.rows(preview.drivers, 'drivers').map((row) => [row.driverName, row.scheduledWorkDays, row.startedTrips, row.publishedRoutes, row.assignedOrders, row.completedOrders, row.failedOrders, row.plannedKm, row.actualKm]),
    ];
  }

  private vehicleExportRows(preview: ReportPreview): unknown[][] {
    return [['車牌', '倉庫', '出車路線', '已發布路線', '訂單數', '箱數', '門市數', '平均裝載率', '預估公里', '實際公里', '里程狀態'], ...this.rows(preview.vehicles, 'vehicles').map((row) => [row.plateNumber, row.warehouseId, row.assignedRoutes, row.publishedRoutes, row.orders, row.boxes, row.distinctStores, this.percent(row.averageLoadRatePercent), row.publishedPlannedKm, row.actualKm, row.actualMileageStatus])];
  }

  private tripExportRows(preview: ReportPreview): unknown[][] {
    return [['日期', '車牌', '出貨倉庫', '路線ID', '出車時間', '實際收車時間', '狀態', '實際公里', '里程來源'],
      ...this.tripRows(preview).map(row => [row['date'], row['plateNumber'], row['warehouseName'], row['routeId'], row['startAt'], row['endAt'],
        this.performanceLabel(row['status']), row['actualKm'], this.performanceLabel(row['distanceSource'])])];
  }

  protected performanceLabel(value: unknown): string {
    const labels: Record<string, string> = {ON_TIME: '準時', LATE: '遲到', MISSING_CLOCK_IN: '缺上班卡',
      APPROVED_FULL_DAY_LEAVE: '核准整天請假（不計應到班）', MISSING_SHIFT_TIME: '班表時間不足', NOT_DUE: '尚未到班時間',
      OPEN: '尚未收車', RETURNED: '已收車', INVALID: '時間異常待核對', GPS_SETTLED: 'GPS 已結算', LEGACY_ODOMETER: '舊紀錄儀表差值', MISSING: '尚無可核對里程'};
    return labels[String(value)] ?? this.value(value);
  }

  private warehouseExportRows(preview: ReportPreview): unknown[][] {
    const warehouses = this.rows(preview.warehouses, 'warehouses').map((row) => ['倉庫', row.warehouseName, row.orders, row.boxes, row.distinctStores, row.routes, row.publishedRoutes, row.unassignedConfirmedOrders, row.completedOrders, this.percent(row.averageLoadRatePercent)]);
    const stores = this.rows(preview.stores, 'stores').map((row) => ['門市', row.storeName, row.orders, row.boxes, '--', '--', '--', '--', row.completedOrders, '--']);
    return [['類型', '名稱', '訂單數', '箱數', '門市數', '路線數', '已發布路線', '待排訂單', '已完成', '補充指標'], ...warehouses, ...stores];
  }

  private exceptionExportRows(preview: ReportPreview): unknown[][] {
    return [['建立時間', '異常類型', '狀態', '訂單', '倉庫', '門市', '司機', '路線', '不符商品', '異常原因', '處理結果', '處理時間分鐘'], ...this.exceptionRows(preview).map((row) => [row.createdAt, this.exceptionTypeLabel(row.type), row.status, row.orderNumber, row.warehouseId, row.storeId, row.driverId, row.routeId, row.loadingItemSummary ?? '', row.description, row.resolution, row.resolutionMinutes])];
  }

  private loadingExceptionExportRows(preview: ReportPreview): unknown[][] {
    return [['案件編號', '建立時間', '訂單', '案件狀態', '商品代碼', '商品名稱', '應點數量', '實點數量', '缺少數量', '單位', '點交結果', '點交時間', '點交備註'],
      ...this.exceptionRows(preview).filter(row => row.type === 'LOADING_MISMATCH').flatMap(row => {
        const prefix = [row.exceptionId, row.createdAt, row.orderNumber, row.status === 'OPEN' ? '待處理' : row.status === 'CLOSED' ? '已結案' : row.status];
        return row.loadingItems?.length ? row.loadingItems.map(item => [...prefix, item.productCode, item.itemName,
          item.expectedQuantity, item.loadedQuantity, item.missingQuantity, item.unit, this.loadingItemLabel(item), item.checkedAt, item.notes])
          : [[...prefix, null, null, null, null, null, null, row.loadingItemSummary, null, null]];
      })];
  }

  private notesExportRows(): unknown[][] {
    return [['欄位', '說明'], ['預估公里與油費', '依發布路線的規劃資料計算，沒有資料時留白。'], ['實際公里', '由行車里程紀錄提供，沒有完成紀錄時留白。'], ['完成率', '已完成訂單除以可計算完成率的訂單。'], ['司機與車輛', '只在後端能對應到已發布路線或異常案件時顯示。'], ['異常原因', '依異常案件的描述與處理結果顯示，不會用預設文字代替。']];
  }

  private asRows(value: unknown): ReportRow[] {
    return Array.isArray(value) ? value.filter((row): row is ReportRow => typeof row === 'object' && row !== null) : [];
  }

  private orderStatusLabel(status: OrderDto['status']): string {
    return {
      PENDING_CONFIRM: '待確認',
      CONFIRMED: '待排車',
      LOADED: '已點交',
      IN_DELIVERY: '配送中',
      NO_SIGNATURE: '無人簽收',
      COMPLETED: '已完成',
      CANCELLED: '已取消',
      FAILED: '配送失敗',
    }[status];
  }

  private selectedWarehouseLabel(): string { return this.warehouses().find((warehouse) => warehouse.id === this.warehouseId())?.name ?? '全部倉庫'; }
  private selectedStoreLabel(): string { return this.stores().find((store) => store.id === this.storeId())?.name ?? '全部門市'; }
  private selectedDriverLabel(): string { return this.drivers().find((driver) => driver.id === this.driverId())?.name ?? '全部司機'; }
  private selectedTonnageLabel(): string {
    return this.tonnageOptions().find((option) => option.value === this.tonnage())?.label ?? '全部噸位';
  }
  private today(): string { return new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Taipei'}).format(new Date()); }
}

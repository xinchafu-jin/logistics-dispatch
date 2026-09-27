import {Component, computed, inject, OnDestroy, OnInit, signal} from '@angular/core';
import {Router} from '@angular/router';
import {catchError, forkJoin, of, Subscription} from 'rxjs';
import {MatIconModule} from '@angular/material/icon';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {OrderDto, ReportCollectionDto, ReportPerformanceDto} from '../../../../core/services/dispatch-api.models';

type ReportPeriod = 'year' | 'month' | 'week';

interface ChartBucket {
  label: string;
  date: string;
  completed: number;
  total: number;
  height: number;
  from: string;
  to: string;
}

interface StatusSummary {
  label: string;
  detail: string;
  count: number;
  percentage: number | null;
  tone: string;
  filter: string;
}

interface OperationalSources {
  performance: ReportPerformanceDto | null;
  warehouses: ReportCollectionDto | null;
  exceptions: ReportCollectionDto | null;
}

interface WarehouseReportRow {
  warehouseId?: number | null;
  warehouseName?: string;
  orders?: number;
  completedOrders?: number;
  publishedRoutes?: number;
  unassignedConfirmedOrders?: number;
}

@Component({
  selector: 'app-operation-report',
  imports: [MatIconModule],
  templateUrl: './operation-report.html',
  styleUrl: './operation-report.scss',
})
export class OperationReport implements OnInit, OnDestroy {
  private readonly api = inject(DispatchApiService);
  private readonly router = inject(Router);
  private supplementalRequest?: Subscription;

  readonly activePeriod = signal<ReportPeriod>('week');
  readonly selectedYear = signal(new Date().getFullYear());
  readonly selectedMonth = signal(new Date().getMonth());
  readonly selectedWeek = signal(0);
  readonly orders = signal<OrderDto[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly operationalData = signal<OperationalSources | null>(null);
  readonly operationalLoading = signal(false);
  readonly operationalError = signal('');
  readonly warehouseError = signal('');
  readonly exceptionError = signal('');

  readonly periods: {id: ReportPeriod; label: string}[] = [
    {id: 'year', label: '年度'},
    {id: 'month', label: '月度'},
    {id: 'week', label: '週別'},
  ];

  // Current order state grouped by scheduled delivery date, not historical as-of data.
  // Keep the denominator aligned with the report API; never call it OTIF or on-time delivery.
  readonly report = computed(() => {
    const orders = this.ordersInPeriod();
    const today = this.todayTaipei();
    const count = (statuses: OrderDto['status'][]) => orders.filter((order) => statuses.includes(order.status)).length;
    const completed = count(['COMPLETED']);
    const eligible = count(['CONFIRMED', 'LOADED', 'IN_DELIVERY', 'NO_SIGNATURE', 'FAILED', 'COMPLETED']);
    return {
      total: orders.length,
      boxes: orders.reduce((sum, order) => sum + (order.boxCount || 0), 0),
      completed,
      eligible,
      rate: eligible ? (completed / eligible) * 100 : null,
      pendingApproval: count(['PENDING_CONFIRM']),
      waitingDispatch: count(['CONFIRMED']),
      waitingRate: eligible ? (count(['CONFIRMED']) / eligible) * 100 : null,
      inProgress: count(['LOADED', 'IN_DELIVERY']),
      noSignature: count(['NO_SIGNATURE']),
      failed: count(['FAILED']),
      exceptionRate: eligible ? (count(['FAILED', 'NO_SIGNATURE']) / eligible) * 100 : null,
      cancelled: count(['CANCELLED']),
      futureScheduled: orders.filter((order) => order.deliveryDate > today).length,
      range: this.periodRangeLabel(),
    };
  });

  readonly chartData = computed(() => this.buildChartData(this.ordersInPeriod()));
  readonly chartMaximum = computed(() => Math.max(1, ...this.chartData().map((bucket) => bucket.completed)));
  readonly statusBreakdown = computed<StatusSummary[]>(() => {
    const orders = this.ordersInPeriod();
    const groups: {statuses: OrderDto['status'][]; label: string; detail: string; tone: string; filter: string}[] = [
      {statuses: ['COMPLETED'], label: '已完成', detail: '已完成配送', tone: 'complete', filter: 'COMPLETED'},
      {statuses: ['LOADED', 'IN_DELIVERY'], label: '配送中', detail: '已點交或正在配送', tone: 'progress', filter: 'DELIVERY'},
      {statuses: ['CONFIRMED'], label: '待排車', detail: '已審核，尚待調度', tone: 'pending', filter: 'CONFIRMED'},
      {statuses: ['PENDING_CONFIRM'], label: '待審核', detail: '尚未確認配送需求', tone: 'pending', filter: 'PENDING_CONFIRM'},
      {statuses: ['NO_SIGNATURE'], label: '無人簽收', detail: '需要後續處理', tone: 'warning', filter: 'NO_SIGNATURE'},
      {statuses: ['FAILED'], label: '配送失敗', detail: '需要查明原因', tone: 'warning', filter: 'FAILED'},
      {statuses: ['CANCELLED'], label: '已取消', detail: '不計入完成率', tone: 'neutral', filter: 'CANCELLED'},
    ];
    return groups.map((group) => {
      const count = orders.filter((order) => group.statuses.includes(order.status)).length;
      return {...group, count, percentage: this.rate(count, orders.length)};
    });
  });

  readonly attentionOrders = computed(() => this.ordersInPeriod()
    .filter((order) => order.status === 'FAILED' || order.status === 'NO_SIGNATURE' || order.status === 'CONFIRMED')
    .sort((left, right) => left.deliveryDate.localeCompare(right.deliveryDate))
    .slice(0, 6));

  readonly capacity = computed(() => {
    const data = this.operationalData();
    if (!data?.performance) return null;
    return {
      people: data.performance.workforce,
      fleet: data.performance.fleet,
      ...this.caseStats(),
    };
  });

  readonly caseStats = computed(() => {
    const cases = this.operationalData()?.exceptions;
    if (!cases) return null;
    const recordedExceptions = this.collectionNumber(cases, 'recordedCases');
    const closedExceptions = this.collectionNumber(cases, 'closedCases');
    return {recordedExceptions, closedExceptions, openedExceptions: this.collectionNumber(cases, 'openCases'),
      caseClosureRate: this.rate(closedExceptions, recordedExceptions)};
  });

  readonly warehouseRows = computed(() => {
    const data = this.operationalData();
    if (!data) return [];
    const directory = this.collectionRows<WarehouseReportRow>(data.warehouses, 'warehouses');
    const extra: WarehouseReportRow[] = (data.performance?.warehouses ?? []).filter(performance => !directory.some(row => row.warehouseId === performance.warehouseId))
      .map(performance => ({warehouseId: performance.warehouseId, warehouseName: performance.warehouseName}));
    return [...directory, ...extra]
      .map((row) => {
        const orders = this.ordersInPeriod().filter((order) => order.warehouseId === row.warehouseId);
        const eligible = orders.filter((order) => ['CONFIRMED', 'LOADED', 'IN_DELIVERY', 'NO_SIGNATURE', 'FAILED', 'COMPLETED'].includes(order.status)).length;
        const completed = orders.filter((order) => order.status === 'COMPLETED').length;
        const performance = data.performance?.warehouses.find(performance => performance.warehouseId === row.warehouseId);
        return {...row, orders: orders.length,
          completedOrders: completed, eligibleOrders: eligible, completionRate: this.rate(completed, eligible),
          people: performance?.workforce, fleet: performance?.fleet};
      })
      .sort((left, right) => (left.warehouseId ?? Number.MAX_SAFE_INTEGER) - (right.warehouseId ?? Number.MAX_SAFE_INTEGER));
  });

  readonly warehouseTotals = computed(() => {
    if (!this.operationalData()) return null;
    const rows = this.warehouseRows();
    const eligible = rows.reduce((sum, row) => sum + row.eligibleOrders, 0);
    const completed = rows.reduce((sum, row) => sum + row.completedOrders, 0);
    return {eligible, completed, completionRate: this.rate(completed, eligible)};
  });

  ngOnInit(): void {
    this.api.getOrders().subscribe({
      next: (orders) => {
        this.orders.set(orders);
        this.syncPeriodToAvailableData();
        this.loading.set(false);
        this.loadOperationalData();
      },
      error: () => {
        this.errorMessage.set('暫時無法載入訂單資料，請稍後再試。');
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void { this.supplementalRequest?.unsubscribe(); }

  protected setPeriod(period: ReportPeriod): void {
    this.activePeriod.set(period);
    if (period === 'week') {
      this.selectedWeek.set(Math.min(this.selectedWeek(), this.monthWeeks().length - 1));
    }
    this.loadOperationalData();
  }

  protected stepPeriod(direction: -1 | 1): void {
    const year = this.selectedYear();
    const month = this.selectedMonth();
    if (this.activePeriod() === 'year') {
      this.selectedYear.set(year + direction);
      this.loadOperationalData();
      return;
    }
    if (this.activePeriod() === 'month') {
      const next = new Date(year, month + direction, 1);
      this.selectedYear.set(next.getFullYear());
      this.selectedMonth.set(next.getMonth());
      this.loadOperationalData();
      return;
    }
    // 以七天移動，跨月、跨年時不重複或跳過同一個自然週。
    const anchor = new Date(this.periodDates().start);
    anchor.setDate(anchor.getDate() + direction * 7 + 3);
    this.selectedYear.set(anchor.getFullYear());
    this.selectedMonth.set(anchor.getMonth());
    this.selectedWeek.set(this.weekIndexForDate(anchor));
    this.loadOperationalData();
  }

  protected openHistory(from?: string, to?: string, warehouseId?: number, sheet = 'overview', status?: string): void {
    const dates = this.periodDates();
    void this.router.navigate(['/dispatch/history'], {queryParams: {
      from: from ?? this.toDateString(dates.start),
      to: to ?? this.toDateString(dates.end),
      ...(warehouseId ? {warehouseId} : {}),
      sheet,
      ...(status ? {status} : {}),
    }});
  }

  protected drillInto(bucket: ChartBucket): void { this.openHistory(bucket.from, bucket.to, undefined, 'orders'); }
  protected openWarehouse(row: WarehouseReportRow, sheet = 'orders'): void { this.openHistory(undefined, undefined, row.warehouseId ?? undefined, sheet); }
  protected openOrders(status?: string): void { this.openHistory(undefined, undefined, undefined, 'orders', status); }
  protected openSheet(sheet: string): void { this.openHistory(undefined, undefined, undefined, sheet); }
  protected openOrder(order: OrderDto): void {
    void this.router.navigate(['/dispatch/orders'], {queryParams: {order: order.orderNumber}});
  }

  protected chartHeading(): string {
    return this.activePeriod() === 'year' ? '每月已完成 / 總訂單' : this.activePeriod() === 'month' ? '每週已完成 / 總訂單' : '週一至週六已完成 / 總訂單';
  }

  protected chartFootnote(): string {
    const period = this.activePeriod();
    const sundayCount = period === 'week' ? this.ordersInPeriod().filter(order => this.parseDate(order.deliveryDate).getDay() === 0).length : 0;
    const scope = period === 'month' ? '按週一至週日分週，月初與月底僅計入本月日期。'
      : period === 'week' ? `一週固定週一至週日；週日休息，圖表只顯示週一至週六。${sundayCount ? `本週另有 ${sundayCount} 筆週日歷史訂單，仍保留在總覽統計與歷史查詢。` : ''}` : '';
    return `依預定配送日分組，顯示查詢當下的訂單狀態。${scope}點選${period === 'year' ? '月份' : period === 'month' ? '週別' : '日期'}可查看明細。`;
  }

  protected percent(value: number | null): string { return value === null ? '—' : `${value.toFixed(1)}%`; }
  protected number(value: number | null): string { return value === null ? '—' : value.toLocaleString('zh-TW', {maximumFractionDigits: 1}); }
  protected retryOperationalData(): void { this.loadOperationalData(); }

  protected statusLabel(status: OrderDto['status']): string {
    return {PENDING_CONFIRM: '待審核', CONFIRMED: '待排車', LOADED: '已點交', IN_DELIVERY: '配送中',
      NO_SIGNATURE: '無人簽收', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '配送失敗'}[status];
  }

  private syncPeriodToAvailableData(): void {
    const today = this.todayTaipei();
    const latest = this.orders()
      .filter(order => order.deliveryDate <= today)
      .map((order) => this.parseDate(order.deliveryDate))
      .filter((date) => !Number.isNaN(date.getTime()))
      .sort((left, right) => right.getTime() - left.getTime())[0];
    const anchor = latest ?? this.parseDate(today);
    this.selectedYear.set(anchor.getFullYear());
    this.selectedMonth.set(anchor.getMonth());
    this.selectedWeek.set(this.weekIndexForDate(anchor));
  }

  private loadOperationalData(): void {
    const {start, end} = this.periodDates();
    const query = {period: 'CUSTOM' as const, from: this.toDateString(start), to: this.toDateString(end)};
    this.supplementalRequest?.unsubscribe();
    this.operationalLoading.set(true);
    this.operationalError.set('');
    this.warehouseError.set('');
    this.exceptionError.set('');
    this.operationalData.set(null);
    this.supplementalRequest = forkJoin({
      performance: this.api.getReportPerformance(query).pipe(catchError(() => {
        this.operationalError.set('人員與出車紀錄暫時無法載入；訂單、倉庫與異常資料仍可查看。');
        return of(null);
      })),
      warehouses: this.api.getReportWarehouses(query).pipe(catchError(() => {
        this.warehouseError.set('倉庫訂單查詢暫時失敗；已取得的人車倉庫清單與訂單現況仍保留。');
        return of(null);
      })),
      exceptions: this.api.getReportExceptions(query).pipe(catchError(() => {
        this.exceptionError.set('異常案件暫時無法載入；其餘報表仍可查看。');
        return of(null);
      })),
    }).subscribe({
      next: (data) => {
        this.operationalData.set(data);
        this.operationalLoading.set(false);
      },
      error: () => {
        this.operationalError.set('運力與倉庫資料暫時無法載入，訂單現況仍可查看。');
        this.operationalLoading.set(false);
      },
    });
  }

  private collectionRows<T>(collection: ReportCollectionDto | null, key: string): T[] {
    const value = collection?.[key];
    return Array.isArray(value) ? value as T[] : [];
  }

  private collectionNumber(collection: ReportCollectionDto, key: string): number {
    const value = collection[key];
    return typeof value === 'number' ? value : 0;
  }

  private rate(numerator: number, denominator: number): number | null {
    return denominator > 0 ? numerator / denominator * 100 : null;
  }

  private todayTaipei(): string {
    return new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Taipei'}).format(new Date());
  }

  private ordersInPeriod(): OrderDto[] {
    const {start, end} = this.periodDates();
    return this.orders().filter((order) => {
      const date = this.parseDate(order.deliveryDate);
      return date >= start && date <= end;
    });
  }

  private buildChartData(orders: OrderDto[]): ChartBucket[] {
    const {start} = this.periodDates();
    const buckets = this.activePeriod() === 'year'
      ? Array.from({length: 12}, (_, month) => {
          const from = new Date(this.selectedYear(), month, 1);
          return {label: `${month + 1}月`, from, to: new Date(this.selectedYear(), month + 1, 0)};
        })
      : this.activePeriod() === 'month'
        ? this.monthWeeks().map((week, index) => ({label: `第${index + 1}週`, from: week.start, to: week.end}))
        : Array.from({length: 6}, (_, day) => {
            const from = new Date(start);
            from.setDate(start.getDate() + day);
            return {label: this.weekdayLabel(from), from, to: from};
          });

    const counts = buckets.map(({from, to}) => {
      const included = orders.filter((order) => {
        const date = this.parseDate(order.deliveryDate);
        return date >= from && date <= to;
      });
      return {total: included.length, completed: included.filter((order) => order.status === 'COMPLETED').length};
    });
    const max = Math.max(1, ...counts.map((count) => count.completed));
    return buckets.map((bucket, index) => ({
      label: bucket.label,
      date: this.bucketDateLabel(bucket.from, bucket.to),
      ...counts[index],
      height: (counts[index].completed / max) * 100,
      from: this.toDateString(bucket.from),
      to: this.toDateString(bucket.to),
    }));
  }

  private periodDates(): {start: Date; end: Date} {
    const year = this.selectedYear();
    const month = this.selectedMonth();
    if (this.activePeriod() === 'year') return {start: new Date(year, 0, 1), end: new Date(year, 11, 31)};
    if (this.activePeriod() === 'month') return {start: new Date(year, month, 1), end: new Date(year, month + 1, 0)};
    const start = this.mondayOf(new Date(year, month, 1));
    start.setDate(start.getDate() + this.selectedWeek() * 7);
    const end = new Date(start);
    end.setDate(start.getDate() + 6);
    return {start, end};
  }

  private periodRangeLabel(): string {
    const {start, end} = this.periodDates();
    return `${this.toDateString(start).replaceAll('-', '/')} — ${this.toDateString(end).replaceAll('-', '/')}`;
  }

  private mondayOf(value: Date): Date {
    const monday = new Date(value);
    monday.setDate(value.getDate() - (value.getDay() + 6) % 7);
    return monday;
  }

  private weekIndexForDate(value: Date): number {
    const monthStart = new Date(value.getFullYear(), value.getMonth(), 1);
    return Math.floor(((value.getDate() - 1) + (monthStart.getDay() + 6) % 7) / 7);
  }

  private monthWeeks(): {start: Date; end: Date}[] {
    const monthStart = new Date(this.selectedYear(), this.selectedMonth(), 1);
    const monthEnd = new Date(this.selectedYear(), this.selectedMonth() + 1, 0);
    const weeks: {start: Date; end: Date}[] = [];
    for (const monday = this.mondayOf(monthStart); monday <= monthEnd; monday.setDate(monday.getDate() + 7)) {
      const sunday = new Date(monday);
      sunday.setDate(monday.getDate() + 6);
      weeks.push({start: new Date(Math.max(monthStart.getTime(), monday.getTime())),
        end: new Date(Math.min(monthEnd.getTime(), sunday.getTime()))});
    }
    return weeks;
  }

  private bucketDateLabel(from: Date, to: Date): string {
    if (this.activePeriod() === 'year') return `${from.getFullYear()}/${String(from.getMonth() + 1).padStart(2, '0')}`;
    if (this.activePeriod() === 'month') return `${this.monthDayLabel(from)}–${this.monthDayLabel(to)}`;
    return this.monthDayLabel(from);
  }

  private parseDate(value: string): Date { return new Date(`${value}T00:00:00`); }
  private toDateString(value: Date): string { return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`; }
  private weekdayLabel(value: Date): string { return ['日', '一', '二', '三', '四', '五', '六'][value.getDay()]; }
  private monthDayLabel(value: Date): string { return `${String(value.getMonth() + 1).padStart(2, '0')}/${String(value.getDate()).padStart(2, '0')}`; }
}

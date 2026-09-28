import {Component, computed, inject, OnDestroy, OnInit, signal} from '@angular/core';
import {Router} from '@angular/router';
import {catchError, forkJoin, of, Subscription} from 'rxjs';
import {MatIconModule} from '@angular/material/icon';
import {NgTemplateOutlet} from '@angular/common';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {OrderDto, ReportPerformanceDto, ReportOutcomesDto} from '../../../../core/services/dispatch-api.models';
import {UNSETTLED_ORDER_CATEGORIES, matchesOrderProgress} from '../../report-unsettled-orders';

type ReportPeriod = 'year' | 'month' | 'week' | 'day';

interface OperationalSources {
  performance: ReportPerformanceDto | null;
  outcomes: ReportOutcomesDto | null;
  orders: OrderDto[] | null;
}

interface ChartBucket {from: string; to: string; label: string; dateLabel: string; count: number; other: number;}
interface StatusTile {label: string; value: number; metric: string; icon: string; tone?: 'warning' | 'muted';}
interface RateDial {label: string; value: number | null; numerator: number; denominator: number; sheet: string; metric: string;}


@Component({
  selector: 'app-operation-report',
  imports: [MatIconModule, NgTemplateOutlet],
  templateUrl: './operation-report.html',
  styleUrl: './operation-report.scss',
})
export class OperationReport implements OnInit, OnDestroy {
  private readonly api = inject(DispatchApiService);
  private readonly router = inject(Router);
  private supplementalRequest?: Subscription;

  // 首次進入顯示本月累計；週別仍由使用者選擇，不將其他期間混入本週。
  readonly activePeriod = signal<ReportPeriod>('month');
  readonly selectedYear = signal(new Date().getFullYear());
  readonly selectedMonth = signal(new Date().getMonth());
  readonly selectedWeek = signal(0);
  readonly selectedDay = signal(new Date().getDate());
  readonly loading = signal(true);
  readonly operationalData = signal<OperationalSources | null>(null);
  readonly operationalLoading = signal(false);
  readonly operationalError = signal('');
  readonly outcomeError = signal('');
  readonly unsettledError = signal('');

  readonly periods: {id: ReportPeriod; label: string}[] = [
    {id: 'year', label: '年度'},
    {id: 'month', label: '月度'},
    {id: 'week', label: '週別'},
    {id: 'day', label: '日別'},
  ];

  readonly capacity = computed(() => {
    const data = this.operationalData();
    if (!data?.performance) return null;
    return {
      people: data.performance.workforce,
    };
  });
  readonly outcomes = computed(() => this.operationalData()?.outcomes ?? null);
  readonly delivery = computed(() => this.outcomes()?.delivery ?? null);
  readonly fleet = computed(() => this.operationalData()?.performance?.fleet ?? null);
  readonly recovery = computed(() => this.outcomes()?.recovery ?? null);
  readonly warehouseSummary = computed(() => this.outcomes()?.loading ?? null);

  readonly periodOrders = computed(() => {
    const orders = this.operationalData()?.orders;
    if (!orders) return null;
    const {start, end} = this.periodDates();
    const from = this.toDateString(start), to = this.toDateString(end);
    return orders.filter(order => order.deliveryDate >= from && order.deliveryDate <= to);
  });
  readonly unsettledStats = computed(() => {
    const rows = this.periodOrders();
    if (!rows) return null;
    return {total: rows.length, unsettled: rows.filter(row => matchesOrderProgress(row, 'unsettled')).length,
      completed: rows.filter(row => row.status === 'COMPLETED').length};
  });

  readonly peopleDials = computed<RateDial[] | null>(() => {
    const p = this.capacity()?.people;
    return p ? [
      {label: '打卡率', value: p.attendanceRate, numerator: p.attendedShifts, denominator: p.dueShifts, sheet: 'attendance', metric: 'clocked-in'},
      {label: '準時上班率', value: p.onTimeRate, numerator: p.onTimeShifts, denominator: p.attendedShifts, sheet: 'attendance', metric: 'on-time'},
      {label: '加班率', value: p.overtimeRate, numerator: p.overtimeShifts, denominator: p.finishedShifts, sheet: 'attendance', metric: 'overtime'},
    ] : null;
  });
  readonly peopleTiles = computed<StatusTile[] | null>(() => {
    const p = this.capacity()?.people;
    return p ? [
      {label: '遲到', value: p.lateShifts, metric: 'late', icon: 'schedule', tone: 'warning'},
      {label: '缺上班卡', value: p.missingClockInShifts, metric: 'missing-clock-in', icon: 'event_busy', tone: 'warning'},
    ] : null;
  });
  readonly orderTiles = computed<StatusTile[] | null>(() => {
    const d = this.delivery();
    return d ? [
      {label: '完整交付', value: d.fullOrders, metric: 'full', icon: 'task_alt'},
      {label: '交貨不完整', value: d.deliveredOrders - d.fullOrders - d.missingQualityOrders, metric: 'incomplete', icon: 'inventory_2', tone: 'warning'},
      {label: '未確認交付', value: d.outstandingOrders, metric: 'outstanding', icon: 'pending_actions', tone: 'warning'},
      {label: '箱數待核對', value: d.missingQualityOrders, metric: 'missing-quality', icon: 'fact_check', tone: 'muted'},
    ] : null;
  });
  readonly unsettledTiles = computed<StatusTile[] | null>(() => {
    const rows = this.periodOrders();
    return rows ? UNSETTLED_ORDER_CATEGORIES.map(item => ({label: item.label, icon: item.icon, metric: item.id,
      value: rows.filter(row => matchesOrderProgress(row, item.id)).length})) : null;
  });
  readonly loadingTiles = computed<StatusTile[] | null>(() => {
    const w = this.warehouseSummary();
    return w ? [
      {label: '點交相符', value: w.matchedOrders, metric: 'matched', icon: 'checklist'},
      {label: '點交不符', value: w.mismatchedOrders, metric: 'mismatched', icon: 'rule', tone: 'warning'},
      {label: '點交紀錄缺漏', value: w.missingLoadingOrders, metric: 'missing-loading', icon: 'fact_check', tone: 'muted'},
      {label: '到期未排車', value: w.dueUnassignedOrders, metric: 'unassigned', icon: 'local_shipping', tone: 'warning'},
    ] : null;
  });

  readonly tripBuckets = computed<ChartBucket[] | null>(() => {
    const p = this.operationalData()?.performance;
    return p ? this.calendarBuckets().map(bucket => ({...bucket, other: 0,
      count: p.trips.filter(trip => typeof trip['date'] === 'string' && trip['date'] >= bucket.from && trip['date'] <= bucket.to).length})) : null;
  });
  readonly orderBuckets = computed<ChartBucket[] | null>(() => this.outcomes()
    ? this.calendarBuckets().map(bucket => {
      const rows = this.outcomes()!.daily.filter(day => day.date >= bucket.from && day.date <= bucket.to);
      const count = rows.reduce((sum, day) => sum + day.fullOrders, 0);
      return {...bucket, count, other: rows.reduce((sum, day) => sum + day.dueOrders, 0) - count};
    }) : null);
  readonly warehouseComparisons = computed(() => this.outcomes()?.warehouses ?? null);
  readonly tripChartHasData = computed(() => this.tripBuckets()?.some(b => b.count > 0) ?? false);
  readonly orderChartHasData = computed(() => this.orderBuckets()?.some(b => b.count + b.other > 0) ?? false);
  readonly unsettledChartHasData = computed(() => this.unsettledTiles()?.some(tile => tile.value > 0) ?? false);

  protected chartHeight(count: number, buckets: ChartBucket[]): number {
    const max = Math.max(1, ...buckets.map(b => b.count + b.other));
    return count / max * 100;
  }
  protected unsettledHeight(value: number): number {
    return value / Math.max(1, ...(this.unsettledTiles() ?? []).map(row => row.value)) * 100;
  }
  protected openBucket(bucket: ChartBucket, sheet: string): void {
    void this.router.navigate(['/dispatch/history'], {queryParams: {from: bucket.from, to: bucket.to, sheet}});
  }
  protected scopeLabel(): string {
    return this.activePeriod() === 'year' ? '每月' : this.activePeriod() === 'month' ? '每週'
      : this.activePeriod() === 'day' ? '當日' : '週一至週六';
  }
  private calendarBuckets(): Omit<ChartBucket, 'count' | 'other'>[] {
    const {start} = this.periodDates();
    const ranges = this.activePeriod() === 'year'
      ? Array.from({length: 12}, (_, m) => ({start: new Date(this.selectedYear(), m, 1), end: new Date(this.selectedYear(), m + 1, 0), label: `${m + 1}月`, dateLabel: ''}))
      : this.activePeriod() === 'month'
        ? this.monthWeeks().map((week, i) => ({...week, label: `第${i + 1}週`, dateLabel: `${week.start.getDate()}–${week.end.getDate()}日`}))
        : this.activePeriod() === 'day'
          ? [{start, end: start, label: '當日', dateLabel: `${start.getMonth() + 1}/${start.getDate()}`}]
        : Array.from({length: 6}, (_, d) => {
          const day = new Date(start); day.setDate(day.getDate() + d);
          return {start: day, end: day, label: `週${['一', '二', '三', '四', '五', '六'][d]}`, dateLabel: `${day.getMonth() + 1}/${day.getDate()}`};
        });
    return ranges.map(range => ({from: this.toDateString(range.start), to: this.toDateString(range.end), label: range.label, dateLabel: range.dateLabel}));
  }

  ngOnInit(): void {
    this.syncPeriodToToday();
    this.loadOperationalData();
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
    if (this.activePeriod() === 'day') {
      const next = new Date(this.periodDates().start);
      next.setDate(next.getDate() + direction);
      this.selectedYear.set(next.getFullYear());
      this.selectedMonth.set(next.getMonth());
      this.selectedDay.set(next.getDate());
      this.selectedWeek.set(this.weekIndexForDate(next));
      this.loadOperationalData();
      return;
    }
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
    this.selectedDay.set(anchor.getDate());
    this.selectedWeek.set(this.weekIndexForDate(anchor));
    this.loadOperationalData();
  }

  protected openHistory(sheet = 'overview', metric = ''): void {
    const dates = this.periodDates();
    void this.router.navigate(['/dispatch/history'], {queryParams: {
      from: this.toDateString(dates.start),
      to: this.toDateString(dates.end),
      sheet,
      ...(metric ? {metric} : {}),
    }});
  }

  protected openSheet(sheet: string): void { this.openHistory(sheet); }
  protected openMetric(sheet: string, metric: string): void { this.openHistory(sheet, metric); }
  protected dialDegrees(value: number | null): number { return value === null ? 0 : Math.min(100, Math.max(0, value)) * 3.6; }

  protected openWarehouse(warehouseId: number | null): void {
    const dates = this.periodDates();
    void this.router.navigate(['/dispatch/history'], {queryParams: {
      from: this.toDateString(dates.start), to: this.toDateString(dates.end), sheet: 'loading-quality',
      ...(warehouseId === null ? {} : {warehouseId}),
    }});
  }

  protected percent(value: number | null): string { return value === null ? '—' : `${value.toFixed(1)}%`; }
  protected number(value: number | null): string { return value === null ? '—' : value.toLocaleString('zh-TW', {maximumFractionDigits: 1}); }
  protected retryOperationalData(): void { this.loadOperationalData(); }

  private syncPeriodToToday(): void {
    const anchor = this.parseDate(this.todayTaipei());
    this.selectedYear.set(anchor.getFullYear());
    this.selectedMonth.set(anchor.getMonth());
    this.selectedDay.set(anchor.getDate());
    this.selectedWeek.set(this.weekIndexForDate(anchor));
  }

  private loadOperationalData(): void {
    const {start, end} = this.periodDates();
    const query = {period: 'CUSTOM' as const, from: this.toDateString(start), to: this.toDateString(end)};
    this.supplementalRequest?.unsubscribe();
    this.operationalLoading.set(true);
    this.operationalError.set('');
    this.outcomeError.set('');
    this.unsettledError.set('');
    this.operationalData.set(null);
    this.supplementalRequest = forkJoin({
      performance: this.api.getReportPerformance(query).pipe(catchError(() => {
        this.operationalError.set('人員打卡與出車資料暫時無法載入。');
        return of(null);
      })),
      outcomes: this.api.getReportOutcomes(query).pipe(catchError(() => {
        this.outcomeError.set('配送成果資料暫時無法載入。');
        return of(null);
      })),
      orders: this.api.getOrders().pipe(catchError(() => {
        this.unsettledError.set('未結單訂單資料暫時無法載入。');
        return of(null);
      })),
    }).subscribe({
      next: (data) => {
        this.operationalData.set(data);
        this.loading.set(false);
        this.operationalLoading.set(false);
      },
      error: () => {
        this.operationalError.set('營運資料暫時無法載入。');
        this.operationalLoading.set(false);
        this.loading.set(false);
      },
    });
  }

  private todayTaipei(): string {
    return new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Taipei'}).format(new Date());
  }


  private periodDates(): {start: Date; end: Date} {
    const year = this.selectedYear();
    const month = this.selectedMonth();
    if (this.activePeriod() === 'year') return {start: new Date(year, 0, 1), end: new Date(year, 11, 31)};
    if (this.activePeriod() === 'month') return {start: new Date(year, month, 1), end: new Date(year, month + 1, 0)};
    if (this.activePeriod() === 'day') {
      const day = Math.min(this.selectedDay(), new Date(year, month + 1, 0).getDate());
      const start = new Date(year, month, day);
      return {start, end: new Date(start)};
    }
    const start = this.mondayOf(new Date(year, month, 1));
    start.setDate(start.getDate() + this.selectedWeek() * 7);
    const end = new Date(start);
    end.setDate(start.getDate() + 6);
    return {start, end};
  }

  protected periodRangeLabel(): string {
    const {start, end} = this.periodDates();
    if (this.activePeriod() === 'day') return this.toDateString(start).replaceAll('-', '/');
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

  private parseDate(value: string): Date { return new Date(`${value}T00:00:00`); }
  private toDateString(value: Date): string { return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`; }
}

import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { forkJoin } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { OrderDto, StoreDto } from '../../../../core/services/dispatch-api.models';

type ReportPeriod = 'year' | 'month' | 'week';

interface ChartBucket {
  label: string;
  date: string;
  count: number;
  height: number;
  from: string;
  to: string;
}

interface AreaPerformance {
  area: string;
  completed: number;
  routes: number;
}

interface ReportMetrics {
  range: string;
  score: number;
  label: string;
  note: string;
  completion: string;
  total: number;
}

interface StatusSummary {
  label: string;
  detail: string;
  percentage: string;
  tone: string;
}

@Component({
  selector: 'app-operation-report',
  imports: [MatIconModule],
  templateUrl: './operation-report.html',
  styleUrl: './operation-report.scss',
})
export class OperationReport implements OnInit {
  private readonly api = inject(DispatchApiService);
  private readonly router = inject(Router);

  readonly activePeriod = signal<ReportPeriod>('week');
  readonly selectedYear = signal(new Date().getFullYear());
  readonly selectedMonth = signal(new Date().getMonth());
  readonly selectedWeek = signal(0);
  readonly orders = signal<OrderDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');

  readonly periods: { id: ReportPeriod; label: string }[] = [
    { id: 'year', label: '年度' },
    { id: 'month', label: '本月' },
    { id: 'week', label: '本週' },
  ];

  readonly report = computed<ReportMetrics>(() => {
    const orders = this.ordersInPeriod();
    const total = orders.length;
    const completed = orders.filter((order) => order.status === 'COMPLETED').length;
    const score = total ? Math.round((completed / total) * 100) : 0;

    return {
      range: this.periodRangeLabel(),
      score,
      label: total ? (score >= 95 ? '配送完整率達標' : '配送完整率待留意') : '尚無配送資料',
      note: total ? '依此期間所有訂單的完成狀態統計。' : '這個期間尚未有配送訂單。',
      completion: `${total ? ((completed / total) * 100).toFixed(1) : '0.0'}%`,
      total,
    };
  });

  readonly chartData = computed(() => this.buildChartData(this.ordersInPeriod()));
  readonly areas = computed(() => this.buildAreas(this.ordersInPeriod(), this.stores()));
  readonly statusBreakdown = computed<StatusSummary[]>(() => {
    const orders = this.ordersInPeriod();
    const total = orders.length || 1;
    const statuses: { status: OrderDto['status']; label: string; detail: string; tone: string }[] = [
      { status: 'COMPLETED', label: '已完成', detail: '正常簽收結案', tone: 'complete' },
      { status: 'IN_DELIVERY', label: '配送中', detail: '目前正在配送', tone: 'followup' },
      { status: 'LOADED', label: '已點交', detail: '已在倉庫裝車，尚未抵達門市', tone: 'followup' },
      { status: 'FAILED', label: '配送失敗', detail: '需要異常處理', tone: 'exception' },
      { status: 'CONFIRMED', label: '待排車', detail: '等待調度安排', tone: 'pending' },
    ];

    return statuses.map((item) => ({
      ...item,
      percentage: `${((orders.filter((order) => order.status === item.status).length / total) * 100).toFixed(1)}%`,
    }));
  });

  readonly failedOrders = computed(() => this.ordersInPeriod().filter((order) => order.status === 'FAILED'));

  ngOnInit(): void {
    this.loadReport();
  }

  protected setPeriod(period: ReportPeriod): void {
    this.activePeriod.set(period);
  }

  protected drillInto(bucket: ChartBucket): void {
    if (this.activePeriod() === 'year') {
      const date = this.parseDate(bucket.from);
      this.selectedYear.set(date.getFullYear());
      this.selectedMonth.set(date.getMonth());
      this.selectedWeek.set(0);
      this.activePeriod.set('month');
      return;
    }

    if (this.activePeriod() === 'month') {
      this.selectedWeek.set(this.weekIndexForDate(this.parseDate(bucket.from)));
      this.activePeriod.set('week');
      return;
    }

    void this.router.navigate(['/dispatch/history'], {queryParams: {from: bucket.from, to: bucket.to}});
  }

  protected chartHeading(): string {
    return this.activePeriod() === 'year'
      ? '每月訂單筆數'
      : this.activePeriod() === 'month'
        ? '每週訂單筆數'
        : '每日訂單筆數';
  }

  protected chartFootnote(): string {
    return this.activePeriod() === 'year'
      ? '點選月份可查看整月訂單。'
      : this.activePeriod() === 'month'
        ? '點選週次可查看該週每日訂單。'
        : '點選日期可直接查看該日訂單歷史。';
  }

  private loadReport(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    forkJoin({orders: this.api.getOrders(), stores: this.api.getStores()}).subscribe({
      next: ({orders, stores}) => {
        this.orders.set(orders);
        this.stores.set(stores);
        this.syncPeriodToAvailableData();
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('暫時無法載入訂單資料，請稍後再試。');
        this.loading.set(false);
      },
    });
  }

  private syncPeriodToAvailableData(): void {
    const latest = this.orders()
      .map((order) => this.parseDate(order.deliveryDate))
      .filter((date) => !Number.isNaN(date.getTime()))
      .sort((left, right) => right.getTime() - left.getTime())[0];
    if (!latest) {
      return;
    }
    this.selectedYear.set(latest.getFullYear());
    this.selectedMonth.set(latest.getMonth());
    this.selectedWeek.set(this.weekIndexForDate(latest));
  }

  private ordersInPeriod(): OrderDto[] {
    const {start, end} = this.periodDates();
    return this.orders().filter((order) => {
      const date = this.parseDate(order.deliveryDate);
      return date >= start && date <= end;
    });
  }

  private buildChartData(orders: OrderDto[]): ChartBucket[] {
    const {start, end} = this.periodDates();
    const buckets = this.activePeriod() === 'year'
      ? Array.from({length: 12}, (_, month) => {
          const from = new Date(this.selectedYear(), month, 1);
          return {label: `${month + 1}月`, from, to: new Date(this.selectedYear(), month + 1, 0)};
        })
      : this.activePeriod() === 'month'
        ? Array.from({length: 4}, (_, week) => {
            const from = new Date(this.selectedYear(), this.selectedMonth(), 1 + (week * 7));
            const to = new Date(this.selectedYear(), this.selectedMonth(), week === 3 ? end.getDate() : (week + 1) * 7);
            return {label: `第 ${week + 1} 週`, from, to};
          })
        : Array.from({length: 7}, (_, day) => {
            const from = new Date(start);
            from.setDate(start.getDate() + day);
            return {label: this.weekdayLabel(from), from, to: from};
          });

    const counts = buckets.map(({from, to}) => orders.filter((order) => {
      const date = this.parseDate(order.deliveryDate);
      return date >= from && date <= to;
    }).length);
    const max = Math.max(...counts, 1);

    return buckets.map((bucket, index) => ({
      label: bucket.label,
      date: this.bucketDateLabel(bucket.from, bucket.to),
      count: counts[index],
      height: (counts[index] / max) * 100,
      from: this.toDateString(bucket.from),
      to: this.toDateString(bucket.to),
    }));
  }

  private buildAreas(orders: OrderDto[], stores: StoreDto[]): AreaPerformance[] {
    const grouped = new Map<string, {total: number; completed: number}>();
    const storesById = new Map(stores.filter((store) => store.id != null).map((store) => [store.id!, store]));

    orders.forEach((order) => {
      const area = this.extractArea(storesById.get(order.storeId)?.address ?? '');
      const current = grouped.get(area) ?? {total: 0, completed: 0};
      current.total += 1;
      if (order.status === 'COMPLETED') {
        current.completed += 1;
      }
      grouped.set(area, current);
    });

    return [...grouped.entries()].map(([area, value]) => ({
      area,
      completed: value.total ? (value.completed / value.total) * 100 : 0,
      routes: value.total,
    }));
  }

  private periodDates(): {start: Date; end: Date} {
    const year = this.selectedYear();
    const month = this.selectedMonth();
    if (this.activePeriod() === 'year') {
      return {start: new Date(year, 0, 1), end: new Date(year, 11, 31)};
    }
    if (this.activePeriod() === 'month') {
      return {start: new Date(year, month, 1), end: new Date(year, month + 1, 0)};
    }
    const start = new Date(year, month, 1 + (this.selectedWeek() * 7));
    const end = new Date(year, month, Math.min(start.getDate() + 6, new Date(year, month + 1, 0).getDate()));
    return {start, end};
  }

  private periodRangeLabel(): string {
    const {start, end} = this.periodDates();
    return `${this.toDateString(start).replaceAll('-', '/')} - ${this.toDateString(end).replaceAll('-', '/')}`;
  }

  private weekIndexForDate(value: Date): number {
    return Math.min(3, Math.floor((value.getDate() - 1) / 7));
  }

  private bucketDateLabel(from: Date, to: Date): string {
    if (this.activePeriod() === 'year') {
      return `${from.getFullYear()}/${String(from.getMonth() + 1).padStart(2, '0')}`;
    }
    if (this.activePeriod() === 'month') {
      return `${this.monthDayLabel(from)} - ${this.monthDayLabel(to)}`;
    }
    return this.monthDayLabel(from);
  }

  private parseDate(value: string): Date {
    return new Date(`${value}T00:00:00`);
  }

  private toDateString(value: Date): string {
    return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
  }

  private weekdayLabel(value: Date): string {
    return ['日', '一', '二', '三', '四', '五', '六'][value.getDay()];
  }

  private monthDayLabel(value: Date): string {
    return `${String(value.getMonth() + 1).padStart(2, '0')}/${String(value.getDate()).padStart(2, '0')}`;
  }

  private extractArea(address: string): string {
    return address.match(/高雄市([^\s]+區)/)?.[1] ?? '未提供區域';
  }
}

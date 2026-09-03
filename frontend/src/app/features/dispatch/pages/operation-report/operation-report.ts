import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import {
  LucideChartNoAxesCombined,
  LucideCircleCheck,
  LucideClock3,
  LucideRoute,
  LucideTriangleAlert,
} from '@lucide/angular';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { OrderDto, StoreDto } from '../../../../core/services/dispatch-api.models';

type ReportPeriod = 'week' | 'month';

interface DailyDelivery {
  day: string;
  date: string;
  count: number;
  height: number;
  onTime: number;
}

interface AreaPerformance {
  area: string;
  completed: number;
  onTime: string;
  routes: number;
}

interface ReportMetrics {
  range: string;
  healthScore: number;
  healthLabel: string;
  healthNote: string;
  completion: string;
  onTime: string;
  deliveryTime: string;
  exceptionRate: string;
  total: string;
  trend: string;
}

interface StatusSummary {
  label: string;
  detail: string;
  percentage: string;
  tone: string;
}

interface ExceptionSource {
  label: string;
  count: string;
  detail: string;
  tone: string;
}

@Component({
  selector: 'app-operation-report',
  imports: [
    LucideChartNoAxesCombined,
    LucideCircleCheck,
    LucideClock3,
    LucideRoute,
    LucideTriangleAlert,
  ],
  templateUrl: './operation-report.html',
  styleUrl: './operation-report.scss',
})
export class OperationReport implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly activePeriod = signal<ReportPeriod>('week');
  readonly orders = signal<OrderDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');

  readonly periods: { id: ReportPeriod; label: string }[] = [
    { id: 'week', label: '本週' },
    { id: 'month', label: '本月' },
  ];

  readonly report = computed<ReportMetrics>(() => {
    const periodOrders = this.ordersInPeriod();
    const total = periodOrders.length;
    const completed = periodOrders.filter((order) => order.status === 'COMPLETED').length;
    const completionRate = total ? (completed / total) * 100 : 0;

    return {
      range: this.periodRangeLabel(),
      healthScore: Math.round(completionRate),
      healthLabel: total ? (completionRate >= 95 ? '資料穩定' : '需要關注') : '尚無資料',
      healthNote: total
        ? '完成率由後端 OrderController 的訂單狀態計算。'
        : '目前期間沒有後端訂單，畫面不使用預設假資料。',
      completion: `${completionRate.toFixed(1)}%`,
      onTime: '後端未提供',
      deliveryTime: '後端未提供',
      exceptionRate: '後端未提供',
      total: String(total),
      trend: '後端未提供',
    };
  });

  readonly chartData = computed(() => this.buildChartData(this.ordersInPeriod()));
  readonly areas = computed(() => this.buildAreas(this.ordersInPeriod(), this.stores()));

  readonly statusBreakdown = computed(() => {
    const orders = this.ordersInPeriod();
    const total = orders.length || 1;
    const statuses: { status: OrderDto['status']; label: string; detail: string; tone: string }[] =
      [
        { status: 'COMPLETED', label: '已完成', detail: '正常簽收結案', tone: 'complete' },
        { status: 'IN_DELIVERY', label: '配送中', detail: '目前正在配送', tone: 'followup' },
        { status: 'FAILED', label: '配送失敗', detail: '需要異常處理', tone: 'exception' },
        { status: 'CONFIRMED', label: '待排車', detail: '等待調度安排', tone: 'pending' },
      ];

    return statuses.map((item) => ({
      label: item.label,
      detail: item.detail,
      tone: item.tone,
      percentage: `${((orders.filter((order) => order.status === item.status).length / total) * 100).toFixed(1)}%`,
    }));
  });

  readonly exceptionSources = computed<ExceptionSource[]>(() => {
    return [
      {
        label: '異常案件 API',
        count: '--',
        detail: 'Controller 已建立，但目前固定回傳 501',
        tone: 'warning',
      },
    ];
  });

  ngOnInit(): void {
    this.loadReport();
  }

  setPeriod(period: ReportPeriod): void {
    this.activePeriod.set(period);
  }

  private loadReport(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    forkJoin({
      orders: this.api.getOrders(),
      stores: this.api.getStores(),
    }).subscribe({
      next: ({ orders, stores }) => {
        this.orders.set(orders);
        this.stores.set(stores);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('無法取得報表資料，請確認後端服務與登入狀態。');
        this.loading.set(false);
      },
    });
  }

  private ordersInPeriod(): OrderDto[] {
    const { start, end } = this.periodDates();
    return this.orders().filter((order) => {
      const date = this.parseDate(order.deliveryDate);
      return date >= start && date <= end;
    });
  }

  private buildChartData(orders: OrderDto[]): DailyDelivery[] {
    const { start, end } = this.periodDates();
    const days: Date[] = [];
    const cursor = new Date(start);
    while (cursor <= end) {
      days.push(new Date(cursor));
      cursor.setDate(cursor.getDate() + 1);
    }

    const counts = days.map((day) => {
      const date = this.toDateString(day);
      return orders.filter((order) => order.deliveryDate === date).length;
    });
    const max = Math.max(...counts, 1);

    return days.map((day, index) => ({
      day: this.activePeriod() === 'week' ? this.weekdayLabel(day) : `第 ${index + 1} 天`,
      date: `${String(day.getMonth() + 1).padStart(2, '0')}/${String(day.getDate()).padStart(2, '0')}`,
      count: counts[index],
      height: (counts[index] / max) * 100,
      onTime: 0,
    }));
  }

  private buildAreas(orders: OrderDto[], stores: StoreDto[]): AreaPerformance[] {
    const grouped = new Map<string, { total: number; completed: number }>();

    orders.forEach((order) => {
      const store = stores.find((item) => item.id === order.storeId);
      const area = this.extractArea(store?.address ?? '');
      const current = grouped.get(area) ?? { total: 0, completed: 0 };
      current.total += 1;
      if (order.status === 'COMPLETED') {
        current.completed += 1;
      }
      grouped.set(area, current);
    });

    return [...grouped.entries()].map(([area, value]) => ({
      area,
      completed: value.total ? (value.completed / value.total) * 100 : 0,
      onTime: '後端未提供',
      routes: value.total,
    }));
  }

  private periodDates(): { start: Date; end: Date } {
    const dates = this.orders()
      .map((order) => this.parseDate(order.deliveryDate))
      .filter((date) => !Number.isNaN(date.getTime()));
    const anchor = dates.sort((a, b) => b.getTime() - a.getTime())[0] ?? new Date();
    const end = new Date(anchor);
    const start = new Date(anchor);

    if (this.activePeriod() === 'month') {
      start.setDate(1);
    } else {
      start.setDate(start.getDate() - 6);
    }

    return { start, end };
  }

  private periodRangeLabel(): string {
    const { start, end } = this.periodDates();
    return `${this.toDateString(start).replaceAll('-', '/')} - ${this.toDateString(end).replaceAll('-', '/')}`;
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

  private extractArea(address: string): string {
    return address.match(/台南市([^\s]+區)/)?.[1] ?? '未提供區域';
  }
}

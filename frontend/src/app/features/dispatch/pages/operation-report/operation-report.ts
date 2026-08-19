import { Component, computed, signal } from '@angular/core';
import {
  LucideChartNoAxesCombined,
  LucideCircleCheck,
  LucideClock3,
  LucideRoute,
  LucideTriangleAlert,
} from '@lucide/angular';

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
  onTime: number;
  routes: number;
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
export class OperationReport {
  readonly activePeriod = signal<ReportPeriod>('week');

  readonly periods: { id: ReportPeriod; label: string }[] = [
    { id: 'week', label: '本週' },
    { id: 'month', label: '本月' },
  ];

  readonly weeklyDeliveries: DailyDelivery[] = [
    { day: '一', date: '08/11', count: 48, height: 68, onTime: 93.8 },
    { day: '二', date: '08/12', count: 52, height: 74, onTime: 94.2 },
    { day: '三', date: '08/13', count: 45, height: 64, onTime: 91.1 },
    { day: '四', date: '08/14', count: 57, height: 81, onTime: 95.5 },
    { day: '五', date: '08/15', count: 61, height: 87, onTime: 96.2 },
    { day: '六', date: '08/16', count: 39, height: 56, onTime: 94.9 },
    { day: '日', date: '08/17', count: 42, height: 60, onTime: 93.4 },
  ];

  readonly monthlyDeliveries: DailyDelivery[] = [
    { day: '第 1 週', date: '08/01', count: 264, height: 71, onTime: 92.8 },
    { day: '第 2 週', date: '08/08', count: 281, height: 76, onTime: 94.1 },
    { day: '第 3 週', date: '08/15', count: 305, height: 83, onTime: 95.2 },
    { day: '本週', date: '08/18', count: 48, height: 53, onTime: 93.4 },
  ];

  readonly areas: AreaPerformance[] = [
    { area: '永康區', completed: 98.2, onTime: 94.8, routes: 12 },
    { area: '東區', completed: 97.4, onTime: 95.7, routes: 9 },
    { area: '安平區', completed: 95.8, onTime: 91.6, routes: 8 },
    { area: '中西區', completed: 96.7, onTime: 92.4, routes: 7 },
    { area: '仁德區', completed: 94.9, onTime: 90.8, routes: 6 },
  ];

  readonly report = computed(() => {
    if (this.activePeriod() === 'month') {
      return {
        range: '2026/08/01 - 2026/08/18',
        healthScore: 92,
        healthLabel: '穩定運作',
        healthNote: '整體服務維持目標區間，需持續降低定位與工時相關異常。',
        completion: '96.5%',
        onTime: '93.9%',
        deliveryTime: '44 分',
        exceptionRate: '2.4%',
        total: '898',
        trend: '+4.2%',
      };
    }

    return {
      range: '2026/08/11 - 2026/08/17',
      healthScore: 94,
      healthLabel: '運作良好',
      healthNote: '配送完成率與時效表現穩定，異常案件均已納入追蹤。',
      completion: '96.8%',
      onTime: '93.4%',
      deliveryTime: '42 分',
      exceptionRate: '2.1%',
      total: '344',
      trend: '+2.6%',
    };
  });

  readonly chartData = computed(() =>
    this.activePeriod() === 'week' ? this.weeklyDeliveries : this.monthlyDeliveries,
  );

  setPeriod(period: ReportPeriod): void {
    this.activePeriod.set(period);
  }
}

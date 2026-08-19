import { Component, computed, signal } from '@angular/core';
import {
  LucideCircleCheck,
  LucideClock3,
  LucideMapPinned,
  LucideSearch,
  LucideTriangleAlert,
  LucideUserRound,
} from '@lucide/angular';

type IncidentFilter = 'all' | 'critical' | 'attention' | 'resolved';
type IncidentSeverity = 'critical' | 'attention' | 'normal';
type IncidentStatus = '待處理' | '待回覆' | '已結案';

interface Incident {
  id: string;
  title: string;
  category: string;
  severity: IncidentSeverity;
  status: IncidentStatus;
  driver: string;
  vehicle: string;
  zone: string;
  occurredAt: string;
  detail: string;
  recommendation: string;
  owner: string;
}

@Component({
  selector: 'app-anomaly-center',
  imports: [
    LucideCircleCheck,
    LucideClock3,
    LucideMapPinned,
    LucideSearch,
    LucideTriangleAlert,
    LucideUserRound,
  ],
  templateUrl: './anomaly-center.html',
  styleUrl: './anomaly-center.scss',
})
export class AnomalyCenter {
  readonly activeFilter = signal<IncidentFilter>('all');
  readonly searchTerm = signal('');
  readonly selectedIncidentId = signal('EX-20260818-001');
  readonly actionMessage = signal('');

  readonly filters: { id: IncidentFilter; label: string }[] = [
    { id: 'all', label: '全部' },
    { id: 'critical', label: '緊急' },
    { id: 'attention', label: '注意' },
    { id: 'resolved', label: '已結案' },
  ];

  readonly incidents = signal<Incident[]>([
    {
      id: 'EX-20260818-001',
      title: '車輛定位中斷',
      category: '車機定位',
      severity: 'critical',
      status: '待處理',
      driver: '陳志明 · DR-017',
      vehicle: 'KLD-205',
      zone: '永康區',
      occurredAt: '08:18',
      detail: '已超過 12 分鐘未回傳 GPS 定位，任務仍處於配送中。',
      recommendation: '先以電話確認司機與車機狀態；10 分鐘內無法恢復時，啟動鄰近車輛支援評估。',
      owner: '未指派',
    },
    {
      id: 'EX-20260818-002',
      title: '司機工時接近上限',
      category: '人員工時',
      severity: 'attention',
      status: '待回覆',
      driver: '黃信翔 · DR-044',
      vehicle: 'KLD-412',
      zone: '安平區',
      occurredAt: '08:06',
      detail: '依目前路線預估，完成本趟後將超過今日建議工時。',
      recommendation: '確認未完成配送點，必要時轉派下午可用車輛承接最後一站。',
      owner: '王怡文',
    },
    {
      id: 'EX-20260818-003',
      title: '收件資訊待補',
      category: '配送資料',
      severity: 'normal',
      status: '待回覆',
      driver: '林柏安 · DR-024',
      vehicle: 'KLD-118',
      zone: '東區',
      occurredAt: '07:52',
      detail: '東區便利商店缺少收貨聯絡人電話，司機無法於抵達前確認卸貨位置。',
      recommendation: '請客服補齊聯絡資料後同步至司機端任務明細。',
      owner: '林子晴',
    },
    {
      id: 'EX-20260818-004',
      title: '低溫車廂溫度偏離',
      category: '車輛設備',
      severity: 'attention',
      status: '已結案',
      driver: '王雅雯 · DR-031',
      vehicle: 'KLD-308',
      zone: '中西區',
      occurredAt: '07:35',
      detail: '車廂溫度一度高於任務設定值，已完成再次校驗。',
      recommendation: '保留本趟溫度紀錄，下午出車前再次進行設備自檢。',
      owner: '王怡文',
    },
  ]);

  readonly visibleIncidents = computed(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.incidents().filter((incident) => {
      const matchesFilter =
        filter === 'all' ||
        (filter === 'resolved' && incident.status === '已結案') ||
        incident.severity === filter;
      const source =
        `${incident.id} ${incident.title} ${incident.vehicle} ${incident.driver} ${incident.zone}`.toLowerCase();
      return matchesFilter && (!term || source.includes(term));
    });
  });

  readonly selectedIncident = computed(() => {
    const incidents = this.visibleIncidents();
    return (
      incidents.find((incident) => incident.id === this.selectedIncidentId()) ??
      incidents[0] ??
      null
    );
  });

  readonly summary = computed(() => {
    const incidents = this.incidents();
    return {
      critical: incidents.filter(
        (incident) => incident.severity === 'critical' && incident.status !== '已結案',
      ).length,
      pending: incidents.filter((incident) => incident.status === '待回覆').length,
      resolved: incidents.filter((incident) => incident.status === '已結案').length,
    };
  });

  setFilter(filter: IncidentFilter): void {
    this.activeFilter.set(filter);
    this.syncSelectedIncident();
  }

  updateSearch(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
    this.syncSelectedIncident();
  }

  selectIncident(id: string): void {
    this.selectedIncidentId.set(id);
    this.actionMessage.set('');
  }

  assignIncident(): void {
    const selected = this.selectedIncident();
    if (!selected || selected.status === '已結案') {
      return;
    }

    this.updateIncident(selected.id, { owner: '王怡文', status: '待回覆' });
    this.actionMessage.set(`已指派給王怡文，等待處理進度回覆。`);
  }

  resolveIncident(): void {
    const selected = this.selectedIncident();
    if (!selected || selected.status === '已結案') {
      return;
    }

    this.updateIncident(selected.id, { status: '已結案' });
    this.actionMessage.set('此異常已標記為結案，紀錄將保留於今日營運報表。');
  }

  severityLabel(severity: IncidentSeverity): string {
    return severity === 'critical' ? '緊急' : severity === 'attention' ? '注意' : '一般';
  }

  private updateIncident(id: string, changes: Partial<Incident>): void {
    this.incidents.update((incidents) =>
      incidents.map((incident) => (incident.id === id ? { ...incident, ...changes } : incident)),
    );
  }

  private syncSelectedIncident(): void {
    this.selectedIncidentId.set(this.visibleIncidents()[0]?.id ?? '');
  }
}

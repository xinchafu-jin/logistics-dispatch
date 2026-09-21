import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {MatIconModule} from '@angular/material/icon';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {
  ExceptionCaseDto,
  ExceptionType,
} from '../../../../core/services/dispatch-api.models';

type ExceptionFilter = 'ALL' | 'NO_SIGNATURE' | 'GOODS_ISSUE';

@Component({
  selector: 'app-anomaly-center',
  imports: [MatIconModule, ],
  templateUrl: './anomaly-center.html',
  styleUrl: './anomaly-center.scss',
})
export class AnomalyCenter implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly filters: { key: ExceptionFilter; label: string }[] = [
    { key: 'ALL', label: '全部' },
    { key: 'NO_SIGNATURE', label: '無人簽收' },
    { key: 'GOODS_ISSUE', label: '貨況異常' },
  ];
  readonly incidents = signal<ExceptionCaseDto[]>([]);
  readonly activeFilter = signal<ExceptionFilter>('ALL');
  readonly selectedIncidentId = signal<number | null>(null);
  readonly loading = signal(true);
  readonly confirming = signal(false);
  readonly confirmDialogOpen = signal(false);
  readonly errorMessage = signal('');
  readonly actionMessage = signal('');
  readonly lastLoadedAt = signal<Date | null>(null);

  readonly filteredIncidents = computed(() => {
    const filter = this.activeFilter();
    return this.incidents().filter((incident) => {
      if (filter === 'ALL') {
        return true;
      }
      if (filter === 'NO_SIGNATURE') {
        return incident.type === 'NO_SIGNATURE';
      }
      return this.isGoodsIssue(incident.type);
    });
  });
  readonly selectedIncident = computed(() => {
    const incidents = this.filteredIncidents();
    return incidents.find((incident) => incident.id === this.selectedIncidentId()) ?? incidents[0] ?? null;
  });
  readonly summary = computed(() => {
    const incidents = this.incidents();
    return {
      total: incidents.length,
      noSignature: incidents.filter((incident) => incident.type === 'NO_SIGNATURE').length,
      goodsIssue: incidents.filter((incident) => this.isGoodsIssue(incident.type)).length,
      replacementBoxes: incidents.reduce(
        (total, incident) => total + (incident.replacementRequiredBoxCount ?? 0),
        0,
      ),
    };
  });

  ngOnInit(): void {
    this.loadPendingConfirmations();
  }

  protected refresh(): void {
    if (!this.confirming()) {
      this.loadPendingConfirmations();
    }
  }

  protected setFilter(filter: ExceptionFilter): void {
    this.activeFilter.set(filter);
    this.syncSelection();
  }

  protected selectIncident(incidentId: number): void {
    this.selectedIncidentId.set(incidentId);
    this.actionMessage.set('確認後，後續訂單會送入待排車。');
  }

  protected openConfirmDialog(): void {
    if (this.selectedIncident() && !this.confirming()) {
      this.confirmDialogOpen.set(true);
    }
  }

  protected closeConfirmDialog(): void {
    if (!this.confirming()) {
      this.confirmDialogOpen.set(false);
    }
  }

  protected confirmSelected(): void {
    const incident = this.selectedIncident();
    if (!incident || this.confirming()) {
      return;
    }

    this.confirming.set(true);
    this.errorMessage.set('');
    this.api.confirmExceptionCase(incident.id).subscribe({
      next: () => {
        this.incidents.update((incidents) => incidents.filter((item) => item.id !== incident.id));
        this.selectedIncidentId.set(null);
        this.syncSelection();
        this.confirmDialogOpen.set(false);
        this.actionMessage.set('已確認異常，後續訂單已送入待排車。');
        this.confirming.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法確認此異常，請重新整理後再試。'));
        this.confirming.set(false);
      },
    });
  }

  protected exceptionTypeLabel(type: ExceptionType): string {
    return {
      NO_SIGNATURE: '無人簽收',
      SHORTAGE: '貨物短少',
      DAMAGE: '貨物毀損',
      SHORTAGE_AND_DAMAGE: '短少與毀損',
      DRIVER_REPORT: '司機回報',
      PHONE_HANDLED: '電話補登',
    }[type];
  }

  protected severityClass(type: ExceptionType): 'critical' | 'attention' {
    return type === 'SHORTAGE_AND_DAMAGE' || type === 'DAMAGE' ? 'critical' : 'attention';
  }

  protected formatDateTime(value: string | null): string {
    if (!value) {
      return '--';
    }

    const date = new Date(
      /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}+08:00`,
    );
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return new Intl.DateTimeFormat('zh-TW', {
      timeZone: 'Asia/Taipei',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(date);
  }

  protected formatRefreshTime(value: Date | null): string {
    if (!value) {
      return '尚未讀取';
    }

    return new Intl.DateTimeFormat('zh-TW', {
      timeZone: 'Asia/Taipei',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(value);
  }

  protected quantity(value: number | null): string {
    return value === null ? '--' : `${value} 箱`;
  }

  private loadPendingConfirmations(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.confirmDialogOpen.set(false);
    this.api.getPendingExceptionConfirmations().subscribe({
      next: (incidents) => {
        this.incidents.set(incidents);
        this.lastLoadedAt.set(new Date());
        this.syncSelection();
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '目前無法讀取到期異常提醒。'));
        this.loading.set(false);
      },
    });
  }

  private syncSelection(): void {
    const visibleIds = new Set(this.filteredIncidents().map((incident) => incident.id));
    if (this.selectedIncidentId() !== null && visibleIds.has(this.selectedIncidentId()!)) {
      return;
    }
    this.selectedIncidentId.set(this.filteredIncidents()[0]?.id ?? null);
  }

  private isGoodsIssue(type: ExceptionType): boolean {
    return type === 'SHORTAGE' || type === 'DAMAGE' || type === 'SHORTAGE_AND_DAMAGE';
  }

  private readError(error: unknown, fallback: string): string {
    if (!(error instanceof HttpErrorResponse)) {
      return fallback;
    }
    if (typeof error.error === 'string' && error.error.trim()) {
      return error.error;
    }
    if (typeof error.error?.message === 'string' && error.error.message.trim()) {
      return error.error.message;
    }
    return error.status ? `後端回應 ${error.status}` : fallback;
  }
}

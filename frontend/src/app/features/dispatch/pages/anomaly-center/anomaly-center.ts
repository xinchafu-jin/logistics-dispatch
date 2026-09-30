import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, TemplateRef, computed, effect, inject, OnInit, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { AdminThemeService } from '../../../../core/theme/admin-theme.service';
import { DriverCasesService, driverCaseCategoryLabel } from '../../../../core/services/driver-cases.service';
import { DriverChatSocketService } from '../../../../core/services/driver-chat-socket.service';
import {
  DriverCaseDto,
  DriverCaseOrderDto,
  DriverCaseOrdersDto,
  ExceptionCaseDto,
  ExceptionType,
  OrderStatus,
} from '../../../../core/services/dispatch-api.models';

type ExceptionFilter = 'ALL' | 'NO_SIGNATURE' | 'GOODS_ISSUE';
/** driver＝司機即時回報（接收、結案），delivery＝隔日 06:00 進來的配送異常（確認補送） */
type AnomalyView = 'driver' | 'delivery';
type CaseListFilter = 'OPEN' | 'CLOSED';

@Component({
  selector: 'app-anomaly-center',
  imports: [MatIconModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  templateUrl: './anomaly-center.html',
  styleUrl: './anomaly-center.scss',
})
export class AnomalyCenter implements OnInit {
  private readonly api = inject(DispatchApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  // 對話框開在 body 底下吃不到後台深淺色，開啟時要帶 theme.dialogPanelClass()
  private readonly theme = inject(AdminThemeService);
  private readonly destroyRef = inject(DestroyRef);

  // ── 司機回報：資料在 DriverCasesService（鈴鐺、聊天室共用同一份，靠推播即時更新）──
  protected readonly driverCases = inject(DriverCasesService);
  protected readonly isSocketConnected = inject(DriverChatSocketService).isConnected;
  /**
   * 結案視窗的內容，寫在 anomaly-center.html 最下面。用 MatDialog 開、不沿用本頁的 .modal-backdrop：
   * 頁面在 dispatch-shell 的 z-index: 0 堆疊裡，本頁的視窗 z-index 再高也蓋不過聊天室（z-index 80）；
   * MatDialog 掛在 body 底下，不受這個限制。
   */
  private readonly closeCaseDialogTemplate = viewChild.required<TemplateRef<unknown>>('closeCaseDialog');
  private closeCaseDialogRef: MatDialogRef<unknown> | null = null;

  readonly view = signal<AnomalyView>('driver');
  readonly caseFilter = signal<CaseListFilter>('OPEN');
  readonly selectedCaseId = signal<number | null>(null);
  readonly caseAction = signal<'accept' | 'close' | null>(null);
  readonly caseNotice = signal('');
  readonly caseError = signal('');
  // 結案視窗針對哪一件：開視窗時就固定下來。推播會新增、重排案件，不能拿「目前選中的」，不然可能結錯件
  readonly closingCaseId = signal<number | null>(null);
  readonly closeResolution = signal('');
  readonly closeError = signal('');
  /**
   * 案件路線上還沒結束的單，結案時可以勾選改期補送。
   * 司機回報結案不會自己動訂單；不處理的話，路線日期一過，看板日期列那天就會一直是「未結案」。
   */
  readonly closeOrders = signal<DriverCaseOrdersDto | null>(null);
  readonly closeOrdersState = signal<'loading' | 'ready' | 'error'>('loading');
  readonly redeliverOrderIds = signal<ReadonlySet<number>>(new Set());
  /** 路線日期已過時，沒勾的單還剩幾張；後端也會擋，這裡先讓按鈕停用、把原因寫出來 */
  readonly unresolvedOrderCount = computed(() => {
    const orders = this.closeOrders();
    if (!orders?.mustResolveAll) {
      return 0;
    }
    return orders.orders.filter((order) => !this.redeliverOrderIds().has(order.id)).length;
  });

  readonly visibleCases = computed(() =>
    this.caseFilter() === 'OPEN' ? this.driverCases.openCases() : this.driverCases.closedCases(),
  );
  readonly caseListState = computed(() =>
    this.caseFilter() === 'OPEN' ? this.driverCases.loadState() : this.driverCases.closedLoadState(),
  );
  readonly selectedCase = computed(() =>
    this.visibleCases().find((item) => item.id === this.selectedCaseId()) ?? null,
  );
  readonly closingCase = computed(() => {
    const caseId = this.closingCaseId();
    return caseId === null ? null : this.driverCases.findCase(caseId);
  });
  readonly caseSummary = computed(() => {
    const open = this.driverCases.openCases();
    return {
      waiting: open.filter((item) => item.acceptedAt === null).length,
      handling: open.filter((item) => item.acceptedAt !== null).length,
      stuck: open.filter((item) => item.canContinue === false).length,
      unread: open.reduce((sum, item) => sum + item.unreadCount, 0),
    };
  });

  /**
   * 選中的那件要固定在 id 上：推播會新增、重排案件，如果只靠「沒選就顯示第一件」，
   * 主管看到一半，詳情就會跳成別件。選中的那件不在清單上了（例如被結案）才改選第一件。
   * 清單還在載入時不動：鈴鐺帶 ?case=ID 進來時，要等資料到了才判斷那件在不在。
   */
  private readonly keepCaseSelection = effect(() => {
    if (this.caseListState() !== 'ready') {
      return;
    }
    const cases = this.visibleCases();
    const selectedId = this.selectedCaseId();
    if (selectedId !== null && cases.some((item) => item.id === selectedId)) {
      return;
    }
    this.selectedCaseId.set(cases[0]?.id ?? null);
  });

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
    // 進頁面重抓一次司機回報；之後的變化靠推播
    this.driverCases.load();
    // 鈴鐺點進來會帶 ?case=ID：切到司機回報並選中那一件
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const caseId = Number(params.get('case'));
        if (Number.isInteger(caseId) && caseId > 0) {
          this.view.set('driver');
          this.caseFilter.set('OPEN');
          this.selectedCaseId.set(caseId);
        }
      });
  }

  protected refresh(): void {
    if (this.view() === 'driver') {
      this.driverCases.load();
      if (this.caseFilter() === 'CLOSED') {
        this.driverCases.loadClosed();
      }
      return;
    }
    if (!this.confirming()) {
      this.loadPendingConfirmations();
    }
  }

  protected isRefreshing(): boolean {
    return this.view() === 'driver' ? this.caseListState() === 'loading' : this.loading() || this.confirming();
  }

  protected setView(view: AnomalyView): void {
    this.view.set(view);
  }

  // ── 司機回報 ───────────────────────────────────────────

  protected setCaseFilter(filter: CaseListFilter): void {
    this.caseFilter.set(filter);
    this.selectedCaseId.set(null);
    this.caseNotice.set('');
    this.caseError.set('');
    // 已結案的第一次切過去才載
    if (filter === 'CLOSED' && this.driverCases.closedLoadState() === 'idle') {
      this.driverCases.loadClosed();
    }
  }

  protected selectCase(caseId: number): void {
    this.selectedCaseId.set(caseId);
    this.caseNotice.set('');
    this.caseError.set('');
  }

  /** 接收後直接打開聊天室的這一件：流程是「異常中心接收 → 聊天室溝通 → 回異常中心結案」 */
  protected acceptSelectedCase(): void {
    const item = this.selectedCase();
    if (!item || this.caseAction() !== null) {
      return;
    }
    this.caseAction.set('accept');
    this.caseNotice.set('');
    this.caseError.set('');
    this.driverCases.accept(item.id).subscribe({
      next: () => {
        this.caseAction.set(null);
        this.caseNotice.set('已接收，對話在右下角的聊天室。');
        this.driverCases.requestOpenChat(item.id);
      },
      error: (error: unknown) => {
        this.caseAction.set(null);
        this.caseError.set(this.readError(error, '無法接收這件案件，請重新整理後再試。'));
        // 多半是別人剛接收或結案了：重抓，畫面才會跟資料庫一致
        this.driverCases.load();
      },
    });
  }

  protected openSelectedCaseChat(): void {
    const item = this.selectedCase();
    if (item) {
      this.driverCases.requestOpenChat(item.id);
    }
  }

  protected openCloseCaseDialog(): void {
    const item = this.selectedCase();
    if (!item || this.caseAction() !== null) {
      return;
    }
    this.closingCaseId.set(item.id);
    this.closeResolution.set('');
    this.closeError.set('');
    this.loadCloseOrders(item);
    this.closeCaseDialogRef = this.dialog.open(this.closeCaseDialogTemplate(), {
      width: '520px',
      maxWidth: 'calc(100vw - 32px)',
      panelClass: this.theme.dialogPanelClass(),
    });
    this.closeCaseDialogRef.afterClosed().subscribe(() => {
      this.closeCaseDialogRef = null;
      this.closingCaseId.set(null);
    });
  }

  /**
   * 預設勾選：日期已過的全勾（本來就一定要處理）；司機說不能繼續配送也全勾；
   * 不然只勾案件綁的那張。主管可以再改。
   */
  private loadCloseOrders(item: DriverCaseDto): void {
    this.closeOrders.set(null);
    this.closeOrdersState.set('loading');
    this.redeliverOrderIds.set(new Set());
    this.api.getDriverCaseUnfinishedOrders(item.id).subscribe({
      next: (orders) => {
        if (this.closingCaseId() !== item.id) {
          return;
        }
        const checkAll = orders.mustResolveAll || item.canContinue === false;
        this.redeliverOrderIds.set(new Set(orders.orders
          .filter((order) => checkAll || order.id === item.orderId)
          .map((order) => order.id)));
        this.closeOrders.set(orders);
        this.closeOrdersState.set('ready');
      },
      error: () => {
        if (this.closingCaseId() === item.id) {
          this.closeOrdersState.set('error');
        }
      },
    });
  }

  protected toggleRedeliverOrder(orderId: number, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.redeliverOrderIds.update((ids) => {
      const next = new Set(ids);
      if (checked) {
        next.add(orderId);
      } else {
        next.delete(orderId);
      }
      return next;
    });
    this.closeError.set('');
  }

  protected closeOrderStatusLabel(order: DriverCaseOrderDto): string {
    const labels: Partial<Record<OrderStatus, string>> = {
      PENDING_CONFIRM: '待確認',
      CONFIRMED: '未點交',
      LOADED: '已點交',
      IN_DELIVERY: '配送中',
    };
    return labels[order.status] ?? order.status;
  }

  protected updateCloseResolution(event: Event): void {
    this.closeResolution.set((event.target as HTMLTextAreaElement).value);
    this.closeError.set('');
  }

  protected submitCloseCase(): void {
    const caseId = this.closingCaseId();
    const resolution = this.closeResolution().trim();
    if (caseId === null || !resolution || this.caseAction() !== null
      || this.closeOrdersState() !== 'ready' || this.unresolvedOrderCount() > 0) {
      return;
    }
    this.caseAction.set('close');
    this.closeError.set('');
    this.driverCases.close(caseId, resolution, [...this.redeliverOrderIds()]).subscribe({
      next: () => {
        this.caseAction.set(null);
        this.closeCaseDialogRef?.close();
        this.caseNotice.set('已結案，司機端會看到處理結果。');
      },
      error: (error: unknown) => {
        this.caseAction.set(null);
        this.closeError.set(this.readError(error, '結案沒有成功，請稍後再試。'));
      },
    });
  }

  protected caseCategoryLabel(item: DriverCaseDto): string {
    return driverCaseCategoryLabel(item.category);
  }

  protected caseStatusLabel(item: DriverCaseDto): string {
    if (item.status === 'CLOSED') {
      return '已結案';
    }
    return item.acceptedAt ? '處理中' : '等待接收';
  }

  /** 紅：不能繼續配送、交通事故、身體或人身安全；黃：其他進行中的；結案用預設的灰 */
  protected caseSeverity(item: DriverCaseDto): 'critical' | 'attention' | 'closed' {
    if (item.status === 'CLOSED') {
      return 'closed';
    }
    if (item.canContinue === false || item.category === 'ACCIDENT' || item.category === 'PERSONAL') {
      return 'critical';
    }
    return 'attention';
  }

  protected canContinueLabel(item: DriverCaseDto): string {
    if (item.canContinue === null) {
      return '--';
    }
    return item.canContinue ? '可以繼續配送' : '無法繼續配送';
  }

  // ── 到期配送異常 ─────────────────────────────────────────

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
      next: (confirmed) => {
        this.incidents.update((incidents) => incidents.filter((item) => item.id !== incident.id));
        this.selectedIncidentId.set(null);
        this.syncSelection();
        this.confirmDialogOpen.set(false);
        this.actionMessage.set(`已確認異常，後續訂單已送入 ${confirmed.followUpDeliveryDate ?? '對應日期'} 的待排車區。`);
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
      LOADING_MISMATCH: '點交不符',
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
    return error.status ? `操作未完成（代碼 ${error.status}）` : fallback;
  }
}

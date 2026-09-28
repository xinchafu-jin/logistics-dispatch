import {Component, DestroyRef, OnInit, TemplateRef, computed, effect, inject, signal, output, viewChild} from '@angular/core';
import {DOCUMENT} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {takeUntilDestroyed, toSignal} from '@angular/core/rxjs-interop';
import {MatListModule} from '@angular/material/list';
import {
  ActivatedRouteSnapshot,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import {Observable, filter, forkJoin, map} from 'rxjs';
import {MatSelectModule} from '@angular/material/select';
import {MatInputModule} from '@angular/material/input';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatButtonModule} from '@angular/material/button';
import {MatTooltipModule} from '@angular/material/tooltip';
import {TextFieldModule} from '@angular/cdk/text-field';
import {MatExpansionModule} from '@angular/material/expansion';
import {MatDialog, MatDialogModule} from '@angular/material/dialog';
import {MatIconModule} from '@angular/material/icon';
import {MatBadgeModule} from '@angular/material/badge';
import {BrandLogo} from '../../../../shared/ui/brand-logo/brand-logo';
import {AuthService} from '../../../../core/auth/auth.service';
import {
  AdminStickyNoteDto,
  AdminStickyNoteRequestDto,
  AiPendingActionDto,
  DriverLeaveRequestDto,
  DriverCaseDto, DriverDto, DriverMessageDto, DriverMessagePushDto,
  EmergencyLeaveDto,
  EmergencyLeaveReplacementCandidateDto,
} from '../../../../core/services/dispatch-api.models';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DispatchBoardEventsService} from '../../../../core/services/dispatch-board-events.service';
import {DriverCasesService, driverCaseCategoryLabel} from '../../../../core/services/driver-cases.service';
import {DriverChatSocketService} from '../../../../core/services/driver-chat-socket.service';
import {AdminThemeService} from '../../../../core/theme/admin-theme.service';
import {FormsModule} from '@angular/forms';

// case：已接收、還沒結案的司機回報，一件一串；對話仍屬於回報的司機，但跟一般對話分開
type ChatContact =
  | { kind: 'ai' }
  | { kind: 'driver'; driverId: number; name: string }
  | { kind: 'case'; caseId: number; name: string };

// 鈴鐺裡能拒絕的只剩臨時離班。司機帳號由後台在人車資源直接開通，沒有「司機申請、主管審核」這條流程
// （9/29 拿掉前端的帳號申請區塊；它呼叫的 /api/driver-account-applications 後端在 9/20 的合併就不存在了）。
// kind 留著：拒絕按鈕的處理中狀態用 'reject-' + kind + '-' + id 比對
type RejectionTarget = { kind: 'leave'; id: number; name: string };

// 聊天室狀態
type ChatView = 'closed' | 'narrow' | 'wide';
type ChatMessageRole = 'user' | 'assistant';
type StickyNoteDockSide = 'left' | 'right';

interface ChatMessage {
  role: ChatMessageRole;
  text: string;
}

@Component({
  selector: 'app-dispatch-shell',
  imports: [
    MatIconModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatButtonModule,
    MatTooltipModule,
    BrandLogo,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    TextFieldModule,
    FormsModule,
    MatExpansionModule,
    MatDialogModule,
    MatBadgeModule,
  ],
  templateUrl: './dispatch-shell.html',
  styleUrl: './dispatch-shell.scss',
})
export class DispatchShell implements OnInit {
  private readonly document = inject(DOCUMENT);
  private readonly theme = inject(AdminThemeService);
  protected readonly user = inject(AuthService).user;
  protected readonly isSigningOut = signal(false);
  protected readonly isLightTheme = this.theme.isLightTheme;
  protected readonly isNotificationsOpen = signal(false);
  protected readonly pendingEmergencyLeaves = signal<EmergencyLeaveDto[]>([]);
  protected readonly pendingTemporaryLeaveRequests = signal<DriverLeaveRequestDto[]>([]);
  protected readonly selectedEmergencyLeave = signal<EmergencyLeaveDto | null>(null);
  protected readonly replacementCandidates = signal<EmergencyLeaveReplacementCandidateDto[]>([]);
  protected readonly selectedReplacementDriverId = signal<number | null>(null);
  protected readonly rejectionTarget = signal<RejectionTarget | null>(null);
  protected readonly rejectionReason = signal('');
  protected readonly notificationError = signal('');
  protected readonly isLoadingNotifications = signal(false);
  protected readonly notificationAction = signal<string | null>(null);
  //司機名單
  protected readonly chatDrivers = signal<DriverDto[]>([]);
  //司機名單載入失敗的訊息，空字串代表沒有錯誤
  protected readonly chatDriversError = signal('');
  //聊天室人員選取 預設ai
  protected readonly selectedChatContact = signal<ChatContact>({kind: 'ai'});
  // 用一個狀態而非 isOpen + isNarrow 兩個布林，才不會出現「沒打開卻是窄版」的組合
  protected readonly chatView = signal<ChatView>('closed');
  //聊天室輸出內容
  protected readonly chatOutput = signal<ChatMessage[]>([]);
  //聊天室輸入內容
  protected readonly chatInput = signal("");
  //等待回復開關
  protected readonly chatWaiting = false;
  // 任務開關
  readonly panelOpenState = signal(false);
  //司機訊息
  readonly driverMessages = signal<DriverMessageDto[]>([]);
  // 司機對話的輸入框；跟 AI 的 chatInput 分開，換對象時打到一半的字才不會被送給別人
  protected readonly driverChatInput = signal('');
  // 送出中：鎖住送出鈕，避免連按送出兩則一樣的訊息
  protected readonly isSendingDriverMessage = signal(false);
  // 司機對話的錯誤訊息，空字串代表沒有錯誤
  protected readonly driverChatError = signal('');
  protected readonly adminStickyNotes = signal<AdminStickyNoteDto[]>([]);
  protected readonly stickyNotesLoading = signal(false);
  protected readonly stickyNoteSaving = signal(false);
  protected readonly stickyNotesError = signal('');
  protected readonly stickyNotesNotice = signal('');
  protected readonly stickyNoteTitle = signal('');
  protected readonly stickyNoteContent = signal('');
  protected readonly editingStickyNoteId = signal<number | null>(null);
  protected readonly deletingStickyNoteId = signal<number | null>(null);
  protected readonly stickyNoteDeleteBusy = signal(false);
  protected readonly stickyNoteDockSide = signal<StickyNoteDockSide>(this.readStickyNoteDockSide());
  protected readonly stickyNoteDragOffset = signal(0);
  protected readonly isStickyNoteDragging = signal(false);
  protected readonly stickyNoteLauncherTransform = computed(
    () => `translateY(-50%) translateX(${this.stickyNoteDragOffset()}px)`,
  );
  private stickyNoteDragPointerId: number | null = null;
  private stickyNoteDragStartX = 0;
  private stickyNoteLastDragEndedAt = 0;
  // 紅點：driverId → 司機發的、還沒被任何管理員讀的則數。沒有未讀的司機不在裡面
  protected readonly unreadByDriver = signal<Record<number, number>>({});
  // 大頭照載入失敗的司機；記下來改顯示名字第一個字，不然會一直顯示破圖
  private readonly failedDriverPhotoIds = signal<ReadonlySet<number>>(new Set());
  // 聊天室按鈕上的總數：一般對話加上聊天室裡列出的案件（已接收的）。還沒接收的案件不在聊天室，未讀顯示在異常中心
  protected readonly totalUnread = computed(() =>
    Object.values(this.unreadByDriver()).reduce((sum, count) => sum + count, 0)
    + this.driverCases.acceptedCases().reduce((sum, item) => sum + item.unreadCount, 0),
  );

  // ── 案件對話：跟一般對話分開存，換到別串時才不會混在一起 ──
  protected readonly caseMessages = signal<DriverMessageDto[]>([]);
  protected readonly caseChatInput = signal('');
  protected readonly isSendingCaseMessage = signal(false);
  protected readonly caseChatError = signal('');
  // 聊天室開著的那件；剛被結案時會從已結案清單找到，畫面改成只能看
  protected readonly selectedCase = computed(() => {
    const contact = this.selectedChatContact();
    return contact.kind === 'case' ? this.driverCases.findCase(contact.caseId) : null;
  });

  /** 看著某件案件的對話、而且有未讀，就標已讀；跟 markViewingDriverRead 同一套想法 */
  private readonly markViewingCaseRead = effect(() => {
    const contact = this.selectedChatContact();
    if (this.chatView() !== 'wide' || contact.kind !== 'case') {
      return;
    }
    const caseId = contact.caseId;
    const hasUnread =
      (this.driverCases.findCase(caseId)?.unreadCount ?? 0) > 0 ||
      this.caseMessages().some((message) => message.senderType === 'DRIVER' && !message.readAt);
    if (!hasUnread) {
      return;
    }

    // 先在畫面上清掉，理由同 markViewingDriverRead：不清的話 effect 重跑會連打好幾次 API
    this.driverCases.clearUnread(caseId);
    this.markCaseMessagesAsRead('DRIVER', new Date().toISOString());
    this.api.markDriverCaseMessagesRead(caseId).subscribe({error: () => undefined});
  });

  /**
   * 「調度員正看著某位司機的對話」而且有未讀，就標已讀。
   *
   * 用 effect 而不是在每個入口各呼叫一次：會讓人「看到」的入口有換司機、對話載入完成、
   * 開著時收到新訊息、打開聊天室、窄版展開成寬版，漏接任何一個紅點就消不掉；
   * effect 只描述「看得到＋有未讀＝標已讀」，哪個 signal 變了都會重新判斷。
   */
  private readonly markViewingDriverRead = effect(() => {
    const contact = this.selectedChatContact();
    // 窄版只剩聯絡人清單、看不到對話內容，不能算讀過
    if (this.chatView() !== 'wide' || contact.kind !== 'driver') {
      return;
    }
    const driverId = contact.driverId;
    const hasUnread =
      (this.unreadByDriver()[driverId] ?? 0) > 0 ||
      this.driverMessages().some((message) => message.senderType === 'DRIVER' && !message.readAt);
    if (!hasUnread) {
      return;
    }

    // 先在畫面上清掉：不清的話這裡改了 signal，effect 重跑時還是有未讀，會連續打好幾次 API。
    // 後台畫面不顯示司機訊息的已讀時間，所以先填本機時間就好，只是讓條件不再成立
    this.setDriverUnread(driverId, 0);
    this.markDriverMessagesAsRead('DRIVER', new Date().toISOString());
    // 失敗不重試：重抓紅點會把未讀抓回來又觸發這裡，網路斷著就會一直打。
    // 資料庫仍是未讀，下次重連（重抓 summary）或重新點開這位司機時會再標一次
    this.api.markDriverMessagesRead(driverId).subscribe({error: () => undefined});
  });


  // 案件的部分靠推播即時加減（DriverCasesService），其他待辦只在載入頁面和打開鈴鐺時抓
  protected readonly notificationCount = computed(
    () => this.pendingEmergencyLeaves().length
      + this.pendingTemporaryLeaveRequests().length
      + this.driverCases.waitingCases().length,
  );

  protected readonly aiPendingAction = signal<AiPendingActionDto[]>([]);

  private readonly authService = inject(AuthService);
  private readonly api = inject(DispatchApiService);
  private readonly boardEvents = inject(DispatchBoardEventsService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly chatSocket = inject(DriverChatSocketService);
  // 司機回報案件：鈴鐺、聊天室的案件聯絡人都讀這裡；異常中心是另一個頁面，也讀同一份
  protected readonly driverCases = inject(DriverCasesService);
  private readonly destroyRef = inject(DestroyRef);
  // 確認執行視窗的內容，寫在 dispatch-shell.html 最下面的 <ng-template #confirmPlanDialog>
  private readonly confirmPlanDialog = viewChild.required<TemplateRef<unknown>>('confirmPlanDialog');
  private readonly stickyNotesDialogTemplate = viewChild.required<TemplateRef<unknown>>('stickyNotesDialog');

  /**
   * 標頭顯示的分頁標題。來源是路由 data（app.routes.ts），各頁不再自己畫標題。
   *
   * 用 data.title 而不是 Angular 內建的 Route.title：後者會連帶改掉瀏覽器分頁標題，
   * 把 index.html 的品牌名「捷流智慧物流」蓋掉。
   */
  protected readonly pageTitle = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.readRouteTitle()),
    ),
    // shell 是在導覽過程中才建立的，第一次 NavigationEnd 會晚於這裡；
    // 但 routerState 在啟用子路由前就已更新，所以初始值直接讀得到，標題不會閃一下空白
    {initialValue: this.readRouteTitle()},
  );

  ngOnInit(): void {
    this.loadPendingNotifications();
    this.loadAdminStickyNotes();
    // 異常中心按「接收」「開啟對話」時，由這裡打開聊天室的那一件（兩者不是父子元件，只能靠 service 傳話）
    this.driverCases.openChatRequests$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((caseId) => this.openCaseChat(caseId));
    this.connectChatSocket();
  }

  signOut(): void {
    if (this.isSigningOut()) {
      return;
    }

    this.isSigningOut.set(true);
    this.authService.logout().subscribe({
      next: () => {
        void this.router.navigateByUrl('/login').finally(() => this.isSigningOut.set(false));
      },
      error: () => this.isSigningOut.set(false),
    });
  }

  protected toggleTheme(): void {
    this.theme.toggle();
  }

  protected onNavClick(event: MouseEvent): void {
    const target = (event.target as HTMLElement)?.closest('a, button');
    if (target instanceof HTMLElement) {
      target.blur();
    }
  }

  protected toggleNotifications(): void {
    if (this.isNotificationsOpen()) {
      this.closeNotifications();
      return;
    }

    this.isNotificationsOpen.set(true);
    this.loadPendingNotifications();
  }

  protected closeNotifications(): void {
    if (this.notificationAction() === null) {
      this.isNotificationsOpen.set(false);
      this.selectedEmergencyLeave.set(null);
      this.replacementCandidates.set([]);
      this.selectedReplacementDriverId.set(null);
      this.rejectionTarget.set(null);
      this.rejectionReason.set('');
    }
  }

  protected reviewEmergencyLeave(leave: EmergencyLeaveDto): void {
    const action = `load-replacements-${leave.id}`;
    this.notificationAction.set(action);
    this.notificationError.set('');
    this.selectedEmergencyLeave.set(leave);
    this.replacementCandidates.set([]);
    this.selectedReplacementDriverId.set(null);

    this.api.getEmergencyLeaveReplacementCandidates(leave.id).subscribe({
      next: (candidates) => {
        this.replacementCandidates.set(candidates);
        this.selectedReplacementDriverId.set(candidates[0]?.driverId ?? null);
        this.notificationAction.set(null);
      },
      error: () => {
        this.notificationError.set('無法取得可接手的司機，請重新整理後再試。');
        this.notificationAction.set(null);
      },
    });
  }

  protected selectReplacementDriver(event: Event): void {
    const value = Number((event.target as HTMLSelectElement).value);
    this.selectedReplacementDriverId.set(Number.isInteger(value) && value > 0 ? value : null);
  }

  protected approveEmergencyLeave(): void {
    const leave = this.selectedEmergencyLeave();
    const replacementDriverId = this.selectedReplacementDriverId();
    if (!leave || !replacementDriverId) {
      this.notificationError.set('請先選擇接手司機。');
      return;
    }

    const action = `approve-leave-${leave.id}`;
    this.runNotificationAction(
      action,
      this.api.approveEmergencyLeaveRequest(leave.id, replacementDriverId),
      () => this.removeEmergencyLeave(leave.id),
    );
  }

  protected beginEmergencyLeaveRejection(leave: EmergencyLeaveDto): void {
    this.rejectionTarget.set({kind: 'leave', id: leave.id, name: leave.driverName ?? `司機 #${leave.driverId}`});
    this.rejectionReason.set('');
    this.notificationError.set('');
  }

  protected openScheduleLeaveReview(): void {
    this.closeNotifications();
    void this.router.navigateByUrl('/dispatch/schedules');
  }

  /** 鈴鐺裡的司機回報：到異常中心選中那一件，接收、結案都在那邊做 */
  protected openDriverCaseInAnomalyCenter(caseId: number): void {
    this.closeNotifications();
    void this.router.navigate(['/dispatch/anomalies'], {queryParams: {case: caseId}});
  }

  /**
   * 聊天室裡的「到異常中心結案」。聊天室是寬 720px 的浮動面板，開著時會蓋住異常中心整個詳情欄
   * （900px 高的螢幕，面板上緣約在 340px，詳情欄的按鈕在 400px 左右），所以先收合聊天室再選中這一件。
   * 結案本身仍只在異常中心做，不在這裡另寫一份。
   */
  protected goCloseCaseInAnomalyCenter(caseId: number): void {
    this.closeChat();
    void this.router.navigate(['/dispatch/anomalies'], {queryParams: {case: caseId}});
  }

  protected caseCategoryLabel(item: DriverCaseDto): string {
    return driverCaseCategoryLabel(item.category);
  }

  protected temporaryLeaveRequestLabel(request: DriverLeaveRequestDto): string {
    switch (request.requestMode) {
      case 'TEMPORARY':
        return '當日特殊事由';
      case 'MAKEUP':
        return '事後補請';
      case 'SYSTEM_NO_SHOW':
        return '待說明特殊事由';
      default:
        return '待審請假';
    }
  }

  protected temporaryLeaveRequestPeriod(request: DriverLeaveRequestDto): string {
    if (request.fullDay) {
      return '整天';
    }
    return `${request.leaveStart?.slice(0, 5) ?? '--:--'} - ${request.leaveEnd?.slice(0, 5) ?? '--:--'}`;
  }

  protected updateRejectionReason(event: Event): void {
    this.rejectionReason.set((event.target as HTMLTextAreaElement).value);
  }

  protected cancelRejection(): void {
    if (this.notificationAction() === null) {
      this.rejectionTarget.set(null);
      this.rejectionReason.set('');
    }
  }

  protected submitRejection(): void {
    const target = this.rejectionTarget();
    const reason = this.rejectionReason().trim();
    if (!target) {
      return;
    }

    if (!reason) {
      this.notificationError.set('請填寫拒絕原因。');
      return;
    }

    const action = `reject-${target.kind}-${target.id}`;
    this.runNotificationAction(action, this.api.rejectEmergencyLeaveRequest(target.id, reason), () => {
      this.removeEmergencyLeave(target.id);
      this.rejectionTarget.set(null);
      this.rejectionReason.set('');
    });
  }

  protected isNotificationAction(action: string): boolean {
    return this.notificationAction() === action;
  }

  protected formatNotificationTime(value: string | null): string {
    if (!value) {
      return '--';
    }

    const normalized = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}+08:00`;
    const date = new Date(normalized);
    if (Number.isNaN(date.getTime())) {
      return value;
    }

    return new Intl.DateTimeFormat('zh-TW', {
      month: 'numeric',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(date);
  }

  private loadPendingNotifications(): void {
    if (this.isLoadingNotifications()) {
      return;
    }

    this.isLoadingNotifications.set(true);
    this.notificationError.set('');
    forkJoin({
      leaves: this.api.getPendingEmergencyLeaveRequests(),
      leaveRequests: this.api.getPendingLeaveRequests(),
    }).subscribe({
      next: ({leaves, leaveRequests}) => {
        this.pendingEmergencyLeaves.set(leaves);
        this.pendingTemporaryLeaveRequests.set(
          leaveRequests
            .filter((request) =>
              request.batchId === null
              && ['TEMPORARY', 'MAKEUP', 'SYSTEM_NO_SHOW'].includes(request.requestMode),
            )
            .sort((left, right) => Date.parse(right.requestedAt) - Date.parse(left.requestedAt)),
        );
        const selectedId = this.selectedEmergencyLeave()?.id;
        this.selectedEmergencyLeave.set(leaves.find((leave) => leave.id === selectedId) ?? null);
        this.isLoadingNotifications.set(false);
      },
      error: () => {
        this.notificationError.set('暫時無法取得主管待辦，請重新整理後再試。');
        this.isLoadingNotifications.set(false);
      },
    });
  }

  private runNotificationAction(
    action: string,
    request: Observable<unknown>,
    onSuccess: () => void,
  ): void {
    this.notificationAction.set(action);
    this.notificationError.set('');
    request.subscribe({
      next: () => {
        onSuccess();
        this.notificationAction.set(null);
      },
      error: () => {
        this.notificationError.set('操作未完成，請確認資料後再試。');
        this.notificationAction.set(null);
      },
    });
  }

  private removeEmergencyLeave(leaveId: number): void {
    this.pendingEmergencyLeaves.update((items) => items.filter((item) => item.id !== leaveId));
    this.selectedEmergencyLeave.set(null);
    this.replacementCandidates.set([]);
    this.selectedReplacementDriverId.set(null);
  }

  /** 沿著路由樹走到最深一層取標題：標題掛在子路由上，shell 這一層沒有 */
  private readRouteTitle(): string {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let title = '';

    while (route) {
      title = (route.data['title'] as string | undefined) ?? title;
      route = route.firstChild;
    }

    return title;
  }

//-------------------------------- 聊天室方法---------------------------------------------------------------
  protected openChat(): void {
    this.chatView.set('wide');
    this.loadChatDrivers();
    this.loadPlan();
  }

  protected closeChat(): void {
    this.chatView.set('closed');
  }

  protected toggleChatWidth(): void {
    this.chatView.update((view) => (view === 'wide' ? 'narrow' : 'wide'));
  }

  protected openStickyNotes(): void {
    this.resetStickyNoteForm();
    this.stickyNotesNotice.set('');
    this.dialog.open(this.stickyNotesDialogTemplate(), {
      width: '520px',
      maxWidth: 'calc(100vw - 32px)',
      maxHeight: 'min(80vh, 720px)',
      panelClass: this.theme.dialogPanelClass(),
    });
    this.loadAdminStickyNotes();
  }

  protected openStickyNotesFromLauncher(): void {
    if (Date.now() - this.stickyNoteLastDragEndedAt < 250) return;
    this.openStickyNotes();
  }

  protected startStickyNoteDrag(event: PointerEvent): void {
    if (event.button !== 0) return;
    this.stickyNoteDragPointerId = event.pointerId;
    this.stickyNoteDragStartX = event.clientX;
    this.stickyNoteDragOffset.set(0);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  protected moveStickyNoteDrag(event: PointerEvent): void {
    if (event.pointerId !== this.stickyNoteDragPointerId) return;
    const offset = event.clientX - this.stickyNoteDragStartX;
    if (Math.abs(offset) < 6 && !this.isStickyNoteDragging()) return;
    this.isStickyNoteDragging.set(true);
    this.stickyNoteDragOffset.set(offset);
    event.preventDefault();
  }

  protected endStickyNoteDrag(event: PointerEvent): void {
    if (event.pointerId !== this.stickyNoteDragPointerId) return;
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) target.releasePointerCapture(event.pointerId);

    if (this.isStickyNoteDragging()) {
      const viewportWidth = this.document.defaultView?.innerWidth ?? 0;
      const side: StickyNoteDockSide = event.clientX < viewportWidth / 2 ? 'left' : 'right';
      this.stickyNoteDockSide.set(side);
      this.persistStickyNoteDockSide(side);
      this.stickyNoteLastDragEndedAt = Date.now();
    }

    this.stickyNoteDragPointerId = null;
    this.stickyNoteDragOffset.set(0);
    this.isStickyNoteDragging.set(false);
  }

  protected cancelStickyNoteDrag(event: PointerEvent): void {
    if (event.pointerId !== this.stickyNoteDragPointerId) return;
    this.stickyNoteDragPointerId = null;
    this.stickyNoteDragOffset.set(0);
    this.isStickyNoteDragging.set(false);
  }

  protected loadAdminStickyNotes(): void {
    this.stickyNotesLoading.set(true);
    this.stickyNotesError.set('');
    this.api.getAdminStickyNotes().subscribe({
      next: (notes) => {
        this.adminStickyNotes.set(this.sortStickyNotesByNewest(notes));
        this.stickyNotesLoading.set(false);
      },
      error: () => {
        this.stickyNotesError.set('無法載入備忘錄，請稍後重試。');
        this.stickyNotesLoading.set(false);
      },
    });
  }

  protected saveStickyNote(): void {
    const content = this.stickyNoteContent().trim();
    if (!content || this.stickyNoteSaving()) return;

    const editingId = this.editingStickyNoteId();
    const existing = editingId === null
      ? undefined
      : this.adminStickyNotes().find((note) => note.id === editingId);
    const request: AdminStickyNoteRequestDto = {
      title: this.stickyNoteTitle().trim() || null,
      content,
      color: existing?.color ?? null,
      sortOrder: existing?.sortOrder ?? 0,
    };

    this.stickyNoteSaving.set(true);
    this.stickyNotesError.set('');
    const save = editingId === null
      ? this.api.createAdminStickyNote(request)
      : this.api.updateAdminStickyNote(editingId, request);
    save.subscribe({
      next: (saved) => {
        this.adminStickyNotes.update((notes) => {
          const updated = editingId === null
            ? [...notes, saved]
            : notes.map((note) => note.id === saved.id ? saved : note);
          return this.sortStickyNotesByNewest(updated);
        });
        this.stickyNoteSaving.set(false);
        this.resetStickyNoteForm();
        this.stickyNotesNotice.set(editingId === null ? '備忘錄已新增。' : '備忘錄已更新。');
      },
      error: (error: HttpErrorResponse) => {
        this.stickyNoteSaving.set(false);
        this.stickyNotesError.set(error.error?.message ?? '備忘錄沒有儲存，請稍後重試。');
      },
    });
  }

  protected editStickyNote(note: AdminStickyNoteDto): void {
    this.editingStickyNoteId.set(note.id);
    this.stickyNoteTitle.set(note.title ?? '');
    this.stickyNoteContent.set(note.content);
    this.deletingStickyNoteId.set(null);
    this.stickyNotesError.set('');
  }

  protected cancelStickyNoteEdit(): void {
    this.resetStickyNoteForm();
  }

  protected deleteStickyNote(note: AdminStickyNoteDto): void {
    if (this.stickyNoteDeleteBusy()) return;
    if (this.deletingStickyNoteId() !== note.id) {
      this.deletingStickyNoteId.set(note.id);
      return;
    }

    this.stickyNoteDeleteBusy.set(true);
    this.stickyNotesError.set('');
    this.api.deleteAdminStickyNote(note.id).subscribe({
      next: () => {
        this.adminStickyNotes.update((notes) => notes.filter((item) => item.id !== note.id));
        this.deletingStickyNoteId.set(null);
        this.stickyNoteDeleteBusy.set(false);
        if (this.editingStickyNoteId() === note.id) this.resetStickyNoteForm();
        this.stickyNotesNotice.set('備忘錄已刪除。');
      },
      error: (error: HttpErrorResponse) => {
        this.stickyNoteDeleteBusy.set(false);
        this.stickyNotesError.set(error.error?.message ?? '備忘錄沒有刪除，請稍後重試。');
      },
    });
  }

  protected cancelStickyNoteDelete(): void {
    this.deletingStickyNoteId.set(null);
  }

  private resetStickyNoteForm(): void {
    this.editingStickyNoteId.set(null);
    this.stickyNoteTitle.set('');
    this.stickyNoteContent.set('');
    this.deletingStickyNoteId.set(null);
  }

  private sortStickyNotesByNewest(notes: readonly AdminStickyNoteDto[]): AdminStickyNoteDto[] {
    return [...notes].sort((left, right) => {
      const updatedAt = Date.parse(right.updatedAt) - Date.parse(left.updatedAt);
      return Number.isNaN(updatedAt) || updatedAt === 0 ? right.id - left.id : updatedAt;
    });
  }

  private readStickyNoteDockSide(): StickyNoteDockSide {
    try {
      return this.document.defaultView?.localStorage.getItem('dispatch-sticky-note-dock') === 'left'
        ? 'left'
        : 'right';
    } catch {
      return 'right';
    }
  }

  private persistStickyNoteDockSide(side: StickyNoteDockSide): void {
    try {
      this.document.defaultView?.localStorage.setItem('dispatch-sticky-note-dock', side);
    } catch {
      // Local storage may be unavailable in private or restricted browser contexts.
    }
  }

  /**
   * 窄版時點任何聯絡人都要展開。
   *
   * 不能只靠 selectionChange：單選清單點「已選中」的項目不會發出事件（Material list.mjs 的 _toggleOnInteraction），
   * 所以要綁在清單的 click / keydown 上，不管點的人有沒有選中都會執行。
   */
  protected expandChatIfNarrow(): void {
    if (this.chatView() === 'narrow') {
      this.chatView.set('wide');
    }
  }

  private loadChatDrivers(): void {
    // 司機名單很少變動，載過一次就不再請求，避免每次打開聊天室都打一次 API
    if (this.chatDrivers().length > 0) {
      return;
    }

    this.chatDriversError.set('');
    this.api.getDrivers().subscribe({
      next: (drivers) => {
        // 後端 findAll 會連停用司機一起回傳，停用帳號登入不了司機端，放進清單也聯絡不到
        const activeDrivers = drivers.filter((driver) => driver.isActive);
        this.chatDrivers.set(activeDrivers);
      },
      error: () => {
        this.chatDriversError.set('無法取得司機名單，請稍後再試。');
      },
    });
  }

  // 只負責換選中的人；窄版展開改由 expandChatIfNarrow 處理，因為點已選中的人不會進到這裡
  protected selectChatContact(contact: ChatContact): void {
    this.selectedChatContact.set(contact);
    if (contact.kind === 'driver') {
      this.loadDriverConversation(contact.driverId);
    } else if (contact.kind === 'case') {
      this.loadCaseConversation(contact.caseId);
    }
  }

  protected isSelectedChatContact(contact: ChatContact): boolean {
    const selected = this.selectedChatContact();

    if (contact.kind === 'ai') {
      return selected.kind === 'ai';
    }
    // 模板每次重畫都會建立新物件，=== 比的是不是同一個物件，所以要比內容
    if (contact.kind === 'case') {
      return selected.kind === 'case' && selected.caseId === contact.caseId;
    }
    return selected.kind === 'driver' && selected.driverId === contact.driverId;
  }

  /** 程式裡切到某件案件用；樣板的 [value] 不能用它（每次呼叫都是新物件，理由見 dispatch-shell.html） */
  private caseContact(item: DriverCaseDto): ChatContact {
    return {kind: 'case', caseId: item.id, name: this.caseContactName(item)};
  }

  /** 異常中心按了「接收」或「開啟對話」：打開聊天室並切到這一件 */
  private openCaseChat(caseId: number): void {
    const item = this.driverCases.findCase(caseId);
    if (!item) {
      return;
    }
    this.openChat();
    this.selectChatContact(this.caseContact(item));
  }

  protected caseContactName(item: DriverCaseDto): string {
    return `${driverCaseCategoryLabel(item.category)} · ${item.driverName ?? '舊版回報'}`;
  }

  private loadCaseConversation(caseId: number): void {
    this.caseMessages.set([]);
    // 換串時輸入框一起清空，理由同 loadDriverConversation
    this.caseChatInput.set('');
    this.caseChatError.set('');
    this.api.getDriverCaseMessages(caseId).subscribe({
      next: (messages) => {
        if (this.isOpenCase(caseId)) {
          this.caseMessages.set(messages);
        }
      },
      error: () => {
        if (this.isOpenCase(caseId)) {
          this.caseChatError.set('無法取得案件對話，請稍後再試。');
        }
      },
    });
  }

  /** 在案件裡回覆司機；跟 sendDriverMessage 同一種寫法。結案後後端會擋，畫面也會把輸入框換成處理結果 */
  protected sendCaseMessage(): void {
    const contact = this.selectedChatContact();
    const content = this.caseChatInput().trim();
    if (contact.kind !== 'case' || !content || this.isSendingCaseMessage()) {
      return;
    }

    const caseId = contact.caseId;
    this.isSendingCaseMessage.set(true);
    this.caseChatError.set('');
    this.api.sendDriverCaseMessage(caseId, content).subscribe({
      next: (saved) => {
        this.isSendingCaseMessage.set(false);
        if (!this.isOpenCase(caseId)) {
          return;
        }
        this.caseChatInput.set('');
        this.mergeCaseMessages([saved]);
      },
      error: (error: HttpErrorResponse) => {
        this.isSendingCaseMessage.set(false);
        if (this.isOpenCase(caseId)) {
          this.caseChatError.set(error.error?.message ?? '訊息沒有送出，請稍後再試。');
        }
      },
    });
  }

  /** 用 id 去重、依 id 由舊到新；理由同 mergeDriverMessages（送出的那則會從回應和推播各收到一次） */
  private mergeCaseMessages(incoming: DriverMessageDto[]): void {
    this.caseMessages.update((current) => {
      const byId = new Map<number, DriverMessageDto>();
      for (const message of [...current, ...incoming]) {
        byId.set(message.id, message);
      }
      return [...byId.values()].sort((a, b) => a.id - b.id);
    });
  }

  private markCaseMessagesAsRead(senderType: DriverMessageDto['senderType'], readAt: string | null): void {
    this.caseMessages.update((messages) =>
      messages.map((message) =>
        message.senderType === senderType && !message.readAt ? {...message, readAt} : message,
      ),
    );
  }

  private isOpenCase(caseId: number): boolean {
    const contact = this.selectedChatContact();
    return contact.kind === 'case' && contact.caseId === caseId;
  }

  protected chatSend(): void {
    const message = this.chatInput();

    this.chatOutput.update((messages) => [...messages, {role: 'user', text: message}]);
    this.chatInput.set('');

    this.api.chatWithAi(message).subscribe({
      next: (chat) => {
        this.chatOutput.update((messages) => [...messages, {role: 'assistant', text: chat.reply}]);
        // 回應帶的是整份清單（不是只有這次新增的），直接整包換掉；AI 回覆的文字不能當清單內容
        this.applyPlan(chat.pendingActions);
      }
    });
  }


  private loadPlan(): void {
    this.api.getAiPlan().subscribe({
      next: (actions) => {
        this.applyPlan(actions);
      }
    })

  }

  private applyPlan(actions: AiPendingActionDto[]): void {
    this.aiPendingAction.set(actions);
  }

  // 刪除
  protected delAllPlan(): void {
    this.api.clearAiPlan().subscribe({
      next: () => {
        this.loadPlan();
      }
    })
  }

  protected delPan(id: string): void {
    this.api.removeAiPlanAction(id).subscribe({
      next: (action) => {
        this.loadPlan();
      }
    })

  }

  // 確認執行：先開視窗讓調度員看過清單，按「執行」才呼叫 API
  protected openConfirmPlan(): void {
    this.dialog.open(this.confirmPlanDialog(), {panelClass: this.theme.dialogPanelClass()}).afterClosed().subscribe((ok) => {
      // 按取消是 false；點背景、按 Esc 是 undefined，只有按「執行」才是 true
      if (!ok) {
        return;
      }
      this.api.confirmAiPlan().subscribe({
        next: () => {
          this.applyPlan([]);
          // 看板在另一個元件，資料庫已經被 AI 改過，要它重讀，不然舊畫面一拖曳就會蓋回去
          this.boardEvents.notifyBoardChanged();
        },
      });
    });
  }

  //拿司機id訊息
  protected loadDriverConversation(driverId: number): void {
    this.driverMessages.set([]);
    // 換人時輸入框一起清空：打給 A 的字留在框裡，按下送出就會送給 B
    this.driverChatInput.set('');
    this.driverChatError.set('');
    this.api.getDriverMessages(driverId).subscribe({
      next: (message) => {
        if (!this.isOpenDriver(driverId)) {
          return
        }
        this.driverMessages.set(message);
      },
      error: () => {
        if (this.isOpenDriver(driverId)) {
          this.driverChatError.set('無法取得對話，請稍後再試。');
        }
      },
    })
  }

  /**
   * 回覆目前開著的司機。
   *
   * 成功：把後端存好的那一則（有 id）合併進清單、清空輸入框。
   * 失敗：保留輸入框的字，讓調度員修改或再按一次；錯誤訊息優先用後端回的（例如超過 1000 字）。
   */
  protected sendDriverMessage(): void {
    const contact = this.selectedChatContact();
    const content = this.driverChatInput().trim();
    // 送出中再按一次（Enter 連按、手滑雙擊）直接忽略，不然會送出兩則一樣的
    if (contact.kind !== 'driver' || !content || this.isSendingDriverMessage()) {
      return;
    }

    const driverId = contact.driverId;
    this.isSendingDriverMessage.set(true);
    this.driverChatError.set('');
    this.api.sendDriverMessage(driverId, content).subscribe({
      next: (saved) => {
        this.isSendingDriverMessage.set(false);
        // 送出途中換了人：訊息已經存進資料庫，換回來時會重新載到，這裡不能塞進別人的視窗
        if (!this.isOpenDriver(driverId)) {
          return;
        }
        this.driverChatInput.set('');
        this.mergeDriverMessages([saved]);
      },
      error: (error: HttpErrorResponse) => {
        this.isSendingDriverMessage.set(false);
        if (this.isOpenDriver(driverId)) {
          // 後端的錯誤格式是 ApiResponse.failure(message)，有訊息就直接顯示
          this.driverChatError.set(error.error?.message ?? '訊息沒有送出，請稍後再試。');
        }
      },
    });
  }

  /**
   * 把新訊息合併進目前的對話：用 id 去重、依 id 由舊到新排序。
   *
   * 不能直接 push：之後接上 WebSocket，自己送出的訊息會「POST 回應」和「推播」各收到一次，
   * 用 id 當 key 才不會出現兩則一樣的。
   */
  private mergeDriverMessages(incoming: DriverMessageDto[]): void {
    this.driverMessages.update((current) => {
      const byId = new Map<number, DriverMessageDto>();
      for (const message of current) {
        byId.set(message.id, message);
      }
      for (const message of incoming) {
        byId.set(message.id, message);
      }
      return [...byId.values()].sort((a, b) => a.id - b.id);
    });
  }

  private isOpenDriver(driverId: number): boolean {
    const contact = this.selectedChatContact();
    return contact.kind === 'driver' && contact.driverId === driverId;

  }

  // -------------------------------- 聊天室即時推播（WebSocket）--------------------------------

  /**
   * 登入後就連線，不等打開聊天室：之後的紅點要在聊天室關著時也能更新。
   * dispatch-shell 銷毀（登出離開後台）時斷線，不然登出後還會一直用舊 token 重撥。
   */
  private connectChatSocket(): void {
    this.chatSocket.pushes$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((push) => this.handleChatPush(push));
    // 第一次連上也會觸發：順便載紅點；重連時一起重抓，補回斷線期間漏掉的未讀
    this.chatSocket.connected$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.loadUnreadSummary();
        this.catchUpOpenConversation();
      });

    this.chatSocket.connect();
    this.destroyRef.onDestroy(() => this.chatSocket.disconnect());
  }

  /**
   * 收到推播。紅點每位司機都要算；對話內容只更新「畫面上開著的那位」。
   * 開著的那位是否要標已讀，交給 markViewingDriverRead 判斷，這裡只管把數字加上去。
   */
  private handleChatPush(push: DriverMessagePushDto): void {
    // 案件建立、接收、結案：DriverCasesService 處理（鈴鐺、異常中心、聊天室的案件清單都讀那邊）
    if (push.type === 'CASE_OPENED' || push.type === 'CASE_ACCEPTED' || push.type === 'CASE_CLOSED') {
      return;
    }
    // 帶 exceptionCaseId 的是案件那一串：不能併進一般對話、也不能算進司機的紅點，
    // 不然案件訊息會出現在一般對話裡。案件的未讀數由 DriverCasesService 算
    const caseId = push.type === 'MESSAGE' ? push.message?.exceptionCaseId : push.exceptionCaseId;
    if (caseId != null) {
      this.handleCaseChatPush(caseId, push);
      return;
    }

    if (push.type === 'MESSAGE' && push.message) {
      // 只有司機發的才算未讀；管理員發的（自己或同事）不用提醒
      if (push.message.senderType === 'DRIVER') {
        this.setDriverUnread(push.driverId, (this.unreadByDriver()[push.driverId] ?? 0) + 1);
      }
      if (this.isOpenDriver(push.driverId)) {
        // 自己剛送出的那則也會推回來一次，mergeDriverMessages 用 id 去重，不會出現兩則
        this.mergeDriverMessages([push.message]);
      }
      return;
    }

    if (push.type === 'READ' && push.readSenderType) {
      // 已讀是所有管理員共用的：同事點開了，自己這邊的紅點也要消
      if (push.readSenderType === 'DRIVER') {
        this.setDriverUnread(push.driverId, 0);
      }
      if (this.isOpenDriver(push.driverId)) {
        // 把這一方發的、還沒讀的訊息，在畫面上標成已讀（不用再打一次 API）
        this.markDriverMessagesAsRead(push.readSenderType, push.readAt ?? null);
      }
    }
  }

  /** 案件對話的訊息、已讀：只更新聊天室開著的那一件 */
  private handleCaseChatPush(caseId: number, push: DriverMessagePushDto): void {
    if (!this.isOpenCase(caseId)) {
      return;
    }
    if (push.type === 'MESSAGE' && push.message) {
      this.mergeCaseMessages([push.message]);
      return;
    }
    if (push.type === 'READ' && push.readSenderType) {
      this.markCaseMessagesAsRead(push.readSenderType, push.readAt ?? null);
    }
  }

  private loadUnreadSummary(): void {
    this.api.getDriverMessageSummary().subscribe({
      next: (summary) => {
        const unread: Record<number, number> = {};
        for (const item of summary) {
          unread[item.driverId] = item.unreadCount;
        }
        this.unreadByDriver.set(unread);
      },
    });
  }

  protected driverPhotoUrl(driver: DriverDto): string | null {
    if (!driver.profilePhotoUrl || (driver.id !== undefined && this.failedDriverPhotoIds().has(driver.id))) {
      return null;
    }
    return driver.profilePhotoUrl;
  }

  protected markDriverPhotoFailed(driverId: number | undefined): void {
    if (driverId === undefined) {
      return;
    }
    this.failedDriverPhotoIds.update((ids) => new Set(ids).add(driverId));
  }

  protected driverUnread(driverId: number | undefined): number {
    return driverId === undefined ? 0 : (this.unreadByDriver()[driverId] ?? 0);
  }

  private setDriverUnread(driverId: number, count: number): void {
    this.unreadByDriver.update((current) => ({...current, [driverId]: count}));
  }

  /** 把目前對話中某一方發的、還沒讀的訊息標上 readAt */
  private markDriverMessagesAsRead(senderType: DriverMessageDto['senderType'], readAt: string | null): void {
    this.driverMessages.update((messages) =>
      messages.map((message) =>
        message.senderType === senderType && !message.readAt ? {...message, readAt} : message,
      ),
    );
  }

  /**
   * 連上時（包含斷線重連）補抓：WebSocket 斷線期間的推播不會補發，只能用 afterId 自己問回來。
   * 清單是空的就整串重載；有東西就只問「比最後一則新的」，接在後面。
   */
  private catchUpOpenConversation(): void {
    const contact = this.selectedChatContact();
    if (contact.kind === 'case') {
      this.catchUpOpenCase(contact.caseId);
      return;
    }
    if (contact.kind !== 'driver') {
      return;
    }

    const driverId = contact.driverId;
    const lastId = this.driverMessages().at(-1)?.id;
    if (lastId === undefined) {
      this.loadDriverConversation(driverId);
      return;
    }

    this.api.getDriverMessages(driverId, lastId).subscribe({
      next: (newer) => {
        if (this.isOpenDriver(driverId)) {
          this.mergeDriverMessages(newer);
        }
      },
    });
  }

  /** 案件對話的重連補抓；做法同上。案件清單（含未讀數）由 DriverCasesService 在連上時重抓 */
  private catchUpOpenCase(caseId: number): void {
    const lastId = this.caseMessages().at(-1)?.id;
    if (lastId === undefined) {
      this.loadCaseConversation(caseId);
      return;
    }

    this.api.getDriverCaseMessages(caseId, lastId).subscribe({
      next: (newer) => {
        if (this.isOpenCase(caseId)) {
          this.mergeCaseMessages(newer);
        }
      },
    });
  }
}

import {Component, DestroyRef, OnInit, TemplateRef, computed, effect, inject, signal, output, viewChild} from '@angular/core';
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
  AiPendingActionDto,
  DriverAccountApplicationDto, DriverDto, DriverMessageDto, DriverMessagePushDto,
  EmergencyLeaveDto,
  EmergencyLeaveReplacementCandidateDto,
} from '../../../../core/services/dispatch-api.models';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DispatchBoardEventsService} from '../../../../core/services/dispatch-board-events.service';
import {DispatchHeaderService} from '../../../../core/services/dispatch-header.service';
import {DriverChatSocketService} from '../../../../core/services/driver-chat-socket.service';
import {AdminThemeService} from '../../../../core/theme/admin-theme.service';
import {FormsModule} from '@angular/forms';

type ChatContact =
  | { kind: 'ai' }
  | { kind: 'driver'; driverId: number; name: string };

type RejectionTarget =
  | { kind: 'application'; id: number; name: string }
  | { kind: 'leave'; id: number; name: string };

// 聊天室狀態
type ChatView = 'closed' | 'narrow' | 'wide';
type ChatMessageRole = 'user' | 'assistant';

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
  private readonly theme = inject(AdminThemeService);
  protected readonly user = inject(AuthService).user;
  protected readonly isSigningOut = signal(false);
  protected readonly isLightTheme = this.theme.isLightTheme;
  // 頂部欄右側的頁面資訊，由各頁寫入（目前只有今日調度）
  protected readonly headerMeta = inject(DispatchHeaderService).meta;
  protected readonly isNotificationsOpen = signal(false);
  protected readonly pendingApplicationCount = signal(0);
  protected readonly pendingApplications = signal<DriverAccountApplicationDto[]>([]);
  protected readonly pendingEmergencyLeaves = signal<EmergencyLeaveDto[]>([]);
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
  // 紅點：driverId → 司機發的、還沒被任何管理員讀的則數。沒有未讀的司機不在裡面
  protected readonly unreadByDriver = signal<Record<number, number>>({});
  // 大頭照載入失敗的司機；記下來改顯示名字第一個字，不然會一直顯示破圖
  private readonly failedDriverPhotoIds = signal<ReadonlySet<number>>(new Set());
  // 聊天室按鈕上的總數
  protected readonly totalUnread = computed(() =>
    Object.values(this.unreadByDriver()).reduce((sum, count) => sum + count, 0),
  );

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


  protected readonly notificationCount = computed(
    () => this.pendingApplicationCount() + this.pendingEmergencyLeaves().length,
  );

  protected readonly aiPendingAction = signal<AiPendingActionDto[]>([]);

  private readonly authService = inject(AuthService);
  private readonly api = inject(DispatchApiService);
  private readonly boardEvents = inject(DispatchBoardEventsService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly chatSocket = inject(DriverChatSocketService);
  private readonly destroyRef = inject(DestroyRef);
  // 確認執行視窗的內容，寫在 dispatch-shell.html 最下面的 <ng-template #confirmPlanDialog>
  private readonly confirmPlanDialog = viewChild.required<TemplateRef<unknown>>('confirmPlanDialog');

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

  protected approveApplication(application: DriverAccountApplicationDto): void {
    const action = `approve-application-${application.id}`;
    this.runNotificationAction(action, this.api.approveDriverAccountApplication(application.id), () => {
      this.pendingApplications.update((items) => items.filter((item) => item.id !== application.id));
      this.pendingApplicationCount.update((count) => Math.max(0, count - 1));
    });
  }

  protected beginApplicationRejection(application: DriverAccountApplicationDto): void {
    this.rejectionTarget.set({kind: 'application', id: application.id, name: application.name});
    this.rejectionReason.set('');
    this.notificationError.set('');
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
    const request =
      target.kind === 'application'
        ? this.api.rejectDriverAccountApplication(target.id, reason)
        : this.api.rejectEmergencyLeaveRequest(target.id, reason);
    this.runNotificationAction(action, request, () => {
      if (target.kind === 'application') {
        this.pendingApplications.update((items) => items.filter((item) => item.id !== target.id));
        this.pendingApplicationCount.update((count) => Math.max(0, count - 1));
      } else {
        this.removeEmergencyLeave(target.id);
      }
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
      count: this.api.getPendingDriverAccountApplicationCount(),
      applications: this.api.getPendingDriverAccountApplications(),
      leaves: this.api.getPendingEmergencyLeaveRequests(),
    }).subscribe({
      next: ({count, applications, leaves}) => {
        this.pendingApplicationCount.set(count.count);
        this.pendingApplications.set(applications);
        this.pendingEmergencyLeaves.set(leaves);
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
    }
  }

  protected isSelectedChatContact(contact: ChatContact): boolean {
    const selected = this.selectedChatContact();

    if (contact.kind === 'ai') {
      return selected.kind === 'ai';
    }
    // 模板每次重畫都會建立新物件，=== 比的是不是同一個物件，所以要比內容
    return selected.kind === 'driver' && selected.driverId === contact.driverId;
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
    this.dialog.open(this.confirmPlanDialog()).afterClosed().subscribe((ok) => {
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
}

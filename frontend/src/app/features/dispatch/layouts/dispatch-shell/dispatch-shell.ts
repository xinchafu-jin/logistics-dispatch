import {Component, OnInit, TemplateRef, computed, inject, signal, output, viewChild} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
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
import {
  LucideArrowLeftToLine,
  LucideArrowRightToLine,
  LucideBell,
  LucideCalendarDays,
  LucideChartNoAxesCombined,
  LucideChevronDown,
  LucideClipboardCheck,
  LucideLogOut,
  LucideMapPinned,
  LucideMoon,
  LucideSendHorizontal,
  LucideSun,
  LucideTriangleAlert,
  LucideTruck,
  LucideUserRound,
  LucideWorkflow,
  LucideX,
} from '@lucide/angular';
import {BrandLogo} from '../../../../shared/ui/brand-logo/brand-logo';
import {AuthService} from '../../../../core/auth/auth.service';
import {
  AiPendingActionDto,
  DriverAccountApplicationDto, DriverDto,
  EmergencyLeaveDto,
  EmergencyLeaveReplacementCandidateDto,
} from '../../../../core/services/dispatch-api.models';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
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
    LucideBell,
    LucideCalendarDays,
    LucideWorkflow,
    LucideClipboardCheck,
    LucideTruck,
    LucideMapPinned,
    LucideMoon,
    LucideSun,
    LucideUserRound,
    LucideLogOut,
    LucideTriangleAlert,
    LucideChartNoAxesCombined,
    LucideArrowLeftToLine,
    LucideArrowRightToLine,
    LucideChevronDown,
    MatButtonModule,
    MatTooltipModule,
    BrandLogo,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    TextFieldModule,
    LucideSendHorizontal,
    LucideX,
    FormsModule,
    MatExpansionModule,
    MatDialogModule,
  ],
  templateUrl: './dispatch-shell.html',
  styleUrl: './dispatch-shell.scss',
})
export class DispatchShell implements OnInit {
  private readonly theme = inject(AdminThemeService);
  protected readonly user = inject(AuthService).user;
  protected readonly isSigningOut = signal(false);
  protected readonly isLightTheme = this.theme.isLightTheme;
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


  protected readonly notificationCount = computed(
    () => this.pendingApplicationCount() + this.pendingEmergencyLeaves().length,
  );

  protected readonly aiPendingAction = signal<AiPendingActionDto[]>([]);

  private readonly authService = inject(AuthService);
  private readonly api = inject(DispatchApiService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
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
        this.notificationError.set('暫時無法取得主管待辦，請確認後端服務後重新整理。');
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
        this.notificationError.set('操作未完成，請確認資料與後端狀態後再試。');
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
        next: () => this.applyPlan([]),
      });
    });
  }

}

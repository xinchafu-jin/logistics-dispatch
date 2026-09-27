import {HttpErrorResponse} from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  TemplateRef,
  ViewChild,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {Router} from '@angular/router';
import {FormsModule} from '@angular/forms';
import {TextFieldModule} from '@angular/cdk/text-field';
import {MatBadgeModule} from '@angular/material/badge';
import {MatBottomSheet, MatBottomSheetModule, MatBottomSheetRef} from '@angular/material/bottom-sheet';
import {MatButtonModule} from '@angular/material/button';
import {MatCalendar, MatCalendarCellClassFunction, MatDatepickerModule} from '@angular/material/datepicker';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import type * as maplibregl from 'maplibre-gl';
import {Observable, of, single, switchMap} from 'rxjs';
import {DriverAuthService} from '../../core/auth/driver-auth.service';
import {
  clearStoredMapLocation,
  readStoredMapLocation,
  saveStoredMapLocation,
} from '../../core/location/driver-map-location.storage';
import {DriverChatSocketService} from '../../core/services/driver-chat-socket.service';
import {DriverGpsTrackingService} from '../../core/services/driver-gps-tracking.service';
import {
  AttendanceRecordDto,
  DeliveryRecordResponse,
  DriverLeaveHistoryResponse,
  DriverLeaveBatchResponse,
  DriverMakeupLeaveRequest,
  DriverPlannedLeaveBatchRequest,
  DriverLeaveRequest,
  DriverLeaveRequestResponse,
  EmergencyLeaveResponse,
  DriverProfileDto,
  DriverRouteTask,
  DriverShiftDto,
  DriverMessageDto,
  DriverMessagePushDto,
  DriverTaskStop,
  DriverTaskOrderStatus,
  DriverTasksResponse,
} from '../../core/services/driver-operations.models';
import {DriverOperationsService} from '../../core/services/driver-operations.service';
import {DriverWeather, DriverWeatherService} from '../../core/services/driver-weather.service';
import {BrandLogo} from '../../shared/ui/brand-logo/brand-logo';
import {ScheduleCellLabels} from '../../shared/ui/schedule-cell-labels/schedule-cell-labels';
import {ScheduleLeaveComposer} from './schedule-leave-composer/schedule-leave-composer';
import {pendingLeaveDatesInMonth} from './schedule-leave-composer/schedule-leave-status';
import {PreTripCheck} from './pre-trip-check/pre-trip-check';

type AttendanceViewState = 'loading' | 'not-clocked-in' | 'ready' | 'error';
type DriverTab = 'map' | 'tasks' | 'profile' | 'schedule';
type TaskViewState = 'loading' | 'ready' | 'empty' | 'error';
type ScheduleViewState = 'loading' | 'ready' | 'empty' | 'error';
type NavigationRouteState = 'idle' | 'loading' | 'ready' | 'error';
type ChatViewState = 'loading' | 'ready' | 'empty' | 'error';

interface DriverTaskSelection {
  route: DriverRouteTask;
  stop: DriverTaskStop;
}

interface DriverLeaveForm {
  workDate: string;
  leaveType: DriverLeaveRequest['leaveType'];
  leaveStart: string;
  leaveEnd: string;
  reason: string;
}

type LeaveApplicationTab = 'temporary' | 'planned' | 'makeup';
type LeaveTopic = 'schedule' | 'emergency';

interface DriverPlannedLeaveForm {
  leaveType: DriverLeaveRequest['leaveType'];
  workDates: string[];
  reason: string;
}

interface DriverMakeupLeaveForm {
  workDate: string;
  leaveType: DriverLeaveRequest['leaveType'];
  reason: string;
}

interface LoadingItemForm {
  checked: boolean;
  loadedQuantity: string;
  notes: string;
}

type MapPosition = [lng: number, lat: number];
const DRIVER_ROUTE_SOURCE_ID = 'driver-navigation-route';
const DRIVER_ROUTE_LAYER_ID = 'driver-navigation-route-line';
const DRIVER_MAP_DEFAULT_CENTER: MapPosition = [120.3014, 22.6273];
const OPEN_FREE_MAP_STYLE = 'https://tiles.openfreemap.org/styles/liberty';

// 找出路線上離 here 最近的點，回傳距離（公尺）與索引。
export function findNearest(
  here: MapPosition,
  route: MapPosition[],
): { distance: number; index: number } {
  let distance = Infinity;
  let index = 0;

  for (let i = 0; i < route.length; i++) {
    const d = distanceInMeters(here, route[i]);
    if (d < distance) {
      distance = d;
      index = i;
    }
  }

  return {distance, index};
}

function calculateRouteDistance(route: MapPosition[]): number {
  let distance = 0;
  for (let i = 0; i < route.length - 1; i++) {
    distance += distanceInMeters(route[i], route[i + 1]);
  }
  return distance;
}

function calculateRemainingRouteDistance(here: MapPosition, route: MapPosition[]): number {
  if (route.length === 0) {
    return 0;
  }

  return distanceInMeters(here, route[0]) + calculateRouteDistance(route);
}

function distanceInMeters(from: MapPosition, to: MapPosition): number {
  const earthRadiusMeters = 6_371_000;
  const latitudeDelta = (to[1] - from[1]) * (Math.PI / 180);
  const longitudeDelta = (to[0] - from[0]) * (Math.PI / 180);
  const fromLatitude = from[1] * (Math.PI / 180);
  const toLatitude = to[1] * (Math.PI / 180);
  const arc =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(fromLatitude) * Math.cos(toLatitude) * Math.sin(longitudeDelta / 2) ** 2;

  return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(arc), Math.sqrt(1 - arc));
}

function bearingInDegrees(from: MapPosition, to: MapPosition): number {
  const longitudeDelta = (to[0] - from[0]) * (Math.PI / 180);
  const fromLatitude = from[1] * (Math.PI / 180);
  const toLatitude = to[1] * (Math.PI / 180);
  const y = Math.sin(longitudeDelta) * Math.cos(toLatitude);
  const x =
    Math.cos(fromLatitude) * Math.sin(toLatitude) -
    Math.sin(fromLatitude) * Math.cos(toLatitude) * Math.cos(longitudeDelta);

  return (Math.atan2(y, x) * (180 / Math.PI) + 360) % 360;
}

const OFF_ROUTE_METERS = 70;
const OFF_ROUTE_STREAK = 3;
const RECALC_COOLDOWN_MS = 15_000;

@Component({
  selector: 'app-driver-dashboard',
  imports: [
    MatBadgeModule,
    MatBottomSheetModule,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    TextFieldModule,
    FormsModule,
    BrandLogo,
    PreTripCheck,
    ScheduleCellLabels,
    ScheduleLeaveComposer,
  ],
  templateUrl: './driver-dashboard.html',
  styleUrl: './driver-dashboard.scss',
})
export class DriverDashboard implements AfterViewInit, OnDestroy {
  @ViewChild('driverMap') private driverMapElement?: ElementRef<HTMLElement>;
  // 聊天 Bottom Sheet 的內容，寫在 driver-dashboard.html 最下面的 <ng-template #driverChatSheet>
  @ViewChild('driverChatSheet') private driverChatSheet?: TemplateRef<unknown>;

  protected readonly user = inject(DriverAuthService).user;
  protected readonly weather = signal<DriverWeather | null>(null);
  protected readonly weatherUnavailable = signal(false);
  protected readonly attendance = signal<AttendanceRecordDto | null>(null);
  protected readonly attendanceViewState = signal<AttendanceViewState>('loading');
  protected readonly attendanceError = signal<string | null>(null);
  protected readonly isSubmitting = signal(false);
  protected readonly remainingBreakSeconds = signal(0);
  protected readonly publishedShifts = signal<DriverShiftDto[]>([]);
  protected readonly scheduleViewState = signal<ScheduleViewState>('loading');
  protected readonly scheduleError = signal<string | null>(null);
  protected readonly scheduleMonth = signal(this.monthStart(new Date()));
  private scheduleRequestVersion = 0;
  // 班表月曆點選的那一天，下方顯示這天的班次；預設今天
  protected readonly selectedScheduleDate = signal(new Date());
  // workDate（YYYY-MM-DD）→ 班次，月曆每一格、下方明細都從這裡查
  private readonly shiftsByDate = computed(
    () => new Map(this.publishedShifts().map((shift) => [shift.workDate, shift])),
  );
  protected readonly selectedShift = computed(
    () => this.shiftsByDate().get(this.toIsoDate(this.selectedScheduleDate())) ?? null,
  );
  protected readonly pendingScheduleDates = computed(() => pendingLeaveDatesInMonth(this.leaveRequests(), this.scheduleMonth()));
  private readonly scheduleCalendar = viewChild<MatCalendar<Date>>('scheduleCalendar');
  private readonly calendarLeaveComposer = viewChild<ScheduleLeaveComposer>('calendarLeaveComposer');
  protected readonly scheduleDateFilter = (date: Date) => this.calendarLeaveComposer()?.canSelectDate(date) ?? true;

  protected refreshScheduleCalendar(): void { this.scheduleCalendar()?.updateTodaysDate(); }

  protected selectScheduleDate(date: Date | null): void {
    if (!date) return;
    this.selectedScheduleDate.set(date);
    this.calendarLeaveComposer()?.toggleDate(date);
  }

  protected showScheduleToday(): void {
    const today = new Date();
    this.selectedScheduleDate.set(today);
    const calendar = this.scheduleCalendar();
    if (calendar) calendar.activeDate = today;
  }

  protected applyCalendarLeaves(saved: DriverLeaveRequestResponse[]): void {
    const ids = new Set(saved.map(request => request.id));
    this.leaveRequests.update(requests => [...saved, ...requests.filter(request => !ids.has(request.id))]
      .sort((left, right) => right.requestedAt.localeCompare(left.requestedAt)));
    this.refreshScheduleCalendar();
  }

  /**
   * 月曆每一格的 class：依當天班別上色（shift-work／shift-day_off／shift-leave），樣式在 styles.scss。
   * Material 只在月曆重畫時才呼叫這個函式，所以班表載完要呼叫 updateTodaysDate() 讓它重畫。
   */
  protected readonly scheduleDateClass: MatCalendarCellClassFunction<Date> = (date, view) => {
    if (view !== 'month') {
      return '';
    }
    const shift = this.shiftsByDate().get(this.toIsoDate(date));
    const status = this.scheduleViewState() === 'loading' ? 'loading'
      : this.scheduleViewState() === 'error' ? 'unavailable' : shift?.shiftType.toLowerCase() ?? 'not-published';
    const pending = this.pendingScheduleDates().has(this.toIsoDate(date));
    return `shift-cell shift-${status} ${pending ? 'leave-request-pending' : ''} ${this.calendarLeaveComposer()?.dateClass(date) ?? ''}`;
  };

  /**
   * 月曆標頭的上一月／下一月換月時，跟著載那個月的班表。
   * MatCalendar 沒有「換月」的 output，只能聽它的 stateChanges（activeDate 變了就會發），自己比對月份。
   * 月曆只在班表分頁存在，viewChild 會跟著出現、消失，所以用 effect 在它出現時訂閱、消失時退訂。
   */
  private readonly followScheduleCalendarMonth = effect((onCleanup) => {
    const calendar = this.scheduleCalendar();
    if (!calendar) {
      return;
    }
    const subscription = calendar.stateChanges.subscribe(() => {
      const month = this.monthStart(calendar.activeDate);
      if (month.getTime() !== this.scheduleMonth().getTime()) {
        this.scheduleMonth.set(month);
        this.loadPublishedShifts();
      }
    });
    onCleanup(() => subscription.unsubscribe());
  });
  protected readonly todayTasks = signal<DriverTasksResponse | null>(null);
  protected readonly inspectionReady = signal<Record<number, boolean>>({});
  protected setInspectionReady(routeId: number, passed: boolean): void {
    this.inspectionReady.update(ready => ({...ready, [routeId]: passed}));
  }
  protected readonly taskViewState = signal<TaskViewState>('loading');
  protected readonly taskError = signal<string | null>(null);
  protected readonly selectedTask = signal<DriverTaskSelection | null>(null);
  protected readonly activeDeliveryOrderId = signal<number | null>(null);
  protected readonly deliveryNotes = signal('');
  protected readonly deliveryPhoto = signal<File | null>(null);
  protected readonly deliveryExceptionOpen = signal(false);
  protected readonly deliveryExceptionDescription = signal('');
  protected readonly activeLoadingOrderId = signal<number | null>(null);
  protected readonly loadingBoxCount = signal('');
  protected readonly loadingNotes = signal('');
  protected readonly loadingItemForms = signal<Record<number, LoadingItemForm>>({});
  /** 箱數不符時先停一次讓司機再點一遍；改了箱數就要重新確認 */
  protected readonly loadingMismatchPending = signal(false);
  protected readonly taskActionError = signal<string | null>(null);
  protected readonly taskActionMessage = signal<string | null>(null);
  protected readonly isTaskSubmitting = signal(false);
  protected readonly startMileageReading = signal('');
  protected readonly endMileageReading = signal('');
  protected readonly mileageError = signal<string | null>(null);
  protected readonly mileageMessage = signal<string | null>(null);
  protected readonly isMileageSubmitting = signal(false);
  protected readonly emergencyLeaves = signal<EmergencyLeaveResponse[]>([]);
  protected readonly emergencyLeaveReason = signal('');
  protected readonly emergencyLeaveError = signal<string | null>(null);
  protected readonly emergencyLeaveMessage = signal<string | null>(null);
  protected readonly isEmergencyLeaveFormOpen = signal(false);
  protected readonly isEmergencyLeaveSubmitting = signal(false);
  protected readonly isEmergencyLeaveHistoryLoading = signal(false);
  protected readonly leaveRequests = signal<DriverLeaveRequestResponse[]>([]);
  protected readonly leaveForm = signal<DriverLeaveForm>({
    workDate: this.toIsoDate(new Date()),
    leaveType: 'SPECIAL',
    leaveStart: '',
    leaveEnd: '',
    reason: '',
  });
  protected readonly leaveApplicationTab = signal<LeaveApplicationTab>('temporary');
  protected readonly leaveTopic = signal<LeaveTopic>('schedule');
  protected readonly plannedLeaveForm = signal<DriverPlannedLeaveForm>({
    leaveType: 'ANNUAL',
    workDates: [],
    reason: '',
  });
  protected readonly plannedLeaveShifts = signal<DriverShiftDto[]>([]);
  protected readonly isPlannedLeaveLoading = signal(false);
  protected readonly makeupLeaveForm = signal<DriverMakeupLeaveForm>({
    workDate: '',
    leaveType: 'SICK',
    reason: '',
  });
  protected readonly selectedMakeupEvidenceFile = signal<File | null>(null);
  protected readonly leaveHistoryDate = signal('');
  protected readonly leaveError = signal<string | null>(null);
  protected readonly leaveMessage = signal<string | null>(null);
  protected readonly isLeaveSubmitting = signal(false);
  protected readonly isLeaveListLoading = signal(false);
  protected readonly leaveListAvailable = signal(false);
  protected readonly isLeaveHistoryLoading = signal(false);
  protected readonly expandedLeaveId = signal<number | null>(null);
  protected readonly leaveHistories = signal<Record<number, DriverLeaveHistoryResponse[]>>({});
  protected readonly leaveHistoryErrors = signal<Record<number, string>>({});
  protected readonly unreadLeaveCount = computed(
    () => this.leaveRequests().filter((request) => !request.driverReadAt).length,
  );
  protected readonly leaveHistoryDates = computed(() =>
    [...new Set(this.leaveRequests().map((request) => request.workDate))].sort((left, right) => right.localeCompare(left)),
  );
  protected readonly filteredLeaveRequests = computed(() => {
    const workDate = this.leaveHistoryDate();
    return workDate
      ? this.leaveRequests().filter((request) => request.workDate === workDate)
      : this.leaveRequests();
  });
  /** 事後補請只能把系統記下的當日未到紀錄補上原因，不能自行挑任意過去日期。 */
  protected readonly makeupLeaveCandidates = computed(() =>
    this.leaveRequests()
      .filter((request) =>
        request.requestMode === 'SYSTEM_NO_SHOW' && request.status === 'PENDING' && request.fullDay,
      )
      .sort((left, right) => right.workDate.localeCompare(left.workDate)),
  );
  protected readonly profile = signal<DriverProfileDto | null>(null);
  protected readonly profileError = signal<string | null>(null);
  protected readonly profilePhotoMessage = signal<string | null>(null);
  protected readonly isProfilePhotoUploading = signal(false);
  protected readonly profilePhotoLoadFailed = signal(false);
  protected readonly navigationRouteState = signal<NavigationRouteState>('idle');
  protected readonly navigationDistanceMeters = signal<number | null>(null);
  protected readonly navigationDurationSeconds = signal<number | null>(null);
  protected readonly mapLocationStatus = signal('尚未取得目前位置');
  protected readonly activeTab = signal<DriverTab>('map');
  protected readonly isAttendanceSheetExpanded = signal(false);
  protected readonly isAttendanceSheetDragging = signal(false);
  protected readonly attendanceSheetDragOffset = signal(0);
  protected readonly chatMessages = signal<DriverMessageDto[]>([]);
  protected readonly chatViewState = signal<ChatViewState>('loading');
  protected readonly chatError = signal<string | null>(null);
  // 聊天鈕紅點：調度中心發的、司機還沒讀的則數。只看已載入的清單（最近 50 則＋之後的推播），
  // 未讀超過 50 則的情況不會發生在一天的配送裡，所以不另外開一支「司機未讀數」API
  protected readonly unreadChatCount = computed(
    () => this.chatMessages().filter((message) => message.senderType === 'ADMIN' && !message.readAt).length,
  );
  // 輸入框；sheet 關掉再打開，打到一半的字還在
  protected readonly chatInput = signal('');
  // 送出中：鎖住送出鈕，避免連按送出兩則一樣的訊息
  protected readonly isSendingChatMessage = signal(false);
  // 送出失敗的訊息，空字串代表沒有錯誤；跟 chatError（載入失敗）分開，送不出去不能把整串對話換成錯誤畫面
  protected readonly chatSendError = signal('');
  // 聊天 sheet 是否開著；用 signal 而不是只看 driverChatSheetRef，effect 才追蹤得到
  private readonly isChatOpen = signal(false);

  /**
   * 司機「看得到對話」而且有未讀，就標已讀。跟後台 dispatch-shell 的 markViewingDriverRead 同一種寫法。
   *
   * 會讓司機看到的入口有：打開 sheet、對話載入完成、開著時收到新訊息、重連補抓；
   * 用 effect 只描述「開著＋有未讀＝標已讀」，不用在每個入口各呼叫一次，漏一個紅點就消不掉。
   */
  private readonly markViewingChatRead = effect(() => {
    if (!this.isChatOpen() || this.unreadChatCount() === 0) {
      return;
    }

    // 先在畫面上標掉：不標的話 effect 重跑時還是有未讀，會連續打好幾次 API。
    // 後端標完會推 READ 回來，但 readAt 已經有值就不會再改，不影響畫面
    const readAt = new Date().toISOString();
    this.chatMessages.update((messages) =>
      messages.map((message) => (message.senderType === 'ADMIN' && !message.readAt ? {...message, readAt} : message)),
    );
    // 失敗不重試：重抓會把未讀抓回來又觸發這裡，網路斷著就會一直打。
    // 資料庫仍是未讀，下次重連或重開 sheet 重新載入時紅點會回來，再標一次
    this.operations.markMessagesRead().subscribe({error: () => undefined});
  });

  protected readonly gpsTracking = inject(DriverGpsTrackingService);

  private readonly authService = inject(DriverAuthService);
  private readonly operations = inject(DriverOperationsService);
  private readonly weatherService = inject(DriverWeatherService);
  private readonly router = inject(Router);
  private readonly bottomSheet = inject(MatBottomSheet);
  private readonly chatSocket = inject(DriverChatSocketService);
  // 目前開著的聊天 Bottom Sheet；null 代表沒開，用來避免連點開出兩層
  private driverChatSheetRef: MatBottomSheetRef | null = null;
  private breakTimer: ReturnType<typeof setInterval> | null = null;
  private attendanceRefreshTimer: ReturnType<typeof setInterval> | null = null;
  private leaveRefreshTimer: ReturnType<typeof setInterval> | null = null;
  private maplibre: typeof import('maplibre-gl') | null = null;
  private driverMap: maplibregl.Map | null = null;
  private currentMapLocation: MapPosition | null = null;
  private lastMovementLocation: MapPosition | null = null;
  private lastMovementHeading: number | null = null;
  private currentLocationMarker: maplibregl.Marker | null = null;
  private destinationMarker: maplibregl.Marker | null = null;
  private destinationPopup: maplibregl.Popup | null = null;
  private routeLatLng: MapPosition[] = [];
  private routeDistanceScale = 1;
  private routeDurationSecondsPerMeter: number | null = null;
  private wakeLock: WakeLockSentinel | null = null;
  protected readonly isNavigating = signal(false);
  private offRouteStreak = 0;
  private lastRecalcAt = 0;
  private mapLocationWatchId: number | null = null;
  private attendanceSheetPointerId: number | null = null;
  private attendanceSheetPointerStartY: number | null = null;
  private ignoreAttendanceSheetClick = false;

  constructor() {
    this.weatherService.getCurrentWeather().subscribe({
      next: (weather) => this.weather.set(weather),
      error: () => this.weatherUnavailable.set(true),
    });
    this.loadAttendance();
    this.loadPublishedShifts();
    this.loadTodayTasks();
    this.loadProfile();
    this.loadLeaveRequests();
    this.leaveRefreshTimer = setInterval(() => {
      if (this.activeTab() === 'schedule') {
        this.loadLeaveRequests();
      }
    }, 60_000);
    this.connectChatSocket();
  }

  /**
   * 聊天室的即時推播：進工作台就連線，不等打開聊天卡片，之後紅點才能在卡片關著時也更新。
   * 斷線寫在 ngOnDestroy 與 signOut，不寫在 DriverAuthService.logout()：
   * 連線 service 本身要向 DriverAuthService 拿 token，反過來注入會變成互相依賴，Angular 會直接報錯。
   *
   * 收到的推播合併到對話清單。第一次連上也要載一次對話：不載的話，
   * 司機登入前調度中心就發的訊息不會算進紅點，要點開聊天才知道有人找他。
   * 重連時同樣重載，補回斷線期間漏掉的推播。
   */
  private connectChatSocket(): void {
    this.chatSocket.pushes$
      .pipe(takeUntilDestroyed())
      .subscribe((push) => this.handleChatPush(push));
    this.chatSocket.connected$
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.loadChatMessages());
    this.chatSocket.connect();
  }

  ngAfterViewInit(): void {
    void this.initializeMap();
  }

  ngOnDestroy(): void {
    // 登出離開工作台時，sheet 還掛在 body 上的 overlay 裡，要一起關掉
    this.driverChatSheetRef?.dismiss();
    this.chatSocket.disconnect();
    this.stopNavigation();
    this.clearBreakTimer();
    this.clearAttendanceRefreshTimer();
    if (this.leaveRefreshTimer) {
      clearInterval(this.leaveRefreshTimer);
      this.leaveRefreshTimer = null;
    }
    this.gpsTracking.stop();
    this.stopMapLocationWatch();
    this.currentLocationMarker?.remove();
    this.destinationMarker?.remove();
    this.destinationPopup?.remove();
    this.driverMap?.remove();
    this.driverMap = null;
  }

  protected clockIn(): void {
    this.runAttendanceAction(() => this.operations.clockIn());
  }

  protected startBreak(): void {
    this.runAttendanceAction(() => this.operations.startBreak());
  }

  protected clockOut(): void {
    this.runAttendanceAction(() => this.operations.clockOut());
  }

  protected refreshAttendance(): void {
    this.loadAttendance();
  }

  /**
   * 打開與調度中心的聊天（Material Bottom Sheet，從底部滑上來）。
   *
   * 內容用 <ng-template>，不另開元件：跟後台確認視窗（MatDialog）同一種寫法，一頁看得到全部。
   * 之後的對話內容與 WebSocket 連線要放在這個元件的 signal／service，不能放在 sheet 裡：
   * sheet 關掉時裡面的畫面會整個銷毀，放在裡面的話，關著時收不到訊息、重開要整串重載。
   */
  protected openDriverChat(): void {
    if (!this.driverChatSheet || this.driverChatSheetRef) {
      return;
    }
    this.driverChatSheetRef = this.bottomSheet.open(this.driverChatSheet, {
      ariaLabel: '與調度中心的對話',
      // 全螢幕：尺寸寫在 styles.scss 的 .driver-chat-sheet-panel。
      // 只設 height 不夠，Material 自己的 CSS 還限制了 max-height: 80vh 和寬螢幕下的寬度
      panelClass: 'driver-chat-sheet-panel',
    });
    // 點背景、按 Esc、下滑關閉都會走到這裡，統一在這裡清掉，下次才開得起來
    this.isChatOpen.set(true);
    this.driverChatSheetRef.afterDismissed().subscribe(() => {
      this.driverChatSheetRef = null;
      this.isChatOpen.set(false);
    });
    this.loadChatMessages();
  }

  protected closeDriverChat(): void {
    this.driverChatSheetRef?.dismiss();
  }

  protected isOwnChatMessage(message: DriverMessageDto): boolean {
    return message.senderType === 'DRIVER';
  }

  protected formatChatTime(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    return new Intl.DateTimeFormat('zh-TW', {
      hour: '2-digit',
      minute: '2-digit',
    }).format(date);
  }

  private loadChatMessages(): void {
    this.chatViewState.set('loading');
    this.chatError.set(null);
    this.operations.getMessages().subscribe({
      next: (messages) => {
        this.chatMessages.set(this.mergeChatMessages(messages));
        // 要不要標已讀交給 markViewingChatRead：背景載入（連線、重連）時 sheet 關著，就不會標
        this.chatViewState.set(this.chatMessages().length ? 'ready' : 'empty');
      },
      error: () => {
        this.chatViewState.set('error');
        this.chatError.set('目前無法取得對話內容。');
      },
    });
  }

  private handleChatPush(push: DriverMessagePushDto): void {
    if (push.type === 'MESSAGE' && push.message) {
      this.chatMessages.set(this.mergeChatMessages([push.message]));
      this.chatViewState.set('ready');
      return;
    }

    // DRIVER：調度中心讀了我的訊息，畫面顯示「已讀」；
    // ADMIN：自己標已讀後後端也會推回來，可能比 HTTP 回應先到；只補還沒填的，誰先到都一樣
    if (push.type === 'READ' && push.readSenderType && push.readAt) {
      const readAt = push.readAt;
      this.chatMessages.update((messages) =>
        messages.map((message) =>
          message.senderType === push.readSenderType && !message.readAt ? {...message, readAt} : message,
        ),
      );
    }
  }

  private mergeChatMessages(incoming: DriverMessageDto[]): DriverMessageDto[] {
    const merged = new Map(this.chatMessages().map((message) => [message.id, message]));
    incoming.forEach((message) => merged.set(message.id, message));
    return Array.from(merged.values()).sort((left, right) => left.id - right.id);
  }

  /**
   * 發訊息給調度中心。跟後台 dispatch-shell 的 sendDriverMessage 同一種寫法。
   *
   * 成功：把後端存好的那一則（有 id）合併進清單、清空輸入框；推播之後會再送來同一則，用 id 去重。
   * 失敗：保留輸入框的字，讓司機修改或再按一次；錯誤訊息優先用後端回的（例如超過 1000 字）。
   */
  protected sendChatMessage(): void {
    const content = this.chatInput().trim();
    // 送出中再按一次（Enter 連按、手滑雙擊）直接忽略，不然會送出兩則一樣的
    if (!content || this.isSendingChatMessage()) {
      return;
    }

    this.isSendingChatMessage.set(true);
    this.chatSendError.set('');
    this.operations.sendMessage(content).subscribe({
      next: (saved) => {
        this.isSendingChatMessage.set(false);
        this.chatInput.set('');
        this.chatMessages.set(this.mergeChatMessages([saved]));
        this.chatViewState.set('ready');
      },
      error: (error: HttpErrorResponse) => {
        this.isSendingChatMessage.set(false);
        this.chatSendError.set(error.error?.message ?? '訊息沒有送出，請稍後再試。');
      },
    });
  }

  protected profilePhotoUrl(): string | null {
    if (this.profilePhotoLoadFailed()) {
      return null;
    }
    return this.profile()?.profilePhotoUrl ?? null;
  }

  protected profileInitial(): string {
    const name = this.profile()?.name ?? this.user()?.name ?? '司';
    return name.trim().slice(0, 1) || '司';
  }

  protected handleProfilePhotoLoadError(): void {
    this.profilePhotoLoadFailed.set(true);
  }

  protected selectProfilePhoto(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file || this.isProfilePhotoUploading()) {
      return;
    }

    if (file.size > 5 * 1024 * 1024) {
      this.profileError.set('大頭照不可超過 5 MB。');
      input.value = '';
      return;
    }

    this.isProfilePhotoUploading.set(true);
    this.profileError.set(null);
    this.profilePhotoMessage.set(null);

    this.operations.uploadProfilePhoto(file).subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.profilePhotoLoadFailed.set(false);
        this.profilePhotoMessage.set('大頭照已更新。');
        this.isProfilePhotoUploading.set(false);
      },
      error: (error: unknown) => {
        this.profileError.set(this.getErrorMessage(error, '大頭照上傳失敗。'));
        this.isProfilePhotoUploading.set(false);
      },
    });

    input.value = '';
  }

  protected signOut(): void {
    this.clearBreakTimer();
    this.gpsTracking.stop();
    // 在清掉 token 之前先斷線：不然 5 秒後會用已經失效的 token 重撥
    this.chatSocket.disconnect();
    this.stopMapLocationWatch();
    clearStoredMapLocation();
    this.authService.logout();
    void this.router.navigateByUrl('/login');
  }

  protected toggleAttendanceSheet(): void {
    if (this.ignoreAttendanceSheetClick) {
      this.ignoreAttendanceSheetClick = false;
      return;
    }

    this.isAttendanceSheetExpanded.update((expanded) => !expanded);
    this.attendanceSheetDragOffset.set(0);
  }

  protected startAttendanceSheetDrag(event: PointerEvent): void {
    if (event.button !== 0) {
      return;
    }

    this.attendanceSheetPointerId = event.pointerId;
    this.attendanceSheetPointerStartY = event.clientY;
    this.isAttendanceSheetDragging.set(false);
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }

  protected moveAttendanceSheetDrag(event: PointerEvent): void {
    if (this.attendanceSheetPointerId !== event.pointerId || this.attendanceSheetPointerStartY === null) {
      return;
    }

    const deltaY = event.clientY - this.attendanceSheetPointerStartY;
    if (Math.abs(deltaY) < 6) {
      return;
    }

    this.isAttendanceSheetDragging.set(true);
    const offset = this.isAttendanceSheetExpanded()
      ? Math.max(0, Math.min(deltaY, 140))
      : Math.min(0, Math.max(deltaY, -140));
    this.attendanceSheetDragOffset.set(offset);
  }

  protected endAttendanceSheetDrag(event: PointerEvent): void {
    if (this.attendanceSheetPointerId !== event.pointerId || this.attendanceSheetPointerStartY === null) {
      return;
    }

    const deltaY = event.clientY - this.attendanceSheetPointerStartY;
    if (Math.abs(deltaY) >= 12) {
      this.ignoreAttendanceSheetClick = true;
      if (this.isAttendanceSheetExpanded() && deltaY > 44) {
        this.isAttendanceSheetExpanded.set(false);
      } else if (!this.isAttendanceSheetExpanded() && deltaY < -44) {
        this.isAttendanceSheetExpanded.set(true);
      }
    }

    this.resetAttendanceSheetDrag(event);
  }

  protected cancelAttendanceSheetDrag(event: PointerEvent): void {
    if (this.attendanceSheetPointerId === event.pointerId) {
      this.resetAttendanceSheetDrag(event);
    }
  }

  protected setActiveTab(tab: DriverTab): void {
    this.activeTab.set(tab);

    if (tab === 'schedule') {
      this.loadLeaveRequests();
    }

    if (tab === 'map') {
      setTimeout(() => {
        this.driverMap?.resize();
        this.renderNavigationMap(false);
      }, 0);
    }

    if (tab === 'profile') {
      this.loadEmergencyLeaves();
    }
  }

  protected locateOnMap(): void {
    this.requestMapLocation(true);
  }

  protected selectTaskForNavigation(route: DriverRouteTask, stop: DriverTaskStop): void {
    if (!this.hasCoordinates(stop)) {
      return;
    }

    this.selectedTask.set({route, stop});
    this.setActiveTab('map');
    this.fetchRoute(true);
  }

  protected isSelectedTask(routeId: number, orderId: number): boolean {
    const selected = this.selectedTask();
    return selected?.route.routeId === routeId && selected.stop.orderId === orderId;
  }

  protected canNavigate(stop: DriverTaskStop): boolean {
    return this.hasCoordinates(stop);
  }

  /** 還沒在倉庫點交的單先點交；點交完才能按抵達（後端也會擋） */
  protected canLoad(stop: DriverTaskStop): boolean {
    return stop.orderStatus === 'CONFIRMED';
  }

  protected canMarkArrived(stop: DriverTaskStop): boolean {
    return stop.orderStatus === 'LOADED';
  }

  protected canCompleteDelivery(stop: DriverTaskStop): boolean {
    return stop.orderStatus === 'IN_DELIVERY';
  }

  protected taskStatusLabel(status: DriverTaskOrderStatus): string {
    const labels: Record<DriverTaskOrderStatus, string> = {
      PENDING_CONFIRM: '待確認',
      CONFIRMED: '待點交',
      LOADED: '已點交',
      IN_DELIVERY: '配送中',
      COMPLETED: '已交貨',
      CANCELLED: '已取消',
      FAILED: '配送失敗',
    };

    return labels[status];
  }

  protected isDeliveryActionOpen(stop: DriverTaskStop): boolean {
    return this.activeDeliveryOrderId() === stop.orderId;
  }

  protected openDeliveryAction(stop: DriverTaskStop): void {
    if (!this.canCompleteDelivery(stop)) {
      return;
    }

    this.activeDeliveryOrderId.set(stop.orderId);
    this.deliveryNotes.set('');
    this.deliveryPhoto.set(null);
    this.deliveryExceptionOpen.set(false);
    this.deliveryExceptionDescription.set('');
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);
  }

  protected closeDeliveryAction(): void {
    this.activeDeliveryOrderId.set(null);
    this.deliveryNotes.set('');
    this.deliveryPhoto.set(null);
    this.deliveryExceptionOpen.set(false);
    this.deliveryExceptionDescription.set('');
    this.taskActionError.set(null);
  }

  protected updateDeliveryNotes(event: Event): void {
    this.deliveryNotes.set((event.target as HTMLTextAreaElement).value);
  }

  protected selectDeliveryPhoto(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.item(0) ?? null;
    if (!file) {
      return;
    }
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) {
      this.taskActionError.set('請選擇 5 MB 以下的 JPG、PNG 或 WebP 圖片。');
      return;
    }
    this.deliveryPhoto.set(file);
    this.taskActionError.set(null);
  }

  protected toggleDeliveryException(): void {
    this.deliveryExceptionOpen.update((open) => !open);
    this.deliveryExceptionDescription.set('');
  }

  protected updateDeliveryExceptionDescription(event: Event): void {
    this.deliveryExceptionDescription.set((event.target as HTMLTextAreaElement).value);
  }

  protected isLoadingActionOpen(stop: DriverTaskStop): boolean {
    return this.activeLoadingOrderId() === stop.orderId;
  }

  protected openLoadingAction(stop: DriverTaskStop): void {
    if (!this.canLoad(stop)) {
      return;
    }

    const route = this.todayTasks()?.routes.find(item => item.stops.some(routeStop => routeStop.orderId === stop.orderId));
    if (!route || !this.inspectionReady()[route.routeId]) {
      this.taskActionError.set('請先完成這條路線的點交前安全檢查。');
      return;
    }

    this.closeDeliveryAction();
    this.activeLoadingOrderId.set(stop.orderId);
    this.loadingBoxCount.set(String(stop.expectedBoxCount));
    this.loadingNotes.set('');
    this.loadingItemForms.set(
      Object.fromEntries((stop.items ?? []).map((item) => [item.id, {
        checked: false,
        loadedQuantity: String(item.expectedQuantity),
        notes: '',
      }])),
    );
    this.loadingMismatchPending.set(false);
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);
  }

  protected closeLoadingAction(): void {
    this.activeLoadingOrderId.set(null);
    this.loadingBoxCount.set('');
    this.loadingNotes.set('');
    this.loadingItemForms.set({});
    this.loadingMismatchPending.set(false);
    this.taskActionError.set(null);
  }

  protected updateLoadingBoxCount(event: Event): void {
    this.loadingBoxCount.set((event.target as HTMLInputElement).value);
    this.loadingMismatchPending.set(false);
  }

  protected updateLoadingNotes(event: Event): void {
    this.loadingNotes.set((event.target as HTMLTextAreaElement).value);
  }

  protected loadingItemForm(itemId: number): LoadingItemForm {
    return this.loadingItemForms()[itemId] ?? {checked: false, loadedQuantity: '', notes: ''};
  }

  protected updateLoadingItemChecked(itemId: number, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.loadingItemForms.update((items) => ({
      ...items,
      [itemId]: {...this.loadingItemForm(itemId), checked},
    }));
    this.loadingMismatchPending.set(false);
  }

  protected updateLoadingItemQuantity(itemId: number, event: Event): void {
    const loadedQuantity = (event.target as HTMLInputElement).value;
    this.loadingItemForms.update((items) => ({
      ...items,
      [itemId]: {...this.loadingItemForm(itemId), loadedQuantity},
    }));
    this.loadingMismatchPending.set(false);
  }

  protected updateLoadingItemNotes(itemId: number, event: Event): void {
    const notes = (event.target as HTMLInputElement).value;
    this.loadingItemForms.update((items) => ({
      ...items,
      [itemId]: {...this.loadingItemForm(itemId), notes},
    }));
  }

  protected submitLoading(stop: DriverTaskStop): void {
    if (!this.canLoad(stop) || this.isTaskSubmitting()) {
      return;
    }

    const rawCount = this.loadingBoxCount().trim();
    const loadedBoxCount = Number(rawCount);
    if (!rawCount || !Number.isInteger(loadedBoxCount) || loadedBoxCount < 0) {
      this.taskActionError.set('請輸入 0 以上的整數箱數。');
      return;
    }
    if (loadedBoxCount > stop.expectedBoxCount) {
      this.taskActionError.set(`實點箱數不能多於應點的 ${stop.expectedBoxCount} 箱，多出來的請退回倉庫。`);
      return;
    }
    const items = (stop.items ?? []).map((item) => {
      const form = this.loadingItemForm(item.id);
      const loadedQuantity = Number(form.loadedQuantity.trim());
      return {item, form, loadedQuantity};
    });
    for (const {item, form, loadedQuantity} of items) {
      if (!form.checked) {
        this.taskActionError.set(`請先勾選並核對商品「${item.itemName}」。`);
        return;
      }
      if (!form.loadedQuantity.trim() || !Number.isInteger(loadedQuantity) || loadedQuantity < 0) {
        this.taskActionError.set(`商品「${item.itemName}」的實點數量必須是 0 以上整數。`);
        return;
      }
      if (loadedQuantity > item.expectedQuantity) {
        this.taskActionError.set(`商品「${item.itemName}」的實點數量不能超過應到數量。`);
        return;
      }
    }
    const hasItemMismatch = items.some(({item, loadedQuantity}) => loadedQuantity !== item.expectedQuantity);
    // 箱數或商品任一不符，一送出就定案，所以第一次按先停下來讓司機再確認。
    if ((loadedBoxCount !== stop.expectedBoxCount || hasItemMismatch) && !this.loadingMismatchPending()) {
      this.loadingMismatchPending.set(true);
      this.taskActionError.set(null);
      return;
    }

    this.isTaskSubmitting.set(true);
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);

    const notes = this.loadingNotes().trim() || undefined;
    this.operations.loading({
      orderId: stop.orderId,
      loadedBoxCount,
      notes,
      items: items.length === 0 ? undefined : items.map(({item, form, loadedQuantity}) => ({
        orderItemId: item.id,
        checked: form.checked,
        loadedQuantity,
        notes: form.notes.trim() || undefined,
      })),
    }).subscribe({
      next: (response) => {
        this.applyDeliveryResponse(response);
        this.closeLoadingAction();
        if (response.orderStatus === 'LOADED') {
          this.taskActionMessage.set('點交完成，可以出發配送。');
        } else {
          const followUp = response.followUpOrderNumber
            ? `，明日補送單 ${response.followUpOrderNumber}`
            : '';
          this.taskActionError.set(`箱數不符，已建立異常單${followUp}。這張單今天不配送。`);
        }
        this.isTaskSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.taskActionError.set(this.getErrorMessage(error, '無法完成點交。'));
        this.isTaskSubmitting.set(false);
      },
    });
  }

  protected arriveAtStop(stop: DriverTaskStop): void {
    if (!this.canMarkArrived(stop) || this.isTaskSubmitting()) {
      return;
    }

    this.isTaskSubmitting.set(true);
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);

    this.operations.arrive({orderId: stop.orderId}).subscribe({
      next: (response) => {
        this.applyDeliveryResponse(response);
        this.activeDeliveryOrderId.set(stop.orderId);
        this.taskActionMessage.set('已記錄抵達門市，請完成交貨或等待無人簽收時間。');
        this.isTaskSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.taskActionError.set(this.getErrorMessage(error, '無法記錄抵達狀態。'));
        this.isTaskSubmitting.set(false);
      },
    });
  }

  protected completeDelivery(stop: DriverTaskStop): void {
    if (!this.canCompleteDelivery(stop) || this.isTaskSubmitting()) {
      return;
    }

    this.submitDeliveryResult(
      (photoUrl) =>
        this.operations.deliver({
          orderId: stop.orderId,
          boxCount: stop.expectedBoxCount,
          photoUrl,
          notes: this.optionalDeliveryNotes(),
        }),
      '交貨已完成。',
    );
  }

  protected reportNoSignature(stop: DriverTaskStop): void {
    if (!this.canCompleteDelivery(stop) || this.isTaskSubmitting()) {
      return;
    }

    this.submitDeliveryResult(
      (photoUrl) =>
        this.operations.noSignature({
          orderId: stop.orderId,
          photoUrl,
          notes: this.optionalDeliveryNotes(),
        }),
      '已登記無人簽收，已建立待處理異常。',
    );
  }

  protected reportDeliveryException(stop: DriverTaskStop): void {
    const description = this.deliveryExceptionDescription().trim();
    if (!description || this.isTaskSubmitting()) {
      this.taskActionError.set('請填寫異常說明後再送出。');
      return;
    }

    this.isTaskSubmitting.set(true);
    this.taskActionError.set(null);
    this.operations.reportException({orderId: stop.orderId, description}).subscribe({
      next: (response) => {
        this.applyDeliveryResponse(response);
        this.closeDeliveryAction();
        this.taskActionMessage.set('異常已送出，主管可在異常中心處理。');
        this.isTaskSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.taskActionError.set(this.getErrorMessage(error, '無法送出配送異常。'));
        this.isTaskSubmitting.set(false);
      },
    });
  }

  protected updateStartMileage(event: Event): void {
    this.startMileageReading.set((event.target as HTMLInputElement).value);
  }

  protected updateEndMileage(event: Event): void {
    this.endMileageReading.set((event.target as HTMLInputElement).value);
  }

  protected canRecordMileage(): boolean {
    return this.isWorkingOrOvertime(this.attendance()?.status);
  }

  protected submitStartMileage(): void {
    const odometer = this.readOdometer(this.startMileageReading());
    if (odometer === null || this.isMileageSubmitting()) {
      return;
    }

    this.isMileageSubmitting.set(true);
    this.mileageError.set(null);
    this.mileageMessage.set(null);
    this.operations.startMileage({odometer}).subscribe({
      next: (mileageLog) => {
        this.startMileageReading.set('');
        this.mileageMessage.set(`已記錄出車里程 ${mileageLog.startOdometer} km。`);
        this.isMileageSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.mileageError.set(this.getErrorMessage(error, '無法記錄出車里程。'));
        this.isMileageSubmitting.set(false);
      },
    });
  }

  protected submitEndMileage(): void {
    const odometer = this.readOdometer(this.endMileageReading());
    if (odometer === null || this.isMileageSubmitting()) {
      return;
    }

    this.isMileageSubmitting.set(true);
    this.mileageError.set(null);
    this.mileageMessage.set(null);
    this.operations.endMileage({odometer}).subscribe({
      next: (mileageLog) => {
        this.endMileageReading.set('');
        this.mileageMessage.set(
          `已記錄收車里程 ${mileageLog.endOdometer} km，本日行駛 ${mileageLog.actualDistance ?? 0} km。`,
        );
        this.isMileageSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.mileageError.set(this.getErrorMessage(error, '無法記錄收車里程。'));
        this.isMileageSubmitting.set(false);
      },
    });
  }

  protected recalculateMileage(): void {
    if (this.isMileageSubmitting()) {
      return;
    }
    this.isMileageSubmitting.set(true);
    this.mileageError.set(null);
    this.mileageMessage.set(null);
    this.operations.recalculateMileage().subscribe({
      next: (mileageLog) => {
        const gpsDistance = mileageLog.gpsDistanceKm;
        this.mileageMessage.set(
          gpsDistance === null || gpsDistance === undefined
            ? '里程已重新結算。'
            : `里程已依 GPS 軌跡重新結算：${gpsDistance.toFixed(1)} km。`,
        );
        this.isMileageSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.mileageError.set(this.getErrorMessage(error, '目前無法重新結算里程。'));
        this.isMileageSubmitting.set(false);
      },
    });
  }

  protected navigationRouteDetail(): string | null {
    const distance = this.navigationDistanceMeters();
    const duration = this.navigationDurationSeconds();
    if (distance === null || duration === null) {
      return null;
    }

    const distanceLabel = distance >= 1_000 ? `${(distance / 1_000).toFixed(1)} km` : `${Math.round(distance)} m`;
    return `${distanceLabel} · 約 ${Math.max(1, Math.round(duration / 60))} 分鐘`;
  }

  protected openEmergencyLeaveForm(): void {
    this.emergencyLeaveError.set(null);
    this.emergencyLeaveMessage.set(null);
    this.isEmergencyLeaveFormOpen.set(true);
  }

  protected closeEmergencyLeaveForm(): void {
    if (!this.isEmergencyLeaveSubmitting()) {
      this.isEmergencyLeaveFormOpen.set(false);
      this.emergencyLeaveReason.set('');
      this.emergencyLeaveError.set(null);
    }
  }

  protected updateEmergencyLeaveReason(event: Event): void {
    this.emergencyLeaveReason.set((event.target as HTMLTextAreaElement).value);
  }

  protected canRequestEmergencyLeave(): boolean {
    return (
      this.attendance()?.status === 'WORKING' &&
      !this.emergencyLeaves().some((leave) => leave.status === 'PENDING')
    );
  }

  protected submitEmergencyLeave(): void {
    const reason = this.emergencyLeaveReason().trim();
    if (!reason) {
      this.emergencyLeaveError.set('請填寫臨時請假原因。');
      return;
    }

    this.isEmergencyLeaveSubmitting.set(true);
    this.emergencyLeaveError.set(null);
    this.operations.submitEmergencyLeave({ reason }).subscribe({
      next: (leave) => {
        this.emergencyLeaves.update((items) => [leave, ...items.filter((item) => item.id !== leave.id)]);
        this.emergencyLeaveReason.set('');
        this.emergencyLeaveMessage.set('申請已送出，等待主管安排接手司機。');
        this.isEmergencyLeaveFormOpen.set(false);
        this.isEmergencyLeaveSubmitting.set(false);
        this.loadAttendance();
      },
      error: (error: unknown) => {
        this.emergencyLeaveError.set(this.getErrorMessage(error, '臨時請假申請未完成。'));
        this.isEmergencyLeaveSubmitting.set(false);
      },
    });
  }

  protected emergencyLeaveStatusLabel(status: EmergencyLeaveResponse['status']): string {
    return status === 'PENDING' ? '待主管核准' : status === 'APPROVED' ? '已核准' : '已拒絕';
  }

  protected updateLeaveFormField<K extends keyof DriverLeaveForm>(
    field: K,
    event: Event,
  ): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
    this.leaveForm.update((form) => ({...form, [field]: target.value}));
    this.leaveError.set(null);
  }

  protected setLeaveApplicationTab(tab: LeaveApplicationTab): void {
    this.leaveApplicationTab.set(tab);
    this.leaveError.set(null);
    this.leaveMessage.set(null);
    if (tab === 'planned') {
      this.loadPlannedLeaveShifts();
    }
  }

  protected setLeaveTopic(topic: LeaveTopic): void {
    this.leaveTopic.set(topic);
    this.leaveError.set(null);
    this.leaveMessage.set(null);
    this.emergencyLeaveError.set(null);
    this.emergencyLeaveMessage.set(null);
  }

  protected updatePlannedLeaveField<K extends keyof DriverPlannedLeaveForm>(
    field: K,
    event: Event,
  ): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
    this.plannedLeaveForm.update((form) => ({...form, [field]: target.value}));
    this.leaveError.set(null);
  }

  protected isPlannedDateSelected(workDate: string): boolean {
    return this.plannedLeaveForm().workDates.includes(workDate);
  }

  protected isPlannedDateBlocked(workDate: string): boolean {
    return this.leaveRequests().some((request) =>
      request.workDate === workDate && request.fullDay && request.status !== 'REJECTED',
    );
  }

  protected togglePlannedLeaveDate(workDate: string): void {
    if (this.isLeaveSubmitting() || this.isPlannedDateBlocked(workDate)) {
      return;
    }
    this.plannedLeaveForm.update((form) => ({
      ...form,
      workDates: form.workDates.includes(workDate)
        ? form.workDates.filter((date) => date !== workDate)
        : [...form.workDates, workDate].sort(),
    }));
    this.leaveError.set(null);
  }

  protected updateMakeupLeaveField<K extends keyof DriverMakeupLeaveForm>(
    field: K,
    event: Event,
  ): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
    this.makeupLeaveForm.update((form) => ({...form, [field]: target.value}));
    this.leaveError.set(null);
  }

  protected selectMakeupLeaveDate(workDate: string): void {
    this.makeupLeaveForm.update((form) => ({...form, workDate}));
    this.leaveError.set(null);
  }

  protected selectMakeupEvidence(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    if (file && file.size > 5 * 1024 * 1024) {
      this.leaveError.set('佐證照片不能超過 5 MB。');
      return;
    }
    this.selectedMakeupEvidenceFile.set(file);
  }

  protected selectLeaveHistoryDate(event: Event): void {
    this.leaveHistoryDate.set((event.target as HTMLSelectElement).value);
  }

  protected submitLeaveRequest(): void {
    const form = this.leaveForm();
    const reason = form.reason.trim();
    if (!reason) {
      this.leaveError.set('請填寫當日特殊事由。');
      return;
    }
    if (Boolean(form.leaveStart) !== Boolean(form.leaveEnd)) {
      this.leaveError.set('部分時段請假要同時填寫開始與結束時間；都不填代表整天。');
      return;
    }
    if (form.leaveStart && form.leaveEnd <= form.leaveStart) {
      this.leaveError.set('請假結束時間必須晚於開始時間。');
      return;
    }

    const request: DriverLeaveRequest = {
      workDate: this.toIsoDate(new Date()),
      leaveType: form.leaveType,
      leaveStart: form.leaveStart || null,
      leaveEnd: form.leaveEnd || null,
      reason,
    };
    this.isLeaveSubmitting.set(true);
    this.leaveError.set(null);
    this.leaveMessage.set(null);
    this.operations.submitLeaveRequest(request).subscribe({
      next: (saved) => {
        this.leaveRequests.update((items) =>
          [saved, ...items.filter((item) => item.id !== saved.id)].sort(
            (left, right) => right.requestedAt.localeCompare(left.requestedAt),
          ),
        );
        this.leaveForm.set({
          workDate: this.toIsoDate(new Date()),
          leaveType: 'SPECIAL',
          leaveStart: '',
          leaveEnd: '',
          reason: '',
        });
        this.leaveMessage.set('當日特殊事由已送出，主管審核後會在這裡顯示結果。');
        this.isLeaveSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.leaveError.set(this.getErrorMessage(error, '請假申請未完成。'));
        this.isLeaveSubmitting.set(false);
      },
    });
  }

  protected submitPlannedLeaveRequest(): void {
    const form = this.plannedLeaveForm();
    const reason = form.reason.trim();
    const workDates = form.workDates.filter((date) => !this.isPlannedDateBlocked(date));
    if (workDates.length === 0 || !reason) {
      this.leaveError.set('請至少選擇一個可申請的未來上班日，並填寫原因。');
      return;
    }

    const request: DriverPlannedLeaveBatchRequest = {
      groups: [{leaveType: form.leaveType, workDates, reason}],
    };
    this.isLeaveSubmitting.set(true);
    this.leaveError.set(null);
    this.leaveMessage.set(null);
    this.operations.submitPlannedLeaveBatches(request).subscribe({
      next: (batches) => {
        const saved = batches.flatMap((batch: DriverLeaveBatchResponse) => batch.items);
        this.leaveRequests.update((items) =>
          [...saved, ...items.filter((item) => !saved.some((request) => request.id === item.id))]
            .sort((left, right) => right.requestedAt.localeCompare(left.requestedAt)),
        );
        this.plannedLeaveForm.set({leaveType: form.leaveType, workDates: [], reason: ''});
        this.leaveMessage.set('預排請假已送出，審核中日期不能重複選取。');
        this.isLeaveSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.leaveError.set(this.getErrorMessage(error, '預排請假申請未完成。'));
        this.isLeaveSubmitting.set(false);
      },
    });
  }

  protected submitMakeupLeaveRequest(): void {
    const form = this.makeupLeaveForm();
    const reason = form.reason.trim();
    if (!form.workDate || !reason) {
      this.leaveError.set('請從可補請班次選擇日期，並填寫原因。');
      return;
    }

    const request: DriverMakeupLeaveRequest = {
      workDate: form.workDate,
      leaveType: form.leaveType,
      reason,
    };
    const evidence = this.selectedMakeupEvidenceFile();
    const operation = evidence
      ? this.operations.uploadLeaveEvidencePhoto(evidence).pipe(
          switchMap((upload) => this.operations.submitMakeupLeave({...request, evidencePhotoUrl: upload.url})),
        )
      : this.operations.submitMakeupLeave(request);

    this.isLeaveSubmitting.set(true);
    this.leaveError.set(null);
    this.leaveMessage.set(null);
    operation.subscribe({
      next: (saved) => {
        this.leaveRequests.update((items) =>
          [saved, ...items.filter((item) => item.id !== saved.id)]
            .sort((left, right) => right.requestedAt.localeCompare(left.requestedAt)),
        );
        this.makeupLeaveForm.set({workDate: '', leaveType: 'SICK', reason: ''});
        this.selectedMakeupEvidenceFile.set(null);
        this.leaveMessage.set('事後補請已送出，主管審核結果會顯示在下方。');
        this.isLeaveSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.leaveError.set(this.getErrorMessage(error, '事後補請未完成。'));
        this.isLeaveSubmitting.set(false);
      },
    });
  }

  protected leaveTypeLabel(type: DriverLeaveRequestResponse['leaveType']): string {
    return {
      SICK: '病假',
      ANNUAL: '年假',
      PERSONAL: '事假',
      SPECIAL: '特殊事由',
      MENSTRUAL: '生理假',
      BEREAVEMENT: '喪假',
      ABSENT: '曠職',
    }[type];
  }

  protected leaveModeLabel(request: DriverLeaveRequestResponse): string {
    return {
      PREPLANNED: '預排請假',
      TEMPORARY: '當日特殊事由',
      MAKEUP: '事後補請',
      SYSTEM_NO_SHOW: '待說明特殊事由',
      ADMIN_PLANNED_PARTIAL: '主管預排時段假',
    }[request.requestMode];
  }

  protected leaveStatusLabel(status: DriverLeaveRequestResponse['status']): string {
    return status === 'PENDING' ? '待審核' : status === 'APPROVED' ? '已核准' : '未核准';
  }

  protected toggleLeaveHistory(request: DriverLeaveRequestResponse): void {
    if (this.expandedLeaveId() === request.id) {
      this.expandedLeaveId.set(null);
      return;
    }

    this.expandedLeaveId.set(request.id);
    if (!this.leaveHistories()[request.id]) {
      this.leaveHistoryErrors.update((errors) => ({...errors, [request.id]: ''}));
      this.isLeaveHistoryLoading.set(true);
      this.operations.getLeaveRequestHistory(request.id).subscribe({
        next: (events) => {
          this.leaveHistories.update((histories) => ({...histories, [request.id]: events}));
          this.leaveHistoryErrors.update((errors) => ({...errors, [request.id]: ''}));
          this.isLeaveHistoryLoading.set(false);
        },
        error: (error: unknown) => {
          this.leaveHistoryErrors.update((errors) => ({
            ...errors,
            [request.id]: this.getErrorMessage(error, '無法取得請假歷程。'),
          }));
          this.isLeaveHistoryLoading.set(false);
        },
      });
    }
  }

  protected markLeaveRequestRead(request: DriverLeaveRequestResponse): void {
    if (request.driverReadAt) {
      return;
    }
    this.operations.markLeaveRequestRead(request.id).subscribe({
      next: (updated) => {
        this.leaveRequests.update((items) =>
          items.map((item) => (item.id === updated.id ? updated : item)),
        );
      },
      error: (error: unknown) => {
        this.leaveError.set(this.getErrorMessage(error, '無法將結果標記為已讀。'));
      },
    });
  }

  protected leaveHistoryLabel(eventType: string): string {
    return {
      SUBMITTED: '送出申請',
      APPROVED: '主管核准',
      REJECTED: '主管退回',
      TYPE_CHANGED: '修正假別',
      PLANNED: '主管預排',
    }[eventType] ?? eventType;
  }

  protected attendancePunctualityLabel(attendance: AttendanceRecordDto): string {
    const status = attendance.punctualityStatus;
    if (!status) return '尚未判定';
    if (status === 'ON_TIME') return '準時';
    if (status === 'LATE_EXCUSED') return `遲到 ${attendance.lateMinutes ?? 0} 分鐘（已核准）`;
    if (status === 'LATE') return `遲到 ${attendance.lateMinutes ?? 0} 分鐘`;
    if (status === 'LEAVE_COVERED') return '遲到時段已由請假涵蓋';
    return `需補請假 ${attendance.leaveRequiredMinutes ?? attendance.lateMinutes ?? 0} 分鐘`;
  }

  protected formatDateTime(value: string | null): string {
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

  protected hasNavigationDestination(): boolean {
    return this.destinationLocation() !== null;
  }

  protected destinationName(): string {
    return this.selectedTask()?.stop.storeName ?? '尚無下一送達點';
  }

  protected destinationDetail(): string {
    const selected = this.selectedTask();
    if (!selected) {
      return this.taskViewState() === 'loading' ? '正在取得今日任務' : '尚無已發布配送任務';
    }

    return selected.stop.address;
  }

  private requestMapLocation(isManualRequest: boolean): void {
    if (!this.driverMap) {
      return;
    }

    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      this.mapLocationStatus.set('此裝置不支援定位功能');
      return;
    }

    if (isManualRequest || !this.hasStoredMapLocation()) {
      this.mapLocationStatus.set('正在取得目前位置...');
    }

    navigator.geolocation.getCurrentPosition(
      (position) => {
        this.applyMapPosition(position, true);
      },
      (error) => {
        if (isManualRequest || !this.hasStoredMapLocation()) {
          this.mapLocationStatus.set(this.getLocationErrorMessage(error));
        }
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 0 },
    );
  }

  private startMapLocationWatch(): void {
    if (
      this.mapLocationWatchId !== null ||
      typeof navigator === 'undefined' ||
      !navigator.geolocation
    ) {
      return;
    }

    if (!this.currentMapLocation) {
      this.mapLocationStatus.set('正在取得目前位置...');
    }

    this.mapLocationWatchId = navigator.geolocation.watchPosition(
      (position) => this.applyMapPosition(position, true),
      (error) => {
        if (this.isNavigating() || !this.currentMapLocation) {
          this.mapLocationStatus.set(this.getLocationErrorMessage(error));
        }
      },
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 0 },
    );
  }

  private stopMapLocationWatch(): void {
    if (
      this.mapLocationWatchId === null ||
      typeof navigator === 'undefined' ||
      !navigator.geolocation
    ) {
      return;
    }

    navigator.geolocation.clearWatch(this.mapLocationWatchId);
    this.mapLocationWatchId = null;
  }

  private applyMapPosition(position: GeolocationPosition, animate: boolean): void {
    const location: MapPosition = [position.coords.longitude, position.coords.latitude];
    const heading = this.resolveMovementHeading(position.coords.heading, location);
    if (this.isNavigating()) {
      this.applyNavigationPosition(location, heading);
    } else {
      this.showMapLocation(location, animate, heading);
    }
    this.lastMovementLocation = location;
    this.saveMapLocation(location);
    this.mapLocationStatus.set('已定位至目前位置');
  }

  private resolveMovementHeading(gpsHeading: number | null, location: MapPosition): number | null {
    if (typeof gpsHeading === 'number' && Number.isFinite(gpsHeading) && gpsHeading >= 0) {
      this.lastMovementHeading = gpsHeading;
      return gpsHeading;
    }

    if (this.lastMovementLocation && distanceInMeters(this.lastMovementLocation, location) >= 4) {
      this.lastMovementHeading = bearingInDegrees(this.lastMovementLocation, location);
    }

    return this.lastMovementHeading;
  }

  protected statusLabel(): string {
    const status = this.attendance()?.status;

    if (status === 'WORKING') {
      return '配送中';
    }

    if (status === 'ON_BREAK') {
      return '休息中';
    }

    if (status === 'OVERTIME') {
      return '加班中';
    }

    if (status === 'CLOCKED_OUT') {
      return '已下班';
    }

    return '尚未上班';
  }

  protected weatherLabel(): string {
    const weather = this.weather();
    if (weather) {
      return `${weather.city}${weather.condition}，${weather.temperature}°C`;
    }

    return this.weatherUnavailable() ? '天氣暫時無法取得' : '正在取得高雄天氣';
  }

  protected formatBreakCountdown(): string {
    const seconds = Math.max(0, this.remainingBreakSeconds());
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${String(minutes).padStart(2, '0')}:${String(remainingSeconds).padStart(2, '0')}`;
  }

  protected formatTime(value: string | null): string {
    if (!value) {
      return '--:--';
    }

    const dateTimeMatch = value.match(/(?:T|\s)(\d{2}:\d{2})/);
    const timeMatch = value.match(/^(\d{2}:\d{2})/);
    return dateTimeMatch?.[1] ?? timeMatch?.[1] ?? '--:--';
  }

  protected formatShiftType(shiftType: DriverShiftDto['shiftType']): string {
    if (shiftType === 'WORK') {
      return '上班';
    }

    if (shiftType === 'DAY_OFF') {
      return '休假';
    }

    if (shiftType === 'LEAVE') {
      return '請假';
    }

    return '未排班';
  }

  protected formatShiftDate(workDate: string): string {
    const [year, month, day] = workDate.split('-').map(Number);
    const date = new Date(year, month - 1, day);
    return new Intl.DateTimeFormat('zh-TW', {
      month: 'numeric',
      day: 'numeric',
      weekday: 'short',
    }).format(date);
  }

  protected formatOvertime(minutes: number): string | null {
    return minutes > 0 ? `加班 ${minutes} 分鐘` : null;
  }

  protected shiftTypeClass(shiftType: DriverShiftDto['shiftType']): string {
    return `is-${shiftType.toLowerCase()}`;
  }

  private loadProfile(): void {
    this.profileError.set(null);
    this.operations.getProfile().subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.profilePhotoLoadFailed.set(false);
      },
      error: (error: unknown) => {
        this.profileError.set(this.getErrorMessage(error, '無法取得司機資料。'));
      },
    });
  }

  private loadAttendance(silent = false): void {
    if (!silent) {
      this.attendanceViewState.set('loading');
    }
    this.attendanceError.set(null);

    this.operations.getTodayAttendance().subscribe({
      next: (attendance) => this.applyAttendance(attendance),
      error: (error: unknown) => {
        if (this.isNotClockedInError(error)) {
          this.attendance.set(null);
          this.attendanceViewState.set('not-clocked-in');
          this.gpsTracking.stop();
          this.clearAttendanceRefreshTimer();
          return;
        }

        this.attendanceViewState.set('error');
        this.attendanceError.set(this.getErrorMessage(error, '無法取得今日出勤狀態。'));
        this.gpsTracking.stop();
        this.clearAttendanceRefreshTimer();
      },
    });
  }

  private loadPublishedShifts(): void {
    const requestVersion = ++this.scheduleRequestVersion;
    const {from, to} = this.monthRange(this.scheduleMonth());
    this.scheduleViewState.set('loading');
    this.scheduleError.set(null);
    this.scheduleCalendar()?.updateTodaysDate();

    this.operations.getPublishedShifts(from, to).subscribe({
      next: (shifts) => {
        if (requestVersion !== this.scheduleRequestVersion) return;
        this.publishedShifts.set(
          [...shifts].sort((left, right) => left.workDate.localeCompare(right.workDate)),
        );
        this.scheduleViewState.set(shifts.length ? 'ready' : 'empty');
        // 月曆不會因為資料變了自己重畫格子的 class，要手動叫它重畫
        this.scheduleCalendar()?.updateTodaysDate();
      },
      error: (error: unknown) => {
        if (requestVersion !== this.scheduleRequestVersion) return;
        this.publishedShifts.set([]);
        this.scheduleViewState.set('error');
        this.scheduleError.set(this.getErrorMessage(error, '無法取得已發布班表。'));
        this.scheduleCalendar()?.updateTodaysDate();
      },
    });
  }

  private loadTodayTasks(): void {
    this.inspectionReady.set({});
    this.taskViewState.set('loading');
    this.taskError.set(null);

    this.operations.getTodayTasks().subscribe({
      next: (tasks) => {
        this.todayTasks.set(tasks);
        this.taskViewState.set(tasks.routes.length ? 'ready' : 'empty');

        const taskStops = tasks.routes.flatMap((route) =>
          route.stops.map((stop) => ({ route, stop })),
        );
        const selectedOrderId = this.selectedTask()?.stop.orderId;
        const nextSelection =
          taskStops.find((task) => task.stop.orderId === selectedOrderId) ??
          taskStops.find((task) => this.hasCoordinates(task.stop)) ??
          null;

        this.selectedTask.set(nextSelection);
        if (nextSelection && this.hasCoordinates(nextSelection.stop)) {
          this.renderNavigationMap(false);
        }
      },
      error: (error: unknown) => {
        this.todayTasks.set(null);
        this.taskViewState.set('error');
        this.taskError.set(this.getErrorMessage(error, '無法取得今日配送任務。'));
      },
    });
  }

  private loadEmergencyLeaves(): void {
    if (this.isEmergencyLeaveHistoryLoading()) {
      return;
    }

    this.isEmergencyLeaveHistoryLoading.set(true);
    this.operations.getEmergencyLeaves().subscribe({
      next: (leaves) => {
        this.emergencyLeaves.set(
          [...leaves].sort((left, right) => right.requestedAt.localeCompare(left.requestedAt)),
        );
        this.isEmergencyLeaveHistoryLoading.set(false);
      },
      error: (error: unknown) => {
        this.emergencyLeaveError.set(this.getErrorMessage(error, '無法取得臨時請假紀錄。'));
        this.isEmergencyLeaveHistoryLoading.set(false);
      },
    });
  }

  private loadLeaveRequests(): void {
    if (this.isLeaveListLoading()) {
      return;
    }
    this.isLeaveListLoading.set(true);
    this.operations.getLeaveRequests().subscribe({
      next: (requests) => {
        this.leaveListAvailable.set(true);
        this.leaveRequests.set(
          [...requests].sort((left, right) => right.requestedAt.localeCompare(left.requestedAt)),
        );
        this.isLeaveListLoading.set(false);
      },
      error: (error: unknown) => {
        this.leaveError.set(this.getErrorMessage(error, '無法取得一般請假結果。'));
        this.leaveListAvailable.set(false);
        this.isLeaveListLoading.set(false);
      },
    });
  }

  private loadPlannedLeaveShifts(): void {
    if (this.isPlannedLeaveLoading()) {
      return;
    }
    const from = new Date();
    from.setDate(from.getDate() + 1);
    const to = new Date(from);
    to.setDate(to.getDate() + 59);
    this.isPlannedLeaveLoading.set(true);
    this.operations.getPublishedShifts(this.toIsoDate(from), this.toIsoDate(to)).subscribe({
      next: (shifts) => {
        this.plannedLeaveShifts.set(
          shifts.filter((shift) => shift.shiftType === 'WORK')
            .sort((left, right) => left.workDate.localeCompare(right.workDate)),
        );
        this.isPlannedLeaveLoading.set(false);
      },
      error: (error: unknown) => {
        this.plannedLeaveShifts.set([]);
        this.leaveError.set(this.getErrorMessage(error, '無法取得可預排請假的班表。'));
        this.isPlannedLeaveLoading.set(false);
      },
    });
  }

  private runAttendanceAction(action: () => Observable<AttendanceRecordDto>): void {
    this.isSubmitting.set(true);
    this.attendanceError.set(null);

    action().subscribe({
      next: (attendance) => {
        this.isSubmitting.set(false);
        this.applyAttendance(attendance);
      },
      error: (error: unknown) => {
        this.isSubmitting.set(false);
        this.attendanceError.set(this.getErrorMessage(error, '出勤操作未完成。'));
      },
    });
  }

  private submitDeliveryResult(
    action: (photoUrl?: string) => Observable<DeliveryRecordResponse>,
    successMessage: string,
  ): void {
    this.isTaskSubmitting.set(true);
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);

    this.uploadSelectedDeliveryPhoto().pipe(switchMap((photoUrl) => action(photoUrl))).subscribe({
      next: (response) => {
        this.applyDeliveryResponse(response);
        this.closeDeliveryAction();
        this.taskActionMessage.set(successMessage);
        this.isTaskSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.taskActionError.set(this.getErrorMessage(error, '配送結果未完成。'));
        this.isTaskSubmitting.set(false);
      },
    });
  }

  /** 抵達、交貨、點交的回應都帶 orderId 與新狀態，只用這兩個欄位更新站點 */
  private applyDeliveryResponse(response: Pick<DeliveryRecordResponse, 'orderId' | 'orderStatus'>): void {
    this.todayTasks.update((tasks) => {
      if (!tasks) {
        return null;
      }

      return {
        ...tasks,
        routes: tasks.routes.map((route) => ({
          ...route,
          stops: route.stops.map((stop) =>
            stop.orderId === response.orderId
              ? {...stop, orderStatus: response.orderStatus}
              : stop,
          ),
        })),
      };
    });
  }

  private optionalDeliveryNotes(): string | undefined {
    const notes = this.deliveryNotes().trim();
    return notes || undefined;
  }

  private uploadSelectedDeliveryPhoto(): Observable<string | undefined> {
    const file = this.deliveryPhoto();
    return file ? this.operations.uploadDeliveryPhoto(file).pipe(switchMap((response) => of(response.url))) : of(undefined);
  }

  private readOdometer(value: string): number | null {
    const normalizedValue = value.trim();
    if (!normalizedValue) {
      this.mileageError.set('請輸入里程表讀數。');
      return null;
    }

    const odometer = Number(normalizedValue);
    if (!Number.isInteger(odometer) || odometer < 0) {
      this.mileageError.set('請輸入 0 以上的整數里程。');
      return null;
    }

    return odometer;
  }

  private applyAttendance(attendance: AttendanceRecordDto): void {
    this.attendance.set(attendance);
    this.attendanceViewState.set('ready');
    this.attendanceError.set(null);
    this.remainingBreakSeconds.set(Math.max(0, attendance.remainingBreakSeconds));

    if (attendance.status === 'ON_BREAK') {
      this.gpsTracking.stop();
      this.clearAttendanceRefreshTimer();
      this.startBreakCountdown();
      return;
    }

    this.clearBreakTimer();
    if (this.isWorkingOrOvertime(attendance.status)) {
      this.startAttendanceRefreshTimer();
    } else {
      this.clearAttendanceRefreshTimer();
    }

    if (attendance.gpsAllowed) {
      this.gpsTracking.start();
      return;
    }

    this.gpsTracking.stop();
  }

  private startBreakCountdown(): void {
    this.clearBreakTimer();

    if (this.remainingBreakSeconds() === 0) {
      this.loadAttendance();
      return;
    }

    this.breakTimer = setInterval(() => {
      const nextSeconds = Math.max(0, this.remainingBreakSeconds() - 1);
      this.remainingBreakSeconds.set(nextSeconds);

      if (nextSeconds === 0) {
        this.clearBreakTimer();
        this.loadAttendance();
      }
    }, 1_000);
  }

  private clearBreakTimer(): void {
    if (this.breakTimer !== null) {
      clearInterval(this.breakTimer);
      this.breakTimer = null;
    }
  }

  private isWorkingOrOvertime(status: AttendanceRecordDto['status'] | undefined): boolean {
    return status === 'WORKING' || status === 'OVERTIME';
  }

  private startAttendanceRefreshTimer(): void {
    if (this.attendanceRefreshTimer !== null) {
      return;
    }

    this.attendanceRefreshTimer = setInterval(() => this.loadAttendance(true), 60_000);
  }

  private clearAttendanceRefreshTimer(): void {
    if (this.attendanceRefreshTimer !== null) {
      clearInterval(this.attendanceRefreshTimer);
      this.attendanceRefreshTimer = null;
    }
  }

  private resetAttendanceSheetDrag(event: PointerEvent): void {
    const target = event.currentTarget as HTMLElement;
    if (target.hasPointerCapture(event.pointerId)) {
      target.releasePointerCapture(event.pointerId);
    }

    this.attendanceSheetPointerId = null;
    this.attendanceSheetPointerStartY = null;
    this.attendanceSheetDragOffset.set(0);
    this.isAttendanceSheetDragging.set(false);
  }

  private async initializeMap(): Promise<void> {
    const mapElement = this.driverMapElement?.nativeElement;
    if (!mapElement) {
      return;
    }

    const maplibregl = await import('maplibre-gl');
    maplibregl.setWorkerUrl('/maplibre/maplibre-gl-worker.mjs');
    this.maplibre = maplibregl;
    this.driverMap = new maplibregl.Map({
      container: mapElement,
      center: DRIVER_MAP_DEFAULT_CENTER,
      zoom: 12,
      maxZoom: 19,
      maxPitch: 0,
      dragRotate: true,
      touchZoomRotate: true,
      touchPitch: false,
      pitchWithRotate: false,
      attributionControl: {},
      style: OPEN_FREE_MAP_STYLE,
    });
    this.driverMap.addControl(
      new maplibregl.NavigationControl({showCompass: true, showZoom: false, visualizePitch: false}),
      'top-left',
    );

    this.driverMap.once('load', () => {
      this.restoreMapLocation();
      this.startMapLocationWatch();
      this.renderNavigationMap(false);
    });
  }

  private restoreMapLocation(): void {
    const storedLocation = readStoredMapLocation();
    if (!storedLocation) {
      return;
    }

    this.showMapLocation([storedLocation.lng, storedLocation.lat], false);
    this.mapLocationStatus.set('已顯示上次定位，正在更新...');
  }


  private showMapLocation(
    location: MapPosition,
    animate: boolean,
    heading: number | null = this.lastMovementHeading,
  ): void {
    const maplibregl = this.maplibre;
    if (!maplibregl || !this.driverMap) {
      return;
    }

    this.currentMapLocation = location;

    if (this.currentLocationMarker) {
      this.currentLocationMarker.setLngLat(location);
    } else {
      this.currentLocationMarker = new maplibregl.Marker({
        element: this.createDriverMarkerElement(),
        anchor: 'center',
        rotationAlignment: 'viewport',
      })
        .setLngLat(location)
        .addTo(this.driverMap);
    }

    this.currentLocationMarker.setRotation(this.isNavigating() ? 0 : (heading ?? 0));

    if (this.isNavigating() && heading !== null) {
      this.driverMap.easeTo({
        center: location,
        bearing: heading,
        zoom: Math.max(this.driverMap.getZoom(), 16),
        duration: animate ? 500 : 0,
      });
    }

    this.renderNavigationMap(animate);
  }

  private hasStoredMapLocation(): boolean {
    return readStoredMapLocation() !== null;
  }

  private saveMapLocation(location: MapPosition): void {
    saveStoredMapLocation({lat: location[1], lng: location[0]});
  }

  private renderNavigationMap(animate: boolean): void {
    const maplibregl = this.maplibre;
    if (!maplibregl || !this.driverMap || !this.driverMap.isStyleLoaded()) {
      return;
    }

    const destination = this.destinationLocation();
    if (!destination) {
      this.destinationMarker?.remove();
      this.destinationMarker = null;
      this.destinationPopup?.remove();
      this.destinationPopup = null;
      this.setNavigationRoute([]);
      this.routeLatLng = [];
      this.routeDistanceScale = 1;
      this.routeDurationSecondsPerMeter = null;
      this.navigationRouteState.set('idle');
      this.navigationDistanceMeters.set(null);
      this.navigationDurationSeconds.set(null);
      if (this.currentMapLocation) {
        this.driverMap.easeTo({
          center: this.currentMapLocation,
          zoom: 15,
          duration: animate ? 500 : 0,
        });
      }
      return;
    }

    if (this.destinationMarker) {
      this.destinationMarker.setLngLat(destination);
    } else {
      this.destinationMarker = new maplibregl.Marker({
        element: this.createMapMarkerElement('driver-destination-marker', 'B'),
        anchor: 'center',
      })
        .setLngLat(destination)
        .addTo(this.driverMap);
    }

    this.destinationPopup?.remove();
    this.destinationPopup = new maplibregl.Popup({
      closeButton: false,
      closeOnClick: false,
      className: 'driver-destination-tooltip',
      offset: 22,
    })
      .setLngLat(destination)
      .setText(this.destinationName())
      .addTo(this.driverMap);

    if (!this.currentMapLocation) {
      this.driverMap.easeTo({center: destination, zoom: 15, duration: animate ? 500 : 0});
      return;
    }

    if (this.isNavigating()) {
      if (this.routeLatLng.length > 0) {
        this.setNavigationRoute(this.routeLatLng);
      }

      // 導航中只跟著司機位置移動；即使道路路線還沒回傳，也不可縮放到 A/B 全覽。
      this.driverMap.easeTo({center: this.currentMapLocation, duration: 0});
      return;
    }

    if (this.routeLatLng.length === 0) {
      this.driverMap.fitBounds(this.mapBounds(this.currentMapLocation, destination), {
        padding: {top: 94, right: 24, bottom: 310, left: 24},
        duration: animate ? 500 : 0,
        maxZoom: 15,
      });
      return;
    }
    this.setNavigationRoute(this.routeLatLng);
    // 預覽中：框住整條路線給司機看全貌。
    this.driverMap.fitBounds(this.mapBounds(this.currentMapLocation, destination), {
      padding: {top: 94, right: 24, bottom: 310, left: 24},
      duration: animate ? 500 : 0,
      maxZoom: 15,
    });
  }

  private destinationLocation(): MapPosition | null {
    const stop = this.selectedTask()?.stop;
    if (!stop || !this.hasCoordinates(stop)) {
      return null;
    }

    return [stop.lng, stop.lat];
  }

  private createMapMarkerElement(className: string, label: string): HTMLDivElement {
    const element = document.createElement('div');
    element.className = className;
    element.textContent = label;
    element.setAttribute('aria-hidden', 'true');
    return element;
  }

  private createDriverMarkerElement(): HTMLDivElement {
    const element = document.createElement('div');
    element.className = 'driver-location-marker';
    element.setAttribute('aria-label', '目前位置');
    return element;
  }

  private mapBounds(from: MapPosition, to: MapPosition) {
    return new this.maplibre!.LngLatBounds(from, from).extend(to);
  }

  private setNavigationRoute(coordinates: MapPosition[]): void {
    if (!this.driverMap || !this.driverMap.isStyleLoaded()) {
      return;
    }

    if (coordinates.length === 0) {
      if (this.driverMap.getLayer(DRIVER_ROUTE_LAYER_ID)) {
        this.driverMap.removeLayer(DRIVER_ROUTE_LAYER_ID);
      }
      if (this.driverMap.getSource(DRIVER_ROUTE_SOURCE_ID)) {
        this.driverMap.removeSource(DRIVER_ROUTE_SOURCE_ID);
      }
      return;
    }

    const routeData = {
      type: 'Feature' as const,
      properties: {},
      geometry: {type: 'LineString' as const, coordinates},
    };
    const source = this.driverMap.getSource(DRIVER_ROUTE_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;

    if (source) {
      source.setData(routeData);
      return;
    }

    this.driverMap.addSource(DRIVER_ROUTE_SOURCE_ID, {type: 'geojson', data: routeData});
    this.driverMap.addLayer({
      id: DRIVER_ROUTE_LAYER_ID,
      type: 'line',
      source: DRIVER_ROUTE_SOURCE_ID,
      paint: {
        'line-color': '#54cfae',
        'line-width': 5,
        'line-opacity': 0.9,
      },
      layout: {'line-cap': 'round', 'line-join': 'round'},
    });
  }

  private hasCoordinates(
    stop: DriverTaskStop,
  ): stop is DriverTaskStop & { lat: number; lng: number } {
    return typeof stop.lat === 'number' && typeof stop.lng === 'number';
  }

  /** 用本地年月日組 YYYY-MM-DD；toISOString 會先轉 UTC，台灣早上 8 點前會變成前一天 */
  private toIsoDate(date: Date): string {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  }

  private monthStart(date: Date): Date {
    return new Date(date.getFullYear(), date.getMonth(), 1);
  }

  private monthRange(month: Date): { from: string; to: string } {
    const year = month.getFullYear();
    const monthNumber = month.getMonth() + 1;
    const lastDay = new Date(year, monthNumber, 0).getDate();
    const prefix = `${year}-${String(monthNumber).padStart(2, '0')}`;
    return {from: `${prefix}-01`, to: `${prefix}-${String(lastDay).padStart(2, '0')}`};
  }

  private getLocationErrorMessage(error: GeolocationPositionError): string {
    if (error.code === error.PERMISSION_DENIED) {
      return '定位權限被拒絕，請允許瀏覽器存取位置';
    }

    if (error.code === error.POSITION_UNAVAILABLE) {
      return '裝置暫時無法取得定位訊號';
    }

    if (error.code === error.TIMEOUT) {
      return '定位逾時，請確認網路與定位服務後再試';
    }

    return '目前無法取得定位';
  }

  private isNotClockedInError(error: unknown): boolean {
    if (!(error instanceof HttpErrorResponse) || error.status !== 400) {
      return false;
    }

    return this.getBackendMessage(error).includes('尚未打上班卡');
  }

  private getErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 401) {
        return '登入已失效，請重新登入。';
      }
    }

    return this.getBackendMessage(error) || fallback;
  }

  private getBackendMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (typeof error.error === 'string') {
        return error.error;
      }

      if (
        error.error &&
        typeof error.error === 'object' &&
        'message' in error.error &&
        typeof error.error.message === 'string'
      ) {
        return error.error.message;
      }
    }

    return '';
  }

  private fetchRoute(animate: boolean) {
    const from = this.currentMapLocation;
    const to = this.destinationLocation();
    if (!from || !to) {
      return;
    }
    this.navigationRouteState.set('loading');
    this.operations.gpsRoute({
      fromLat: from[1],
      fromLng: from[0],
      toLat: to[1],
      toLng: to[0],
    }).subscribe({
      next: (res): void => {
        this.routeLatLng = res.path.map(([lat, lng]) => [lng, lat]);
        const polylineDistance = calculateRouteDistance(this.routeLatLng);
        this.routeDistanceScale = polylineDistance > 0 ? res.distance / polylineDistance : 1;
        this.routeDurationSecondsPerMeter = res.distance > 0 ? res.duration / res.distance : null;
        this.navigationDistanceMeters.set(res.distance);
        this.navigationDurationSeconds.set(res.duration);
        this.navigationRouteState.set('ready');
        this.renderNavigationMap(animate);
      },
      error: (res) => {
        this.routeLatLng = [];
        this.routeDistanceScale = 1;
        this.routeDurationSecondsPerMeter = null;
        this.navigationDistanceMeters.set(null);
        this.navigationDurationSeconds.set(null);
        this.navigationRouteState.set('error');
        this.renderNavigationMap(animate);
        this.mapLocationStatus.set(
          res.status === 0 ? '網路連線中斷，無法取得路線' : '無法取得路線，請稍後再試'
        );
      }
    });
  }

  protected startNavigation(): void {
    if (this.isNavigating()) {
      return;
    }

    this.isAttendanceSheetExpanded.set(false);
    this.attendanceSheetDragOffset.set(0);
    this.isNavigating.set(true);
    this.offRouteStreak = 0;
    if (this.currentMapLocation) {
      this.showMapLocation(this.currentMapLocation, true, this.lastMovementHeading);
    } else {
      this.focusNavigationOrigin();
    }
    this.renderNavigationMap(false);
    void this.requestWakeLock();
  }

  protected stopNavigation(): void {
    this.isNavigating.set(false);
    this.offRouteStreak = 0;
    this.renderNavigationMap(false);
    void this.releaseWakeLock();
  }

  private focusNavigationOrigin(): void {
    if (!this.driverMap || !this.currentMapLocation) {
      return;
    }

    this.driverMap.easeTo({
      center: this.currentMapLocation,
      zoom: Math.max(this.driverMap.getZoom(), 16),
      duration: 500,
    });
  }

  private applyNavigationPosition(here: MapPosition, heading: number | null): void {
    if (this.routeLatLng.length === 0) {
      this.showMapLocation(here, false, heading);
      return;
    }
    const {distance, index} = findNearest(here, this.routeLatLng);
    if (distance > OFF_ROUTE_METERS) {
      this.offRouteStreak++;
    } else {
      this.offRouteStreak = 0;
      this.routeLatLng = this.routeLatLng.slice(index);
      this.updateRemainingRouteMetrics(here);
    }
    this.showMapLocation(here, false, heading);

    if (
      this.offRouteStreak >= OFF_ROUTE_STREAK && Date.now() - this.lastRecalcAt > RECALC_COOLDOWN_MS
    ) {
      this.lastRecalcAt = Date.now();
      this.offRouteStreak = 0;
      this.fetchRoute(false);
    }

  }

  private updateRemainingRouteMetrics(here: MapPosition): void {
    const calculatedDistance =
      calculateRemainingRouteDistance(here, this.routeLatLng) * this.routeDistanceScale;
    const currentDistance = this.navigationDistanceMeters();
    const remainingDistance = Math.max(
      0,
      currentDistance === null ? calculatedDistance : Math.min(currentDistance, calculatedDistance),
    );

    this.navigationDistanceMeters.set(remainingDistance);
    if (this.routeDurationSecondsPerMeter !== null) {
      this.navigationDurationSeconds.set(remainingDistance * this.routeDurationSecondsPerMeter);
    }
  }


  protected requestWakeLock(): void {
    if (!navigator.wakeLock) {
      return
    }
    navigator.wakeLock.request('screen').then((res) => {
      this.wakeLock = res;
    })
      .catch(() => {
        this.wakeLock = null;
      });

  }

  protected releaseWakeLock(): void {
    this.wakeLock?.release();
    this.wakeLock = null;
  }
}

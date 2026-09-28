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
import {Observable, map, of, single, switchMap} from 'rxjs';
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
  DriverCaseCategory,
  DriverCaseDto,
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
  DriverTaskOrderItem,
  DriverTaskOrderStatus,
  DriverTasksResponse,
  GpsRouteStep,
} from '../../core/services/driver-operations.models';
import {DriverOperationsService} from '../../core/services/driver-operations.service';
import {DriverWeather, DriverWeatherService} from '../../core/services/driver-weather.service';
import {BrandLogo} from '../../shared/ui/brand-logo/brand-logo';
import {PreTripCheck} from './pre-trip-check/pre-trip-check';
import {ScheduleLeaveComposer} from './schedule-leave-composer/schedule-leave-composer';
import {pendingLeaveDatesInMonth} from './schedule-leave-composer/schedule-leave-status';
import {ScheduleCellLabels} from '../../shared/ui/schedule-cell-labels/schedule-cell-labels';

type AttendanceViewState = 'loading' | 'not-clocked-in' | 'ready' | 'error';
type DriverTab = 'map' | 'tasks' | 'profile' | 'schedule';
type TaskViewState = 'loading' | 'ready' | 'empty' | 'error';
type ScheduleViewState = 'loading' | 'ready' | 'empty' | 'error';
type NavigationRouteState = 'idle' | 'loading' | 'ready' | 'error';
type ChatViewState = 'loading' | 'ready' | 'empty' | 'error';
/**
 * 支援中心 sheet 目前在哪一頁。獨立一個 signal，一般對話原本的 chat* signal 不用跟著改：
 * home＝首頁（分類格子、案件清單），create＝建立案件，thread＝對話（caseId 是 null 就是一般對話）
 */
type SupportView =
  | {kind: 'home'}
  | {kind: 'create'; category: DriverCaseCategory}
  | {kind: 'thread'; caseId: number | null};
type CaseListState = 'loading' | 'ready' | 'error';

interface DriverTaskSelection {
  route: DriverRouteTask;
  stop: DriverTaskStop;
}

type NavigationTaskSet = {
  routes: {stops: Pick<DriverTaskStop, 'orderId' | 'orderStatus'>[]}[];
};

export function pendingLoadingOrderCount(tasks: NavigationTaskSet | null): number {
  return (tasks?.routes ?? []).reduce(
    (count, route) => count + route.stops.filter((stop) => stop.orderStatus === 'CONFIRMED').length,
    0,
  );
}

export function navigationReadyForOrder(tasks: NavigationTaskSet | null, orderId: number): boolean {
  if (!tasks || pendingLoadingOrderCount(tasks) > 0) {
    return false;
  }
  const stop = tasks.routes.flatMap((route) => route.stops)
    .find((taskStop) => taskStop.orderId === orderId);
  return stop?.orderStatus === 'LOADED' || stop?.orderStatus === 'IN_DELIVERY';
}

interface DriverLeaveForm {
  workDate: string;
  leaveType: DriverLeaveRequest['leaveType'];
  leaveStart: string;
  leaveEnd: string;
  reason: string;
}

type LeaveApplicationTab = 'temporary' | 'planned' | 'makeup';

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

// ── 逐一轉彎提示 ─────────────────────────────────────────
// 轉彎點離剩下的路線多近才算「還在前面」。OSRM 的轉彎點本身就是路線上的頂點，留一點誤差給切路線的位置
const STEP_ON_ROUTE_METERS = 20;
// 「轉完了」的判斷：曾經開到轉彎點這麼近……
const STEP_APPROACH_METERS = 25;
// ……之後又離開這麼遠。兩個都要成立，停紅燈時 GPS 飄動才不容易被當成已經轉過
const STEP_DEPART_METERS = 20;
// 離終點這麼近就算抵達
const ARRIVE_METERS = 30;
// 進到這個距離內念「即將左轉…」
const NEAR_ANNOUNCE_METERS = 80;
const VOICE_GUIDANCE_STORAGE_KEY = 'driver.voiceGuidance';

/**
 * 轉彎點相對司機的位置。remainingRoute 是已經切掉走過部分的路線，第一個點就在司機附近。
 *
 * alongRouteMeters：沿著剩下的路線開到轉彎點的距離。直線距離在彎路上會偏短，所以沿路線累加；
 *   轉彎點不在剩下的路線上時退回直線距離。
 * onRemainingRoute：轉彎點還在剩下的路線上；false 代表那一段已經被切掉，也就是開過去了。
 */
export function measureStepProgress(
  here: MapPosition,
  remainingRoute: MapPosition[],
  target: MapPosition,
): { alongRouteMeters: number; onRemainingRoute: boolean } {
  if (remainingRoute.length === 0) {
    return {alongRouteMeters: distanceInMeters(here, target), onRemainingRoute: false};
  }

  let travelled = distanceInMeters(here, remainingRoute[0]);
  for (let i = 0; i < remainingRoute.length; i++) {
    if (i > 0) {
      travelled += distanceInMeters(remainingRoute[i - 1], remainingRoute[i]);
    }
    // 從前面往後找第一個靠近的點：路線繞回同一個路口時，才不會對到後面那一次
    let gap = distanceInMeters(remainingRoute[i], target);
    if (gap <= STEP_ON_ROUTE_METERS) {
      // 20 公尺內的第一個點不一定是轉彎點本身（路線點很密時會提早 20 公尺停下），
      // 繼續往前走到最接近的那個點，再補上剩下的直線距離
      while (i + 1 < remainingRoute.length) {
        const nextGap = distanceInMeters(remainingRoute[i + 1], target);
        if (nextGap >= gap) {
          break;
        }
        travelled += distanceInMeters(remainingRoute[i], remainingRoute[i + 1]);
        gap = nextGap;
        i++;
      }
      return {alongRouteMeters: travelled + gap, onRemainingRoute: true};
    }
  }
  return {alongRouteMeters: distanceInMeters(here, target), onRemainingRoute: false};
}

/**
 * 這個轉彎是否已經轉過，兩個條件任一成立就算：
 * 1. 轉彎點已經不在剩下的路線上：那一段被切掉了。GPS 剛好沒取到轉彎附近的點（跳點、隧道）也抓得到。
 * 2. 曾經開到轉彎點附近，現在又離開一段：轉完了。只看「夠近就換下一步」的話，
 *    起點常出現的迴轉點（OSRM 的 continue＋uturn）就在腳下，一開始就會被跳過。
 */
export function isStepPassed(distanceNow: number, closestSoFar: number, onRemainingRoute: boolean): boolean {
  if (!onRemainingRoute) {
    return true;
  }
  return closestSoFar <= STEP_APPROACH_METERS && distanceNow >= closestSoFar + STEP_DEPART_METERS;
}

/** 提示列上的距離；20 公尺內直接說「即將」，數字跳動只會干擾 */
export function formatManeuverDistance(meters: number): string {
  if (meters < 20) {
    return '即將';
  }
  if (meters >= 1_000) {
    return `${(meters / 1_000).toFixed(1)} 公里`;
  }
  if (meters >= 100) {
    return `${Math.round(meters / 10) * 10} 公尺`;
  }
  return `${Math.round(meters / 5) * 5} 公尺`;
}

/** 轉彎動作對應的 Material Icons 圖示；台灣靠右行駛，迴轉一律往左 */
export function maneuverIcon(step: Pick<GpsRouteStep, 'type' | 'modifier'>): string {
  const modifier = step.modifier ?? '';
  const toLeft = modifier.includes('left');
  switch (step.type) {
    case 'arrive':
      return 'flag';
    case 'depart':
      return 'navigation';
    case 'roundabout':
    case 'rotary':
    case 'exit roundabout':
    case 'exit rotary':
      return toLeft ? 'roundabout_left' : 'roundabout_right';
    case 'fork':
      return toLeft ? 'fork_left' : 'fork_right';
    case 'on ramp':
    case 'off ramp':
      return toLeft ? 'ramp_left' : 'ramp_right';
    case 'merge':
      return 'merge';
  }
  switch (modifier) {
    case 'uturn':
      return 'u_turn_left';
    case 'left':
      return 'turn_left';
    case 'right':
      return 'turn_right';
    case 'slight left':
      return 'turn_slight_left';
    case 'slight right':
      return 'turn_slight_right';
    case 'sharp left':
      return 'turn_sharp_left';
    case 'sharp right':
      return 'turn_sharp_right';
    default:
      return 'straight';
  }
}

/** 路名換了但不用轉彎（直行）；這種步驟不念「即將…」，司機什麼都不用做 */
function isStraightThrough(step: GpsRouteStep): boolean {
  return (step.type === 'new name' || step.type === 'continue')
    && (step.modifier === null || step.modifier === 'straight');
}

/** 語音開關記在這台手機上；讀不到（無痕模式、被封鎖）就用預設的開啟 */
function readVoiceGuidancePreference(): boolean {
  try {
    return localStorage.getItem(VOICE_GUIDANCE_STORAGE_KEY) !== 'off';
  } catch {
    return true;
  }
}

// ── 支援中心：例外回報案件 ─────────────────────────────────

export interface CaseCategoryOption {
  code: DriverCaseCategory;
  label: string;
  /** Material Icons 的名稱 */
  icon: string;
  /** 常見情境，點了會組進說明。不另外存欄位：後台看說明就知道，以後改這裡不用動資料庫 */
  quickPicks: readonly string[];
  /** 表單最上面的提醒：人身安全優先，或提醒這類狀況已經有專用按鈕 */
  notice?: string;
  /** 提醒下面的撥號按鈕（手機點了直接撥） */
  calls?: readonly {label: string; tel: string}[];
  /** 交通事故：格子和提醒用紅色 */
  urgent?: boolean;
}

/** 說明組起來最多幾個字；跟後端 exception_cases.description 的 VARCHAR(1000) 一致 */
export const CASE_DESCRIPTION_MAX_LENGTH = 1000;

const EMERGENCY_CALLS = [
  {label: '撥 119', tel: '119'},
  {label: '報警 110', tel: '110'},
] as const;

const OTHER_CASE_CATEGORY: CaseCategoryOption = {code: 'OTHER', label: '其他', icon: 'more_horiz', quickPicks: []};

/**
 * 司機可以選的分類，順序就是支援中心格子的順序。
 * 沒有「門市拒收」：業務上沒有這種情境。無人簽收、交貨短少破損、點交不符都有專用按鈕，
 * 那些才會改訂單狀態、建補送單；案件只負責「先問調度中心怎麼辦」，不改任何狀態。
 */
export const CASE_CATEGORIES: readonly CaseCategoryOption[] = [
  {
    code: 'VEHICLE',
    label: '車輛問題',
    icon: 'car_repair',
    quickPicks: ['無法發動', '爆胎', '儀表警示燈亮', '煞車異常', '升降尾門故障'],
  },
  {
    code: 'ACCIDENT',
    label: '交通事故',
    icon: 'car_crash',
    quickPicks: ['擦撞，無人受傷', '有人受傷', '被後車追撞'],
    notice: '先確認人員安全。有人受傷請撥 119，並報警 110，再回來回報。',
    calls: EMERGENCY_CALLS,
    urgent: true,
  },
  {
    code: 'ROAD',
    label: '路況延誤',
    icon: 'traffic',
    quickPicks: ['嚴重塞車', '道路封閉或施工', '豪雨淹水', '限高或限重過不去'],
  },
  {
    code: 'STORE',
    label: '門市狀況',
    icon: 'storefront',
    quickPicks: ['找不到門市', '地址或導航有誤', '無法停車卸貨', '門市沒開或沒人', '等候太久'],
    notice: '確定沒人可以簽收，請回任務卡按「無人簽收」，系統才會建立補送單。這裡是先跟調度中心確認。',
  },
  {
    code: 'GOODS',
    label: '貨物問題',
    icon: 'inventory_2',
    quickPicks: ['外箱破損', '貨物傾倒', '裝錯貨（別家門市的貨）', '少箱'],
    notice: '交貨時短少或破損的箱數，還是要在任務卡「交貨」裡填，系統會自動建立異常單。這裡是先回報、問怎麼處理。',
  },
  {
    code: 'PERSONAL',
    label: '身體／安全',
    icon: 'health_and_safety',
    quickPicks: ['身體不適', '受傷', '遇到糾紛或威脅'],
    notice: '人身安全優先。需要救護或報警請直接撥打。',
    calls: EMERGENCY_CALLS,
  },
  {
    code: 'SYSTEM',
    label: 'App／系統',
    icon: 'smartphone',
    quickPicks: ['按鈕送不出去', 'GPS 定位不準', '任務資料有誤', '照片傳不上去'],
    notice: 'App 完全不能用時，請回支援中心首頁按「打給倉庫」。',
  },
  OTHER_CASE_CATEGORY,
];

/** 後端多了前端還不認識的分類時退回「其他」，畫面不會壞 */
export function caseCategoryOption(code: DriverCaseCategory): CaseCategoryOption {
  return CASE_CATEGORIES.find((option) => option.code === code) ?? OTHER_CASE_CATEGORY;
}

/** 畫面上的案件狀態。資料庫只有 OPEN／CLOSED；OPEN 再用「調度中心接收了沒」分成等待回覆、處理中 */
export type CaseDisplayStatus = 'waiting' | 'handling' | 'closed';

export function caseDisplayStatus(item: Pick<DriverCaseDto, 'status' | 'acceptedAt'>): CaseDisplayStatus {
  if (item.status === 'CLOSED') {
    return 'closed';
  }
  return item.acceptedAt ? 'handling' : 'waiting';
}

/**
 * 把點選的快選和補充說明組成一段說明，例如「爆胎、儀表警示燈亮：停在台 1 線路肩」。
 * 快選照分類裡的順序排、不照點的順序：同樣的組合每次長得一樣，後台掃清單比較快。
 */
export function composeCaseDescription(
  option: CaseCategoryOption,
  picks: readonly string[],
  note: string,
): string {
  const head = option.quickPicks.filter((pick) => picks.includes(pick)).join('、');
  const detail = note.trim();
  if (head && detail) {
    return `${head}：${detail}`;
  }
  return head || detail;
}

/** 送出前的檢查，回傳錯誤訊息，null 代表可以送。後端一樣要檢查；這裡只是讓司機不用等一趟來回才知道 */
export function validateCaseDraft(description: string, canContinue: boolean | null): string | null {
  if (!description) {
    return '請點選發生的狀況，或寫一段說明。';
  }
  if (description.length > CASE_DESCRIPTION_MAX_LENGTH) {
    return `說明不能超過 ${CASE_DESCRIPTION_MAX_LENGTH} 字。`;
  }
  if (canContinue === null) {
    return '請選擇還能不能繼續配送。';
  }
  return null;
}

/** 合併訊息清單：用 id 去重（推播和 API 回應常常是同一則），依 id 由舊到新排 */
export function mergeMessagesById(
  current: readonly DriverMessageDto[],
  incoming: readonly DriverMessageDto[],
): DriverMessageDto[] {
  const merged = new Map(current.map((message) => [message.id, message]));
  incoming.forEach((message) => merged.set(message.id, message));
  return Array.from(merged.values()).sort((left, right) => left.id - right.id);
}

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
    ScheduleLeaveComposer,
    ScheduleCellLabels,
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
  protected readonly calendarLeaveComposer = viewChild<ScheduleLeaveComposer>('calendarLeaveComposer');
  protected readonly scheduleDateFilter = (date: Date) => this.calendarLeaveComposer()?.canSelectDate(date) ?? true;
  protected selectScheduleDate(date: Date | null): void {
    if (!date) return;
    this.selectedScheduleDate.set(date);
    this.calendarLeaveComposer()?.toggleDate(date);
  }
  protected refreshScheduleCalendar(): void { this.scheduleCalendar()?.updateTodaysDate(); }
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
    this.loadLeaveRequests();
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
  protected readonly pendingLoadingCount = computed(() => pendingLoadingOrderCount(this.todayTasks()));
  /**
   * routeId → 這條路線的出車前安全檢查通過了沒，由路線卡片上的 app-pre-trip-check 回報。
   * 重新整理任務時不清空：同一條路線、同一台車的元件不會重建，也就不會再回報一次，清掉點交按鈕會被誤鎖
   */
  protected readonly inspectionReady = signal<Record<number, boolean>>({});
  protected readonly taskViewState = signal<TaskViewState>('loading');
  protected readonly taskError = signal<string | null>(null);
  protected readonly selectedTask = signal<DriverTaskSelection | null>(null);
  protected readonly activeDeliveryOrderId = signal<number | null>(null);
  protected readonly deliveryNotes = signal('');
  protected readonly deliveryPhoto = signal<File | null>(null);
  protected readonly activeLoadingOrderId = signal<number | null>(null);
  protected readonly loadingNotes = signal('');
  protected readonly loadingItemForms = signal<Record<number, LoadingItemForm>>({});
  protected readonly loadingSummaryChecked = signal(false);
  protected readonly reportingLoadingItemIds = signal<readonly number[]>([]);
  protected readonly taskActionError = signal<string | null>(null);
  protected readonly taskActionMessage = signal<string | null>(null);
  protected readonly isTaskSubmitting = signal(false);
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
    () => this.leaveRequests().filter((request) => request.status !== 'PENDING'
      && request.reviewedAt && !request.driverReadAt).length,
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

  // ── 支援中心（例外回報案件）──
  // sheet 裡現在是哪一頁；sheet 關掉時保留原值，下次打開再重設
  protected readonly supportView = signal<SupportView>({kind: 'home'});
  // 停在一般對話那一頁（不管 sheet 開沒開）；決定 thread 頁要畫一般對話還是案件
  protected readonly isGeneralThread = computed(() => {
    const view = this.supportView();
    return view.kind === 'thread' && view.caseId === null;
  });
  // 司機「正看著」一般對話：sheet 開著而且停在一般對話。
  // 停在支援中心首頁時訊息還沒被看到，這時標已讀的話，紅點還沒被看到就消失了
  private readonly isViewingGeneralChat = computed(() => this.isChatOpen() && this.isGeneralThread());
  // 司機正看著哪一件案件的對話；沒有是 null
  private readonly viewingCaseId = computed(() => {
    const view = this.supportView();
    return this.isChatOpen() && view.kind === 'thread' ? view.caseId : null;
  });

  /**
   * 司機「看得到一般對話」而且有未讀，就標已讀。跟後台 dispatch-shell 的 markViewingDriverRead 同一種寫法。
   *
   * 會讓司機看到的入口有：進入一般對話、對話載入完成、看著時收到新訊息、重連補抓；
   * 用 effect 只描述「看著＋有未讀＝標已讀」，不用在每個入口各呼叫一次，漏一個紅點就消不掉。
   */
  private readonly markViewingChatRead = effect(() => {
    if (!this.isViewingGeneralChat() || this.unreadChatCount() === 0) {
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

  protected readonly caseCategories = CASE_CATEGORIES;
  protected readonly driverCases = signal<DriverCaseDto[]>([]);
  protected readonly caseListState = signal<CaseListState>('loading');
  // 新的在前面。用 id 排不用建立時間：id 由資料庫遞增一定不重複，時間可能一樣
  protected readonly openCases = computed(() =>
    this.driverCases()
      .filter((item) => item.status === 'OPEN')
      .sort((left, right) => right.id - left.id),
  );
  protected readonly closedCases = computed(() =>
    this.driverCases()
      .filter((item) => item.status === 'CLOSED')
      .sort((left, right) => right.id - left.id),
  );
  // 首頁「已結案」清單預設收起來：當天要處理的是進行中的案件
  protected readonly isClosedCaseListOpen = signal(false);
  // 對話頁顯示的案件；一般對話或其他頁是 null。從清單找，推播改了狀態（結案）畫面會跟著變
  protected readonly activeCase = computed(() => {
    const view = this.supportView();
    if (view.kind !== 'thread' || view.caseId === null) {
      return null;
    }
    return this.driverCases().find((item) => item.id === view.caseId) ?? null;
  });
  protected readonly activeCategory = computed(() => {
    const view = this.supportView();
    return view.kind === 'create' ? caseCategoryOption(view.category) : null;
  });
  protected readonly supportHeading = computed(() => {
    const view = this.supportView();
    if (view.kind === 'home') {
      return {eyebrow: '配送支援', title: '支援中心'};
    }
    if (view.kind === 'create') {
      return {eyebrow: '回報新問題', title: caseCategoryOption(view.category).label};
    }
    if (view.caseId === null) {
      return {eyebrow: '一般對話', title: '調度中心'};
    }
    const current = this.activeCase();
    return {
      eyebrow: `案件 #${view.caseId}`,
      title: current ? caseCategoryOption(current.category).label : '案件',
    };
  });
  // 地圖上支援中心按鈕的紅點：一般對話的未讀加上每件案件的未讀
  protected readonly supportUnreadCount = computed(
    () => this.unreadChatCount() + this.driverCases().reduce((sum, item) => sum + item.unreadCount, 0),
  );
  // 建立案件時可以選的訂單：今天路線上的每一站，照路線順序
  protected readonly caseOrderOptions = computed(() =>
    (this.todayTasks()?.routes ?? []).flatMap((route) => route.stops),
  );
  // 「打給倉庫」：網路不通、App 不能用時的退路，號碼來自今天路線的倉庫
  protected readonly warehousePhone = computed(
    () => this.todayTasks()?.routes.find((route) => route.warehouse.phone)?.warehouse.phone ?? null,
  );

  // 建立案件的表單
  protected readonly casePicks = signal<string[]>([]);
  protected readonly caseNote = signal('');
  protected readonly caseOrderId = signal<number | null>(null);
  // null＝還沒選；一定要司機自己選，不給預設值，後台靠這個判斷輕重
  protected readonly caseCanContinue = signal<boolean | null>(null);
  protected readonly casePhoto = signal<File | null>(null);
  // 送出中：鎖住送出鈕。路上訊號差時按了沒反應，司機會再按，不鎖就會建出兩件一樣的案件
  protected readonly isSubmittingCase = signal(false);
  protected readonly caseFormError = signal<string | null>(null);

  // 案件對話。一般對話仍放在 chatMessages，兩串分開存：
  // 一般對話的紅點是從 chatMessages 算的，共用的話打開案件就會把一般對話的紅點算錯
  protected readonly caseMessages = signal<DriverMessageDto[]>([]);
  protected readonly caseThreadState = signal<ChatViewState>('loading');
  // 跟一般對話的輸入框分開：換到別串時，打到一半的字才不會被送到另一串
  protected readonly caseChatInput = signal('');
  protected readonly isSendingCaseMessage = signal(false);
  protected readonly caseSendError = signal('');

  /**
   * 看著某件案件的對話、而且有未讀，就標已讀；跟一般對話的 markViewingChatRead 同一套想法。
   * 未讀看兩個地方：清單上的 unreadCount（對話還在載入時就有），和已載入訊息裡沒讀的回覆。
   */
  private readonly markViewingCaseRead = effect(() => {
    const caseId = this.viewingCaseId();
    if (caseId === null) {
      return;
    }
    const listed = this.driverCases().find((item) => item.id === caseId);
    const hasUnread =
      (listed?.unreadCount ?? 0) > 0 ||
      this.caseMessages().some((message) => message.senderType === 'ADMIN' && !message.readAt);
    if (!hasUnread) {
      return;
    }

    // 先在畫面上標掉，理由同 markViewingChatRead：不標的話 effect 重跑會連打好幾次 API
    const readAt = new Date().toISOString();
    this.caseMessages.update((messages) =>
      messages.map((message) => (message.senderType === 'ADMIN' && !message.readAt ? {...message, readAt} : message)),
    );
    this.driverCases.update((cases) =>
      cases.map((item) => (item.id === caseId ? {...item, unreadCount: 0} : item)),
    );
    this.operations.markCaseMessagesRead(caseId).subscribe({error: () => undefined});
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
  // ── 逐一轉彎提示（資料來自後端 /api/driver/route 的 steps）──
  protected readonly navigationSteps = signal<GpsRouteStep[]>([]);
  // 目前提示的是第幾步；第 0 步是「出發」，位置就在起點，所以從第 1 步開始
  protected readonly currentStepIndex = signal(0);
  // 沿路線開到下一個轉彎點還有幾公尺；還沒收到 GPS 時是 null
  protected readonly distanceToManeuver = signal<number | null>(null);
  protected readonly hasArrived = signal(false);
  protected readonly currentManeuver = computed(
    () => this.navigationSteps()[this.currentStepIndex()] ?? null,
  );
  protected readonly voiceGuidanceEnabled = signal(readVoiceGuidancePreference());
  // 這一步目前為止離轉彎點最近的直線距離，給 isStepPassed 判斷「開近又離開＝轉完了」
  private closestToStep = Infinity;
  // 已經念過「300 公尺後…」／「即將…」的是第幾步，同一步不重複念
  private announcedStepIndex = -1;
  private announcedNearStepIndex = -1;
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
   * 收到的推播合併到對話清單。第一次連上也要載一次對話和案件：不載的話，
   * 司機登入前調度中心就發的訊息不會算進紅點，要點開聊天才知道有人找他。
   * 重連時同樣重載，補回斷線期間漏掉的推播（包括斷線時被結案的案件）。
   */
  private connectChatSocket(): void {
    this.chatSocket.pushes$
      .pipe(takeUntilDestroyed())
      .subscribe((push) => this.handleChatPush(push));
    this.chatSocket.connected$
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.loadChatMessages();
        this.loadCases();
        this.catchUpCaseThread();
      });
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

  /** 地圖上的支援中心按鈕：一律從首頁開始，上次停在哪一串對話不重要，首頁看得到所有未讀 */
  protected openSupportCenter(): void {
    this.supportView.set({kind: 'home'});
    this.openSupportSheet();
  }

  protected closeSupportCenter(): void {
    this.driverChatSheetRef?.dismiss();
  }

  /**
   * 打開支援中心（Material Bottom Sheet，從底部滑上來），停在 supportView 指的那一頁。
   *
   * 內容用 <ng-template>，不另開元件：跟後台確認視窗（MatDialog）同一種寫法，一頁看得到全部。
   * 對話內容、案件清單與 WebSocket 連線放在這個元件的 signal／service，不能放在 sheet 裡：
   * sheet 關掉時裡面的畫面會整個銷毀，放在裡面的話，關著時收不到訊息、重開要整串重載。
   */
  private openSupportSheet(): void {
    if (!this.driverChatSheet || this.driverChatSheetRef) {
      return;
    }
    this.driverChatSheetRef = this.bottomSheet.open(this.driverChatSheet, {
      ariaLabel: '支援中心',
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
    this.loadCases();
  }

  protected backToSupportHome(): void {
    this.supportView.set({kind: 'home'});
  }

  protected toggleClosedCaseList(): void {
    this.isClosedCaseListOpen.update((open) => !open);
  }

  /**
   * 打開一串對話；caseId 是 null 就是一般對話。
   * 一般對話在打開 sheet、WebSocket 連上時就載過，推播也會即時補進來，這裡只在上次載入失敗時重抓，
   * 不然每次點進去都會閃一下「正在載入」。
   */
  protected openCaseThread(caseId: number | null): void {
    if (caseId === null) {
      this.supportView.set({kind: 'thread', caseId: null});
      if (this.chatViewState() === 'error') {
        this.loadChatMessages();
      }
      return;
    }
    // 先清掉上一件的訊息再切頁，不然會閃一下別件案件的對話
    this.caseMessages.set([]);
    this.caseChatInput.set('');
    this.caseSendError.set('');
    this.supportView.set({kind: 'thread', caseId});
    this.loadCaseMessages(caseId);
  }

  /** 點分類開始填案件。每次都從空白表單開始，上一次沒送出的內容不留，免得帶到別的分類 */
  protected startCase(category: DriverCaseCategory, orderId?: number): void {
    this.casePicks.set([]);
    this.caseNote.set('');
    this.caseOrderId.set(orderId ?? this.defaultCaseOrderId(category));
    this.caseCanContinue.set(null);
    this.casePhoto.set(null);
    this.caseFormError.set(null);
    this.supportView.set({kind: 'create', category});
  }

  /** 任務卡的「貨況異常」：直接打開建立案件，分類是貨物問題、帶入這張單 */
  protected reportStopIssue(stop: DriverTaskStop): void {
    this.startCase('GOODS', stop.orderId);
    this.openSupportSheet();
  }

  protected isCasePickSelected(pick: string): boolean {
    return this.casePicks().includes(pick);
  }

  protected toggleCasePick(pick: string): void {
    this.casePicks.update((picks) =>
      picks.includes(pick) ? picks.filter((item) => item !== pick) : [...picks, pick],
    );
    this.caseFormError.set(null);
  }

  protected updateCaseNote(event: Event): void {
    this.caseNote.set((event.target as HTMLTextAreaElement).value);
    this.caseFormError.set(null);
  }

  protected selectCaseOrder(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.caseOrderId.set(value ? Number(value) : null);
  }

  protected setCaseCanContinue(canContinue: boolean): void {
    this.caseCanContinue.set(canContinue);
    this.caseFormError.set(null);
  }

  /** 跟交貨照片同一套限制；選完就把 input 清掉，同一張照片移除後才能再選一次 */
  protected selectCasePhoto(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.item(0) ?? null;
    input.value = '';
    if (!file) {
      return;
    }
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) {
      this.caseFormError.set('請選擇 5 MB 以下的 JPG、PNG 或 WebP 圖片。');
      return;
    }
    this.casePhoto.set(file);
    this.caseFormError.set(null);
  }

  protected removeCasePhoto(): void {
    this.casePhoto.set(null);
  }

  /**
   * 送出案件：有照片先傳照片拿網址（跟交貨照片同一支 API），再建案件；成功後直接進這件案件的對話。
   * 失敗時表單內容全部留著，讓司機再按一次，或改打電話。
   */
  protected submitCase(): void {
    const view = this.supportView();
    if (view.kind !== 'create' || this.isSubmittingCase()) {
      return;
    }
    const canContinue = this.caseCanContinue();
    const description = composeCaseDescription(caseCategoryOption(view.category), this.casePicks(), this.caseNote());
    const error = validateCaseDraft(description, canContinue);
    if (error) {
      this.caseFormError.set(error);
      return;
    }

    this.isSubmittingCase.set(true);
    this.caseFormError.set(null);
    const photo = this.casePhoto();
    // 型別要寫出來：不寫的話 TS 會推成 Observable<string> | Observable<null> 兩種，後面的 pipe 就接不起來
    const photoUrl$: Observable<string | null> = photo
      ? this.operations.uploadDeliveryPhoto(photo).pipe(map((response) => response.url))
      : of(null);
    photoUrl$
      .pipe(
        switchMap((photoUrl) =>
          this.operations.createCase({
            category: view.category,
            orderId: this.caseOrderId(),
            description,
            // 上面 validateCaseDraft 已經擋掉 null，這裡一定是 true 或 false
            canContinue: canContinue === true,
            photoUrl,
          }),
        ),
      )
      .subscribe({
        next: (created) => {
          this.isSubmittingCase.set(false);
          this.upsertCase(created);
          this.openCaseThread(created.id);
        },
        error: (error: unknown) => {
          this.isSubmittingCase.set(false);
          this.caseFormError.set(
            this.getCaseErrorMessage(error, '案件沒有送出，請稍後再試。緊急狀況請直接打電話給倉庫。'),
          );
        },
      });
  }

  /** 在案件裡留言；跟一般對話的 sendChatMessage 同一種寫法，只是打的是這件案件的 API */
  protected sendCaseMessage(): void {
    const current = this.activeCase();
    const content = this.caseChatInput().trim();
    if (!current || current.status === 'CLOSED' || !content || this.isSendingCaseMessage()) {
      return;
    }

    this.isSendingCaseMessage.set(true);
    this.caseSendError.set('');
    this.operations.sendCaseMessage(current.id, content).subscribe({
      next: (saved) => {
        this.isSendingCaseMessage.set(false);
        this.caseChatInput.set('');
        if (this.isShowingCase(current.id)) {
          this.caseMessages.set(mergeMessagesById(this.caseMessages(), [saved]));
          this.caseThreadState.set('ready');
        }
      },
      error: (error: unknown) => {
        this.isSendingCaseMessage.set(false);
        this.caseSendError.set(this.getCaseErrorMessage(error, '訊息沒有送出，請稍後再試。'));
      },
    });
  }

  protected caseCategoryLabel(category: DriverCaseCategory): string {
    return caseCategoryOption(category).label;
  }

  protected caseCategoryIcon(category: DriverCaseCategory): string {
    return caseCategoryOption(category).icon;
  }

  protected caseStatusLabel(item: DriverCaseDto): string {
    const labels: Record<CaseDisplayStatus, string> = {
      waiting: '等待回覆',
      handling: '處理中',
      closed: '已結案',
    };
    return labels[caseDisplayStatus(item)];
  }

  protected caseStatusClass(item: DriverCaseDto): string {
    return 'support-status is-' + caseDisplayStatus(item);
  }

  /** 案件時間：今天只顯示時分，其他天加上月日 */
  protected formatCaseTime(value: string | null): string {
    if (!value) {
      return '';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }
    const isToday = date.toDateString() === new Date().toDateString();
    return new Intl.DateTimeFormat(
      'zh-TW',
      isToday
        ? {hour: '2-digit', minute: '2-digit'}
        : {month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'},
    ).format(date);
  }

  /** 門市、貨物的狀況多半發生在正要送的那一站，先帶入導航中的目的地；其他分類跟訂單無關，預設不指定 */
  private defaultCaseOrderId(category: DriverCaseCategory): number | null {
    if (category !== 'STORE' && category !== 'GOODS') {
      return null;
    }
    return this.selectedTask()?.stop.orderId ?? null;
  }

  /** 支援中心打開、WebSocket 連上（含重連）時抓；已經有資料就不閃成「載入中」 */
  private loadCases(): void {
    if (this.driverCases().length === 0) {
      this.caseListState.set('loading');
    }
    this.operations.getCases().subscribe({
      next: (cases) => {
        this.driverCases.set(cases);
        this.caseListState.set('ready');
      },
      error: () => this.caseListState.set('error'),
    });
  }

  private loadCaseMessages(caseId: number): void {
    this.caseThreadState.set('loading');
    this.operations.getCaseMessages(caseId).subscribe({
      next: (messages) => {
        // 回應回來之前司機可能已經切到別串：丟掉，不然 A 案件的訊息會出現在 B 案件的畫面
        if (!this.isShowingCase(caseId)) {
          return;
        }
        this.caseMessages.set(mergeMessagesById(this.caseMessages(), messages));
        this.caseThreadState.set('ready');
      },
      error: () => {
        if (this.isShowingCase(caseId)) {
          this.caseThreadState.set('error');
        }
      },
    });
  }

  /**
   * 重連時補抓停著的那件案件：斷線期間的推播不會補發。只重抓清單的話，清單上的未讀數會讓
   * markViewingCaseRead 標已讀，後台看到「已讀」，那幾則卻從來沒出現在司機畫面上。
   * 有訊息就只問比最後一則新的（afterId），接在後面；還沒有就整串載。
   */
  private catchUpCaseThread(): void {
    const view = this.supportView();
    if (view.kind !== 'thread' || view.caseId === null) {
      return;
    }
    const caseId = view.caseId;
    const lastId = this.caseMessages().at(-1)?.id;
    if (lastId === undefined) {
      this.loadCaseMessages(caseId);
      return;
    }
    this.operations.getCaseMessages(caseId, lastId).subscribe({
      next: (newer) => {
        if (this.isShowingCase(caseId)) {
          this.caseMessages.set(mergeMessagesById(this.caseMessages(), newer));
        }
      },
      // 補抓失敗不另外提示：下次重連或重新點進這件時會再載一次
      error: () => undefined,
    });
  }

  /** 對話頁停在這件案件（不管 sheet 開沒開）；非同步回應回來時用它確認司機還在同一串 */
  private isShowingCase(caseId: number): boolean {
    const view = this.supportView();
    return view.kind === 'thread' && view.caseId === caseId;
  }

  /** 新案件放最前面；已經有的整件換掉，但未讀數留本機的：案件推播只管狀態，未讀由訊息推播在算 */
  private upsertCase(incoming: DriverCaseDto): void {
    this.driverCases.update((cases) => {
      if (!cases.some((item) => item.id === incoming.id)) {
        return [incoming, ...cases];
      }
      return cases.map((item) => (item.id === incoming.id ? {...incoming, unreadCount: item.unreadCount} : item));
    });
  }

  /**
   * 案件 API 的錯誤訊息。404 只會是網址不存在（後端還沒部署案件 API）：
   * GlobalExceptionHandler 會回「找不到檔案」，對司機沒有意義，改用 fallback
   */
  private getCaseErrorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse && error.status === 404) {
      return fallback;
    }
    return this.getErrorMessage(error, fallback);
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
    // 案件建立、接收、結案：整件換掉。接收後 acceptedAt 有值，畫面從「等待回覆」變「處理中」
    if (push.type === 'CASE_OPENED' || push.type === 'CASE_ACCEPTED' || push.type === 'CASE_CLOSED') {
      if (push.exceptionCase) {
        this.upsertCase(push.exceptionCase);
      }
      return;
    }

    // 帶 exceptionCaseId 的是案件那一串，不能合併進一般對話，不然案件的回覆會出現在一般對話裡；
    // 沒帶的才是一般對話
    const caseId = push.type === 'MESSAGE' ? push.message?.exceptionCaseId : push.exceptionCaseId;
    if (caseId != null) {
      this.handleCasePush(caseId, push);
      return;
    }

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

  /**
   * 案件那一串的推播。正看著這件案件就直接放進畫面（標已讀交給 markViewingCaseRead），
   * 沒在看就只在清單上把未讀加一，等司機點進去再載整串。
   */
  private handleCasePush(caseId: number, push: DriverMessagePushDto): void {
    const isViewing = this.viewingCaseId() === caseId;
    if (push.type === 'MESSAGE' && push.message) {
      const message = push.message;
      if (isViewing) {
        this.caseMessages.set(mergeMessagesById(this.caseMessages(), [message]));
        this.caseThreadState.set('ready');
      }
      if (message.senderType === 'ADMIN') {
        // 後端規定先接收才能回覆，所以有回覆就一定已經接收；CASE_ACCEPTED 推播漏掉時，靠這裡補上 acceptedAt
        this.driverCases.update((cases) =>
          cases.map((item) =>
            item.id !== caseId
              ? item
              : {
                  ...item,
                  acceptedAt: item.acceptedAt ?? message.createdAt,
                  unreadCount: isViewing ? item.unreadCount : item.unreadCount + 1,
                },
          ),
        );
      }
      return;
    }

    if (push.type === 'READ' && push.readSenderType && push.readAt) {
      const readAt = push.readAt;
      const readSenderType = push.readSenderType;
      if (this.isShowingCase(caseId)) {
        this.caseMessages.update((messages) =>
          messages.map((message) =>
            message.senderType === readSenderType && !message.readAt ? {...message, readAt} : message,
          ),
        );
      }
      // ADMIN＝司機自己在別台裝置讀了回覆，這件的紅點也要歸零
      if (readSenderType === 'ADMIN') {
        this.driverCases.update((cases) =>
          cases.map((item) => (item.id === caseId ? {...item, unreadCount: 0} : item)),
        );
      }
    }
  }

  private mergeChatMessages(incoming: DriverMessageDto[]): DriverMessageDto[] {
    return mergeMessagesById(this.chatMessages(), incoming);
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

    if (tab === 'tasks') {
      // 路線與商品明細可能在司機登入後才發布或補齊；重新進任務頁要看到最新資料。
      this.loadTodayTasks(true);
    }

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
    if (!this.canNavigate(stop)) {
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
    return this.hasCoordinates(stop) && navigationReadyForOrder(this.todayTasks(), stop.orderId);
  }

  protected canStartNavigation(): boolean {
    const selected = this.selectedTask();
    return selected !== null && this.canNavigate(selected.stop);
  }

  protected navigationUnavailableLabel(stop: DriverTaskStop): string {
    if (!this.hasCoordinates(stop)) {
      return '無座標';
    }
    return this.pendingLoadingCount() > 0 || stop.orderStatus === 'CONFIRMED'
      ? '先點交'
      : '不可導航';
  }

  protected navigationUnavailableReason(stop: DriverTaskStop): string {
    if (!this.hasCoordinates(stop)) {
      return '此站點尚未設定座標';
    }
    if (this.pendingLoadingCount() > 0) {
      return `尚有 ${this.pendingLoadingCount()} 筆訂單未完成倉庫點交，請全部點交後再導航`;
    }
    return '這張訂單目前不可導航';
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
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);
  }

  protected closeDeliveryAction(): void {
    this.activeDeliveryOrderId.set(null);
    this.deliveryNotes.set('');
    this.deliveryPhoto.set(null);
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

  protected isLoadingActionOpen(stop: DriverTaskStop): boolean {
    return this.activeLoadingOrderId() === stop.orderId;
  }

  protected setInspectionReady(routeId: number, passed: boolean): void {
    this.inspectionReady.update((ready) => ({...ready, [routeId]: passed}));
  }

  protected openLoadingAction(stop: DriverTaskStop): void {
    if (!this.canLoad(stop)) {
      return;
    }
    // 按鈕沒通過時本來就不能按，這裡再擋一次；後端 DeliveryService.load 也會擋
    const route = this.todayTasks()?.routes.find((item) => item.stops.some((routeStop) => routeStop.orderId === stop.orderId));
    if (!route || !this.inspectionReady()[route.routeId]) {
      this.taskActionError.set('請先通過這條路線的出車前安全檢查。');
      return;
    }

    this.closeDeliveryAction();
    this.activeLoadingOrderId.set(stop.orderId);
    this.loadingNotes.set('');
    this.loadingSummaryChecked.set(false);
    this.loadingItemForms.set(
      Object.fromEntries((stop.items ?? []).map((item) => [item.id, {
        checked: false,
      }])),
    );
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);
  }

  protected closeLoadingAction(): void {
    this.activeLoadingOrderId.set(null);
    this.loadingNotes.set('');
    this.loadingSummaryChecked.set(false);
    this.reportingLoadingItemIds.set([]);
    this.loadingItemForms.set({});
    this.taskActionError.set(null);
  }

  protected updateLoadingNotes(event: Event): void {
    this.loadingNotes.set((event.target as HTMLTextAreaElement).value);
  }

  protected updateLoadingSummaryChecked(event: Event): void {
    this.loadingSummaryChecked.set((event.target as HTMLInputElement).checked);
  }

  protected loadingItemForm(itemId: number): LoadingItemForm {
    return this.loadingItemForms()[itemId] ?? {checked: false};
  }

  protected checkedLoadingItemCount(stop: DriverTaskStop): number {
    return stop.items.filter((item) => this.loadingItemForm(item.id).checked).length;
  }

  protected loadingChecklistComplete(stop: DriverTaskStop): boolean {
    return stop.items.length > 0
      ? this.checkedLoadingItemCount(stop) === stop.items.length
      : this.loadingSummaryChecked();
  }

  protected updateLoadingItemChecked(itemId: number, event: Event): void {
    if (this.isTaskSubmitting()) {
      return;
    }
    const checked = (event.target as HTMLInputElement).checked;
    this.loadingItemForms.update((items) => ({
      ...items,
      [itemId]: {...this.loadingItemForm(itemId), checked},
    }));
  }

  protected handleLoadingMismatch(stop: DriverTaskStop): void {
    if (!this.canLoad(stop) || this.isTaskSubmitting() || !stop.items.length) {
      return;
    }
    const items = stop.items.filter((item) => this.loadingItemForm(item.id).checked);
    if (!items.length) {
      this.taskActionError.set('請先勾選點交不符的商品。');
      return;
    }
    this.reportLoadingMismatch(stop, items);
  }

  protected reportLoadingMismatch(stop: DriverTaskStop, items: readonly DriverTaskOrderItem[]): void {
    if (!this.canLoad(stop) || this.isTaskSubmitting() || !items.length) {
      return;
    }
    const route = this.todayTasks()?.routes.find((task) => task.stops.some((row) => row.orderId === stop.orderId));
    if (!route || !this.inspectionReady()[route.routeId]) {
      this.taskActionError.set('請先通過這條路線的出車前安全檢查。');
      return;
    }
    this.reportingLoadingItemIds.set(items.map((item) => item.id));
    this.isTaskSubmitting.set(true);
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);
    this.operations.reportLoadingMismatch({
      orderId: stop.orderId,
      orderItemIds: items.map((item) => item.id),
      notes: this.loadingNotes().trim() || undefined,
    }).subscribe({
      next: (response) => {
        this.applyDeliveryResponse(response);
        this.closeLoadingAction();
        const rebuilt = response.followUpOrderNumber
          ? `已重建訂單 ${response.followUpOrderNumber}，待主管確認。`
          : '請主管重新建單。';
        const products = items.map((item) => `${item.itemName}（應點 ${item.expectedQuantity} ${item.unit}）`).join('、');
        this.taskActionMessage.set(`已送出倉庫點交不符異常：${products}。原單停止配送，${rebuilt}`);
        this.reportingLoadingItemIds.set([]);
        this.isTaskSubmitting.set(false);
      },
      error: (error: unknown) => {
        this.reportingLoadingItemIds.set([]);
        this.isTaskSubmitting.set(false);
        this.taskActionError.set(this.getErrorMessage(error, '點交不符回報失敗，尚未送出異常案件，請再試。'));
      },
    });
  }

  protected submitLoading(stop: DriverTaskStop): void {
    if (!this.canLoad(stop) || this.isTaskSubmitting()) {
      return;
    }

    if (stop.items.length === 0 && !this.loadingSummaryChecked()) {
      this.taskActionError.set('請先核對訂單品項摘要與應點箱數，並勾選確認。');
      return;
    }
    const items = stop.items ?? [];
    for (const item of items) {
      if (!this.loadingItemForm(item.id).checked) {
        this.taskActionError.set(`請先勾選並核對商品「${item.itemName}」。`);
        return;
      }
    }

    this.isTaskSubmitting.set(true);
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);

    const notes = this.loadingNotes().trim() || undefined;
    this.operations.loading({
      orderId: stop.orderId,
      loadedBoxCount: stop.expectedBoxCount,
      notes,
      items: items.length === 0 ? undefined : items.map((item) => ({
        orderItemId: item.id,
        checked: true,
        loadedQuantity: item.expectedQuantity,
      })),
    }).subscribe({
      next: (response) => {
        this.applyDeliveryResponse(response);
        this.closeLoadingAction();
        if (response.orderStatus === 'LOADED') {
          const remaining = this.pendingLoadingCount();
          this.taskActionMessage.set(remaining > 0
            ? `這張單已完成點交，還有 ${remaining} 筆訂單待點交。`
            : '全部訂單已完成點交，可以開始導航。');
        } else {
          const followUp = response.followUpOrderNumber
            ? `，重建單 ${response.followUpOrderNumber} 待主管確認`
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

  protected updateEndMileage(event: Event): void {
    this.endMileageReading.set((event.target as HTMLInputElement).value);
  }

  protected canRecordMileage(): boolean {
    return this.isWorkingOrOvertime(this.attendance()?.status);
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
    const {from, to} = this.monthRange(this.scheduleMonth());
    this.scheduleViewState.set('loading');
    this.scheduleError.set(null);

    this.operations.getPublishedShifts(from, to).subscribe({
      next: (shifts) => {
        this.publishedShifts.set(
          [...shifts].sort((left, right) => left.workDate.localeCompare(right.workDate)),
        );
        this.scheduleViewState.set(shifts.length ? 'ready' : 'empty');
        // 月曆不會因為資料變了自己重畫格子的 class，要手動叫它重畫
        this.scheduleCalendar()?.updateTodaysDate();
      },
      error: (error: unknown) => {
        this.publishedShifts.set([]);
        this.scheduleCalendar()?.updateTodaysDate();
        this.scheduleViewState.set('error');
        this.scheduleError.set(this.getErrorMessage(error, '無法取得已發布班表。'));
      },
    });
  }

  private loadTodayTasks(silent = false): void {
    if (!silent) {
      this.taskViewState.set('loading');
    }
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
    this.leaveListAvailable.set(false);
    this.operations.getLeaveRequests().subscribe({
      next: (requests) => {
        this.leaveRequests.set(
          [...requests].sort((left, right) => right.requestedAt.localeCompare(left.requestedAt)),
        );
        this.leaveListAvailable.set(true);
        this.isLeaveListLoading.set(false);
        this.refreshScheduleCalendar();
      },
      error: (error: unknown) => {
        this.leaveListAvailable.set(false);
        this.leaveError.set(this.getErrorMessage(error, '無法取得一般請假結果。'));
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
      this.mileageError.set('請輸入行車紀錄器里程。');
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
      new maplibregl.NavigationControl({showCompass: true, showZoom: true, visualizePitch: false}),
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
      this.resetNavigationSteps([]);
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
        // 偏航重算也會走到這裡：轉彎清單和「目前第幾步」要跟新路線一起換掉，不然會提示舊路線的轉彎
        this.resetNavigationSteps(res.steps ?? []);
        if (this.isNavigating() && this.currentMapLocation) {
          this.updateManeuverProgress(this.currentMapLocation);
        }
        this.renderNavigationMap(animate);
      },
      error: (res) => {
        this.resetNavigationSteps([]);
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
    if (this.isNavigating() || !this.canStartNavigation()) {
      return;
    }

    this.isAttendanceSheetExpanded.set(false);
    this.attendanceSheetDragOffset.set(0);
    this.isNavigating.set(true);
    this.offRouteStreak = 0;
    // 從頭開始提示：預覽時可能已經走了一段，重新從第一個轉彎算，也讓第一句語音重新念
    this.resetNavigationSteps(this.navigationSteps());
    if (this.currentMapLocation) {
      this.showMapLocation(this.currentMapLocation, true, this.lastMovementHeading);
      // 第一句語音要在按下「開始導航」的這次點擊裡念：iOS Safari 只允許使用者操作觸發的第一次發聲
      this.updateManeuverProgress(this.currentMapLocation);
    } else {
      this.focusNavigationOrigin();
      this.speak('開始導航');
    }
    this.renderNavigationMap(false);
    void this.requestWakeLock();
  }

  protected stopNavigation(): void {
    this.isNavigating.set(false);
    this.offRouteStreak = 0;
    this.cancelSpeech();
    this.renderNavigationMap(false);
    void this.releaseWakeLock();
  }

  protected toggleVoiceGuidance(): void {
    const enabled = !this.voiceGuidanceEnabled();
    this.voiceGuidanceEnabled.set(enabled);
    try {
      localStorage.setItem(VOICE_GUIDANCE_STORAGE_KEY, enabled ? 'on' : 'off');
    } catch {
      // 存不了（無痕模式）就只在這次有效，不影響導航
    }
    if (!enabled) {
      this.cancelSpeech();
    }
  }

  protected maneuverIconFor(step: GpsRouteStep): string {
    return maneuverIcon(step);
  }

  protected maneuverDistanceLabel(): string {
    const distance = this.distanceToManeuver();
    return distance === null ? '定位中' : formatManeuverDistance(distance);
  }

  /** 換新路線（或清空）時從頭來：第 0 步「出發」就在起點，直接從第 1 步提示 */
  private resetNavigationSteps(steps: GpsRouteStep[]): void {
    this.navigationSteps.set(steps);
    this.currentStepIndex.set(steps.length > 1 ? 1 : 0);
    this.distanceToManeuver.set(null);
    this.hasArrived.set(false);
    this.closestToStep = Infinity;
    this.announcedStepIndex = -1;
    this.announcedNearStepIndex = -1;
  }

  /**
   * 每收到一次導航中的 GPS 位置：算到下一個轉彎點的距離，轉過了就換下一步，並在該念的時候念語音。
   * 要在 routeLatLng 切掉走過的部分之後呼叫，measureStepProgress 才判斷得出轉彎點是不是已經被切掉。
   */
  private updateManeuverProgress(here: MapPosition): void {
    const steps = this.navigationSteps();
    if (steps.length === 0 || this.hasArrived()) {
      return;
    }

    let index = this.currentStepIndex();
    // 一次 GPS 更新可能跨過好幾個很近的轉彎（連續轉彎、GPS 跳點），所以一路往後推到還沒轉的那一步
    for (;;) {
      const step = steps[index];
      const target: MapPosition = [step.lng, step.lat];
      const {alongRouteMeters, onRemainingRoute} = measureStepProgress(here, this.routeLatLng, target);
      this.closestToStep = Math.min(this.closestToStep, distanceInMeters(here, target));

      if (step.type === 'arrive') {
        this.distanceToManeuver.set(alongRouteMeters);
        if (alongRouteMeters <= ARRIVE_METERS) {
          this.hasArrived.set(true);
          this.speak('已抵達目的地');
          return;
        }
        this.announceManeuver(index, step, alongRouteMeters);
        return;
      }

      const isLastStep = index >= steps.length - 1;
      if (!isLastStep && isStepPassed(distanceInMeters(here, target), this.closestToStep, onRemainingRoute)) {
        index++;
        this.currentStepIndex.set(index);
        this.closestToStep = Infinity;
        continue;
      }

      this.distanceToManeuver.set(alongRouteMeters);
      this.announceManeuver(index, step, alongRouteMeters);
      return;
    }
  }

  /**
   * 每一步最多念兩次：
   * 1. 剛變成「下一個轉彎」時念一次，遠的話帶距離：「300 公尺後，左轉進入復興一路」
   * 2. 開到 80 公尺內再提醒一次：「即將左轉進入復興一路」；直行不用做事，不念
   * 剛變成下一步時就已經在 80 公尺內（兩個轉彎很近），直接念提示本身，不再另外念「即將」
   */
  private announceManeuver(index: number, step: GpsRouteStep, distance: number): void {
    if (this.announcedStepIndex !== index) {
      this.announcedStepIndex = index;
      if (distance > NEAR_ANNOUNCE_METERS) {
        this.speak(`${formatManeuverDistance(distance)}後，${step.instruction}`);
      } else {
        this.announcedNearStepIndex = index;
        this.speak(step.instruction);
      }
      return;
    }

    if (distance <= NEAR_ANNOUNCE_METERS && this.announcedNearStepIndex !== index && !isStraightThrough(step)) {
      this.announcedNearStepIndex = index;
      this.speak(`即將${step.instruction}`);
    }
  }

  /** 用瀏覽器內建的語音合成念提示；新的一句會打斷還沒念完的舊句，免得排隊念過時的距離 */
  private speak(text: string): void {
    if (!this.voiceGuidanceEnabled() || typeof window === 'undefined' || !('speechSynthesis' in window)) {
      return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-TW';
    window.speechSynthesis.speak(utterance);
  }

  private cancelSpeech(): void {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
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
    // 偏離路線時也更新：距離會變大，等上面的偏航重算換新路線；轉彎清單在 fetchRoute 裡跟著換
    this.updateManeuverProgress(here);
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

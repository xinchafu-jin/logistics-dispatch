import {HttpErrorResponse} from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import {Router} from '@angular/router';
import {MatIconModule} from '@angular/material/icon';
import type * as maplibregl from 'maplibre-gl';
import {Observable, single} from 'rxjs';
import {DriverAuthService} from '../../core/auth/driver-auth.service';
import {
  clearStoredMapLocation,
  readStoredMapLocation,
  saveStoredMapLocation,
} from '../../core/location/driver-map-location.storage';
import {DriverGpsTrackingService} from '../../core/services/driver-gps-tracking.service';
import {
  AttendanceRecordDto,
  DeliveryRecordResponse,
  EmergencyLeaveResponse,
  DriverProfileDto,
  DriverRouteTask,
  DriverShiftDto,
  DriverTaskStop,
  DriverTaskOrderStatus,
  DriverTasksResponse,
} from '../../core/services/driver-operations.models';
import {DriverOperationsService} from '../../core/services/driver-operations.service';
import {DriverWeather, DriverWeatherService} from '../../core/services/driver-weather.service';
import {BrandLogo} from '../../shared/ui/brand-logo/brand-logo';

type AttendanceViewState = 'loading' | 'not-clocked-in' | 'ready' | 'error';
type DriverTab = 'map' | 'tasks' | 'profile' | 'schedule';
type TaskViewState = 'loading' | 'ready' | 'empty' | 'error';
type ScheduleViewState = 'loading' | 'ready' | 'empty' | 'error';
type NavigationRouteState = 'idle' | 'loading' | 'ready' | 'error';

interface DriverTaskSelection {
  route: DriverRouteTask;
  stop: DriverTaskStop;
}

type MapPosition = [lng: number, lat: number];
const DRIVER_ROUTE_SOURCE_ID = 'driver-navigation-route';
const DRIVER_ROUTE_LAYER_ID = 'driver-navigation-route-line';

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

//todo 之後看是否加進階選項參數化
const OFF_ROUTE_METERS = 70;
const OFF_ROUTE_STREAK = 3;
// cd
const RECALC_COOLDOWN_MS = 15_000;

@Component({
  selector: 'app-driver-dashboard',
  imports: [
    MatIconModule,
    BrandLogo,
  ],
  templateUrl: './driver-dashboard.html',
  styleUrl: './driver-dashboard.scss',
})
export class DriverDashboard implements AfterViewInit, OnDestroy {
  @ViewChild('driverMap') private driverMapElement?: ElementRef<HTMLElement>;

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
  protected readonly scheduleMonthLabel = computed(() =>
    new Intl.DateTimeFormat('zh-TW', {year: 'numeric', month: 'long'}).format(
      this.scheduleMonth(),
    ),
  );
  protected readonly todayTasks = signal<DriverTasksResponse | null>(null);
  protected readonly taskViewState = signal<TaskViewState>('loading');
  protected readonly taskError = signal<string | null>(null);
  protected readonly selectedTask = signal<DriverTaskSelection | null>(null);
  protected readonly activeDeliveryOrderId = signal<number | null>(null);
  protected readonly deliveryNotes = signal('');
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

  protected readonly gpsTracking = inject(DriverGpsTrackingService);

  private readonly authService = inject(DriverAuthService);
  private readonly operations = inject(DriverOperationsService);
  private readonly weatherService = inject(DriverWeatherService);
  private readonly router = inject(Router);
  private breakTimer: ReturnType<typeof setInterval> | null = null;
  private attendanceRefreshTimer: ReturnType<typeof setInterval> | null = null;
  private maplibre: typeof import('maplibre-gl') | null = null;
  private driverMap: maplibregl.Map | null = null;
  private currentMapLocation: MapPosition | null = null;
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
  }

  ngAfterViewInit(): void {
    void this.initializeMap();
  }

  ngOnDestroy(): void {
    this.stopNavigation();
    this.clearBreakTimer();
    this.clearAttendanceRefreshTimer();
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

  protected canMarkArrived(stop: DriverTaskStop): boolean {
    return stop.orderStatus === 'CONFIRMED';
  }

  protected canCompleteDelivery(stop: DriverTaskStop): boolean {
    return stop.orderStatus === 'IN_DELIVERY';
  }

  protected taskStatusLabel(status: DriverTaskOrderStatus): string {
    const labels: Record<DriverTaskOrderStatus, string> = {
      PENDING_CONFIRM: '待確認',
      CONFIRMED: '待配送',
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
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);
  }

  protected closeDeliveryAction(): void {
    this.activeDeliveryOrderId.set(null);
    this.deliveryNotes.set('');
    this.taskActionError.set(null);
  }

  protected updateDeliveryNotes(event: Event): void {
    this.deliveryNotes.set((event.target as HTMLTextAreaElement).value);
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
      () =>
        this.operations.deliver({
          orderId: stop.orderId,
          boxCount: stop.expectedBoxCount,
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
      () =>
        this.operations.noSignature({
          orderId: stop.orderId,
          notes: this.optionalDeliveryNotes(),
        }),
      '已登記無人簽收，後端已建立待處理異常。',
    );
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

  protected changeScheduleMonth(offset: number): void {
    const current = this.scheduleMonth();
    const next = new Date(current.getFullYear(), current.getMonth() + offset, 1);
    this.scheduleMonth.set(next);
    this.loadPublishedShifts();
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
    if (this.isNavigating()) {
      this.applyNavigationPosition(location);
    } else {
      this.showMapLocation(location, animate);
    }
    this.saveMapLocation(location);
    this.mapLocationStatus.set('已定位至目前位置');
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
      },
      error: (error: unknown) => {
        this.publishedShifts.set([]);
        this.scheduleViewState.set('error');
        this.scheduleError.set(this.getErrorMessage(error, '無法取得已發布班表。'));
      },
    });
  }

  private loadTodayTasks(): void {
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
    action: () => Observable<DeliveryRecordResponse>,
    successMessage: string,
  ): void {
    this.isTaskSubmitting.set(true);
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);

    action().subscribe({
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

  private applyDeliveryResponse(response: DeliveryRecordResponse): void {
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
    this.maplibre = maplibregl;
    this.driverMap = new maplibregl.Map({
      container: mapElement,
      center: [120.3014, 22.6273],
      zoom: 12,
      maxZoom: 19,
      attributionControl: {},
      style: {
        version: 8,
        sources: {
          openStreetMap: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>',
          },
        },
        layers: [
          {
            id: 'openStreetMap',
            type: 'raster',
            source: 'openStreetMap',
          },
        ],
      },
    });

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

  private showMapLocation(location: MapPosition, animate: boolean): void {
    const maplibregl = this.maplibre;
    if (!maplibregl || !this.driverMap) {
      return;
    }

    this.currentMapLocation = location;

    if (this.currentLocationMarker) {
      this.currentLocationMarker.setLngLat(location);
    } else {
      this.currentLocationMarker = new maplibregl.Marker({
        element: this.createMapMarkerElement('driver-location-marker', 'A'),
        anchor: 'center',
      })
        .setLngLat(location)
        .addTo(this.driverMap);
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

    if (this.routeLatLng.length === 0) {
      this.driverMap.fitBounds(this.mapBounds(this.currentMapLocation, destination), {
        padding: {top: 94, right: 24, bottom: 310, left: 24},
        duration: animate ? 500 : 0,
        maxZoom: 15,
      });
      return;
    }
    this.setNavigationRoute(this.routeLatLng);
    if (this.isNavigating()) {
      // 導航中：鏡頭平移跟著司機，不改縮放，避免畫面一直跳
      this.driverMap.easeTo({center: this.currentMapLocation, duration: 0});
    } else {
      // 預覽中：框住整條路線給司機看全貌
      this.driverMap.fitBounds(this.mapBounds(this.currentMapLocation, destination), {
        padding: {top: 94, right: 24, bottom: 310, left: 24},
        duration: animate ? 500 : 0,
        maxZoom: 15,
      });
    }
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
    this.renderNavigationMap(false);
    this.focusNavigationOrigin();
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

  private applyNavigationPosition(here: MapPosition): void {
    if (this.routeLatLng.length === 0) {
      this.showMapLocation(here, false);
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
    this.showMapLocation(here, false);

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

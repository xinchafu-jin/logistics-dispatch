import { HttpErrorResponse } from '@angular/common/http';
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
import { Router } from '@angular/router';
import {
  LucideCalendarDays,
  LucideChevronLeft,
  LucideChevronRight,
  LucideChevronDown,
  LucideChevronUp,
  LucideCircleCheck,
  LucideCircleStop,
  LucideCloudFog,
  LucideCloudLightning,
  LucideCloudRain,
  LucideCloudSnow,
  LucideCloudSun,
  LucideCoffee,
  LucideGauge,
  LucideListTodo,
  LucideLocateFixed,
  LucideMap,
  LucideMapPin,
  LucideMoon,
  LucideNavigation,
  LucidePackageCheck,
  LucidePlay,
  LucideSun,
  LucideTriangleAlert,
  LucideUserRound,
} from '@lucide/angular';
import * as L from 'leaflet';
import { Observable } from 'rxjs';
import { DriverAuthService } from '../../core/auth/driver-auth.service';
import {
  clearStoredMapLocation,
  readStoredMapLocation,
  saveStoredMapLocation,
} from '../../core/location/driver-map-location.storage';
import { DriverGpsTrackingService } from '../../core/services/driver-gps-tracking.service';
import {
  AttendanceRecordDto,
  DeliveryRecordResponse,
  EmergencyLeaveResponse,
  DriverRouteTask,
  DriverShiftDto,
  DriverTaskStop,
  DriverTaskOrderStatus,
  DriverTasksResponse,
  GpsRouteResponse,
} from '../../core/services/driver-operations.models';
import { DriverOperationsService } from '../../core/services/driver-operations.service';
import { DriverWeather, DriverWeatherService } from '../../core/services/driver-weather.service';
import { BrandLogo } from '../../shared/ui/brand-logo/brand-logo';

type AttendanceViewState = 'loading' | 'not-clocked-in' | 'ready' | 'error';
type DriverTab = 'map' | 'tasks' | 'profile' | 'schedule';
type TaskViewState = 'loading' | 'ready' | 'empty' | 'error';
type ScheduleViewState = 'loading' | 'ready' | 'empty' | 'error';
type NavigationRouteState = 'idle' | 'loading' | 'ready' | 'error';

interface DriverTaskSelection {
  route: DriverRouteTask;
  stop: DriverTaskStop;
}

@Component({
  selector: 'app-driver-dashboard',
  imports: [
    BrandLogo,
    LucideCalendarDays,
    LucideChevronLeft,
    LucideChevronRight,
    LucideChevronDown,
    LucideChevronUp,
    LucideCircleCheck,
    LucideCircleStop,
    LucideCloudFog,
    LucideCloudLightning,
    LucideCloudRain,
    LucideCloudSnow,
    LucideCloudSun,
    LucideCoffee,
    LucideGauge,
    LucideListTodo,
    LucideLocateFixed,
    LucideMap,
    LucideMapPin,
    LucideMoon,
    LucideNavigation,
    LucidePackageCheck,
    LucidePlay,
    LucideSun,
    LucideTriangleAlert,
    LucideUserRound,
  ],
  templateUrl: './driver-dashboard.html',
  styleUrl: './driver-dashboard.scss',
})
export class DriverDashboard implements AfterViewInit, OnDestroy {
  @ViewChild('driverMap') private driverMapElement?: ElementRef<HTMLElement>;

  protected readonly user = inject(DriverAuthService).user;
  protected readonly weather = signal<DriverWeather | null>(null);
  protected readonly weatherUnavailable = signal(false);
  protected readonly isDarkTheme = signal(this.readSavedTheme() === 'dark');
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
    new Intl.DateTimeFormat('zh-TW', { year: 'numeric', month: 'long' }).format(
      this.scheduleMonth(),
    ),
  );
  protected readonly todayTasks = signal<DriverTasksResponse | null>(null);
  protected readonly taskViewState = signal<TaskViewState>('loading');
  protected readonly taskError = signal<string | null>(null);
  protected readonly selectedTask = signal<DriverTaskSelection | null>(null);
  protected readonly activeDeliveryOrderId = signal<number | null>(null);
  protected readonly deliveryPhotoUrl = signal('');
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
  private driverMap: L.Map | null = null;
  private currentMapLocation: L.LatLng | null = null;
  private currentLocationMarker: L.Marker | null = null;
  private destinationMarker: L.Marker | null = null;
  private navigationLine: L.Polyline | null = null;
  private navigationRoutePath: L.LatLng[] | null = null;
  private navigationRouteOrigin: L.LatLng | null = null;
  private navigationRouteDestination: L.LatLng | null = null;
  private navigationRouteRequestId = 0;
  private isNavigationRouteLoading = false;
  private mapLocationWatchId: number | null = null;
  private hasFocusedCurrentMapLocation = false;
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
  }

  ngAfterViewInit(): void {
    this.initializeMap();
    this.restoreMapLocation();
    this.startMapLocationWatch();
  }

  ngOnDestroy(): void {
    this.clearBreakTimer();
    this.gpsTracking.stop();
    this.stopMapLocationWatch();
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

  protected signOut(): void {
    this.clearBreakTimer();
    this.gpsTracking.stop();
    this.stopMapLocationWatch();
    clearStoredMapLocation();
    this.authService.logout();
    void this.router.navigateByUrl('/login');
  }

  protected toggleTheme(): void {
    const nextTheme = this.isDarkTheme() ? 'light' : 'dark';
    this.isDarkTheme.set(nextTheme === 'dark');

    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('logistics-dispatch.driver-theme', nextTheme);
    }
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
        this.driverMap?.invalidateSize();
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

    this.selectedTask.set({ route, stop });
    this.setActiveTab('map');
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
    this.deliveryPhotoUrl.set('');
    this.deliveryNotes.set('');
    this.taskActionError.set(null);
    this.taskActionMessage.set(null);
  }

  protected closeDeliveryAction(): void {
    this.activeDeliveryOrderId.set(null);
    this.deliveryPhotoUrl.set('');
    this.deliveryNotes.set('');
    this.taskActionError.set(null);
  }

  protected updateDeliveryPhoto(event: Event): void {
    this.deliveryPhotoUrl.set((event.target as HTMLInputElement).value);
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

    this.operations.arrive({ orderId: stop.orderId }).subscribe({
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

    const photo = this.deliveryPhotoUrl().trim();
    if (!photo) {
      this.taskActionError.set('請貼上照片上傳服務回傳的憑證網址。');
      return;
    }

    this.submitDeliveryResult(
      () =>
        this.operations.deliver({
          orderId: stop.orderId,
          boxCount: stop.expectedBoxCount,
          photo,
          notes: this.optionalDeliveryNotes(),
        }),
      '交貨已完成。',
    );
  }

  protected reportNoSignature(stop: DriverTaskStop): void {
    if (!this.canCompleteDelivery(stop) || this.isTaskSubmitting()) {
      return;
    }

    const photo = this.deliveryPhotoUrl().trim();
    if (!photo) {
      this.taskActionError.set('請貼上現場照片上傳服務回傳的憑證網址。');
      return;
    }

    this.submitDeliveryResult(
      () =>
        this.operations.noSignature({
          orderId: stop.orderId,
          photo,
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
    return this.attendance()?.status === 'WORKING';
  }

  protected submitStartMileage(): void {
    const odometer = this.readOdometer(this.startMileageReading());
    if (odometer === null || this.isMileageSubmitting()) {
      return;
    }

    this.isMileageSubmitting.set(true);
    this.mileageError.set(null);
    this.mileageMessage.set(null);
    this.operations.startMileage({ odometer }).subscribe({
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
    this.operations.endMileage({ odometer }).subscribe({
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

  protected startInAppNavigation(): void {
    if (!this.destinationLocation()) {
      return;
    }

    this.activeTab.set('map');
    setTimeout(() => {
      this.driverMap?.invalidateSize();
      this.renderNavigationMap(true);
      this.requestNavigationRoute(true);
    }, 0);
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
        if (!this.currentMapLocation) {
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
    const location = L.latLng(position.coords.latitude, position.coords.longitude);
    this.showMapLocation(location, animate);
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

  private loadAttendance(): void {
    this.attendanceViewState.set('loading');
    this.attendanceError.set(null);

    this.operations.getTodayAttendance().subscribe({
      next: (attendance) => this.applyAttendance(attendance),
      error: (error: unknown) => {
        if (this.isNotClockedInError(error)) {
          this.attendance.set(null);
          this.attendanceViewState.set('not-clocked-in');
          this.gpsTracking.stop();
          return;
        }

        this.attendanceViewState.set('error');
        this.attendanceError.set(this.getErrorMessage(error, '無法取得今日出勤狀態。'));
        this.gpsTracking.stop();
      },
    });
  }

  private loadPublishedShifts(): void {
    const { from, to } = this.monthRange(this.scheduleMonth());
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
              ? { ...stop, orderStatus: response.orderStatus }
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
      this.startBreakCountdown();
      return;
    }

    this.clearBreakTimer();
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

  private initializeMap(): void {
    const mapElement = this.driverMapElement?.nativeElement;
    if (!mapElement) {
      return;
    }

    this.driverMap = L.map(mapElement, { zoomControl: false }).setView([22.6273, 120.3014], 12);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19,
    }).addTo(this.driverMap);
    this.driverMap.attributionControl.setPrefix(false);
  }

  private restoreMapLocation(): void {
    const storedLocation = readStoredMapLocation();
    if (!storedLocation) {
      return;
    }

    this.showMapLocation(L.latLng(storedLocation.lat, storedLocation.lng), false);
    this.mapLocationStatus.set('已顯示上次定位，正在更新...');
  }

  private showMapLocation(location: L.LatLng, animate: boolean): void {
    this.currentMapLocation = location;

    if (this.currentLocationMarker) {
      this.currentLocationMarker.setLatLng(location);
    } else {
      this.currentLocationMarker = L.marker(location, {
        icon: L.divIcon({
          className: 'driver-location-marker',
          html: '<span>A</span>',
          iconSize: [28, 28],
          iconAnchor: [14, 14],
        }),
        interactive: false,
      }).addTo(this.driverMap!);
    }

    this.renderNavigationMap(animate);
  }

  private hasStoredMapLocation(): boolean {
    return readStoredMapLocation() !== null;
  }

  private saveMapLocation(location: L.LatLng): void {
    saveStoredMapLocation({ lat: location.lat, lng: location.lng });
  }

  private renderNavigationMap(animate: boolean): void {
    if (!this.driverMap) {
      return;
    }

    this.destinationMarker?.remove();
    this.destinationMarker = null;
    this.navigationLine?.remove();
    this.navigationLine = null;

    const destination = this.destinationLocation();
    if (!destination) {
      this.clearNavigationRoute();
      if (this.currentMapLocation) {
        this.focusCurrentMapLocation(animate);
      }
      return;
    }

    this.destinationMarker = L.marker(destination, {
      icon: L.divIcon({
        className: 'driver-destination-marker',
        html: '<span>B</span>',
        iconSize: [34, 34],
        iconAnchor: [17, 17],
      }),
      title: this.destinationName(),
      zIndexOffset: 1000,
    })
      .addTo(this.driverMap)
      .bindTooltip(this.destinationName(), {
        className: 'driver-destination-tooltip',
        direction: 'top',
        offset: [0, -18],
        permanent: true,
      });

    if (!this.currentMapLocation) {
      this.driverMap.setView(destination, 15, { animate });
      return;
    }

    const routePath = this.navigationRouteDestination?.equals(destination)
      ? this.navigationRoutePath
      : null;
    this.navigationLine = L.polyline(routePath ?? [this.currentMapLocation, destination], {
      color: '#54cfae',
      weight: 4,
      opacity: 0.82,
      dashArray: routePath ? undefined : '8 8',
    }).addTo(this.driverMap);
    this.focusCurrentMapLocation(animate);
    this.requestNavigationRoute();
  }

  private requestNavigationRoute(force = false): void {
    const origin = this.currentMapLocation;
    const destination = this.destinationLocation();
    if (!origin || !destination || this.isNavigationRouteLoading) {
      return;
    }

    const routeDestinationChanged = !this.navigationRouteDestination?.equals(destination);
    const routeOriginMoved = !this.navigationRouteOrigin || this.navigationRouteOrigin.distanceTo(origin) >= 80;
    if (!force && this.navigationRoutePath && !routeDestinationChanged && !routeOriginMoved) {
      return;
    }
    if (!force && this.navigationRouteState() === 'error' && !routeDestinationChanged && !routeOriginMoved) {
      return;
    }

    this.isNavigationRouteLoading = true;
    this.navigationRouteState.set('loading');
    const requestId = ++this.navigationRouteRequestId;
    this.operations
      .getNavigationRoute({
        fromLat: origin.lat,
        fromLng: origin.lng,
        toLat: destination.lat,
        toLng: destination.lng,
      })
      .subscribe({
        next: (route) => this.applyNavigationRoute(requestId, origin, destination, route),
        error: () => {
          if (requestId !== this.navigationRouteRequestId) {
            return;
          }

          this.isNavigationRouteLoading = false;
          this.navigationRoutePath = null;
          this.navigationRouteOrigin = origin;
          this.navigationRouteDestination = destination;
          this.navigationRouteState.set('error');
          this.navigationDistanceMeters.set(null);
          this.navigationDurationSeconds.set(null);
          this.renderNavigationMap(false);
        },
      });
  }

  private applyNavigationRoute(
    requestId: number,
    origin: L.LatLng,
    destination: L.LatLng,
    route: GpsRouteResponse,
  ): void {
    if (requestId !== this.navigationRouteRequestId) {
      return;
    }

    this.isNavigationRouteLoading = false;
    const path = route.path
      .filter(([lat, lng]) => Number.isFinite(lat) && Number.isFinite(lng))
      .map(([lat, lng]) => L.latLng(lat, lng));
    if (path.length < 2) {
      this.navigationRoutePath = null;
      this.navigationRouteState.set('error');
      this.renderNavigationMap(false);
      return;
    }

    this.navigationRoutePath = path;
    this.navigationRouteOrigin = origin;
    this.navigationRouteDestination = destination;
    this.navigationDistanceMeters.set(route.distance);
    this.navigationDurationSeconds.set(route.duration);
    this.navigationRouteState.set('ready');
    this.renderNavigationMap(false);
  }

  private clearNavigationRoute(): void {
    this.navigationRouteRequestId++;
    this.isNavigationRouteLoading = false;
    this.navigationRoutePath = null;
    this.navigationRouteOrigin = null;
    this.navigationRouteDestination = null;
    this.navigationRouteState.set('idle');
    this.navigationDistanceMeters.set(null);
    this.navigationDurationSeconds.set(null);
  }

  private focusCurrentMapLocation(animate: boolean): void {
    if (!this.driverMap || !this.currentMapLocation) {
      return;
    }

    if (this.hasFocusedCurrentMapLocation) {
      this.driverMap.panTo(this.currentMapLocation, { animate });
      return;
    }

    this.driverMap.setView(this.currentMapLocation, Math.max(this.driverMap.getZoom(), 15), { animate });
    this.hasFocusedCurrentMapLocation = true;
  }

  private destinationLocation(): L.LatLng | null {
    const stop = this.selectedTask()?.stop;
    if (!stop || !this.hasCoordinates(stop)) {
      return null;
    }

    return L.latLng(stop.lat, stop.lng);
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
    return { from: `${prefix}-01`, to: `${prefix}-${String(lastDay).padStart(2, '0')}` };
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

  private readSavedTheme(): 'light' | 'dark' {
    if (typeof localStorage === 'undefined') {
      return 'dark';
    }

    return localStorage.getItem('logistics-dispatch.driver-theme') === 'light' ? 'light' : 'dark';
  }
}

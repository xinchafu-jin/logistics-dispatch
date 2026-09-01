import { HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';
import {
  LucideCalendarDays,
  LucideCircleStop,
  LucideCloudFog,
  LucideCloudLightning,
  LucideCloudRain,
  LucideCloudSnow,
  LucideCloudSun,
  LucideCoffee,
  LucideListTodo,
  LucideLocateFixed,
  LucideMap,
  LucideMapPin,
  LucideMoon,
  LucidePlay,
  LucideSun,
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
import { AttendanceRecordDto, DriverShiftDto } from '../../core/services/driver-operations.models';
import { DriverOperationsService } from '../../core/services/driver-operations.service';
import { DriverWeather, DriverWeatherService } from '../../core/services/driver-weather.service';
import { BrandLogo } from '../../shared/ui/brand-logo/brand-logo';

type AttendanceViewState = 'loading' | 'not-clocked-in' | 'ready' | 'error';
type DriverTab = 'map' | 'tasks' | 'profile' | 'schedule';

@Component({
  selector: 'app-driver-dashboard',
  imports: [
    BrandLogo,
    LucideCalendarDays,
    LucideCircleStop,
    LucideCloudFog,
    LucideCloudLightning,
    LucideCloudRain,
    LucideCloudSnow,
    LucideCloudSun,
    LucideCoffee,
    LucideListTodo,
    LucideLocateFixed,
    LucideMap,
    LucideMapPin,
    LucideMoon,
    LucidePlay,
    LucideSun,
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
  protected readonly mapLocationStatus = signal('尚未取得目前位置');
  protected readonly activeTab = signal<DriverTab>('map');

  protected readonly gpsTracking = inject(DriverGpsTrackingService);

  private readonly authService = inject(DriverAuthService);
  private readonly operations = inject(DriverOperationsService);
  private readonly weatherService = inject(DriverWeatherService);
  private readonly router = inject(Router);
  private breakTimer: ReturnType<typeof setInterval> | null = null;
  private driverMap: L.Map | null = null;
  private currentLocationMarker: L.Marker | null = null;

  constructor() {
    this.weatherService.getCurrentWeather().subscribe({
      next: (weather) => this.weather.set(weather),
      error: () => this.weatherUnavailable.set(true),
    });
    this.loadAttendance();
    this.loadPublishedShifts();
  }

  ngAfterViewInit(): void {
    this.initializeMap();
    this.restoreMapLocation();
    this.requestMapLocation(false);
  }

  ngOnDestroy(): void {
    this.clearBreakTimer();
    this.gpsTracking.stop();
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

  protected setActiveTab(tab: DriverTab): void {
    this.activeTab.set(tab);

    if (tab === 'map') {
      setTimeout(() => this.driverMap?.invalidateSize(), 0);
    }
  }

  protected locateOnMap(): void {
    this.requestMapLocation(true);
  }

  private requestMapLocation(isManualRequest: boolean): void {
    if (!this.driverMap) {
      return;
    }

    if (!navigator.geolocation) {
      this.mapLocationStatus.set('此裝置不支援定位功能');
      return;
    }

    if (isManualRequest || !this.hasStoredMapLocation()) {
      this.mapLocationStatus.set('正在取得目前位置...');
    }

    navigator.geolocation.getCurrentPosition(
      (position) => {
        const location = L.latLng(position.coords.latitude, position.coords.longitude);
        this.showMapLocation(location, true);
        this.saveMapLocation(location);
        this.mapLocationStatus.set('已定位至目前位置');
      },
      (error) => {
        if (isManualRequest || !this.hasStoredMapLocation()) {
          this.mapLocationStatus.set(this.getLocationErrorMessage(error));
        }
      },
      { enableHighAccuracy: true, timeout: 10_000, maximumAge: 60_000 },
    );
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

    return this.weatherUnavailable() ? '天氣暫時無法取得' : '正在取得台南天氣';
  }

  protected formatBreakCountdown(): string {
    const seconds = Math.max(0, this.remainingBreakSeconds());
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${String(minutes).padStart(2, '0')}:${String(remainingSeconds).padStart(2, '0')}`;
  }

  protected formatTime(value: string | null): string {
    return value ? value.slice(0, 5) : '--:--';
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
    const today = new Date();
    const from = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-01`;
    const lastDay = new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate();
    const to = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(
      lastDay,
    ).padStart(2, '0')}`;

    this.operations.getPublishedShifts(from, to).subscribe({
      next: (shifts) => this.publishedShifts.set(shifts),
      error: () => this.publishedShifts.set([]),
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

  private initializeMap(): void {
    const mapElement = this.driverMapElement?.nativeElement;
    if (!mapElement) {
      return;
    }

    this.driverMap = L.map(mapElement, { zoomControl: false }).setView([23.7, 120.96], 8);
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
    this.currentLocationMarker?.remove();
    this.currentLocationMarker = L.marker(location, {
      icon: L.divIcon({
        className: 'driver-location-marker',
        html: '<span>A</span>',
        iconSize: [34, 34],
        iconAnchor: [17, 17],
      }),
      interactive: false,
    }).addTo(this.driverMap!);
    this.driverMap?.setView(location, 15, { animate });
  }

  private hasStoredMapLocation(): boolean {
    return readStoredMapLocation() !== null;
  }

  private saveMapLocation(location: L.LatLng): void {
    saveStoredMapLocation({ lat: location.lat, lng: location.lng });
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

    return fallback;
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

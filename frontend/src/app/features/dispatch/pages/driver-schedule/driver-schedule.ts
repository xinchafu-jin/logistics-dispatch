import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import {
  LucideCalendarDays,
  LucideChevronLeft,
  LucideChevronRight,
  LucideCircleCheck,
  LucideFileClock,
  LucideRefreshCw,
  LucideSave,
  LucideSend,
  LucideTriangleAlert,
  LucideUserRound,
} from '@lucide/angular';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {
  DriverDto,
  DriverShiftDto,
  DriverShiftUpdateRequest,
  ScheduleMonthDto,
  ShiftType,
} from '../../../../core/services/dispatch-api.models';

interface MonthDay {
  iso: string;
  day: number;
  weekday: string;
  isWeekend: boolean;
}

interface DriverScheduleRow {
  driverId: number;
  driverName: string;
  driverAccount: string;
  entries: { day: MonthDay; shift: DriverShiftDto | null }[];
}

interface ShiftEditorForm {
  shiftType: ShiftType;
  workStart: string;
  workEnd: string;
  overtimeMinutes: string;
  changeReason: string;
}

const weekdays = ['日', '一', '二', '三', '四', '五', '六'];

@Component({
  selector: 'app-driver-schedule',
  imports: [
    LucideCalendarDays,
    LucideChevronLeft,
    LucideChevronRight,
    LucideCircleCheck,
    LucideFileClock,
    LucideRefreshCw,
    LucideSave,
    LucideSend,
    LucideTriangleAlert,
    LucideUserRound,
  ],
  templateUrl: './driver-schedule.html',
  styleUrl: './driver-schedule.scss',
})
export class DriverSchedule implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly selectedMonth = signal(this.currentMonthValue());
  readonly scheduleMonth = signal<ScheduleMonthDto | null>(null);
  readonly shifts = signal<DriverShiftDto[]>([]);
  readonly drivers = signal<DriverDto[]>([]);
  readonly selectedShiftId = signal<number | null>(null);
  readonly editorForm = signal<ShiftEditorForm | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly scheduleMissing = signal(false);
  readonly errorMessage = signal('');
  readonly actionMessage = signal('');

  readonly days = computed(() => this.buildMonthDays(this.selectedMonth()));
  readonly isDraft = computed(() => this.scheduleMonth()?.status === 'DRAFT');
  readonly isPublished = computed(() => this.scheduleMonth()?.status === 'PUBLISHED');
  readonly selectedShift = computed(() => {
    const selectedId = this.selectedShiftId();
    return selectedId === null
      ? null
      : (this.shifts().find((shift) => shift.id === selectedId) ?? null);
  });
  readonly selectedDriver = computed(() => {
    const driverId = this.selectedShift()?.driverId;
    return driverId === undefined
      ? null
      : (this.drivers().find((driver) => driver.id === driverId) ?? null);
  });
  readonly rows = computed<DriverScheduleRow[]>(() => {
    const driversById = new Map<number, DriverDto>();
    for (const driver of this.drivers()) {
      if (driver.id !== undefined) {
        driversById.set(driver.id, driver);
      }
    }

    const shiftsByDriver = new Map<number, Map<string, DriverShiftDto>>();
    for (const shift of this.shifts()) {
      const byDate = shiftsByDriver.get(shift.driverId) ?? new Map<string, DriverShiftDto>();
      byDate.set(shift.workDate, shift);
      shiftsByDriver.set(shift.driverId, byDate);
    }

    return [...shiftsByDriver.entries()].map(([driverId, shiftsByDate]) => {
      const driver = driversById.get(driverId);
      return {
        driverId,
        driverName: driver?.name ?? `司機 #${driverId}`,
        driverAccount: driver?.account ?? '--',
        entries: this.days().map((day) => ({ day, shift: shiftsByDate.get(day.iso) ?? null })),
      };
    });
  });
  readonly summary = computed(() => {
    const shifts = this.shifts();
    return {
      drivers: new Set(shifts.map((shift) => shift.driverId)).size,
      work: shifts.filter((shift) => shift.shiftType === 'WORK').length,
      leave: shifts.filter((shift) => shift.shiftType === 'LEAVE').length,
      unassigned: shifts.filter((shift) => shift.shiftType === 'UNASSIGNED').length,
    };
  });

  ngOnInit(): void {
    this.loadDrivers();
    this.loadMonth();
  }

  protected moveMonth(offset: number): void {
    const [year, month] = this.selectedMonth().split('-').map(Number);
    const target = new Date(year, month - 1 + offset, 1);
    this.selectedMonth.set(
      `${target.getFullYear()}-${String(target.getMonth() + 1).padStart(2, '0')}`,
    );
    this.resetSelection();
    this.loadMonth();
  }

  protected selectMonth(event: Event): void {
    const month = (event.target as HTMLInputElement).value;
    if (!/^\d{4}-\d{2}$/.test(month) || month === this.selectedMonth()) {
      return;
    }

    this.selectedMonth.set(month);
    this.resetSelection();
    this.loadMonth();
  }

  protected generateMonth(): void {
    if (this.saving()) {
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');
    this.api.generateScheduleMonth(this.selectedMonth()).subscribe({
      next: (month) => {
        this.scheduleMonth.set(month);
        this.scheduleMissing.set(false);
        this.actionMessage.set(`${this.displayMonth()} 的草稿班表已建立。`);
        this.loadShifts(month.id);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法建立班表草稿。'));
        this.saving.set(false);
      },
    });
  }

  protected refresh(): void {
    if (this.saving()) {
      return;
    }
    this.loadDrivers();
    this.loadMonth();
  }

  protected selectShift(shift: DriverShiftDto): void {
    this.selectedShiftId.set(shift.id);
    this.editorForm.set(this.toEditorForm(shift));
    this.errorMessage.set('');
    this.actionMessage.set('');
  }

  protected changeShiftType(event: Event): void {
    const shiftType = (event.target as HTMLSelectElement).value as ShiftType;
    this.editorForm.update((form) => (form ? { ...form, shiftType } : form));
  }

  protected updateWorkStart(event: Event): void {
    const workStart = (event.target as HTMLInputElement).value;
    this.editorForm.update((form) => (form ? { ...form, workStart } : form));
  }

  protected updateWorkEnd(event: Event): void {
    const workEnd = (event.target as HTMLInputElement).value;
    this.editorForm.update((form) => (form ? { ...form, workEnd } : form));
  }

  protected updateOvertime(event: Event): void {
    const overtimeMinutes = (event.target as HTMLInputElement).value;
    this.editorForm.update((form) => (form ? { ...form, overtimeMinutes } : form));
  }

  protected updateReason(event: Event): void {
    const changeReason = (event.target as HTMLInputElement).value;
    this.editorForm.update((form) => (form ? { ...form, changeReason } : form));
  }

  protected saveShift(): void {
    const shift = this.selectedShift();
    const form = this.editorForm();
    if (!shift || !form || !this.isDraft() || this.saving()) {
      return;
    }

    if (form.shiftType === 'LEAVE') {
      this.markLeave();
      return;
    }

    const request = this.toUpdateRequest(form);
    if (!request) {
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');
    this.api.updateDriverShift(shift.id, request).subscribe({
      next: (updatedShift) => {
        this.replaceShift(updatedShift);
        this.actionMessage.set(`${this.shiftLabel(updatedShift.shiftType)}班次已儲存。`);
        this.saving.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法儲存班次。'));
        this.saving.set(false);
      },
    });
  }

  protected markLeave(): void {
    const shift = this.selectedShift();
    const form = this.editorForm();
    if (!shift || !form || !this.canMarkLeave() || this.saving()) {
      return;
    }

    const reason = form.changeReason.trim();
    if (!reason) {
      this.errorMessage.set('請填寫請假原因。');
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');
    this.api.markDriverShiftLeave(shift.id, { reason }).subscribe({
      next: (updatedShift) => {
        this.replaceShift(updatedShift);
        this.actionMessage.set('已登記請假。');
        this.saving.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法登記請假。'));
        this.saving.set(false);
      },
    });
  }

  protected syncDrivers(): void {
    const month = this.scheduleMonth();
    if (!month || !this.isDraft() || this.saving()) {
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');
    this.api.syncScheduleDrivers(month.id).subscribe({
      next: (shifts) => {
        this.shifts.set(shifts);
        this.resetSelection();
        this.actionMessage.set('已同步目前啟用的司機至整月班表。');
        this.saving.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法同步新司機。'));
        this.saving.set(false);
      },
    });
  }

  protected publishMonth(): void {
    const month = this.scheduleMonth();
    if (!month || !this.isDraft() || this.saving()) {
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');
    this.api.publishScheduleMonth(month.id).subscribe({
      next: (publishedMonth) => {
        this.scheduleMonth.set(publishedMonth);
        this.actionMessage.set('班表已發布，司機端現在可以讀取本月班次。');
        this.saving.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法發布班表。'));
        this.saving.set(false);
      },
    });
  }

  protected shiftLabel(shiftType: ShiftType): string {
    return {
      UNASSIGNED: '未排定',
      WORK: '上班',
      DAY_OFF: '休假',
      LEAVE: '請假',
    }[shiftType];
  }

  protected scheduleStatusLabel(): string {
    return this.isPublished() ? '已發布' : '草稿中';
  }

  protected displayMonth(): string {
    const [year, month] = this.selectedMonth().split('-').map(Number);
    return `${year} 年 ${month} 月`;
  }

  protected isSelected(shift: DriverShiftDto): boolean {
    return this.selectedShiftId() === shift.id;
  }

  protected canMarkLeave(): boolean {
    const shift = this.selectedShift();
    return !!shift && shift.workDate >= this.todayValue() && shift.shiftType !== 'LEAVE';
  }

  protected formatTime(value: string | null): string {
    return value ? value.slice(0, 5) : '--:--';
  }

  private loadDrivers(): void {
    this.api.getDrivers().subscribe({
      next: (drivers) => this.drivers.set(drivers),
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法取得司機資料。'));
      },
    });
  }

  private loadMonth(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');
    this.scheduleMissing.set(false);
    this.scheduleMonth.set(null);
    this.shifts.set([]);

    this.api.getScheduleMonth(this.selectedMonth()).subscribe({
      next: (month) => {
        this.scheduleMonth.set(month);
        this.loadShifts(month.id);
      },
      error: (error: unknown) => {
        if (this.isMissingScheduleError(error)) {
          this.scheduleMissing.set(true);
        } else {
          this.errorMessage.set(this.readError(error, '無法取得班表。'));
        }
        this.loading.set(false);
      },
    });
  }

  private loadShifts(scheduleMonthId: number): void {
    this.api.getScheduleMonthShifts(scheduleMonthId).subscribe({
      next: (shifts) => {
        this.shifts.set(shifts);
        this.loading.set(false);
        this.saving.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法取得整月班次。'));
        this.loading.set(false);
        this.saving.set(false);
      },
    });
  }

  private toUpdateRequest(form: ShiftEditorForm): DriverShiftUpdateRequest | null {
    const reason = form.changeReason.trim();
    if (form.shiftType === 'WORK') {
      const overtimeMinutes = Number(form.overtimeMinutes);
      if (!form.workStart || !form.workEnd) {
        this.errorMessage.set('上班日必須設定上下班時間。');
        return null;
      }
      if (form.workEnd <= form.workStart) {
        this.errorMessage.set('下班時間必須晚於上班時間。');
        return null;
      }
      if (!Number.isInteger(overtimeMinutes) || overtimeMinutes < 0) {
        this.errorMessage.set('加班分鐘數必須是 0 以上整數。');
        return null;
      }
      return {
        shiftType: form.shiftType,
        workStart: form.workStart,
        workEnd: form.workEnd,
        overtimeMinutes,
        changeReason: reason,
      };
    }

    return {
      shiftType: form.shiftType,
      workStart: null,
      workEnd: null,
      overtimeMinutes: 0,
      changeReason: reason,
    };
  }

  private replaceShift(updatedShift: DriverShiftDto): void {
    this.shifts.update((shifts) =>
      shifts.map((shift) => (shift.id === updatedShift.id ? updatedShift : shift)),
    );
    this.selectedShiftId.set(updatedShift.id);
    this.editorForm.set(this.toEditorForm(updatedShift));
  }

  private toEditorForm(shift: DriverShiftDto): ShiftEditorForm {
    return {
      shiftType: shift.shiftType,
      workStart: this.toTimeInput(shift.workStart),
      workEnd: this.toTimeInput(shift.workEnd),
      overtimeMinutes: String(shift.overtimeMinutes ?? 0),
      changeReason: shift.changeReason ?? '',
    };
  }

  private resetSelection(): void {
    this.selectedShiftId.set(null);
    this.editorForm.set(null);
  }

  private buildMonthDays(monthValue: string): MonthDay[] {
    const [year, month] = monthValue.split('-').map(Number);
    const length = new Date(year, month, 0).getDate();

    return Array.from({ length }, (_, index) => {
      const day = index + 1;
      const weekdayIndex = new Date(year, month - 1, day).getDay();
      return {
        iso: `${monthValue}-${String(day).padStart(2, '0')}`,
        day,
        weekday: weekdays[weekdayIndex],
        isWeekend: weekdayIndex === 0 || weekdayIndex === 6,
      };
    });
  }

  private currentMonthValue(): string {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  }

  private todayValue(): string {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Taipei',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date());
    const value = (type: string) => parts.find((part) => part.type === type)?.value ?? '';

    return `${value('year')}-${value('month')}-${value('day')}`;
  }

  private toTimeInput(value: string | null): string {
    return value ? value.slice(0, 5) : '';
  }

  /**
   * 後端的 EntityNotFoundException 目前會被全域例外處理器轉成 400，
   * 而不是慣例上的 404。只有明確指出「找不到該月班表」的情況才是可建立的空狀態；
   * 其他 400（例如月份格式錯誤）仍必須呈現給使用者。
   */
  private isMissingScheduleError(error: unknown): boolean {
    if (!(error instanceof HttpErrorResponse)) {
      return false;
    }

    if (error.status === 404) {
      return true;
    }

    const message = this.readError(error, '');
    return error.status === 400 && /^找不到 \d{4}-\d{2} 的班表$/.test(message);
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
    return fallback;
  }
}

import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal, ViewEncapsulation } from '@angular/core';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatDateFormats, provideNativeDateAdapter} from '@angular/material/core';
import {MatCalendarHeader, MatCalendarView, MatDatepicker, MatDatepickerModule} from '@angular/material/datepicker';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatInputModule} from '@angular/material/input';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { AdminThemeService } from '../../../../core/theme/admin-theme.service';
import {
  DriverDto,
  DriverLeaveHistoryDto,
  DriverLeaveBatchDto,
  DriverLeaveRequestDto,
  DriverMonthlyLeaveSummaryDto,
  DriverShiftDto,
  DriverShiftUpdateRequest,
  ScheduleMonthDto,
  LeaveType,
  ShiftType,
} from '../../../../core/services/dispatch-api.models';

/**
 * 這頁的日期選擇器只選到月份，輸入框要顯示「2026年9月」而不是整個日期。
 * 只影響這個元件（放在元件的 providers），其他頁的日期欄位仍是 app.config 的預設格式。
 * 輸入框是唯讀的，不會解析手打的字，所以 parse 不用設。
 */
const MONTH_ONLY_FORMATS: MatDateFormats = {
  parse: {dateInput: null},
  display: {
    dateInput: {year: 'numeric', month: 'long'},
    monthYearLabel: {year: 'numeric', month: 'short'},
    dateA11yLabel: {year: 'numeric', month: 'long', day: 'numeric'},
    monthYearA11yLabel: {year: 'numeric', month: 'long'},
  },
};

/**
 * 專供月份選擇器使用的月曆標頭：
 * 1. 攔截 MatYearView 點選月份後自動切換到 'month'（日期格）的預設行為
 * 2. 左上角年份按鈕只在「月份（year）」與「年份（multi-year）」之間切換，不進入選日視圖
 */
@Component({
  selector: 'app-month-picker-header',
  imports: [MatButtonModule],
  template: `
    <div class="mat-calendar-header">
      <div class="mat-calendar-controls">
        <button
          matButton
          type="button"
          class="mat-calendar-period-button"
          (click)="currentPeriodClicked()"
          [attr.aria-label]="periodButtonLabel"
        >
          <span aria-hidden="true">{{ periodButtonText }}</span>
          <svg
            class="mat-calendar-arrow"
            [class.mat-calendar-invert]="calendar.currentView !== 'year'"
            viewBox="0 0 10 5"
            focusable="false"
            aria-hidden="true"
          >
            <polygon points="0,0 5,5 10,0" />
          </svg>
        </button>

        <div class="mat-calendar-spacer"></div>

        <button
          matIconButton
          type="button"
          class="mat-calendar-previous-button"
          [disabled]="!previousEnabled()"
          (click)="previousClicked()"
          [attr.aria-label]="prevButtonLabel"
        >
          <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
            <path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z" />
          </svg>
        </button>

        <button
          matIconButton
          type="button"
          class="mat-calendar-next-button"
          [disabled]="!nextEnabled()"
          (click)="nextClicked()"
          [attr.aria-label]="nextButtonLabel"
        >
          <svg viewBox="0 0 24 24" focusable="false" aria-hidden="true">
            <path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z" />
          </svg>
        </button>
      </div>
    </div>
  `,
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MonthPickerHeader extends MatCalendarHeader<Date> {
  constructor() {
    super();
    this.calendar._goToDateInView = (date: Date, view: MatCalendarView) => {
      this.calendar.activeDate = date;
      this.calendar.currentView = view === 'month' ? 'year' : view;
    };
  }

  override currentPeriodClicked(): void {
    this.calendar.currentView = this.calendar.currentView === 'year' ? 'multi-year' : 'year';
  }
}

interface MonthDay {
  iso: string;
  day: number;
  weekday: string;
  weekdayIndex: number;
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

interface PlannedPartialLeaveForm {
  leaveType: Exclude<LeaveType, 'ABSENT'>;
  leaveStart: string;
  leaveEnd: string;
  reason: string;
}

const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
const weekdayOptions = [
  { index: 1, label: '一' },
  { index: 2, label: '二' },
  { index: 3, label: '三' },
  { index: 4, label: '四' },
  { index: 5, label: '五' },
  { index: 6, label: '六' },
];
const workdayIndexes = [1, 2, 3, 4, 5];
const saturdayIndexes = [6];
type BatchShiftType = 'WORK' | 'DAY_OFF';

interface BatchRule {
  weekdayIndexes: readonly number[];
  shiftType: BatchShiftType;
}

interface BatchDriverOption {
  id: number;
  name: string;
  account: string;
}

@Component({
  selector: 'app-driver-schedule',
  imports: [
    MatIconModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  providers: [provideNativeDateAdapter(MONTH_ONLY_FORMATS)],
  templateUrl: './driver-schedule.html',
  styleUrl: './driver-schedule.scss',
})
export class DriverSchedule implements OnInit {
  private readonly api = inject(DispatchApiService);
  // 月份選擇器的面板開在 body 底下，吃不到後台深淺色：mat-datepicker 的 panelClass 要帶 theme.dialogPanelClass()
  protected readonly theme = inject(AdminThemeService);
  protected readonly monthPickerHeader = MonthPickerHeader;

  readonly selectedMonth = signal(this.currentMonthValue());
  // 日期選擇器吃 Date；API 與月曆計算仍用 YYYY-MM 字串，只在這裡轉換（取該月 1 號）
  protected readonly selectedMonthDate = computed(() => {
    const [year, month] = this.selectedMonth().split('-').map(Number);
    return new Date(year, month - 1, 1);
  });
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
  readonly pendingLeaveRequests = signal<DriverLeaveRequestDto[]>([]);
  readonly pendingLeaveBatches = signal<DriverLeaveBatchDto[]>([]);
  readonly leaveReviewReasons = signal<Record<number, string>>({});
  readonly leaveReviewErrors = signal<Record<number, string>>({});
  readonly reviewingLeaveId = signal<number | null>(null);
  readonly batchReviewReasons = signal<Record<string, string>>({});
  readonly batchReviewErrors = signal<Record<string, string>>({});
  readonly batchLeaveTypes = [
    {value: 'SICK' as const, label: '病假'},
    {value: 'ANNUAL' as const, label: '年假'},
    {value: 'PERSONAL' as const, label: '事假'},
    {value: 'SPECIAL' as const, label: '特殊事由'},
    {value: 'MENSTRUAL' as const, label: '生理假'},
    {value: 'BEREAVEMENT' as const, label: '喪假'},
  ];
  readonly batchTypeSelections = signal<Record<string, Exclude<LeaveType, 'ABSENT'>>>({});
  readonly reviewingBatchId = signal<string | null>(null);
  readonly leaveReviewLoading = signal(false);
  readonly leaveReviewLoadError = signal('');
  readonly expandedReviewHistoryId = signal<number | null>(null);
  readonly leaveRequestHistories = signal<Record<number, DriverLeaveHistoryDto[]>>({});
  readonly leaveHistoryErrors = signal<Record<number, string>>({});
  readonly leaveHistoryLoadingIds = signal<number[]>([]);
  readonly monthlyLeaveSummaries = signal<Record<number, DriverMonthlyLeaveSummaryDto>>({});
  readonly monthlyLeaveLoadingIds = signal<number[]>([]);
  readonly monthlyLeaveErrors = signal<Record<number, string>>({});
  readonly hoveredDriverId = signal<number | null>(null);
  readonly selectedLeaveDetailDriverId = signal<number | null>(null);
  readonly leaveTooltipPosition = signal({top: 0, left: 0});
  readonly plannedPartialLeaveForm = signal<PlannedPartialLeaveForm>({
    leaveType: 'SPECIAL',
    leaveStart: '',
    leaveEnd: '',
    reason: '',
  });
  /** 預設先選週一至週五，主管最常用的週班規則只需要按一次即可套用。 */
  readonly selectedWeekdayIndexes = signal<number[]>([...workdayIndexes]);
  readonly selectedBatchDriverIds = signal<number[]>([]);
  readonly weekdayOptions = weekdayOptions;

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
  readonly hoveredDriver = computed<DriverScheduleRow | null>(() => {
    const driverId = this.hoveredDriverId();
    return driverId === null
      ? null
      : this.rows().find((row) => row.driverId === driverId) ?? null;
  });
  readonly summary = computed(() => {
    const shifts = this.shifts();
    const regularDayShifts = shifts.filter((shift) => !this.isSundayDate(shift.workDate));
    return {
      drivers: new Set(shifts.map((shift) => shift.driverId)).size,
      work: regularDayShifts.filter((shift) => shift.shiftType === 'WORK').length,
      leave: regularDayShifts.filter((shift) => shift.shiftType === 'LEAVE').length,
      unassigned: regularDayShifts.filter((shift) => shift.shiftType === 'UNASSIGNED').length,
    };
  });
  readonly batchDriverOptions = computed<BatchDriverOption[]>(() => {
    const scheduledDriverIds = new Set(this.shifts().map((shift) => shift.driverId));

    return this.drivers().flatMap((driver) =>
      driver.id !== undefined && driver.isActive && scheduledDriverIds.has(driver.id)
        ? [{ id: driver.id, name: driver.name, account: driver.account }]
        : [],
    );
  });
  readonly selectedBatchDriverCount = computed(() => {
    const availableIds = new Set(this.batchDriverOptions().map((driver) => driver.id));
    return this.selectedBatchDriverIds().filter((id) => availableIds.has(id)).length;
  });
  readonly areAllBatchDriversSelected = computed(() => {
    const drivers = this.batchDriverOptions();
    return drivers.length > 0 && this.selectedBatchDriverCount() === drivers.length;
  });

  ngOnInit(): void {
    this.loadDrivers();
    this.loadMonth();
    this.loadPendingLeaveRequests();
    this.loadPendingLeaveBatches();
  }

  /** 在月曆上點了月份：直接關掉，不讓它往下進到選日期 */
  protected selectMonth(date: Date, picker: MatDatepicker<Date>): void {
    picker.close();
    if (picker.opened) {
      setTimeout(() => picker.close(), 210);
    }
    const month = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
    if (month === this.selectedMonth()) {
      return;
    }

    this.selectedMonth.set(month);
    this.monthlyLeaveSummaries.set({});
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
    this.loadPendingLeaveRequests();
    this.loadPendingLeaveBatches();
  }

  protected isWeekdaySelected(weekdayIndex: number): boolean {
    return this.selectedWeekdayIndexes().includes(weekdayIndex);
  }

  protected toggleWeekday(weekdayIndex: number): void {
    if (this.saving() || weekdayIndex === 0) {
      return;
    }

    this.selectedWeekdayIndexes.update((selected) =>
      selected.includes(weekdayIndex)
        ? selected.filter((item) => item !== weekdayIndex)
        : [...selected, weekdayIndex].sort((left, right) => left - right),
    );
  }

  protected selectWeekdayGroup(weekdayIndexes: readonly number[]): void {
    if (this.saving()) {
      return;
    }

    this.selectedWeekdayIndexes.set(weekdayIndexes.filter((index) => index !== 0));
  }

  protected isBatchDriverSelected(driverId: number): boolean {
    return this.selectedBatchDriverIds().includes(driverId);
  }

  protected toggleBatchDriver(driverId: number): void {
    if (this.saving()) {
      return;
    }

    this.selectedBatchDriverIds.update((selected) =>
      selected.includes(driverId)
        ? selected.filter((id) => id !== driverId)
        : [...selected, driverId].sort((left, right) => left - right),
    );
  }

  protected selectAllBatchDrivers(): void {
    if (!this.saving()) {
      this.selectedBatchDriverIds.set(this.batchDriverOptions().map((driver) => driver.id));
    }
  }

  protected clearBatchDrivers(): void {
    if (!this.saving()) {
      this.selectedBatchDriverIds.set([]);
    }
  }

  protected applyStandardWeek(): void {
    this.applyBatchRules(
      [
        { weekdayIndexes: workdayIndexes, shiftType: 'WORK' },
        { weekdayIndexes: saturdayIndexes, shiftType: 'DAY_OFF' },
      ],
      '已套用週一至週五上班、週六排休；週日固定公休。',
    );
  }

  protected applySelectedWeekdays(shiftType: BatchShiftType): void {
    const weekdayIndexes = this.selectedWeekdayIndexes();
    if (weekdayIndexes.length === 0) {
      this.errorMessage.set('請至少選擇一個星期。');
      return;
    }

    this.applyBatchRules(
      [{ weekdayIndexes, shiftType }],
      `已將所選星期批次設為${this.shiftLabel(shiftType)}。`,
    );
  }

  protected selectedWeekdayDescription(): string {
    const selected = new Set(this.selectedWeekdayIndexes());
    return weekdayOptions
      .filter((weekday) => selected.has(weekday.index))
      .map((weekday) => `週${weekday.label}`)
      .join('、');
  }

  protected selectShift(shift: DriverShiftDto): void {
    if (this.isSundayDate(shift.workDate)) {
      return;
    }
    this.selectedShiftId.set(shift.id);
    this.editorForm.set(this.toEditorForm(shift));
    this.errorMessage.set('');
    this.actionMessage.set('');
  }

  protected updateLeaveReviewReason(requestId: number, event: Event): void {
    const reason = (event.target as HTMLTextAreaElement).value;
    this.leaveReviewReasons.update((reasons) => ({...reasons, [requestId]: reason}));
    this.leaveReviewErrors.update((errors) => ({...errors, [requestId]: ''}));
  }

  protected reviewLeaveRequest(
    request: DriverLeaveRequestDto,
    decision: 'approve' | 'reject',
  ): void {
    const reason = (this.leaveReviewReasons()[request.id] ?? '').trim();
    if (!reason) {
      this.leaveReviewErrors.update((errors) => ({
        ...errors,
        [request.id]: '請填寫給司機看的處理理由。',
      }));
      return;
    }
    if (this.reviewingLeaveId() !== null) {
      return;
    }

    this.reviewingLeaveId.set(request.id);
    this.leaveReviewErrors.update((errors) => ({...errors, [request.id]: ''}));
    const operation = decision === 'approve'
      ? this.api.approveLeaveRequest(request.id, reason)
      : this.api.rejectLeaveRequest(request.id, reason);
    operation.subscribe({
      next: (updated) => {
        this.pendingLeaveRequests.update((items) => items.filter((item) => item.id !== updated.id));
        if (decision === 'approve' && updated.requestMode === 'PREPLANNED') {
          this.loadMonth();
        }
        this.actionMessage.set(
          decision === 'approve'
            ? `${updated.driverName} ${updated.workDate} 的申請已核准，處理理由會同步給司機。`
            : `${updated.driverName} ${updated.workDate} 的申請已退回，處理理由會同步給司機。`,
        );
        this.leaveReviewReasons.update((reasons) => {
          const next = {...reasons};
          delete next[request.id];
          return next;
        });
        this.reviewingLeaveId.set(null);
        this.reloadDriverMonthlyLeaveSummary(updated.driverId);
      },
      error: (error: unknown) => {
        this.leaveReviewErrors.update((errors) => ({
          ...errors,
          [request.id]: this.readError(error, '請假審核未完成。'),
        }));
        this.reviewingLeaveId.set(null);
      },
    });
  }

  protected updateBatchReviewReason(batchId: string, event: Event): void {
    const reason = (event.target as HTMLTextAreaElement).value;
    this.batchReviewReasons.update((reasons) => ({...reasons, [batchId]: reason}));
    this.batchReviewErrors.update((errors) => ({...errors, [batchId]: ''}));
  }

  protected selectedBatchLeaveType(batch: DriverLeaveBatchDto): Exclude<LeaveType, 'ABSENT'> {
    return this.batchTypeSelections()[batch.batchId]
      ?? batch.leaveType as Exclude<LeaveType, 'ABSENT'>;
  }

  protected updateBatchLeaveType(batchId: string, event: Event): void {
    const leaveType = (event.target as HTMLSelectElement).value as Exclude<LeaveType, 'ABSENT'>;
    this.batchTypeSelections.update((types) => ({...types, [batchId]: leaveType}));
    this.batchReviewErrors.update((errors) => ({...errors, [batchId]: ''}));
  }

  protected correctLeaveBatchType(batch: DriverLeaveBatchDto): void {
    const leaveType = this.selectedBatchLeaveType(batch);
    const reason = (this.batchReviewReasons()[batch.batchId] ?? '').trim();
    if (leaveType === batch.leaveType) {
      this.batchReviewErrors.update((errors) => ({...errors, [batch.batchId]: '請先選擇不同的假別。'}));
      return;
    }
    if (!reason) {
      this.batchReviewErrors.update((errors) => ({
        ...errors,
        [batch.batchId]: '改假別前，請填寫會提供給司機的原因。',
      }));
      return;
    }
    if (this.reviewingBatchId() !== null) {
      return;
    }

    this.reviewingBatchId.set(batch.batchId);
    this.batchReviewErrors.update((errors) => ({...errors, [batch.batchId]: ''}));
    this.api.correctLeaveBatchType(batch.batchId, leaveType, reason).subscribe({
      next: (updated) => {
        this.pendingLeaveBatches.update((items) =>
          items.map((item) => item.batchId === updated.batchId ? updated : item),
        );
        this.batchTypeSelections.update((types) => {
          const next = {...types};
          delete next[batch.batchId];
          return next;
        });
        this.batchReviewReasons.update((reasons) => {
          const next = {...reasons};
          delete next[batch.batchId];
          return next;
        });
        this.actionMessage.set(`${updated.driverName} 的預排請假已改為${this.leaveTypeLabel(updated.leaveType)}。`);
        this.reviewingBatchId.set(null);
      },
      error: (error: unknown) => {
        this.batchReviewErrors.update((errors) => ({
          ...errors,
          [batch.batchId]: this.readError(error, '改假別未完成。'),
        }));
        this.reviewingBatchId.set(null);
      },
    });
  }

  protected reviewLeaveBatch(batch: DriverLeaveBatchDto, decision: 'approve' | 'reject'): void {
    const reason = (this.batchReviewReasons()[batch.batchId] ?? '').trim();
    if (!reason) {
      this.batchReviewErrors.update((errors) => ({
        ...errors,
        [batch.batchId]: '請填寫給司機看的處理理由。',
      }));
      return;
    }
    if (this.reviewingBatchId() !== null) {
      return;
    }

    this.reviewingBatchId.set(batch.batchId);
    const operation = decision === 'approve'
      ? this.api.approveLeaveBatch(batch.batchId, reason)
      : this.api.rejectLeaveBatch(batch.batchId, reason);
    operation.subscribe({
      next: (updated) => {
        this.pendingLeaveBatches.update((items) =>
          items.filter((item) => item.batchId !== updated.batchId),
        );
        this.batchReviewReasons.update((reasons) => {
          const next = {...reasons};
          delete next[batch.batchId];
          return next;
        });
        this.reloadDriverMonthlyLeaveSummary(updated.driverId);
        if (decision === 'approve') {
          this.loadMonth();
        }
        this.actionMessage.set(
          decision === 'approve'
            ? `${updated.driverName} 的 ${updated.workDates.length} 天預排請假已核准。`
            : `${updated.driverName} 的預排請假已退回。`,
        );
        this.reviewingBatchId.set(null);
      },
      error: (error: unknown) => {
        this.batchReviewErrors.update((errors) => ({
          ...errors,
          [batch.batchId]: this.readError(error, '預排請假審核未完成。'),
        }));
        this.reviewingBatchId.set(null);
      },
    });
  }

  protected toggleReviewHistory(request: DriverLeaveRequestDto): void {
    if (this.expandedReviewHistoryId() === request.id) {
      this.expandedReviewHistoryId.set(null);
      return;
    }
    this.expandedReviewHistoryId.set(request.id);
    if (this.leaveRequestHistories()[request.id]) {
      return;
    }
    this.leaveHistoryErrors.update((errors) => ({...errors, [request.id]: ''}));
    this.leaveHistoryLoadingIds.update((ids) => [...ids, request.id]);
    this.api.getLeaveRequestHistory(request.id).subscribe({
      next: (events) => {
        this.leaveRequestHistories.update((histories) => ({...histories, [request.id]: events}));
        this.leaveHistoryErrors.update((errors) => ({...errors, [request.id]: ''}));
        this.leaveHistoryLoadingIds.update((ids) => ids.filter((id) => id !== request.id));
      },
      error: (error: unknown) => {
        this.leaveHistoryErrors.update((errors) => ({
          ...errors,
          [request.id]: this.readError(error, '無法取得請假歷程。'),
        }));
        this.leaveHistoryLoadingIds.update((ids) => ids.filter((id) => id !== request.id));
      },
    });
  }

  protected updatePlannedLeaveField<K extends keyof PlannedPartialLeaveForm>(
    field: K,
    event: Event,
  ): void {
    const target = event.target as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
    this.plannedPartialLeaveForm.update((form) => ({...form, [field]: target.value}));
    this.errorMessage.set('');
  }

  protected createPlannedPartialLeave(): void {
    const shift = this.selectedShift();
    const form = this.plannedPartialLeaveForm();
    const reason = form.reason.trim();
    if (!shift || shift.shiftType !== 'WORK' || this.saving()) {
      return;
    }
    if (!form.leaveStart || !form.leaveEnd || form.leaveEnd <= form.leaveStart || !reason) {
      this.errorMessage.set('請確認預排假起訖時間有效，並填寫原因。');
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');
    this.api.createPlannedPartialLeave({
      driverId: shift.driverId,
      workDate: shift.workDate,
      leaveType: form.leaveType,
      leaveStart: form.leaveStart,
      leaveEnd: form.leaveEnd,
      reason,
    }).subscribe({
      next: (request) => {
        this.plannedPartialLeaveForm.update((value) => ({...value, reason: ''}));
        this.actionMessage.set(`${request.driverName} ${request.workDate} 的部分時段預排假已建立。`);
        this.saving.set(false);
        this.reloadDriverMonthlyLeaveSummary(request.driverId);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.readError(error, '無法建立部分時段預排假。'));
        this.saving.set(false);
      },
    });
  }

  protected showDriverLeaveTooltip(driverId: number, event: MouseEvent | FocusEvent): void {
    const target = event.currentTarget as HTMLElement | null;
    if (target && typeof window !== 'undefined') {
      const rect = target.getBoundingClientRect();
      const width = Math.min(340, window.innerWidth - 24);
      const gap = 8;
      this.leaveTooltipPosition.set({
        // 對齊司機所在列，往右覆蓋日期格；班表再靠近畫面底部也不改放到姓名上方。
        top: Math.max(gap, rect.top),
        left: Math.max(gap, Math.min(rect.right + gap, window.innerWidth - width - gap)),
      });
    }
    this.hoveredDriverId.set(driverId);
    this.loadDriverMonthlyLeaveSummary(driverId);
  }

  protected hideDriverLeaveTooltip(): void {
    this.hoveredDriverId.set(null);
  }

  protected toggleDriverLeaveDetails(driverId: number): void {
    this.selectedLeaveDetailDriverId.update((selected) => selected === driverId ? null : driverId);
    this.loadDriverMonthlyLeaveSummary(driverId);
  }

  protected driverName(driverId: number): string {
    return this.drivers().find((driver) => driver.id === driverId)?.name ?? `司機 #${driverId}`;
  }

  protected rosterLeaveRecordsForCell(driverId: number, workDate: string): DriverLeaveRequestDto[] {
    return this.monthlyLeaveSummaries()[driverId]?.records.filter(
      (request) => request.workDate === workDate && this.isRosterVisibleLeave(request),
    ) ?? [];
  }

  protected isRosterVisibleLeave(request: DriverLeaveRequestDto): boolean {
    return request.status === 'APPROVED'
      && (request.requestMode === 'PREPLANNED' || request.requestMode === 'ADMIN_PLANNED_PARTIAL');
  }

  protected formatLeaveHistoryTime(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat('zh-TW', {
      month: 'numeric',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(date);
  }

  protected leaveTypeLabel(type: LeaveType): string {
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

  protected leaveModeLabel(request: DriverLeaveRequestDto): string {
    return {
      PREPLANNED: '預排請假',
      TEMPORARY: '當日特殊事由',
      MAKEUP: '事後補請',
      SYSTEM_NO_SHOW: '待說明特殊事由',
      ADMIN_PLANNED_PARTIAL: '主管預排時段假',
    }[request.requestMode];
  }

  protected leaveStatusLabel(status: DriverLeaveRequestDto['status']): string {
    return status === 'PENDING' ? '待審' : status === 'APPROVED' ? '已核准' : '已退回';
  }

  protected isLeaveHistoryLoading(requestId: number): boolean {
    return this.leaveHistoryLoadingIds().includes(requestId);
  }

  protected isMonthlyLeaveLoading(driverId: number): boolean {
    return this.monthlyLeaveLoadingIds().includes(driverId);
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
    if (!shift || !form || this.isSundayDate(shift.workDate) || !this.isDraft() || this.saving()) {
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
    this.api.markDriverShiftLeave(shift.id, { reason, version: shift.version }).subscribe({
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
        this.loadDrivers();
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

  protected shiftIcon(shiftType: ShiftType): string {
    return {WORK: 'work_outline', DAY_OFF: 'free_breakfast', LEAVE: 'event_busy', UNASSIGNED: 'event_note'}[shiftType];
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
    return (
      !!shift &&
      !this.isSundayDate(shift.workDate) &&
      shift.workDate >= this.todayValue() &&
      shift.shiftType !== 'LEAVE' &&
      (this.isDraft() || shift.shiftType === 'WORK')
    );
  }

  protected formatTime(value: string | null): string {
    return value ? value.slice(0, 5) : '--:--';
  }

  private loadPendingLeaveRequests(): void {
    this.leaveReviewLoading.set(true);
    this.leaveReviewLoadError.set('');
    this.api.getPendingLeaveRequests().subscribe({
      next: (requests) => {
        this.pendingLeaveRequests.set(
          requests.filter((request) => !request.batchId).sort((left, right) =>
            left.workDate.localeCompare(right.workDate)
            || left.requestedAt.localeCompare(right.requestedAt),
          ),
        );
        this.leaveReviewLoading.set(false);
      },
      error: (error: unknown) => {
        this.leaveReviewLoadError.set(this.readError(error, '無法取得待審請假。'));
        this.leaveReviewLoading.set(false);
      },
    });
  }

  private loadPendingLeaveBatches(): void {
    this.api.getPendingLeaveBatches().subscribe({
      next: (batches) => {
        this.pendingLeaveBatches.set(
          [...batches].sort((left, right) => left.requestedAt.localeCompare(right.requestedAt)),
        );
      },
      error: (error: unknown) => {
        this.leaveReviewLoadError.set(this.readError(error, '無法取得待審預排請假。'));
      },
    });
  }

  private loadDriverMonthlyLeaveSummary(driverId: number): void {
    if (this.monthlyLeaveSummaries()[driverId] || this.isMonthlyLeaveLoading(driverId)) {
      return;
    }
    this.monthlyLeaveLoadingIds.update((ids) => [...ids, driverId]);
    this.monthlyLeaveErrors.update((errors) => ({...errors, [driverId]: ''}));
    this.api.getDriverMonthlyLeaveSummary(driverId, this.selectedMonth()).subscribe({
      next: (summary) => {
        this.monthlyLeaveSummaries.update((items) => ({...items, [driverId]: summary}));
        this.monthlyLeaveLoadingIds.update((ids) => ids.filter((id) => id !== driverId));
      },
      error: (error: unknown) => {
        this.monthlyLeaveErrors.update((errors) => ({
          ...errors,
          [driverId]: this.readError(error, '無法取得本月請假摘要。'),
        }));
        this.monthlyLeaveLoadingIds.update((ids) => ids.filter((id) => id !== driverId));
      },
    });
  }

  private reloadDriverMonthlyLeaveSummary(driverId: number): void {
    this.monthlyLeaveSummaries.update((items) => {
      const next = {...items};
      delete next[driverId];
      return next;
    });
    this.monthlyLeaveErrors.update((errors) => ({...errors, [driverId]: ''}));
    this.loadDriverMonthlyLeaveSummary(driverId);
  }

  private applyBatchRules(rules: readonly BatchRule[], successMessage: string): void {
    const month = this.scheduleMonth();
    if (!month || !this.isDraft() || this.saving()) {
      return;
    }

    const weekdayByDate = new Map(this.days().map((day) => [day.iso, day.weekdayIndex]));
    const availableDriverIds = new Set(this.batchDriverOptions().map((driver) => driver.id));
    const selectedDriverIds = new Set(
      this.selectedBatchDriverIds().filter((driverId) => availableDriverIds.has(driverId)),
    );
    if (selectedDriverIds.size === 0) {
      this.errorMessage.set('請至少選擇一位司機。');
      return;
    }
    const targetTypeByWeekday = new Map<number, BatchShiftType>();
    for (const rule of rules) {
      for (const weekdayIndex of rule.weekdayIndexes) {
        targetTypeByWeekday.set(weekdayIndex, rule.shiftType);
      }
    }

    const targets: { shiftId: number; request: DriverShiftUpdateRequest }[] = [];
    for (const shift of this.shifts()) {
      const shiftType = weekdayByDate.get(shift.workDate);
      const targetType = shiftType === undefined ? undefined : targetTypeByWeekday.get(shiftType);
      if (
        !selectedDriverIds.has(shift.driverId) ||
        shiftType === 0 ||
        !targetType ||
        shift.shiftType === 'LEAVE' ||
        shift.shiftType === targetType
      ) {
        continue;
      }

      const request = this.toBatchUpdateRequest(shift, targetType);
      // 任一司機的預設工時無效時，整批不送出，避免只改到部分司機的班表。
      if (!request) {
        return;
      }
      targets.push({ shiftId: shift.id, request });
    }

    if (targets.length === 0) {
      this.actionMessage.set('所選司機與星期沒有需要變更的班次；既有請假不會被覆蓋。');
      this.errorMessage.set('');
      return;
    }

    this.saving.set(true);
    this.errorMessage.set('');
    this.actionMessage.set('');

    const updates = targets.map(({shiftId, request}) => {
      const current = this.shifts().find((shift) => shift.id === shiftId)!;
      return {...current, ...request};
    });

    this.api.updateDriverShiftsBatch(month.id, updates).subscribe({
        next: (updatedShifts) => {
          const updatedById = new Map(updatedShifts.map((shift) => [shift.id, shift]));
          this.shifts.update((shifts) =>
            shifts.map((shift) => updatedById.get(shift.id) ?? shift),
          );
          this.resetSelection();
          this.actionMessage.set(`${successMessage} 共更新 ${updatedShifts.length} 個班次。`);
          this.saving.set(false);
        },
        error: (error: unknown) => {
          this.errorMessage.set(this.readError(error, '批次更新班次失敗，已重新讀取班表。'));
          this.resetSelection();
          // 後端批次 API 會整批回滾；仍重讀資料以處理其他人同時修改的情況。
          this.loadShifts(month.id);
        },
      });
  }

  private toBatchUpdateRequest(
    shift: DriverShiftDto,
    shiftType: BatchShiftType,
  ): DriverShiftUpdateRequest | null {
    if (shiftType === 'DAY_OFF') {
      return {
        shiftType,
        workStart: null,
        workEnd: null,
        overtimeMinutes: 0,
        changeReason: '主管批次排定休假',
      };
    }

    const driver = this.drivers().find((item) => item.id === shift.driverId);
    const workStart = shift.workStart ?? driver?.workStart ?? '';
    const workEnd = shift.workEnd ?? driver?.workEnd ?? '';
    if (!workStart || !workEnd || workEnd <= workStart) {
      this.errorMessage.set(
        `${driver?.name ?? `司機 #${shift.driverId}`} 的預設上下班時間無效，未執行批次更新。`,
      );
      return null;
    }

    return {
      shiftType,
      workStart,
      workEnd,
      overtimeMinutes: 0,
      changeReason: '主管批次排定上班',
    };
  }

  private loadDrivers(): void {
    this.api.getDrivers().subscribe({
      next: (drivers) => {
        this.drivers.set(drivers);
        this.selectedBatchDriverIds.set(
          drivers
            .filter((driver) => driver.id !== undefined && driver.isActive)
            .map((driver) => driver.id!),
        );
      },
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
        weekdayIndex,
        isWeekend: weekdayIndex === 0 || weekdayIndex === 6,
      };
    });
  }

  private isSundayDate(workDate: string): boolean {
    return new Date(`${workDate}T00:00:00`).getDay() === 0;
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

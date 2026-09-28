import {Component, DestroyRef, computed, effect, inject, input, output, signal, untracked} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {HttpErrorResponse} from '@angular/common/http';
import {MatIconModule} from '@angular/material/icon';
import {Observable, map, switchMap} from 'rxjs';
import {DriverLeaveRequest, DriverLeaveRequestResponse, DriverShiftDto} from '../../../core/services/driver-operations.models';
import {DriverOperationsService} from '../../../core/services/driver-operations.service';

export type CalendarLeaveMode = 'planned' | 'temporary' | 'makeup';
type RequestedLeaveType = DriverLeaveRequest['leaveType'];

export function calendarDateKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function todayInTaipei(): string {
  const parts = new Intl.DateTimeFormat('en', {timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit'})
    .formatToParts(new Date());
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)!.value).join('-');
}

@Component({
  selector: 'app-schedule-leave-composer',
  imports: [MatIconModule],
  templateUrl: './schedule-leave-composer.html',
  styleUrl: './schedule-leave-composer.scss',
})
export class ScheduleLeaveComposer {
  readonly shifts = input<DriverShiftDto[]>([]);
  readonly month = input.required<Date>();
  readonly selectedDate = input<Date | null>(null);
  readonly scheduleReady = input(false);
  readonly requests = input<DriverLeaveRequestResponse[]>([]);
  readonly requestsLoading = input(false);
  readonly requestsAvailable = input(false);
  readonly submitted = output<DriverLeaveRequestResponse[]>();
  readonly calendarChange = output<void>();
  readonly goToToday = output<void>();
  readonly activeMode = signal<CalendarLeaveMode | null>(null);
  readonly leaveType = signal<RequestedLeaveType | ''>('');
  readonly selectedDates = signal<string[]>([]);
  readonly reason = signal('');
  readonly leaveStart = signal('');
  readonly leaveEnd = signal('');
  readonly submitting = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly evidence = signal<File | null>(null);
  readonly modes = [
    {value: 'planned', label: '排假', hint: '未來・可複選', icon: 'event_available'},
    {value: 'temporary', label: '當日臨請', hint: '特殊事由・限今天', icon: 'today'},
    {value: 'makeup', label: '事後補請', hint: '過去・可複選', icon: 'history'},
  ] as const;
  readonly types = [
    {value: 'SICK', label: '病假'}, {value: 'ANNUAL', label: '年假'},
    {value: 'PERSONAL', label: '事假'}, {value: 'BEREAVEMENT', label: '喪假'},
    {value: 'MENSTRUAL', label: '生理假'}, {value: 'SPECIAL', label: '特殊事由'},
  ] as const;
  readonly modeLabel = computed(() => this.modes.find(mode => mode.value === this.activeMode())?.label ?? '');
  readonly timeError = computed(() => {
    if (this.activeMode() === 'planned' || !this.activeMode()) return '';
    const start = this.leaveStart(), end = this.leaveEnd();
    if (!!start !== !!end) return '部分時段請填完整的開始與結束時間。';
    return start && end <= start ? '結束時間必須晚於開始時間。' : '';
  });
  readonly newReplies = computed(() => {
    const selected = this.selectedDate();
    const month = this.month();
    if (!selected || selected.getFullYear() !== month.getFullYear() || selected.getMonth() !== month.getMonth()) return [];
    const workDate = calendarDateKey(selected);
    return this.requests().filter(request => request.workDate === workDate && request.status !== 'PENDING'
      && !request.driverReadAt && request.reviewedAt)
      .sort((left, right) => right.reviewedAt!.localeCompare(left.reviewedAt!));
  });
  readonly guide = computed(() => {
    if (!this.leaveType()) return '先選假別，再點上方日曆選日期。';
    return this.activeMode() === 'planned' ? '點上方日曆複選明天以後的上班日；再次點選即可取消。'
      : this.activeMode() === 'makeup' ? '點上方日曆複選今天以前的已發布上班日；有打卡的日期須填起訖時間。'
        : `申請日期固定為今天 ${todayInTaipei()}，不必選日期。`;
  });
  private readonly api = inject(DriverOperationsService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly notifyCalendar = effect(() => {
    this.activeMode(); this.leaveType(); this.selectedDates(); this.shifts(); this.scheduleReady();
    this.requests(); this.requestsLoading(); this.requestsAvailable(); this.submitting();
    this.timeError(); this.leaveStart(); this.leaveEnd();
    untracked(() => this.calendarChange.emit());
  });

  open(mode: CalendarLeaveMode): void {
    if (this.submitting()) return;
    if (this.activeMode() === mode) {this.cancel(); return;}
    this.clear();
    this.activeMode.set(mode);
    if (mode === 'temporary') this.selectedDates.set([todayInTaipei()]);
    if (mode !== 'makeup') this.goToToday.emit();
  }

  cancel(): void {
    if (this.submitting()) return;
    this.clear();
    this.activeMode.set(null);
  }

  private clear(): void {
    this.selectedDates.set([]); this.leaveType.set(''); this.reason.set(''); this.evidence.set(null);
    this.leaveStart.set(''); this.leaveEnd.set(''); this.error.set(''); this.message.set('');
  }

  chooseType(type: RequestedLeaveType): void {
    if (this.submitting()) return;
    this.leaveType.set(type); this.error.set('');
  }

  private conflicting(date: string): boolean {
    return this.requests().some(request => request.workDate === date && request.status !== 'REJECTED'
      && !(this.activeMode() === 'makeup' && (request.requestMode === 'SYSTEM_NO_SHOW' || request.requestMode === 'TEMPORARY')
        && request.submissionSource === 'SYSTEM' && request.fullDay && request.status === 'PENDING')
      && !(request.status === 'APPROVED' && (this.activeMode() === 'temporary' || this.activeMode() === 'makeup')
        && !this.timeError() && !request.fullDay && this.leaveStart() && this.leaveEnd()
        && request.leaveStart && request.leaveEnd
        && (this.leaveEnd() <= request.leaveStart.slice(0, 5) || this.leaveStart() >= request.leaveEnd.slice(0, 5))));
  }

  readonly selectionBlocked = computed(() => this.selectedDates().some(date => this.conflicting(date)));

  private readonly discardNewlyBlockedDates = effect(() => {
    if (!this.activeMode() || this.activeMode() === 'temporary') return;
    const selected = this.selectedDates();
    const available = selected.filter(date => !this.conflicting(date));
    if (available.length !== selected.length) {
      this.selectedDates.set(available);
      this.error.set('已有待審或已核准重疊申請的日期已取消選取，不能重複申請。');
    }
  });

  readReply(request: DriverLeaveRequestResponse): void {
    this.api.markLeaveRequestRead(request.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: saved => this.submitted.emit([saved]),
      error: () => this.error.set('標記通知已讀失敗，請稍後重試。'),
    });
  }

  canSelectDate(date: Date): boolean {
    if (!this.activeMode()) return true; // Reading a schedule remains possible outside the form.
    if (this.submitting() || !this.leaveType() || this.requestsLoading() || !this.requestsAvailable()) return false;
    const key = calendarDateKey(date);
    const today = todayInTaipei();
    if (this.activeMode() === 'temporary') return false;
    if (this.conflicting(key)) return false;
    // 選日期只依目前已發布班表，不以打卡紀錄或整天補請候選清單鎖住上班日。
    // 有無打卡、時段／各日班次是否合法仍由提交 API 在同一交易中驗證。
    return (this.activeMode() === 'makeup' ? key < today : key > today)
      && this.scheduleReady() && this.shifts().some(shift => shift.workDate === key && shift.shiftType === 'WORK');
  }

  dateClass(date: Date): string {
    return this.activeMode() && this.selectedDates().includes(calendarDateKey(date)) ? 'leave-date-selected' : '';
  }

  toggleDate(date: Date): void {
    if (!this.canSelectDate(date) || !this.activeMode()) return;
    const key = calendarDateKey(date);
    if (!this.selectedDates().includes(key) && this.selectedDates().length >= 62) {
      this.error.set('一次最多選擇 62 天。'); return;
    }
    this.selectedDates.update(dates => dates.includes(key) ? dates.filter(item => item !== key) : [...dates, key].sort());
    this.error.set('');
  }

  removeDate(date: string): void {
    if (this.submitting() || this.activeMode() === 'temporary') return;
    this.selectedDates.update(dates => dates.filter(item => item !== date));
  }

  setEvidence(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    if (file && (file.size > 5 * 1024 * 1024 || !['image/jpeg', 'image/png', 'image/webp'].includes(file.type))) {
      this.error.set('請上傳 5 MB 以內的 JPG、PNG 或 WebP 照片。'); return;
    }
    this.evidence.set(file); this.error.set('');
  }

  submit(): void {
    if (this.submitting()) return;
    const mode = this.activeMode(), type = this.leaveType(), dates = [...this.selectedDates()], reason = this.reason().trim();
    const today = todayInTaipei();
    if (!mode || !type || !dates.length || !reason || reason.length > 500) {
      this.error.set('請選擇假別、日期，並填寫 1 至 500 字的請假原因。'); return;
    }
    if (!this.requestsAvailable() || this.requestsLoading()) {this.error.set('請假紀錄尚未載入，請稍後再送出。'); return;}
    if (dates.some(date => mode === 'planned' ? date <= today : mode === 'makeup' ? date >= today : date !== today)) {
      this.error.set('日期不符合申請類型，請重新選擇。'); return;
    }
    if (dates.some(date => this.conflicting(date))) {this.error.set('所選日期已有待審核或已核准的申請，請移除後再送出。'); return;}
    if (this.timeError()) {this.error.set(this.timeError()); return;}
    let operation: Observable<DriverLeaveRequestResponse[]>;
    if (mode === 'planned') {
      operation = this.api.submitPlannedLeaveBatches({groups: [{workDates: dates, leaveType: type, reason}]})
        .pipe(map(batches => batches.flatMap(batch => batch.items)));
    } else if (mode === 'temporary') {
      operation = this.api.submitLeaveRequest({workDate: today, leaveType: type, reason,
        leaveStart: this.leaveStart() || undefined, leaveEnd: this.leaveEnd() || undefined}).pipe(map(saved => [saved]));
    } else {
      const request = {workDates: dates, leaveType: type, reason,
        ...(this.leaveStart() ? {leaveStart: this.leaveStart(), leaveEnd: this.leaveEnd()} : {})};
      operation = this.evidence() ? this.api.uploadLeaveEvidencePhoto(this.evidence()!).pipe(
        switchMap(upload => this.api.submitMakeupLeaveBatch({...request, evidencePhotoUrl: upload.url})))
        : this.api.submitMakeupLeaveBatch(request);
    }
    this.submitting.set(true); this.error.set('');
    operation.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: saved => {
        this.submitting.set(false); this.cancel();
        this.message.set(`${dates.length} 天的申請已送出，等待主管審核；主管回覆後會顯示通知。`);
        this.submitted.emit(saved);
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        const detail = error instanceof HttpErrorResponse ? error.error?.message : null;
        this.error.set(typeof detail === 'string' ? detail : '申請未完成，資料與選取日期已保留，請稍後重試。');
      },
    });
  }
}

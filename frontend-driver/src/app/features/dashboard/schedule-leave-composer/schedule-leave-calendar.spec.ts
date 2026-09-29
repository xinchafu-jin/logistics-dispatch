import {ANIMATION_MODULE_TYPE, Component, ViewChild, signal} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MAT_DATE_LOCALE, provideNativeDateAdapter} from '@angular/material/core';
import {MatCalendar, MatDatepickerModule} from '@angular/material/datepicker';
import {of} from 'rxjs';
import {DriverOperationsService} from '../../../core/services/driver-operations.service';
import {DriverLeaveRequestResponse, DriverShiftDto} from '../../../core/services/driver-operations.models';
import {ScheduleCellLabels} from '../../../shared/ui/schedule-cell-labels/schedule-cell-labels';
import {calendarDateKey, ScheduleLeaveComposer} from './schedule-leave-composer';
import {pendingLeaveDatesInMonth} from './schedule-leave-status';

@Component({
  imports: [MatDatepickerModule, ScheduleCellLabels, ScheduleLeaveComposer],
  template: `<mat-calendar appScheduleCellLabels class="driver-schedule-calendar" [startAt]="month"
    [selected]="composer.activeMode() ? null : selected" [dateClass]="dateClass" [dateFilter]="dateFilter"
    (selectedChange)="select($event)" />
    <app-schedule-leave-composer #composer [month]="month" [shifts]="shifts" [scheduleReady]="true"
      [requests]="requests()" [requestsAvailable]="true" (calendarChange)="refresh()" />`,
})
class CalendarHost {
  @ViewChild(MatCalendar) calendar!: MatCalendar<Date>;
  @ViewChild(ScheduleLeaveComposer) composer!: ScheduleLeaveComposer;
  month = new Date(2026, 8, 1);
  selected: Date | null = new Date(2026, 8, 27);
  readonly requests = signal<DriverLeaveRequestResponse[]>([]);
  shifts = [1, 18, 24, 25, 28, 29].map(day => ({id: day, workDate: `2026-09-${String(day).padStart(2, '0')}`, shiftType: 'WORK'} as DriverShiftDto));
  readonly dateFilter = (date: Date) => this.composer?.canSelectDate(date) ?? true;
  readonly dateClass = (date: Date, view: string) => view === 'month'
    ? `shift-cell shift-work ${pendingLeaveDatesInMonth(this.requests(), this.month).has(calendarDateKey(date)) ? 'leave-request-pending' : ''} ${this.composer?.dateClass(date) ?? ''}` : '';
  select(date: Date | null): void {if (date) {this.selected = date; this.composer.toggleDate(date);}}
  refresh(): void {this.calendar?.updateTodaysDate();}
}

describe('實際日曆連動請假複選', () => {
  let fixture: ComponentFixture<CalendarHost>;
  let api: {getMakeupLeaveCandidates: ReturnType<typeof vi.fn>};
  beforeEach(async () => {
    vi.useFakeTimers({toFake: ['Date']}); vi.setSystemTime(new Date('2026-09-27T04:00:00Z'));
    api = {getMakeupLeaveCandidates: vi.fn(() => of(['2026-09-24', '2026-09-25']))};
    TestBed.configureTestingModule({imports: [CalendarHost], providers: [provideNativeDateAdapter(),
      {provide: MAT_DATE_LOCALE, useValue: 'zh-TW'}, {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations'},
      {provide: DriverOperationsService, useValue: api}]});
    fixture = TestBed.createComponent(CalendarHost); await render();
  });
  afterEach(() => {fixture.destroy(); vi.useRealTimers();});
  async function render(): Promise<void> {
    fixture.changeDetectorRef.markForCheck();
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); await Promise.resolve();
  }
  function dayCell(day: number): HTMLButtonElement {
    const cells = Array.from(fixture.nativeElement.querySelectorAll('mat-month-view .mat-calendar-body-cell')) as HTMLButtonElement[];
    return cells.find(cell => Number.parseInt(cell.querySelector('.mat-calendar-body-cell-content')!.firstChild!.textContent!.trim(), 10) === day)!;
  }

  it('排假直接點兩個日曆格子可複選，再点同一天可取消，日期與班別不消失', async () => {
    const composer = fixture.componentInstance.composer;
    composer.open('planned'); composer.chooseType('ANNUAL'); await render();
    dayCell(28).click(); await render(); dayCell(29).click(); await render();
    expect(composer.selectedDates()).toEqual(['2026-09-28', '2026-09-29']);
    expect(dayCell(28).classList.contains('leave-date-selected')).toBe(true);
    expect(dayCell(28).getAttribute('aria-pressed')).toBe('true');
    expect(dayCell(28).getAttribute('aria-label')).toContain('已選請假日期');
    expect(dayCell(28).querySelector('.schedule-cell-shift')?.textContent).toBe('上班');
    dayCell(28).click(); await render();
    expect(composer.selectedDates()).toEqual(['2026-09-29']);
    expect(dayCell(28).getAttribute('aria-pressed')).toBe('false');
    expect(dayCell(28).getAttribute('aria-label')).not.toContain('已選請假日期');
  });

  it('排假鎖住過去與今天；補請鎖住今天與未來，切換模式清除原選取', async () => {
    const composer = fixture.componentInstance.composer;
    composer.open('planned'); composer.chooseType('SICK'); await render();
    expect(dayCell(25).getAttribute('aria-disabled')).toBe('true');
    expect(dayCell(27).getAttribute('aria-disabled')).toBe('true');
    dayCell(28).click(); await render();
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    expect(composer.selectedDates()).toEqual([]);
    expect(dayCell(28).getAttribute('aria-disabled')).toBe('true');
    expect(dayCell(27).getAttribute('aria-disabled')).toBe('true');
    expect(dayCell(25).getAttribute('aria-disabled')).not.toBe('true');
    dayCell(24).click(); await render(); dayCell(25).click(); await render();
    expect(composer.selectedDates()).toEqual(['2026-09-24', '2026-09-25']);
  });

  it('過去有打卡的上班日也能直接點日曆選取，補請時間輸入後仍保留日期', async () => {
    api.getMakeupLeaveCandidates.mockReturnValue(of(['2026-09-24']));
    const composer = fixture.componentInstance.composer;
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    expect(dayCell(1).getAttribute('aria-disabled')).not.toBe('true');
    dayCell(1).click(); await render();
    expect(composer.selectedDates()).toEqual(['2026-09-01']);
    const inputs = fixture.nativeElement.querySelectorAll('input[type=time]') as NodeListOf<HTMLInputElement>;
    inputs[0].value = '08:00'; inputs[0].dispatchEvent(new Event('input'));
    inputs[1].value = '09:00'; inputs[1].dispatchEvent(new Event('input')); await render();
    expect(api.getMakeupLeaveCandidates).not.toHaveBeenCalled();
    expect(composer.selectedDates()).toEqual(['2026-09-01']);
    expect(dayCell(1).querySelector('.schedule-cell-shift')?.textContent).toBe('上班');
  });

  it('不用展開申請表單就看得到日曆申請中提示，審核完才解除且保留上班', async () => {
    const host = fixture.componentInstance, composer = host.composer;
    host.requests.set([{id: 18, workDate: '2026-09-18', requestMode: 'MAKEUP', status: 'PENDING',
      submissionSource: 'DRIVER', fullDay: false, leaveStart: '08:00:00', leaveEnd: '09:00:00'} as DriverLeaveRequestResponse]);
    await render();
    expect(composer.requests()).toEqual(host.requests());
    expect(composer.activeMode()).toBeNull();
    expect(dayCell(18).querySelector('.schedule-cell-pending')?.textContent).toBe('申請中');
    expect(dayCell(18).getAttribute('title')).toContain('等待主管審核');
    expect(dayCell(18).querySelector('.schedule-cell-shift')?.textContent).toBe('上班');
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    expect(dayCell(18).getAttribute('aria-disabled')).toBe('true');
    expect(dayCell(18).querySelector('.schedule-cell-pending')?.textContent).toBe('申請中');
    host.requests.set([{...host.requests()[0], status: 'REJECTED'}]); await render();
    expect(dayCell(18).querySelector('.schedule-cell-pending')).toBeNull();
    expect(dayCell(18).getAttribute('title')).toBeNull();
    expect(dayCell(18).getAttribute('aria-disabled')).not.toBe('true');
    expect(dayCell(18).querySelector('.schedule-cell-shift')?.textContent).toBe('上班');
  });
});

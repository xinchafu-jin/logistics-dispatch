import {ComponentFixture, TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {By} from '@angular/platform-browser';
import {Router} from '@angular/router';
import {provideNativeDateAdapter} from '@angular/material/core';
import {EMPTY, of} from 'rxjs';
import {DriverDashboard} from './driver-dashboard';
import {ScheduleLeaveComposer} from './schedule-leave-composer/schedule-leave-composer';
import {DriverAuthService} from '../../core/auth/driver-auth.service';
import {DriverChatSocketService} from '../../core/services/driver-chat-socket.service';
import {DriverGpsTrackingService} from '../../core/services/driver-gps-tracking.service';
import {DriverOperationsService} from '../../core/services/driver-operations.service';
import {DriverWeatherService} from '../../core/services/driver-weather.service';
import {DriverLeaveRequestResponse, DriverShiftDto} from '../../core/services/driver-operations.models';

const date = (day: number) => new Date(2026, 8, day);
const shift = (day: number, shiftType: DriverShiftDto['shiftType'] = 'WORK') => ({
  id: day, workDate: `2026-09-${day}`, shiftType, workStart: '08:00', workEnd: '17:00',
}) as DriverShiftDto;
const request = (day: number, requestMode: DriverLeaveRequestResponse['requestMode'] = 'PREPLANNED') => ({
  id: day, workDate: `2026-09-${day}`, requestMode, status: 'PENDING', submissionSource: 'DRIVER',
  fullDay: true, leaveType: 'ANNUAL', driverReadAt: null, requestedAt: '2026-09-27T12:00:00',
}) as DriverLeaveRequestResponse;

describe('MAJOR uses the complete V3 calendar leave interface', () => {
  let fixture: ComponentFixture<DriverDashboard>;
  let page: any;
  let composer: ScheduleLeaveComposer;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    vi.useFakeTimers({toFake: ['Date']});
    vi.setSystemTime(new Date('2026-09-27T04:00:00Z'));
    vi.spyOn(globalThis, 'setInterval').mockImplementation(() => 0 as any);
    // Keep map, GPS and server startup out of this real-template UI integration test.
    for (const method of ['loadAttendance', 'loadPublishedShifts', 'loadTodayTasks', 'loadProfile', 'loadLeaveRequests', 'connectChatSocket'])
      vi.spyOn(DriverDashboard.prototype as any, method).mockImplementation(() => undefined);
    vi.spyOn(DriverDashboard.prototype as any, 'initializeMap').mockResolvedValue(undefined);
    api = {
      getMakeupLeaveCandidates: vi.fn(() => of(['2026-09-24', '2026-09-25'])),
      submitLeaveRequest: vi.fn(() => of(request(27, 'TEMPORARY'))),
      submitPlannedLeaveBatches: vi.fn(() => of([{items: [request(28)]}])),
      submitMakeupLeaveBatch: vi.fn(() => of([request(24, 'MAKEUP'), request(25, 'MAKEUP')])),
      markLeaveRequestRead: vi.fn(() => of(request(28))),
    };
    TestBed.configureTestingModule({imports: [DriverDashboard], providers: [
      provideNativeDateAdapter(),
      {provide: DriverAuthService, useValue: {user: signal({id: 1, account: 'TEST', name: '測試司機'})}},
      {provide: DriverOperationsService, useValue: api},
      {provide: DriverWeatherService, useValue: {getCurrentWeather: () => EMPTY}},
      {provide: DriverGpsTrackingService, useValue: {stop: vi.fn(), lastUploadedAt: signal(null)}},
      {provide: DriverChatSocketService, useValue: {disconnect: vi.fn(), pushes$: EMPTY, connected$: EMPTY}},
      {provide: Router, useValue: {navigate: vi.fn()}},
    ]});
    fixture = TestBed.createComponent(DriverDashboard);
    page = fixture.componentInstance;
    page.activeTab.set('schedule');
    page.scheduleMonth.set(date(1));
    page.publishedShifts.set([shift(24), shift(25), shift(27), shift(28), shift(29), shift(30, 'DAY_OFF')]);
    page.scheduleViewState.set('ready');
    page.leaveRequests.set([request(29)]);
    page.leaveListAvailable.set(true);
    page.isLeaveListLoading.set(false);
    await render();
    composer = fixture.debugElement.query(By.directive(ScheduleLeaveComposer)).componentInstance;
  });

  afterEach(() => { fixture?.destroy(); vi.restoreAllMocks(); vi.useRealTimers(); });
  async function render() { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); }
  async function openMode(index: number) {
    (fixture.nativeElement.querySelectorAll('.leave-calendar-modes button')[index] as HTMLButtonElement).click();
    await render();
  }

  it('renders all three V3 entry points directly under the calendar, not the old JIN tabs/forms', () => {
    const card = fixture.nativeElement.querySelector('.schedule-calendar-card');
    expect([...card.querySelectorAll('.leave-calendar-modes strong')].map((node: any) => node.textContent))
      .toEqual(['排假', '當日臨請', '事後補請']);
    expect(card.querySelector('app-schedule-leave-composer')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.leave-mode-tabs')).toBeNull();
    expect(fixture.nativeElement.querySelector('.general-leave-panel')).toBeNull();
    expect(fixture.nativeElement.querySelector('.planned-date-options')).toBeNull();
    expect(fixture.nativeElement.querySelector('.emergency-leave-panel')).toBeNull();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.querySelector('.schedule-pending-hint').textContent).toContain('1 天請假申請中');
  });

  it('connects calendar date selection to V3 planned requests and refreshes the pending badges', async () => {
    await openMode(0);
    composer.chooseType('ANNUAL');
    expect(page.scheduleDateFilter(date(28))).toBe(true);
    expect(page.scheduleDateFilter(date(29))).toBe(false);
    expect(page.scheduleDateFilter(date(30))).toBe(false);
    page.selectScheduleDate(date(28));
    await render();
    expect(composer.selectedDates()).toEqual(['2026-09-28']);
    expect(page.scheduleDateClass(date(28), 'month')).toContain('leave-date-selected');
    composer.reason.set('家庭安排'); composer.submit();
    await render();
    expect(api['submitPlannedLeaveBatches']).toHaveBeenCalledWith({groups: [{workDates: ['2026-09-28'], leaveType: 'ANNUAL', reason: '家庭安排'}]});
    expect(page.pendingScheduleDates().has('2026-09-28')).toBe(true);
    expect(page.scheduleDateClass(date(28), 'month')).toContain('leave-request-pending');
    expect(composer.activeMode()).toBeNull();
  });

  it('uses the V3 same-day entry and moves the calendar back to today', async () => {
    page.selectedScheduleDate.set(date(24));
    await openMode(1);
    expect(page.selectedScheduleDate().getDate()).toBe(27);
    composer.chooseType('SPECIAL'); composer.reason.set('今天有特殊事由'); composer.submit();
    await render();
    expect(api['submitLeaveRequest']).toHaveBeenCalledWith(expect.objectContaining({workDate: '2026-09-27', leaveType: 'SPECIAL'}));
  });

  it('uses the V3 multiple-date makeup form rather than the old single-date form', async () => {
    await openMode(2);
    composer.chooseType('SICK');
    page.selectScheduleDate(date(25)); page.selectScheduleDate(date(24));
    composer.reason.set('兩天發燒'); composer.submit();
    await render();
    expect(api['submitMakeupLeaveBatch']).toHaveBeenCalledWith({workDates: ['2026-09-24', '2026-09-25'], leaveType: 'SICK', reason: '兩天發燒'});
    expect(page.leaveRequests().filter((row: DriverLeaveRequestResponse) => row.requestMode === 'MAKEUP')).toHaveLength(2);
  });

  it('keeps the roadside emergency leave/driver handover interface outside the ordinary V3 calendar', async () => {
    page.activeTab.set('profile');
    await render();
    expect(fixture.nativeElement.querySelector('.emergency-leave-panel')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.emergency-leave-panel').textContent).toContain('由主管指定接手司機');
  });
});

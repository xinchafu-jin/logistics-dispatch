import {ANIMATION_MODULE_TYPE} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {DriverSchedule} from './driver-schedule';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';

describe('後台月班表圖示', () => {
  let fixture: ComponentFixture<DriverSchedule>;
  beforeEach(async () => {
    const shifts = ['WORK', 'DAY_OFF', 'LEAVE', 'UNASSIGNED'].map((shiftType, index) => ({
      id: index + 1, driverId: 1, scheduleMonthId: 1, workDate: `2026-09-0${index + 1}`, shiftType,
      workStart: '08:00:00', workEnd: '17:00:00', overtimeMinutes: 0, changeReason: '', version: 0,
    }));
    TestBed.configureTestingModule({imports: [DriverSchedule], providers: [
      {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations'},
      {provide: DispatchApiService, useValue: {
        getDrivers: () => of([{id: 1, account: 'DRV001', name: '測試司機', isActive: true}]),
        getScheduleMonth: () => of({id: 1, scheduleMonth: '2026-09-01', status: 'PUBLISHED'}),
        getScheduleMonthShifts: () => of(shifts), getPendingLeaveRequests: () => of([]), getPendingLeaveBatches: () => of([]),
      }},
    ]});
    fixture = TestBed.createComponent(DriverSchedule); fixture.componentInstance.selectedMonth.set('2026-09');
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
  });
  afterEach(() => fixture.destroy());

  it('上班、休假、請假、未排定都加圖示，保留文字及欄頭日期', () => {
    const buttons = Array.from(fixture.nativeElement.querySelectorAll('.shift-cell')) as HTMLButtonElement[];
    expect(buttons.map(button => button.querySelector('mat-icon')?.textContent?.trim())).toEqual(['work_outline', 'free_breakfast', 'event_busy', 'event_note']);
    expect(buttons.map(button => button.querySelector('span')?.textContent?.trim())).toEqual(['上班', '休假', '請假', '未排定']);
    expect(fixture.nativeElement.querySelectorAll('thead th')).toHaveLength(31);
  });

  it('週日固定公休也有圖示，不變成可編輯班次', () => {
    const sundays = Array.from(fixture.nativeElement.querySelectorAll('td.is-sunday')) as HTMLElement[];
    expect(sundays).toHaveLength(4);
    for (const cell of sundays) {
      expect(cell.querySelector('mat-icon')?.textContent?.trim()).toBe('free_breakfast');
      expect(cell.textContent).toContain('公休');
      expect(cell.querySelector('button')).toBeNull();
    }
  });
});

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
    // 2026-09-06 是週日：後端預設建成休假
    shifts.push({
      id: 5, driverId: 1, scheduleMonthId: 1, workDate: '2026-09-06', shiftType: 'DAY_OFF',
      workStart: '08:00:00', workEnd: '17:00:00', overtimeMinutes: 0, changeReason: '週日公定公休', version: 0,
    });
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
    expect(buttons.map(button => button.querySelector('mat-icon')?.textContent?.trim())).toEqual(['work_outline', 'free_breakfast', 'event_busy', 'event_note', 'free_breakfast']);
    expect(buttons.map(button => button.querySelector('span')?.textContent?.trim())).toEqual(['上班', '休假', '請假', '未排定', '休假']);
    expect(fixture.nativeElement.querySelectorAll('thead th')).toHaveLength(31);
  });

  it('週日預設休假，但跟其他天一樣是可以點的班次', () => {
    const sundays = Array.from(fixture.nativeElement.querySelectorAll('td.is-sunday')) as HTMLElement[];
    expect(sundays).toHaveLength(4);
    const button = sundays[0].querySelector('button.shift-cell');
    expect(button?.classList.contains('is-day-off')).toBe(true);
    expect(button?.querySelector('span')?.textContent?.trim()).toBe('休假');
  });

  it('批次套用的星期可以選週日', () => {
    // 批次面板只在草稿月份顯示，這份假資料是已發布的月份，所以直接看元件提供的選項
    expect(fixture.componentInstance.weekdayOptions.map(weekday => weekday.label)).toEqual(['一', '二', '三', '四', '五', '六', '日']);
  });
});

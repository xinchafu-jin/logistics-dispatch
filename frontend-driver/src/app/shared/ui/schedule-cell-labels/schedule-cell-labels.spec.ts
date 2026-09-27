import {ANIMATION_MODULE_TYPE, Component, ViewChild} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MAT_DATE_LOCALE, provideNativeDateAdapter} from '@angular/material/core';
import {MatCalendar, MatCalendarCellClassFunction, MatDatepickerModule} from '@angular/material/datepicker';
import {ScheduleCellLabels} from './schedule-cell-labels';

@Component({
  imports: [MatDatepickerModule, ScheduleCellLabels],
  template: `<mat-calendar appScheduleCellLabels class="driver-schedule-calendar"
    [startAt]="month" [selected]="selected" [dateClass]="dateClass"
    (selectedChange)="selected = $event!" />`,
})
class CalendarHost {
  @ViewChild(MatCalendar) calendar!: MatCalendar<Date>;
  readonly month = new Date(2026, 8, 1);
  selected = new Date(2026, 8, 27);
  status = 'ready';
  readonly shifts = new Map([[1, 'work'], [2, 'day_off'], [3, 'leave'], [4, 'unassigned'], [27, 'work']]);
  readonly pending = new Set<number>();
  readonly dateClass: MatCalendarCellClassFunction<Date> = (date, view) => {
    if (view !== 'month') return '';
    const shift = this.status === 'ready'
      ? (date.getMonth() === 8 ? this.shifts.get(date.getDate()) : undefined) ?? 'not-published'
      : this.status;
    return `shift-cell shift-${shift} ${this.pending.has(date.getDate()) ? 'leave-request-pending' : ''}`;
  };
}

describe('司機班表日期與班別標示', () => {
  let fixture: ComponentFixture<CalendarHost>;
  let host: CalendarHost;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [CalendarHost],
      providers: [provideNativeDateAdapter(), {provide: MAT_DATE_LOCALE, useValue: 'zh-TW'},
        {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations'}],
    });
    fixture = TestBed.createComponent(CalendarHost);
    host = fixture.componentInstance;
    await render();
  });

  afterEach(() => fixture.destroy());

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await Promise.resolve(); // Allow the native DOM mutation observer to decorate rebuilt cells.
  }

  function dateText(cell: HTMLElement): string {
    const content = cell.querySelector('.mat-calendar-body-cell-content')!;
    return Array.from(content.childNodes).filter(node => node.nodeType === Node.TEXT_NODE)
      .map(node => node.textContent).join('').trim();
  }

  function dayCell(day: number): HTMLButtonElement {
    const cells = Array.from(fixture.nativeElement.querySelectorAll('mat-month-view .mat-calendar-body-cell')) as HTMLButtonElement[];
    return cells.find(cell => Number.parseInt(dateText(cell), 10) === day)!;
  }

  it('上班、休假、請假都保留日期數字，並在日期後加上圖示與文字', () => {
    for (const [day, label] of [[1, '上班'], [2, '休假'], [3, '請假']] as const) {
      const cell = dayCell(day);
      const badge = cell.querySelector('.schedule-cell-shift')!;
      expect(dateText(cell)).toContain(String(day));
      expect(badge.textContent).toBe(label);
      expect(badge.querySelector('svg path')).not.toBeNull();
      expect(badge.getAttribute('aria-hidden')).toBe('true');
      expect(cell.getAttribute('aria-label')).toContain('2026');
      expect(cell.getAttribute('aria-label')).toContain(`，${label}`);
    }
  });

  it('選中日期仍有班別，點日期仍更新原本的選取結果', async () => {
    expect(dayCell(27).querySelector('.mat-calendar-body-selected .schedule-cell-shift')?.textContent).toBe('上班');
    dayCell(2).click();
    await render();
    expect(host.selected.getDate()).toBe(2);
    expect(dayCell(2).querySelector('.mat-calendar-body-selected .schedule-cell-shift')?.textContent).toBe('休假');
  });

  it('重新載入或班別變更後更新標示，不累加重複標示或無障礙文字', async () => {
    host.shifts.set(1, 'leave');
    host.calendar.updateTodaysDate();
    await render();
    host.calendar.updateTodaysDate();
    await render();
    const cell = dayCell(1);
    expect(cell.querySelectorAll('.schedule-cell-shift')).toHaveLength(1);
    expect(cell.querySelector('.schedule-cell-shift')?.textContent).toBe('請假');
    expect(cell.getAttribute('aria-label')?.match(/請假/g)).toHaveLength(1);
    expect(cell.getAttribute('aria-label')).not.toContain('上班');
  });

  it('無班表、未排班、載入中、載入失敗不會被標成上班或休假', async () => {
    expect(dayCell(4).querySelector('.schedule-cell-shift')?.textContent).toBe('未排');
    expect(dayCell(5).querySelector('.schedule-cell-shift')?.textContent).toBe('無班表');
    for (const [state, label] of [['loading', '載入中'], ['unavailable', '未載入']] as const) {
      host.status = state;
      host.calendar.updateTodaysDate();
      await render();
      expect(dayCell(1).querySelector('.schedule-cell-shift')?.textContent).toBe(label);
      expect(dayCell(1).querySelector('svg')).toBeNull();
    }
  });

  it('換月重新標示日期，不把前一個月的班別留在新月份', async () => {
    host.calendar.activeDate = new Date(2026, 9, 1);
    await render();
    expect(dayCell(1).querySelector('.schedule-cell-shift')?.textContent).toBe('無班表');
    expect(fixture.nativeElement.querySelectorAll('.schedule-cell-shift')).toHaveLength(31);
  });

  it('年份與月份選取畫面不出現日期班別標示', async () => {
    host.calendar.currentView = 'multi-year';
    await render();
    expect(fixture.nativeElement.querySelectorAll('.schedule-cell-shift')).toHaveLength(0);
    host.calendar.currentView = 'year';
    await render();
    expect(fixture.nativeElement.querySelectorAll('.schedule-cell-shift')).toHaveLength(0);
    host.calendar.currentView = 'month';
    await render();
    expect(dayCell(1).querySelector('.schedule-cell-shift')?.textContent).toBe('上班');
  });

  it('申請中額外標記，不改變上班班別；標記解除後不殘留', async () => {
    host.pending.add(1); host.calendar.updateTodaysDate(); await render();
    expect(dayCell(1).querySelector('.schedule-cell-shift')?.textContent).toBe('上班');
    expect(dayCell(1).querySelector('.schedule-cell-pending')?.textContent).toBe('申請中');
    expect(dayCell(1).getAttribute('aria-label')).toContain('申請中，等待主管審核，不可重複申請');
    expect(dayCell(1).getAttribute('title')).toContain('等待主管審核');
    host.calendar.updateTodaysDate(); await render();
    expect(dayCell(1).querySelectorAll('.schedule-cell-pending')).toHaveLength(1);
    expect(dayCell(1).getAttribute('aria-label')?.match(/申請中/g)).toHaveLength(1);
    host.pending.clear(); host.calendar.updateTodaysDate(); await render();
    expect(dayCell(1).querySelector('.schedule-cell-pending')).toBeNull();
    expect(dayCell(1).getAttribute('aria-label')).not.toContain('申請中');
    expect(dayCell(1).hasAttribute('title')).toBe(false);
  });
});

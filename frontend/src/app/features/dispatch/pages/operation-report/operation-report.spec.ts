import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { OperationReport } from './operation-report';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {ReportPerformanceDto, ReportWorkforceDto, ReportFleetDto} from '../../../../core/services/dispatch-api.models';

const emptyWorkforce = (): ReportWorkforceDto => ({scheduledWorkShifts: 0, excusedFullDayShifts: 0, dueShifts: 0,
  attendedShifts: 0, onTimeShifts: 0, lateShifts: 0, missingClockInShifts: 0, finishedShifts: 0,
  overtimeShifts: 0, overtimeMinutes: 0, missingTimeShifts: 0, attendanceRate: null, onTimeRate: null, overtimeRate: null});
const emptyFleet = (): ReportFleetDto => ({startedTrips: 0, returnedTrips: 0, openTrips: 0, invalidTrips: 0,
  usedVehicles: 0, distanceRecordedTrips: 0, actualKm: null, returnRate: null});
const performance = (): ReportPerformanceDto => {
  const workforce = {...emptyWorkforce(), scheduledWorkShifts: 2, dueShifts: 2, attendedShifts: 1, onTimeShifts: 1,
    missingClockInShifts: 1, finishedShifts: 1, attendanceRate: 50, onTimeRate: 100, overtimeRate: 0};
  const fleet = {...emptyFleet(), startedTrips: 2, returnedTrips: 1, openTrips: 1, usedVehicles: 1,
    distanceRecordedTrips: 1, actualKm: 0, returnRate: 50};
  return {from: '2026-09-21', to: '2026-09-27', workforce, fleet, shifts: [], trips: [], warehouses: [
    {warehouseId: 1, warehouseName: '測試倉', workforce, fleet},
    {warehouseId: 2, warehouseName: '無訂單倉', workforce: emptyWorkforce(), fleet: emptyFleet()},
  ]};
};

describe('OperationReport chart links to historical query', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<OperationReport>>;
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    navigate = vi.fn(() => Promise.resolve(true));
    TestBed.configureTestingModule({imports: [OperationReport], providers: [
      {provide: Router, useValue: {navigate}},
      {provide: DispatchApiService, useValue: {
        getOrders: () => of([{id: 1, deliveryDate: '2026-09-27', status: 'COMPLETED', storeId: 1, warehouseId: 1}]),
        getReportSummary: () => of({publishedRoutes: 1, dispatchedDrivers: 1, dispatchedVehicles: 1}),
        getReportRoutes: () => of({routes: [{status: 'PUBLISHED', plannedLoadRatePercent: 70, plannedKm: 50, plannedFuelCost: 100, actualKm: null}]}),
        getReportAttendance: () => of({clockInDueShifts: 2, clockedInDueShifts: 1}),
        getReportPerformance: () => of(performance()),
        getReportWarehouses: () => of({warehouses: [{warehouseId: 1, warehouseName: '測試倉', orders: 1, completedOrders: 1, publishedRoutes: 1, unassignedConfirmedOrders: 0}]}),
        getReportExceptions: () => of({recordedCases: 1, openCases: 1, closedCases: 0}),
      }},
    ]});
    fixture = TestBed.createComponent(OperationReport);
    fixture.detectChanges();
  });
  afterEach(() => fixture.destroy());

  it('keeps report sections without the extra shortcut navigation', () => {
    expect(fixture.nativeElement.querySelector('.section-jump')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.report-section').length).toBe(5);
  });

  it('shows a named ratio with its calculation basis in every major section', () => {
    const metrics = [...fixture.nativeElement.querySelectorAll('.report-section > .section-heading .section-ratio')] as HTMLElement[];
    expect(metrics).toHaveLength(5);
    expect(metrics.map(metric => metric.querySelector('span')?.textContent?.trim()))
      .toEqual(['出勤率', '收車完成率', '訂單完成率', '本期案件結案率', '各倉加總完成率']);
    for (const metric of metrics) {
      expect(metric.querySelector('strong')?.textContent).toMatch(/\d+\.\d%/);
      expect(metric.querySelector('small')?.textContent?.trim()).toBeTruthy();
    }
  });

  it('uses actual departure and return records, not loading averages or record coverage as performance', () => {
    expect(fixture.componentInstance.capacity()?.fleet).toMatchObject({startedTrips: 2, returnedTrips: 1, actualKm: 0, returnRate: 50});
    const fleet = fixture.nativeElement.querySelector('#fleet');
    expect(fleet.textContent).toContain('實際出車');
    expect(fleet.textContent).toContain('50.0%');
    expect(fleet.querySelector('.mileage-ratio-card')).toBeNull();
    const returned = [...fleet.querySelectorAll('.execution-grid button')].find((button: any) => button.textContent.includes('收車完成率')) as HTMLButtonElement;
    returned.click();
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-27', sheet: 'vehicles'}});
  });

  it('keeps every warehouse even without orders and links personnel ratios with warehouse filters', () => {
    expect(fixture.componentInstance.warehouseRows()).toHaveLength(2);
    const rows = fixture.nativeElement.querySelectorAll('.warehouse-row:not(.warehouse-head)');
    expect(rows[1].textContent).toContain('無訂單倉');
    expect(rows[1].textContent).toContain('—');
    rows[1].querySelector('button').click();
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-27', warehouseId: 2, sheet: 'attendance'}});
  });

  it('keeps ratios unavailable rather than inventing zero percent when no denominator exists', () => {
    const page = fixture.componentInstance;
    page.operationalData.update(data => ({...data!, performance: {...data!.performance!, workforce: emptyWorkforce(), fleet: emptyFleet(), warehouses: []},
      exceptions: {...data!.exceptions!, recordedCases: 0, openCases: 0, closedCases: 0},
      warehouses: {...data!.warehouses!, warehouses: []}}));
    page.orders.set([]); fixture.detectChanges();
    expect(page.capacity()).toMatchObject({fleet: {returnRate: null}, people: {attendanceRate: null}, caseClosureRate: null});
    expect(page.warehouseTotals()?.completionRate).toBeNull();
    const metrics = [...fixture.nativeElement.querySelectorAll('.section-ratio > strong')] as HTMLElement[];
    expect(metrics.every(metric => metric.textContent?.trim() === '—')).toBe(true);
    expect(fixture.nativeElement.querySelector('#people').textContent).toContain('本期沒有已發布的上班班次');
    expect(fixture.nativeElement.querySelector('#exceptions > header').textContent).toContain('本期無異常案件');
  });

  it('weights warehouse completion by order counts rather than averaging warehouse percentages', () => {
    const page = fixture.componentInstance;
    page.orders.set(Array.from({length: 10}, (_, index) => ({id: index + 1, orderNumber: String(index + 1),
      warehouseId: index === 0 ? 1 : 2, deliveryDate: '2026-09-27', status: index < 2 ? 'COMPLETED' : 'CONFIRMED'})) as never);
    page.operationalData.update(data => ({...data!, warehouses: {...data!.warehouses!, warehouses: [
      {warehouseId: 1, orders: 1}, {warehouseId: 2, orders: 9},
    ]}}));
    fixture.detectChanges();
    expect(page.warehouseTotals()).toEqual({eligible: 10, completed: 2, completionRate: 20});
    expect(fixture.nativeElement.querySelector('#warehouses .section-ratio strong').textContent).toBe('20.0%');
  });

  it('opens the selected full month from the annual chart', () => {
    const page = fixture.componentInstance;
    page.activePeriod.set('year'); fixture.detectChanges();
    const september = fixture.nativeElement.querySelectorAll('.chart-column')[8] as HTMLButtonElement;
    expect(september.getAttribute('aria-label')).toContain('2026-09-01 至 2026-09-30');
    september.click();
    expect(navigate).toHaveBeenCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-01', to: '2026-09-30', sheet: 'orders'}});
    expect(page.activePeriod()).toBe('year');
  });

  it('does not blank warehouses and exception records when performance fails, and allows retry', () => {
    const api = TestBed.inject(DispatchApiService);
    const request = vi.spyOn(api, 'getReportPerformance').mockReturnValue(throwError(() => new Error('404')));
    fixture.nativeElement.querySelector('.period-tabs button').click(); fixture.detectChanges();
    expect(fixture.componentInstance.capacity()).toBeNull();
    expect(fixture.componentInstance.caseStats()?.recordedExceptions).toBe(1);
    expect(fixture.nativeElement.querySelector('#warehouses').textContent).toContain('測試倉');
    expect(fixture.nativeElement.querySelector('#exceptions .case-counts')).not.toBeNull();
    const retry = [...fixture.nativeElement.querySelectorAll('button')].find((button: any) => button.textContent.includes('重新載入報表')) as HTMLButtonElement;
    expect(retry).toBeTruthy();
    request.mockReturnValue(of(performance())); retry.click(); fixture.detectChanges();
    expect(fixture.componentInstance.capacity()?.people.attendanceRate).toBe(50);
    expect(fixture.componentInstance.operationalError()).toBe('');
  });

  it('keeps people and fleet available when a separate exception query fails', () => {
    vi.spyOn(TestBed.inject(DispatchApiService), 'getReportExceptions').mockReturnValue(throwError(() => new Error('500')));
    fixture.nativeElement.querySelector('.period-tabs button').click(); fixture.detectChanges();
    expect(fixture.componentInstance.capacity()?.fleet.returnRate).toBe(50);
    expect(fixture.componentInstance.caseStats()).toBeNull();
    expect(fixture.nativeElement.querySelector('#people .execution-grid')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#exceptions').textContent).toContain('異常案件暫時無法載入');
  });

  it('does not default to a future week just because future delivery orders exist', () => {
    const page = fixture.componentInstance;
    vi.spyOn(page as any, 'todayTaipei').mockReturnValue('2026-09-27');
    page.orders.set([{id: 1, deliveryDate: '2026-10-04', status: 'CONFIRMED', warehouseId: 1}] as never);
    (page as any).syncPeriodToAvailableData(); fixture.detectChanges();
    expect(page.report().range).toBe('2026/09/21 — 2026/09/27');
    page.operationalData.update(data => ({...data!, performance: {...data!.performance!,
      workforce: {...emptyWorkforce(), scheduledWorkShifts: 3}}})); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#people').textContent).toContain('尚未到應上班時間');
    expect(fixture.nativeElement.querySelector('#people').textContent).not.toContain('暫時無法載入');
  });

  it('groups a month by natural weeks and keeps the final partial week separate', () => {
    const page = fixture.componentInstance;
    page.activePeriod.set('month'); page.selectedYear.set(2026); page.selectedMonth.set(8); fixture.detectChanges();
    const fourthWeek = fixture.nativeElement.querySelectorAll('.chart-column')[3] as HTMLButtonElement;
    fourthWeek.click();
    expect(navigate).toHaveBeenCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-27', sheet: 'orders'}});
    const bars = fixture.nativeElement.querySelectorAll('.chart-column');
    expect(bars.length).toBe(5);
    (bars[4] as HTMLButtonElement).click();
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-09-30', sheet: 'orders'}});
  });

  it('opens exactly one selected day from the weekly chart', () => {
    const page = fixture.componentInstance;
    page.activePeriod.set('week'); page.selectedYear.set(2026); page.selectedMonth.set(8); page.selectedWeek.set(3); fixture.detectChanges();
    const firstDay = fixture.nativeElement.querySelectorAll('.chart-column')[0] as HTMLButtonElement;
    firstDay.click();
    expect(navigate).toHaveBeenCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-21', sheet: 'orders'}});
  });

  it('keeps completed counts, total orders and the eligible denominator distinct', () => {
    const page = fixture.componentInstance;
    page.orders.set([
      {id: 1, orderNumber: 'A', deliveryDate: '2026-09-27', status: 'COMPLETED', boxCount: 10},
      {id: 2, orderNumber: 'B', deliveryDate: '2026-09-27', status: 'CONFIRMED', boxCount: 5},
      {id: 3, orderNumber: 'C', deliveryDate: '2026-09-27', status: 'PENDING_CONFIRM', boxCount: 3},
      {id: 4, orderNumber: 'D', deliveryDate: '2026-09-27', status: 'CANCELLED', boxCount: 2},
    ] as never);
    page.selectedYear.set(2026); page.selectedMonth.set(8); page.selectedWeek.set(3);
    fixture.detectChanges();
    expect(page.report()).toMatchObject({total: 4, boxes: 20, completed: 1, eligible: 2, rate: 50});
    const sunday = [...fixture.nativeElement.querySelectorAll('.chart-column')]
      .find((button: HTMLButtonElement) => button.getAttribute('aria-label')?.includes('2026-09-27'));
    expect(sunday).toBeUndefined();
    expect(fixture.nativeElement.querySelector('.chart-footnote').textContent).toContain('4 筆週日歷史訂單');
    expect(fixture.nativeElement.querySelector('.chart-scale').textContent).not.toContain('%');
  });

  it('shows only Monday through Saturday, keeping the weekly query exactly seven days', () => {
    const page = fixture.componentInstance;
    page.activePeriod.set('week'); page.selectedYear.set(2026); page.selectedMonth.set(8); page.selectedWeek.set(3);
    fixture.detectChanges();
    const bars = fixture.nativeElement.querySelectorAll('.chart-column') as NodeListOf<HTMLButtonElement>;
    expect(bars.length).toBe(6);
    expect(bars[0].getAttribute('aria-label')).toContain('2026-09-21');
    expect(bars[5].getAttribute('aria-label')).toContain('2026-09-26');
    expect(page.report().range).toBe('2026/09/21 — 2026/09/27');
  });

  it('moves exactly one week across month and year boundaries without duplication', () => {
    const page = fixture.componentInstance;
    page.selectedYear.set(2026); page.selectedMonth.set(8); page.selectedWeek.set(4);
    fixture.detectChanges();
    expect(page.report().range).toBe('2026/09/28 — 2026/10/04');
    fixture.nativeElement.querySelector('[aria-label="下一期"]').click(); fixture.detectChanges();
    expect(page.report().range).toBe('2026/10/05 — 2026/10/11');
    fixture.nativeElement.querySelector('[aria-label="上一期"]').click(); fixture.detectChanges();
    expect(page.report().range).toBe('2026/09/28 — 2026/10/04');
    page.selectedYear.set(2026); page.selectedMonth.set(11); page.selectedWeek.set(4);
    fixture.detectChanges();
    expect(page.report().range).toBe('2026/12/28 — 2027/01/03');
    fixture.nativeElement.querySelector('[aria-label="下一期"]').click(); fixture.detectChanges();
    expect(page.report().range).toBe('2027/01/04 — 2027/01/10');
  });

  it('partitions every day of a month once, including six-week months and leap days', () => {
    const page = fixture.componentInstance;
    page.activePeriod.set('month');
    for (const [year, month, days, weeks] of [[2026, 7, 31, 6], [2028, 1, 29, 5], [2027, 1, 28, 4]]) {
      page.selectedYear.set(year); page.selectedMonth.set(month); fixture.detectChanges();
      const buckets = page.chartData();
      expect(buckets.length).toBe(weeks);
      const dates: string[] = [];
      for (const bucket of buckets) {
        let count = 0;
        const end = new Date(`${bucket.to}T00:00:00`);
        for (const date = new Date(`${bucket.from}T00:00:00`); date <= end; date.setDate(date.getDate() + 1)) {
          expect(date.getMonth()).toBe(month);
          dates.push(String(date.getDate())); count++;
        }
        expect(count).toBeLessThanOrEqual(7);
      }
      expect(dates.length).toBe(days);
      expect(new Set(dates).size).toBe(days);
    }
  });

  it('keeps route plans, attendance and saved exceptions separate from order completion', () => {
    const page = fixture.componentInstance;
    expect(page.capacity()).toMatchObject({fleet: {startedTrips: 2, returnedTrips: 1},
      people: {dueShifts: 2, attendedShifts: 1, onTimeRate: 100, overtimeRate: 0}, recordedExceptions: 1, openedExceptions: 1});
    expect(page.warehouseRows()[0].warehouseName).toBe('測試倉');
    expect(page.warehouseRows()[0].completionRate).toBe(100);
    expect(fixture.nativeElement.textContent).toContain('缺紀錄不補 0');
    expect([...fixture.nativeElement.querySelectorAll('.report-section')].map((section: HTMLElement) => section.id))
      .toEqual(['people', 'fleet', 'orders', 'exceptions', 'warehouses']);
  });

  it('drills metric cards and status counts into the matching detail sheet', () => {
    const buttons = [...fixture.nativeElement.querySelectorAll('button')] as HTMLButtonElement[];
    buttons.find((button) => button.textContent?.includes('準時打卡率'))!.click();
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-27', sheet: 'attendance'}});
    buttons.find((button) => button.classList.contains('kpi-card') && button.textContent?.includes('待排率'))!.click();
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-27', sheet: 'orders', status: 'CONFIRMED'}});
    buttons.find((button) => button.classList.contains('status-item') && button.textContent?.includes('已完成'))!.click();
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-27', sheet: 'orders', status: 'COMPLETED'}});
  });
});

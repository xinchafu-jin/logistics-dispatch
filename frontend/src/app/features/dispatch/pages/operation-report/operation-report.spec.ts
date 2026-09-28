import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {of, Subject, throwError} from 'rxjs';
import {OperationReport} from './operation-report';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {OrderDto, ReportOutcomesDto, ReportPerformanceDto, ReportWorkforceDto} from '../../../../core/services/dispatch-api.models';

const orders = (): OrderDto[] => (['PENDING_CONFIRM', 'CONFIRMED', 'LOADED', 'IN_DELIVERY',
  'COMPLETED', 'CANCELLED', 'FAILED', 'NO_SIGNATURE'] as OrderDto['status'][]).map((status, i) => ({
    id: i + 1, orderNumber: `TEST-${i + 1}`, storeId: 1, warehouseId: 1, boxCount: 15, notes: '',
    deliveryDate: '2026-09-28', status,
  }));

const emptyWorkforce = (): ReportWorkforceDto => ({scheduledWorkShifts: 0, excusedFullDayShifts: 0, dueShifts: 0,
  attendedShifts: 0, onTimeShifts: 0, lateShifts: 0, missingClockInShifts: 0, finishedShifts: 0,
  overtimeShifts: 0, overtimeMinutes: 0, missingTimeShifts: 0, attendanceRate: null, onTimeRate: null, overtimeRate: null});
const performance = (): ReportPerformanceDto => ({
  from: '2026-09-28', to: '2026-10-04',
  workforce: {...emptyWorkforce(), dueShifts: 4, attendedShifts: 3, onTimeShifts: 2, lateShifts: 1, missingClockInShifts: 1,
    finishedShifts: 2, overtimeShifts: 1, overtimeMinutes: 45, attendanceRate: 75, onTimeRate: 200 / 3, overtimeRate: 50},
  fleet: {startedTrips: 2, returnedTrips: 1, openTrips: 1, invalidTrips: 0,
    usedVehicles: 1, distanceRecordedTrips: 1, actualKm: 30, returnRate: 50},
  warehouses: [], shifts: [], trips: [{mileageId: 1, date: '2026-09-28', actualKm: 30},
    {mileageId: 2, date: '2026-09-28', actualKm: null}],
});
const outcomes = (): ReportOutcomesDto => {
  const delivery = {dueOrders: 4, deliveredOrders: 3, fullOrders: 2, outstandingOrders: 1,
    windowEligibleOrders: 4, windowArrivals: 2, lateArrivals: 1, earlyArrivals: 0, missingArrivalOrders: 1,
    missingWindowOrders: 0, missingQualityOrders: 0, fullDeliveryRate: 50, receivingWindowRate: 50};
  const loading = {checkedOrders: 3, matchedOrders: 2, mismatchedOrders: 1, missingLoadingOrders: 0, dueUnassignedOrders: 0, matchRate: 200 / 3};
  const problems = {assessedOrders: 4, affectedOrders: 2, shortageOrders: 1, damagedOrders: 1, noSignatureOrders: 1, issueRate: 50};
  return {from: '2026-09-28', to: '2026-10-04', asOf: '2026-09-28T20:00:00', delivery, loading, problems,
    safety: {assignedRoutes: 3, inspectedRoutes: 2, passedRoutes: 1, failedRoutes: 1, missingInspectionRoutes: 1, inspectionPassRate: 50},
    recovery: {attemptedOrders: 3, recoveryOrders: 2, attemptedRecoveryOrders: 1, deliveredRecoveryOrders: 1, outstandingRecoveryOrders: 1, recoveryShare: 100 / 3},
    warehouses: [{warehouseId: 1, warehouseName: '左營倉', delivery, loading, problems},
      {warehouseId: 2, warehouseName: '台南倉', delivery, loading: {...loading, checkedOrders: 0, matchedOrders: 0, matchRate: null}, problems}],
    daily: [{date: '2026-09-28', dueOrders: 4, fullOrders: 2}], orders: [],
  };
};

describe('OperationReport purpose-specific visualizations', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<OperationReport>>;
  let navigate: ReturnType<typeof vi.fn>;
  let api: {getReportPerformance: ReturnType<typeof vi.fn>; getReportOutcomes: ReturnType<typeof vi.fn>; getOrders: ReturnType<typeof vi.fn>};
  beforeEach(() => {
    vi.useFakeTimers({toFake: ['Date']}); vi.setSystemTime(new Date('2026-09-28T12:00:00Z'));
    navigate = vi.fn(() => Promise.resolve(true));
    api = {getReportPerformance: vi.fn(() => of(performance())), getReportOutcomes: vi.fn(() => of(outcomes())),
      getOrders: vi.fn(() => of(orders()))};
    TestBed.configureTestingModule({imports: [OperationReport], providers: [
      {provide: Router, useValue: {navigate}}, {provide: DispatchApiService, useValue: api}]});
    fixture = TestBed.createComponent(OperationReport);
    // Existing chart and drill-down scenarios explicitly exercise the current week.
    fixture.componentInstance.activePeriod.set('week'); fixture.detectChanges();
  });
  afterEach(() => { fixture.destroy(); vi.useRealTimers(); });
  const text = (selector: string) => fixture.nativeElement.querySelector(selector)?.textContent ?? '';
  const update = (data: ReportOutcomesDto) => {
    fixture.componentInstance.operationalData.update(source => ({...source!, outcomes: data})); fixture.detectChanges();
  };
  const click = (selector: string) => (fixture.nativeElement.querySelector(selector) as HTMLButtonElement).click();

  it('opens on the current month and includes completed prior-week activity in charts and drill-downs', () => {
    fixture.destroy();
    const p = performance(); p.from = '2026-09-01'; p.to = '2026-09-30';
    p.trips = p.trips.map(trip => ({...trip, date: '2026-09-21'}));
    const d = outcomes(); d.from = p.from; d.to = p.to;
    d.daily = [{date: '2026-09-21', dueOrders: 4, fullOrders: 2}];
    api.getReportPerformance.mockReturnValue(of(p)); api.getReportOutcomes.mockReturnValue(of(d));
    fixture = TestBed.createComponent(OperationReport); fixture.detectChanges();
    expect(fixture.componentInstance.activePeriod()).toBe('month');
    expect(text('.period-stepper strong')).toBe('2026/09/01 — 2026/09/30');
    expect(text('.period-tabs .is-active')).toBe('月度');
    const query = {period: 'CUSTOM', from: '2026-09-01', to: '2026-09-30'};
    for (const read of [api.getReportPerformance, api.getReportOutcomes]) expect(read).toHaveBeenLastCalledWith(query);
    expect(api.getOrders).toHaveBeenLastCalledWith();
    expect(text('#people .rate-card')).toContain('75');
    expect(text('#fleet .score-item')).toContain('2次');
    expect(text('#orders .score-item')).toContain('50.0%');
    expect(text('#unsettled-orders .score-item')).toContain('4筆');
    expect(text('#warehouses .score-item')).toContain('66.7%');
    expect(fixture.componentInstance.tripBuckets()?.[3]).toMatchObject({from: '2026-09-21', to: '2026-09-27', count: 2});
    expect(fixture.componentInstance.orderBuckets()?.[3]).toMatchObject({count: 2, other: 2});
    expect(fixture.nativeElement.querySelector('#fleet .chart-empty')).toBeNull();
    click('#fleet .column:nth-child(4)');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-21', to: '2026-09-27', sheet: 'vehicles'}});
    click('#people .rate-card');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: query.from, to: query.to, sheet: 'attendance', metric: 'clocked-in'}});
  });

  it('does not pull monthly or prior-week records into an explicitly selected empty week', () => {
    click('.period-tabs button:nth-child(2)'); fixture.detectChanges();
    expect(text('#fleet .score-item')).toContain('2次');
    const p = performance(); p.trips = []; p.workforce = emptyWorkforce();
    p.fleet = {...p.fleet, startedTrips: 0, returnedTrips: 0, openTrips: 0, usedVehicles: 0, distanceRecordedTrips: 0, actualKm: null, returnRate: null};
    api.getReportPerformance.mockReturnValue(of(p));
    click('.period-tabs button:nth-child(3)'); fixture.detectChanges();
    expect(fixture.componentInstance.activePeriod()).toBe('week');
    expect(api.getReportPerformance).toHaveBeenLastCalledWith({period: 'CUSTOM', from: '2026-09-28', to: '2026-10-04'});
    expect(text('.period-stepper strong')).toBe('2026/09/28 — 2026/10/04');
    expect(text('#fleet .score-item')).toContain('0次');
    expect(text('#fleet .chart-empty')).toContain('無出車紀錄');
    expect(fixture.nativeElement.querySelectorAll('.rate-ring.is-empty')).toHaveLength(3);
  });

  it('keeps personnel, fleet, delivery outcomes, unfinished orders and warehouses in order', () => {
    expect([...fixture.nativeElement.querySelectorAll('.report-sections > section')].map((s: HTMLElement) => s.id))
      .toEqual(['people', 'fleet', 'orders', 'unsettled-orders', 'warehouses']);
    expect(api.getReportOutcomes).toHaveBeenCalledWith({period: 'CUSTOM', from: '2026-09-28', to: '2026-10-04'});
  });

  it('offers a daily view and scopes every request, chart and drill-down to that day', () => {
    const p = performance(); p.trips.push({mileageId: 3, date: '2026-09-27'}, {mileageId: 4, date: '2026-09-29'});
    const d = outcomes(); d.daily.push({date: '2026-09-27', dueOrders: 10, fullOrders: 9},
      {date: '2026-09-29', dueOrders: 12, fullOrders: 11});
    api.getReportPerformance.mockReturnValue(of(p)); api.getReportOutcomes.mockReturnValue(of(d));
    expect([...fixture.nativeElement.querySelectorAll('.period-tabs button')].map((b: HTMLElement) => b.textContent))
      .toEqual(['年度', '月度', '週別', '日別']);
    click('.period-tabs button:nth-child(4)'); fixture.detectChanges();
    expect(fixture.componentInstance.activePeriod()).toBe('day');
    expect(text('.period-stepper strong')).toBe('2026/09/28');
    const query = {period: 'CUSTOM', from: '2026-09-28', to: '2026-09-28'};
    for (const read of [api.getReportPerformance, api.getReportOutcomes]) expect(read).toHaveBeenLastCalledWith(query);
    expect(api.getOrders).toHaveBeenLastCalledWith();
    expect(fixture.componentInstance.tripBuckets()).toHaveLength(1);
    expect(fixture.componentInstance.tripBuckets()?.[0]).toMatchObject({from: query.from, to: query.to, count: 2});
    expect(fixture.componentInstance.orderBuckets()).toHaveLength(1);
    expect(fixture.componentInstance.orderBuckets()?.[0]).toMatchObject({count: 2, other: 2});
    expect(text('#fleet .chart-heading')).toContain('當日');
    click('#people .rate-card');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {
      queryParams: {from: query.from, to: query.to, sheet: 'attendance', metric: 'clocked-in'}});
    click('#orders .column');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {
      queryParams: {from: query.from, to: query.to, sheet: 'delivery-quality'}});
    click('.warehouse-comparison button');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {
      queryParams: {from: query.from, to: query.to, sheet: 'loading-quality', warehouseId: 1}});
  });

  it.each([
    {year: 2026, month: 8, day: 30, next: '2026-10-01', original: '2026-09-30'},
    {year: 2026, month: 11, day: 31, next: '2027-01-01', original: '2026-12-31'},
    {year: 2024, month: 1, day: 28, next: '2024-02-29', original: '2024-02-28'},
  ])('moves one day at a time across calendar boundaries: $original', ({year, month, day, next, original}) => {
    const page = fixture.componentInstance;
    page.selectedYear.set(year); page.selectedMonth.set(month); page.selectedDay.set(day);
    click('.period-tabs button:nth-child(4)'); fixture.detectChanges();
    click('[aria-label="下一期"]'); fixture.detectChanges();
    expect(api.getReportPerformance).toHaveBeenLastCalledWith({period: 'CUSTOM', from: next, to: next});
    expect(text('.period-stepper strong')).toBe(next.replaceAll('-', '/'));
    click('[aria-label="上一期"]'); fixture.detectChanges();
    expect(api.getReportPerformance).toHaveBeenLastCalledWith({period: 'CUSTOM', from: original, to: original});
  });

  it('clamps the selected day to the month when changing from a longer month', () => {
    const page = fixture.componentInstance;
    page.selectedYear.set(2026); page.selectedMonth.set(0); page.selectedDay.set(31);
    click('.period-tabs button:nth-child(2)');
    click('[aria-label="下一期"]');
    click('.period-tabs button:nth-child(4)'); fixture.detectChanges();
    expect(api.getReportPerformance).toHaveBeenLastCalledWith({period: 'CUSTOM', from: '2026-02-28', to: '2026-02-28'});
    expect(text('.period-stepper strong')).toBe('2026/02/28');
  });
  it('uses circles only for the three personnel percentages with explicit denominators', () => {
    const labels = [...fixture.nativeElement.querySelectorAll('#people .rate-label')].map((s: HTMLElement) => s.childNodes[0].textContent?.trim());
    expect(labels).toEqual(['打卡率', '準時上班率', '加班率']);
    const dials = fixture.nativeElement.querySelectorAll('#people .rate-card');
    expect(dials[0].textContent).toContain('75'); expect(dials[0].textContent).toContain('3 / 4 班');
    expect(dials[1].textContent).toContain('66.7'); expect(dials[1].textContent).toContain('2 / 3 班');
    expect(dials[2].textContent).toContain('50'); expect(dials[2].textContent).toContain('1 / 2 班');
    expect(text('#people')).toContain('45 分鐘');
    expect(fixture.nativeElement.querySelectorAll('.rate-ring')).toHaveLength(3);
    expect(fixture.nativeElement.querySelector('.category-bar')).toBeNull();
  });
  it('shows actual departures, partial actual distance and recovery share, not fleet health or claimed extra costs', () => {
    expect(text('#fleet .score-label')).toBe('出車次數');
    expect(text('#fleet .score-item')).toContain('2次');
    expect(text('#fleet')).toContain('30 km'); expect(text('#fleet')).toContain('1 趟里程未完整');
    expect(text('#fleet')).toContain('已執行補送'); expect(text('#fleet')).toContain('33.3%');
    expect(text('#fleet')).not.toContain('車況可用率'); expect(text('#fleet')).not.toContain('收車完成率');
    expect(fixture.componentInstance.tripBuckets()?.[0].count).toBe(2);
    click('#fleet .inline-detail');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-10-04', sheet: 'recovery'}});
  });
  it('charts complete deliveries against the remaining due orders, not order status', () => {
    expect(fixture.componentInstance.orderBuckets()?.[0]).toMatchObject({count: 2, other: 2});
    const bar = fixture.nativeElement.querySelector('#orders .column');
    expect(bar.getAttribute('aria-label')).toContain('完整 2／到期 4 筆');
    expect((bar.querySelector('.column-fill') as HTMLElement).style.height).toBe('50%');
    expect((bar.querySelector('.column-other') as HTMLElement).style.height).toBe('50%');
    bar.click();
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-09-28', sheet: 'delivery-quality'}});
  });
  it('uses colored order-progress categories and opens the corresponding unfinished orders', () => {
    expect(text('#unsettled-orders .score-item')).toContain('本期未結單');
    expect(text('#unsettled-orders .score-item')).toContain('本期訂單 8 筆 · 已完成 1 筆');
    expect(fixture.componentInstance.unsettledStats()).toEqual({total: 8, unsettled: 4, completed: 1});
    expect(fixture.componentInstance.unsettledTiles()?.map(tile => tile.value)).toEqual([1, 2, 1]);
    expect(fixture.nativeElement.querySelectorAll('#unsettled-orders .issue-column')).toHaveLength(3);
    expect(text('#unsettled-orders')).toContain('待確認');
    expect(text('#unsettled-orders')).toContain('待出貨');
    expect(text('#unsettled-orders')).toContain('配送中');
    expect(text('#unsettled-orders')).not.toContain('倉庫點交不符');
    click('#unsettled-orders .row-link');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-10-04', sheet: 'orders', metric: 'unsettled'}});
    click('#unsettled-orders .issue-column:nth-child(2)');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-10-04', sheet: 'orders', metric: 'awaiting-delivery'}});
  });
  it('compares all warehouses and shows no track for an undefined rate', () => {
    expect(text('#warehouses')).toContain('左營倉'); expect(text('#warehouses')).toContain('台南倉');
    expect(text('#warehouses .score-item')).toContain('66.7%');
    expect(fixture.nativeElement.querySelectorAll('.warehouse-track')).toHaveLength(1);
    expect(text('.warehouse-no-data')).toContain('尚無點交紀錄');
    click('.warehouse-comparison button');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-10-04', sheet: 'loading-quality', warehouseId: 1}});
  });

  it('scopes unfinished counts to the selected day, week, month or year by delivery date', () => {
    const page = fixture.componentInstance;
    const dates = ['2025-12-31', '2026-01-01', '2026-09-01', '2026-09-27', '2026-09-28', '2026-09-30', '2026-10-01', '2027-01-01'];
    page.operationalData.update(source => ({...source!, orders: dates.map((deliveryDate, i) => ({
      ...orders()[0], id: i + 1, deliveryDate, status: 'CONFIRMED',
    }))}));
    page.activePeriod.set('day');
    expect(page.unsettledStats()?.unsettled).toBe(1);
    page.activePeriod.set('week');
    expect(page.unsettledStats()?.unsettled).toBe(3);
    page.activePeriod.set('month');
    expect(page.unsettledStats()?.unsettled).toBe(4);
    page.activePeriod.set('year');
    expect(page.unsettledStats()?.unsettled).toBe(6);
  });
  it('deep links each personnel circle and distance metric to their exact rows', () => {
    for (const [i, metric] of ['clocked-in', 'on-time', 'overtime'].entries()) {
      click(`#people .rate-card:nth-child(${i + 1})`);
      expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-10-04', sheet: 'attendance', metric}});
    }
    click('#fleet .support-metrics button');
    expect(navigate).toHaveBeenLastCalledWith(['/dispatch/history'], {queryParams: {from: '2026-09-28', to: '2026-10-04', sheet: 'vehicles', metric: 'distance-recorded'}});
  });
  it('renders clean empty states without fake zero-percent success or empty bar tracks', () => {
    fixture.componentInstance.operationalData.update(source => ({...source!, performance: {
      ...source!.performance!, workforce: emptyWorkforce(), trips: [], fleet: {...source!.performance!.fleet, startedTrips: 0, actualKm: null}}}));
    const d = outcomes(); d.daily = []; d.delivery.fullDeliveryRate = null;
    d.problems = {...d.problems, affectedOrders: 0, shortageOrders: 0, damagedOrders: 0, noSignatureOrders: 0, issueRate: null};
    fixture.componentInstance.operationalData.update(source => ({...source!, orders: []}));
    update(d);
    expect(fixture.nativeElement.querySelectorAll('.rate-ring.is-empty')).toHaveLength(3);
    expect(text('#fleet .chart-empty')).toContain('無出車紀錄');
    expect(text('#orders .chart-empty')).toContain('無到期應配送訂單');
    expect(text('#unsettled-orders .chart-empty')).toContain('無未結單訂單');
    expect(fixture.nativeElement.querySelector('.issue-fill')).toBeNull();
    expect(text('#fleet')).not.toContain('0 km');
  });
  it('keeps a true zero rate distinct from missing data', () => {
    fixture.componentInstance.operationalData.update(source => ({...source!, performance: {...source!.performance!,
      workforce: {...source!.performance!.workforce, onTimeRate: 0, onTimeShifts: 0}}}));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.rate-ring.is-empty')).toHaveLength(0);
    expect(text('#people .rate-card:nth-child(2)')).toContain('0');
  });
  it('explains weighted totals, cost limitations, current hours and unknown quality in collapsed notes', () => {
    const notes = fixture.nativeElement.querySelector('details.scope-note') as HTMLDetailsElement;
    expect(notes.open).toBe(false);
    for (const claim of ['不平均各倉百分比', '不是補送出車率', '不能據此認定主管派錯單',
      '不是客戶承諾準時交付率', '不代表收件人身分', '不能計算原料品質率']) expect(notes.textContent).toContain(claim);
  });
  it('keeps functioning sections when outcomes fail and provides recovery retry', () => {
    api.getReportOutcomes.mockReturnValue(throwError(() => new Error('503')));
    click('.period-tabs button'); fixture.detectChanges();
    expect(fixture.componentInstance.outcomes()).toBeNull(); expect(text('#orders .score-item')).toContain('—');
    expect(text('#people .rate-card')).toContain('75'); expect(text('#fleet .score-item')).toContain('2次');
    expect(fixture.nativeElement.querySelector('.data-alert')).not.toBeNull();
    api.getReportOutcomes.mockReturnValue(of(outcomes())); click('.retry-action'); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.data-alert')).toBeNull();
  });
  it('keeps delivery outcomes when performance fails', () => {
    api.getReportPerformance.mockReturnValue(throwError(() => new Error('503')));
    click('.period-tabs button'); fixture.detectChanges();
    expect(text('#people')).toContain('人員資料未載入'); expect(text('#fleet .score-item')).toContain('—');
    expect(text('#orders .score-item')).toContain('50.0%');
  });
  it('shows missing order data instead of claiming zero unsettled orders when the source fails', () => {
    api.getOrders.mockReturnValue(throwError(() => new Error('503')));
    click('.period-tabs button'); fixture.detectChanges();
    expect(text('#unsettled-orders .score-item')).toContain('—');
    expect(text('#unsettled-orders .chart-empty')).toContain('訂單資料未載入');
    expect(text('#orders .score-item')).toContain('50.0%');
    expect(fixture.nativeElement.querySelector('.data-alert')).not.toBeNull();
  });
  it('cancels older period requests instead of replacing newer data', () => {
    const stale = new Subject<ReportOutcomesDto>(); api.getReportOutcomes.mockReturnValueOnce(stale);
    click('.period-tabs button'); fixture.detectChanges();
    expect(text('.supplemental-feedback')).toContain('正在更新');
    click('[aria-label="下一期"]'); const d = outcomes(); d.delivery.fullDeliveryRate = null;
    stale.next(d); stale.complete(); fixture.detectChanges();
    expect(text('#orders .score-item')).toContain('50.0%');
  });
  it('uses six weekdays in weekly plots but keeps Sunday totals in the summary', () => {
    const d = outcomes(); d.daily.push({date: '2026-10-04', dueOrders: 2, fullOrders: 1});
    d.delivery.dueOrders = 6; d.delivery.fullOrders = 3; update(d);
    expect(fixture.componentInstance.orderBuckets()).toHaveLength(6);
    expect(fixture.componentInstance.orderBuckets()?.reduce((sum, b) => sum + b.count + b.other, 0)).toBe(4);
    expect(text('#orders .score-item')).toContain('完整 3 / 到期應配送 6');
    click('[aria-label="下一期"]'); fixture.detectChanges();
    expect(text('.period-stepper strong')).toBe('2026/10/05 — 2026/10/11');
  });
  it('clips monthly plot buckets and includes leap day in yearly buckets', () => {
    const page = fixture.componentInstance; page.activePeriod.set('month'); page.selectedMonth.set(8);
    const d = outcomes(); d.daily = [{date: '2026-08-31', dueOrders: 9, fullOrders: 9},
      {date: '2026-09-01', dueOrders: 3, fullOrders: 2}, {date: '2026-09-30', dueOrders: 1, fullOrders: 0},
      {date: '2026-10-01', dueOrders: 9, fullOrders: 9}]; update(d);
    expect(page.orderBuckets()).toHaveLength(5);
    expect(page.orderBuckets()?.[0]).toMatchObject({from: '2026-09-01', to: '2026-09-06', count: 2, other: 1});
    expect(page.orderBuckets()?.at(-1)).toMatchObject({from: '2026-09-28', to: '2026-09-30', count: 0, other: 1});
    page.activePeriod.set('year'); page.selectedYear.set(2024); d.daily = [{date: '2024-02-29', dueOrders: 1, fullOrders: 1}]; update(d);
    expect(page.orderBuckets()).toHaveLength(12);
    expect(page.orderBuckets()?.[1]).toMatchObject({from: '2024-02-01', to: '2024-02-29', count: 1});
  });
});

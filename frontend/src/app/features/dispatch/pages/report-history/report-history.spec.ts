import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, convertToParamMap, Router} from '@angular/router';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {ReportHistory} from './report-history';
import {ReportOrderOutcomeDto} from '../../../../core/services/dispatch-api.models';
import {of} from 'rxjs';

describe('ReportHistory links from the supervisor summary', () => {
  function open(query: Record<string, string>): ReportHistory {
    TestBed.configureTestingModule({providers: [
      {provide: ActivatedRoute, useValue: {snapshot: {queryParamMap: convertToParamMap(query)}}},
      {provide: Router, useValue: {navigate: vi.fn(() => Promise.resolve(true))}},
      {provide: DispatchApiService, useValue: {}},
    ]});
    const page = TestBed.runInInjectionContext(() => new ReportHistory());
    vi.spyOn(page as any, 'loadFilterOptions').mockImplementation(() => undefined);
    vi.spyOn(page as any, 'createPreview').mockImplementation(() => undefined);
    page.ngOnInit();
    return page;
  }

  it('opens the requested sheet and date range', () => {
    const page = open({from: '2026-09-21', to: '2026-09-27', sheet: 'warehouses'});
    expect(page.from()).toBe('2026-09-21');
    expect(page.to()).toBe('2026-09-27');
    expect(page.selectedSheet()).toBe('warehouses');
    expect(page.activePeriod()).toBe('week');
  });

  it.each([
    ['unsettled', ['待確認', '待排車', '已點交', '配送中']],
    ['pending-confirm', ['待確認']],
    ['awaiting-delivery', ['待排車', '已點交']],
    ['in-delivery', ['配送中']],
  ])('filters order details and Excel consistently for %s', (metric, statuses) => {
    const page = open({from: '2026-09-28', to: '2026-10-04', sheet: 'orders', metric}) as any;
    const orders = ['PENDING_CONFIRM', 'CONFIRMED', 'LOADED', 'IN_DELIVERY', 'COMPLETED', 'CANCELLED', 'FAILED', 'NO_SIGNATURE']
      .map((status, i) => ({id: i + 1, orderNumber: `O-${i + 1}`, storeId: 1, warehouseId: 1, boxCount: 15,
        deliveryDate: '2026-09-28', status}));
    const preview = {orders: [...orders, {...orders[0], orderNumber: 'OUTSIDE', deliveryDate: '2026-09-27'}],
      routes: {routes: []}, storeDirectory: []};
    expect(page.metric()).toBe(metric);
    expect(page.orderRows(preview).map((row: any) => row.status)).toEqual(statuses);
    expect(page.orderExportRows(preview).slice(1).map((row: any[]) => row[5])).toEqual(statuses);
  });

  it.each([
    ['2026-01-01', '2026-12-31', 'year'],
    ['2026-09-01', '2026-09-30', 'month'],
    ['2026-09-28', '2026-10-04', 'week'],
    ['2026-09-28', '2026-09-28', 'day'],
    ['2026-09-03', '2026-09-18', 'custom'],
  ])('matches the history period to a summary link for %s through %s', (from, to, period) => {
    const page = open({from, to, sheet: 'attendance', metric: 'late'});
    expect(page.activePeriod()).toBe(period);
    expect(page.from()).toBe(from);
    expect(page.to()).toBe(to);
    expect(page.metric()).toBe('late');
  });

  it('switches year, month, week and day using the same current date as the summary', () => {
    vi.useFakeTimers({toFake: ['Date']});
    vi.setSystemTime(new Date('2026-09-28T12:00:00Z'));
    try {
      const page = open({from: '2026-09-28', to: '2026-10-04', sheet: 'attendance'}) as any;
      page.setPeriod('day');
      expect([page.from(), page.to()]).toEqual(['2026-09-28', '2026-09-28']);
      page.setPeriod('month');
      expect([page.from(), page.to()]).toEqual(['2026-09-01', '2026-09-30']);
      page.setPeriod('year');
      expect([page.from(), page.to()]).toEqual(['2026-01-01', '2026-12-31']);
      page.setPeriod('week');
      expect([page.from(), page.to()]).toEqual(['2026-09-28', '2026-10-04']);
      expect(page.createPreview).toHaveBeenCalledTimes(5); // 首次載入與四次期間切換
      expect(page.reportQuery()).toMatchObject({period: 'CUSTOM', from: '2026-09-28', to: '2026-10-04'});
    } finally { vi.useRealTimers(); }
  });

  it.each([
    ['2026-09-30', '2026-10-01'],
    ['2026-12-31', '2027-01-01'],
    ['2024-02-28', '2024-02-29'],
  ])('moves day-by-day across the boundary after %s', (from, next) => {
    const page = open({from, to: from}) as any;
    page.stepPeriod(1);
    expect([page.from(), page.to()]).toEqual([next, next]);
    page.stepPeriod(-1);
    expect([page.from(), page.to()]).toEqual([from, from]);
  });

  it('moves a Monday-Sunday week across the year boundary', () => {
    const page = open({from: '2026-12-28', to: '2027-01-03'}) as any;
    page.stepPeriod(1);
    expect([page.from(), page.to()]).toEqual(['2027-01-04', '2027-01-10']);
    page.stepPeriod(-1);
    expect([page.from(), page.to()]).toEqual(['2026-12-28', '2027-01-03']);
  });

  it('keeps a manually chosen range custom and discards an outdated preview', () => {
    const page = open({from: '2026-09-28', to: '2026-09-28'}) as any;
    page.preview.set({summary: {from: '2026-09-28', to: '2026-09-28'}});
    page.updateDate('from', new Date(2026, 8, 21));
    page.updateDate('to', new Date(2026, 8, 24));
    expect(page.activePeriod()).toBe('custom');
    expect([page.from(), page.to()]).toEqual(['2026-09-21', '2026-09-24']);
    expect(page.preview()).toBeNull();
    page.stepPeriod(1);
    expect([page.from(), page.to()]).toEqual(['2026-09-21', '2026-09-24']);
    expect(page.reportQuery()).toMatchObject({period: 'CUSTOM', from: '2026-09-21', to: '2026-09-24'});
  });

  it('queries every history source and keeps the preview and URL on the selected day', () => {
    vi.useFakeTimers({toFake: ['Date']});
    vi.setSystemTime(new Date('2026-09-28T12:00:00Z'));
    try {
      const page = open({from: '2026-09-28', to: '2026-10-04', sheet: 'attendance', metric: 'late', warehouseId: '12'}) as any;
      const api = TestBed.inject(DispatchApiService) as any;
      const reads = ['getReportPerformance', 'getReportAttendance', 'getReportRoutes', 'getReportDrivers',
        'getReportVehicles', 'getReportWarehouses', 'getReportStores', 'getReportExceptions'];
      for (const name of reads) api[name] = vi.fn(() => of({}));
      api.getReportSummary = vi.fn((query: any) => of({from: query.from, to: query.to}));
      api.getReportOutcomes = vi.fn((query: any) => of({from: query.from, to: query.to, orders: []}));
      api.getOrders = vi.fn(() => of([]));
      api.getStores = vi.fn(() => of([]));
      page.createPreview.mockImplementation(() => (ReportHistory.prototype as any).createPreview.call(page));
      page.setPeriod('day');
      const query = {period: 'CUSTOM', from: '2026-09-28', to: '2026-09-28', warehouseId: 12,
        storeId: undefined, driverId: undefined};
      for (const name of reads) expect(api[name]).toHaveBeenLastCalledWith(query);
      expect(api.getReportSummary).toHaveBeenLastCalledWith(query);
      expect(api.getReportOutcomes).toHaveBeenLastCalledWith({...query, includeDetails: true});
      expect(page.preview().summary).toMatchObject({from: query.from, to: query.to});
      expect(page.selectedSheet()).toBe('attendance');
      expect(page.metric()).toBe('late');
      expect(TestBed.inject(Router).navigate).toHaveBeenLastCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute), queryParams: {from: query.from, to: query.to},
        queryParamsHandling: 'merge',
      });
    } finally { vi.useRealTimers(); }
  });

  it('ignores an unknown sheet while accepting a valid warehouse filter', () => {
    const page = open({sheet: 'unknown', warehouseId: '12'});
    expect(page.selectedSheet()).toBe('overview');
    expect(page.warehouseId()).toBe(12);
  });

  it.each([
    ['2026-09-02T20:50:34.701', '2026-09-02 20:50:34'],
    ['2026-09-02T20:50:34.701234567', '2026-09-02 20:50:34'],
    ['2026-09-14T17:30:00', '2026-09-14 17:30:00'],
    ['2026-09-15T18:00', '2026-09-15 18:00:00'],
    ['2026-09-02 20:50:34.701', '2026-09-02 20:50:34'],
    ['2026-09-15 18:00:00', '2026-09-15 18:00:00'],
    ['2026-09-02T23:59:59.999999999', '2026-09-02 23:59:59'],
  ])('displays timestamp %s consistently to seconds without changing the date or time', (input, expected) => {
    const page = open({sheet: 'attendance'}) as any;
    expect(page.value(input)).toBe(expected);
  });

  it('leaves non-timestamp values and identifiers intact', () => {
    const page = open({}) as any;
    for (const value of ['2026-09-02', '17:30:00', 'DEMO-RPT2-001', 'TRUCK', 'TIME 記錄待確認']) {
      expect(page.value(value)).toBe(value);
    }
    for (const value of [null, undefined, '']) expect(page.value(value)).toBe('--');
    expect(page.value(null, '未打卡')).toBe('未打卡');
    expect(page.value(45)).toBe('45'); expect(page.value(45.25)).toBe('45.3');
    expect(page.value(true)).toBe('是'); expect(page.value(false)).toBe('否');
  });

  it('exports the same timestamp format while keeping numbers, missing cells and source records unchanged', () => {
    const page = open({sheet: 'attendance'}) as any;
    const sheet: Record<string, unknown> = {};
    const xlsx = {utils: {aoa_to_sheet: vi.fn(() => sheet), book_append_sheet: vi.fn()}};
    const workbook = {};
    const rows = [['打卡下班', '加班分鐘', '缺少紀錄', '訂單'],
      ['2026-09-02T20:50:34.701234567', 45, null, 'DEMO-RPT2-001']];
    page.appendSheet(xlsx, workbook, '司機出勤', rows);
    expect(xlsx.utils.aoa_to_sheet).toHaveBeenCalledWith([
      rows[0], ['2026-09-02 20:50:34', 45, null, 'DEMO-RPT2-001']]);
    expect(rows[1][0]).toBe('2026-09-02T20:50:34.701234567');
    expect((sheet['!cols'] as {wch: number}[])[0].wch).toBe(21);
    expect(xlsx.utils.book_append_sheet).toHaveBeenCalledWith(workbook, sheet, '司機出勤');
  });

  const row = (id: number, overrides: Partial<ReportOrderOutcomeDto> = {}): ReportOrderOutcomeDto => ({
    orderId: id, orderNumber: 'O-' + id, date: '2026-09-28', warehouseId: 1, warehouseName: '左營倉',
    storeId: 10, storeName: '門市', driverId: 20, status: 'COMPLETED', due: true, delivered: true,
    full: true, withinWindow: true, late: false, early: false, missingArrival: false, missingQuality: false,
    assessed: true, shortage: false, damaged: false, noSignature: false, loadingMatched: true,
    loadingMismatch: false, missingLoading: false, dueUnassigned: false, windowStart: null,
    windowEnd: null, arrivedAt: null, deliveredAt: null, loadedAt: null, orderedBoxCount: 10,
    expectedBoxCount: 10, deliveredBoxCount: 10, shortageBoxCount: 0, damagedBoxCount: 0,
    replacementRequiredBoxCount: 0, loadingIssue: null, items: [], orderType: 'NORMAL',
    parentOrderId: null, attempted: true, recovery: false, recoveryReason: null, ...overrides,
  });
  const report = (orders: ReportOrderOutcomeDto[]) => ({outcomes: {orders}} as any);

  it('accepts only metrics belonging to the requested detail sheet', () => {
    expect(open({sheet: 'delivery-quality', metric: 'no-signature'}).metric()).toBe('no-signature');
    TestBed.resetTestingModule();
    expect(open({sheet: 'delivery-quality', metric: 'damaged'}).metric()).toBe('');
  });
  it('opens new recovery and loading tabs from summary links', () => {
    expect(open({sheet: 'recovery', metric: 'attempted'}).selectedSheet()).toBe('recovery');
    TestBed.resetTestingModule();
    expect(open({sheet: 'loading-quality', metric: 'mismatched'}).metric()).toBe('mismatched');
  });
  it('keeps all recorded exceptions after closing and filters warehouse handoff separately', () => {
    const page = open({sheet: 'exceptions'}) as any;
    const data = {exceptions: {cases: [
      {exceptionId: 1, type: 'NO_SIGNATURE', status: 'OPEN'},
      {exceptionId: 2, type: 'LOADING_MISMATCH', status: 'OPEN'},
      {exceptionId: 3, type: 'SHORTAGE', status: 'OPEN'},
      {exceptionId: 4, type: 'DRIVER_REPORT', status: 'CLOSED'},
      {exceptionId: 5, type: 'PHONE_HANDLED', status: 'CLOSED'},
    ]}} as any;
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([1, 2, 3, 4, 5]);
    expect(page.exceptionStatusCount(page.exceptionRows(data), 'OPEN')).toBe(3);
    expect(page.exceptionExportRows(data)).toHaveLength(6);
    page.metric.set('loading-mismatch');
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([2]);
    page.metric.set('no-signature');
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([1]);
    page.metric.set('other-open');
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([3]);
    page.metric.set('open');
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([1, 2, 3]);
    expect(page.exceptionTypeLabel('SHORTAGE')).toBe('交貨差異回報');
    expect(page.exceptionTypeLabel('DAMAGE')).toBe('交貨差異回報');
  });
  it('filters delivery details by the same due and no-signature flags as the graph', () => {
    const page = open({sheet: 'delivery-quality', metric: 'no-signature'}) as any;
    const data = report([row(1), row(2, {noSignature: true, full: false}),
      row(3, {noSignature: true, full: false, due: false})]);
    expect(page.outcomeRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([2]);
    page.metric.set('full');
    expect(page.outcomeRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([1]);
  });
  it('does not describe missing quality evidence as a verified incomplete delivery', () => {
    const page = open({sheet: 'delivery-quality', metric: 'incomplete'}) as any;
    const data = report([row(1, {full: false, missingQuality: true}), row(2, {full: false})]);
    expect(page.outcomeRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([2]);
    page.metric.set('missing-quality');
    expect(page.outcomeRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([1]);
  });
  it('shows expected boxes and zero delivered for no-signature without inventing other missing quantities', () => {
    const page = open({sheet: 'delivery-quality'}) as any;
    const data = report([row(1, {noSignature: true, expectedBoxCount: 15, deliveredBoxCount: 0, full: false}),
      row(2, {expectedBoxCount: null, orderedBoxCount: 8, deliveredBoxCount: null, full: false})]);
    const exported = page.deliveryOutcomeExportRows(data);
    expect(exported[0]).toEqual(['日期', '訂單', '出貨倉庫', '門市', '收貨開始', '收貨截止', '實際抵達', '實際交貨',
      '應送箱數', '實送箱數', '交付結果', '抵達結果']);
    expect(exported[1].slice(8, 10)).toEqual([15, 0]);
    expect(exported[2].slice(8, 10)).toEqual([8, null]);
  });
  it('filters loading cases independently of the receiving deadline', () => {
    const page = open({sheet: 'loading-quality', metric: 'mismatched'}) as any;
    const data = report([row(1, {due: false, loadingMatched: false, loadingMismatch: true}), row(2)]);
    expect(page.outcomeRows(data, true).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([1]);
  });
  it('honors warehouse, store and driver filters in underlying outcome details', () => {
    const page = open({sheet: 'delivery-quality', warehouseId: '1'}) as any;
    page.storeId.set(10); page.driverId.set(20);
    const data = report([row(1), row(2, {warehouseId: 2}), row(3, {storeId: 11}), row(4, {driverId: 21})]);
    expect(page.outcomeRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([1]);
  });
  it('keeps unexecuted approved recovery orders separate from executed ones', () => {
    const page = open({sheet: 'recovery', metric: 'attempted'}) as any;
    const data = report([row(1), row(2, {recovery: true, parentOrderId: 99, delivered: false}),
      row(3, {recovery: true, parentOrderId: 100, attempted: false, delivered: false}),
      row(4, {recovery: true, parentOrderId: 101})]);
    expect(page.recoveryRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([2, 4]);
    page.metric.set('outstanding');
    expect(page.recoveryRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([2, 3]);
  });
  it('does not invent distance or include unsettled trips in the recorded-distance selection', () => {
    const page = open({sheet: 'vehicles', metric: 'distance-recorded'}) as any;
    const data = {performance: {trips: [{mileageId: 1, actualKm: null}, {mileageId: 2, actualKm: 0}, {mileageId: 3, actualKm: 30}]}};
    expect(page.tripRows(data).map((r: any) => r.mileageId)).toEqual([2, 3]);
  });
  it('filters attendance by effective punches, not inferred attendance or approval', () => {
    const page = open({sheet: 'attendance', metric: 'clocked-in'}) as any;
    const data = {performance: {shifts: [{shiftId: 1, onTime: null, attendanceStatus: 'MISSING_CLOCK_IN'},
      {shiftId: 2, onTime: false, overtimeMinutes: 45}, {shiftId: 3, onTime: true, overtimeMinutes: 0}]}};
    expect(page.attendanceRows(data).map((r: any) => r.shiftId)).toEqual([2, 3]);
    page.metric.set('overtime');
    expect(page.attendanceRows(data).map((r: any) => r.shiftId)).toEqual([2]);
  });
  it('resets the old metric when moving to another sheet', () => {
    const page = open({sheet: 'delivery-quality', metric: 'no-signature'}) as any;
    page.selectSheet('attendance'); expect(page.metric()).toBe('');
    expect(page.selectedSheet()).toBe('attendance');
  });
  it('uses the filtered rows for detailed Excel data, including source order and cause', () => {
    const page = open({sheet: 'recovery', metric: 'attempted'}) as any;
    const data = report([row(1, {recovery: true, parentOrderId: 99, recoveryReason: '無人簽收重送',
      noSignature: true, delivered: false, full: false, expectedBoxCount: 15, deliveredBoxCount: 0}),
      row(2, {recovery: true, parentOrderId: 100, attempted: false})]);
    const exported = page.recoveryExportRows(data);
    expect(exported).toHaveLength(2); expect(exported[1]).toContain(99); expect(exported[1]).toContain('無人簽收重送');
    expect(exported[1].slice(9, 11)).toEqual([15, 0]);
  });
  it('clears outdated previews when a query condition changes', () => {
    const page = open({sheet: 'delivery-quality'}) as any;
    page.preview.set(report([row(1)])); page.loading.set(true);
    page.updateFilter('store', {target: {value: '10'}});
    expect(page.storeId()).toBe(10); expect(page.preview()).toBeNull(); expect(page.loading()).toBe(false);
    page.preview.set(report([row(1)]));
    page.updateDate('from', new Date(2026, 8, 29));
    expect(page.from()).toBe('2026-09-29'); expect(page.preview()).toBeNull();
  });
});

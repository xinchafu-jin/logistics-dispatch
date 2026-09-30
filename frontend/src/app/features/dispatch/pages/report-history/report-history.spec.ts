import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, convertToParamMap, Router} from '@angular/router';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {ReportHistory} from './report-history';
import {ReportOrderOutcomeDto, ReportPreTripInspectionDto} from '../../../../core/services/dispatch-api.models';
import {provideNativeDateAdapter} from '@angular/material/core';
import {of, Subject, throwError} from 'rxjs';

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

  function previewApi(): Record<string, any> {
    const api: Record<string, any> = {};
    for (const name of ['getReportPerformance', 'getReportAttendance', 'getReportRoutes', 'getReportDrivers',
      'getReportVehicles', 'getReportWarehouses', 'getReportStores', 'getReportExceptions']) api[name] = vi.fn(() => of({}));
    api['getReportSummary'] = vi.fn(() => of({from: '2026-09-28', to: '2026-09-28', dailyTrend: []}));
    api['getReportOutcomes'] = vi.fn(() => of({orders: []}));
    api['getReportPreTrip'] = vi.fn(() => of({from: '2026-09-28', to: '2026-09-28', inspections: []}));
    api['getOrders'] = vi.fn(() => of([]));
    api['getWarehouses'] = vi.fn(() => of([{id: 1, name: '第一倉'}]));
    api['getStores'] = vi.fn(() => of([{id: 2, name: '第一店'}]));
    api['getDrivers'] = vi.fn(() => of([{id: 3, name: '第一位司機'}]));
    api['getVehicles'] = vi.fn(() => of([{id: 4, plateNumber: 'ABC-1234', vehicleType: '3.5噸貨車'}]));
    return api;
  }

  function inspection(id: number, overrides: Partial<ReportPreTripInspectionDto> = {}): ReportPreTripInspectionDto {
    const groups: [string, string, string][] = [
      ['dashcam', '行車紀錄器', '開機且正常錄影'], ['engineOil', '五油', '引擎機油'],
      ['brakeFluid', '五油', '煞車油'], ['powerSteeringFluid', '五油', '動力方向盤油'],
      ['transmissionOil', '五油', '變速箱油'], ['fuel', '五油', '燃油'],
      ['coolant', '三水', '冷卻水'], ['batteryWater', '三水', '電瓶水'], ['washerFluid', '三水', '雨刷水'],
      ['tirePressure', '二胎', '胎壓'], ['tireTread', '二胎', '胎紋'],
      ['headlights', '四燈', '頭燈'], ['turnSignals', '四燈', '方向燈'],
      ['brakeLights', '四燈', '煞車燈'], ['dashboardLights', '四燈', '儀表板燈'],
    ];
    return {inspectionId: id, workDate: '2026-09-28', submittedAt: '2026-09-28T08:00:00',
      driverId: 3, driverName: '陳柏宇', driverAccount: 'DRV001', vehicleId: 4, plateNumber: 'KAE-2081',
      warehouseId: 1, warehouseName: '高雄左營倉', routeId: 12, routeVersion: 1, alcoholMgL: 0,
      passed: true, invalidatedAt: null, note: null, hasAlcoholPhoto: true, hasFaultPhoto: false,
      abnormalItems: [], checks: groups.map(([key, group, label]) => ({key, group, label, normal: true})), ...overrides};
  }

  it('renders each pre-trip attempt and all 15 recorded checks including faults and unknown values', () => {
    const failed = inspection(1, {passed: false, alcoholMgL: 0.12, abnormalItems: ['酒測', '煞車燈'],
      note: '煞車燈待修', invalidatedAt: '2026-09-28T09:00:00'});
    failed.checks[13].normal = false;
    failed.checks[6].normal = null;
    const api = previewApi();
    api['getReportPreTrip'] = vi.fn(() => of({inspections: [failed, inspection(2)]}));
    TestBed.configureTestingModule({imports: [ReportHistory], providers: [
      provideNativeDateAdapter(),
      {provide: ActivatedRoute, useValue: {snapshot: {queryParamMap: convertToParamMap({
        from: '2026-09-28', to: '2026-09-28', sheet: 'pre-trip'})}}},
      {provide: Router, useValue: {navigate: vi.fn(() => Promise.resolve(true))}},
      {provide: DispatchApiService, useValue: api},
    ]});
    const fixture = TestBed.createComponent(ReportHistory);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.sheet-tabs')?.textContent).toContain('發車前檢點表');
    expect(element.querySelector('.inspection-summary')?.textContent).toContain('檢點紀錄 2 筆');
    expect(element.querySelectorAll('.inspection-group li')).toHaveLength(30);
    expect(element.querySelectorAll('.inspection-group .is-abnormal')).toHaveLength(1);
    expect(element.querySelector('.inspection-table')?.textContent).toContain('未記錄');
    expect(element.querySelector('.inspection-table')?.textContent).toContain('部分項目未記錄');
    expect(element.querySelector('.inspection-table')?.textContent).toContain('0.12');
    expect(element.querySelector('.inspection-table')?.textContent).toContain('煞車燈待修');
    expect(element.querySelector('.inspection-table')?.textContent).toContain('已作廢');
    expect(element.querySelector('.inspection-evidence button')?.textContent).toContain('酒測器照片');
    fixture.destroy();
  });

  it('exports the same selected inspection records and all their checks without replacing unknown results', () => {
    const page = open({sheet: 'pre-trip', metric: 'failed'}) as any;
    const failed = inspection(1, {passed: false, note: '煞車燈待修', abnormalItems: ['煞車燈']});
    failed.checks[13].normal = false; failed.checks[6].normal = null;
    const data = {preTrips: {inspections: [failed, inspection(2), inspection(3, {invalidatedAt: '2026-09-28T09:00:00'})]}};
    expect(page.inspectionRows(data).map((row: ReportPreTripInspectionDto) => row.inspectionId)).toEqual([1]);
    expect(page.inspectionExportRows(data)).toHaveLength(2);
    const items = page.inspectionItemExportRows(data);
    expect(items).toHaveLength(16);
    expect(items.find((row: unknown[]) => row.includes('冷卻水'))).toContain('未記錄');
    expect(items.find((row: unknown[]) => row.includes('煞車燈'))).toContain('異常');
    page.metric.set('invalidated');
    expect(page.inspectionRows(data).map((row: ReportPreTripInspectionDto) => row.inspectionId)).toEqual([3]);
    page.metric.set('');
    expect(page.inspectionExportRows(data)).toHaveLength(4);
    expect(page.inspectionItemExportRows(data)).toHaveLength(46);
  });

  it('keeps other report sheets available when inspection history fails and reports the missing source', () => {
    const page = open({sheet: 'pre-trip'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());
    api.getReportPreTrip = vi.fn(() => throwError(() => ({status: 403})));
    (ReportHistory.prototype as any).createPreview.call(page);
    expect(page.preview()).not.toBeNull();
    expect(page.preview().preTrips).toBeNull();
    expect(page.inspectionError()).toContain('發車前檢點表');
    expect(page.inspectionError()).toContain('沒有查詢權限');
  });

  it('loads private inspection photos and revokes them when switching reports', () => {
    const page = open({sheet: 'pre-trip'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:inspection-photo');
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    api.getReportPreTripPhoto = vi.fn(() => of(new Blob(['photo'], {type: 'image/png'})));
    page.openInspectionPhoto(inspection(7), 'alcohol');
    expect(api.getReportPreTripPhoto).toHaveBeenCalledWith(7, 'alcohol');
    expect(page.inspectionPhoto()).toMatchObject({inspectionId: 7, url: 'blob:inspection-photo', error: null});
    page.selectSheet('attendance');
    expect(page.inspectionPhoto()).toBeNull();
    expect(revokeUrl).toHaveBeenCalledWith('blob:inspection-photo');
    createUrl.mockRestore(); revokeUrl.mockRestore();
  });

  it('explains that the new inspection endpoint needs a backend update when it returns 404', () => {
    const page = open({sheet: 'pre-trip'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());
    api.getReportPreTrip = vi.fn(() => throwError(() => ({status: 404})));
    (ReportHistory.prototype as any).createPreview.call(page);
    expect(page.preview()).not.toBeNull();
    expect(page.inspectionError()).toContain('更新並重啟後端');
  });

  it('cancels stale photo loads and explains missing photo files without hiding inspection history', () => {
    const page = open({sheet: 'pre-trip'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    const pending = new Subject<Blob>();
    api.getReportPreTripPhoto = vi.fn(() => pending);
    page.openInspectionPhoto(inspection(1), 'alcohol');
    page.clearInspectionPhoto();
    pending.next(new Blob(['stale']));
    expect(page.inspectionPhoto()).toBeNull();
    api.getReportPreTripPhoto = vi.fn(() => throwError(() => ({status: 404})));
    page.openInspectionPhoto(inspection(2), 'fault');
    expect(page.inspectionPhoto()).toMatchObject({inspectionId: 2, url: null, error: '這筆照片檔案已不存在。'});
    page.ngOnDestroy();
    expect(page.inspectionPhoto()).toBeNull();
  });

  it.each([
    [500, '後端查詢失敗'], [0, '無法連線到後端服務'], [401, '登入已失效'], [403, '沒有查詢權限'],
  ])('identifies the failed history source and the actual cause for HTTP %s', (status, explanation) => {
    const page = open({from: '2026-09-28', to: '2026-09-28'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());
    api.getReportExceptions = vi.fn(() => throwError(() => ({status, error: 'private backend details'})));
    (ReportHistory.prototype as any).createPreview.call(page);
    expect(page.preview()).toBeNull();
    expect(page.loading()).toBe(false);
    expect(page.errorMessage()).toContain('異常案件');
    expect(page.errorMessage()).toContain(explanation);
    expect(page.errorMessage()).not.toContain('private backend details');
    expect(page.sheets.some((sheet: any) => sheet.id === 'attendance')).toBe(true);
  });

  it('keeps a successful preview when only optional delivery details fail', () => {
    const page = open({from: '2026-09-28', to: '2026-09-28'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());
    api.getReportOutcomes = vi.fn(() => throwError(() => ({status: 500})));
    (ReportHistory.prototype as any).createPreview.call(page);
    expect(page.preview().summary.from).toBe('2026-09-28');
    expect(page.preview().outcomes).toBeNull();
    expect(page.errorMessage()).toBe('');
    expect(page.detailError()).toContain('配送／補送／點交明細');
  });

  it('loads warehouse and store options independently when driver options fail', () => {
    const page = open({}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());
    api.getDrivers = vi.fn(() => throwError(() => ({status: 500})));
    (ReportHistory.prototype as any).loadFilterOptions.call(page);
    expect(page.warehouses()).toEqual([{id: 1, name: '第一倉'}]);
    expect(page.stores()).toEqual([{id: 2, name: '第一店'}]);
    expect(page.drivers()).toEqual([]);
    expect(page.vehicles().map((vehicle: any) => vehicle.id)).toEqual([4]);
    expect(page.filterError()).toContain('司機選項');
  });

  it('groups the vehicle filter by tonnage and includes every vehicle in the selected group', () => {
    const page = open({tonnage: '1.75'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());
    api.getVehicles = vi.fn(() => of([
      {id: 7, plateNumber: 'ZZZ-0007', vehicleType: '3.5噸冷藏車'},
      {id: 8, plateNumber: 'AAA-0008', vehicleType: '未設定車型'},
      {id: 9, plateNumber: 'BBB-0009', vehicleType: '1.75噸貨車'},
      {id: 10, plateNumber: 'AAA-0010', vehicleType: '3.5噸貨車'},
      {id: 11, plateNumber: 'CCC-0011', vehicleType: '1.5噸貨車'},
    ]));

    (ReportHistory.prototype as any).loadFilterOptions.call(page);

    expect(page.tonnageOptions().map((option: any) => option.label)).toEqual([
      '1.5 噸', '1.75 噸', '3.5 噸', '未設定噸位',
    ]);
    expect(page.tonnage()).toBe('1.75');
    expect(page.reportQuery().vehicleIds).toEqual([9]);
    page.updateFilter('tonnage', {target: {value: '3.5'}});
    expect(page.reportQuery().vehicleIds).toEqual([7, 10]);
  });

  it('sends every matching vehicle to each report preview source', () => {
    const page = open({from: '2026-09-28', to: '2026-09-28', tonnage: '1.75'}) as any;
    page.vehicles.set([{id: 7, vehicleType: '1.75噸貨車'}, {id: 9, vehicleType: '1.75噸冷藏車'},
      {id: 8, vehicleType: '3.5噸貨車'}]);
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());

    (ReportHistory.prototype as any).createPreview.call(page);

    for (const name of ['getReportPerformance', 'getReportSummary', 'getReportAttendance', 'getReportRoutes',
      'getReportDrivers', 'getReportVehicles', 'getReportWarehouses', 'getReportStores', 'getReportExceptions', 'getReportPreTrip']) {
      expect(api[name].mock.lastCall[0].vehicleIds).toEqual([7, 9]);
    }
    expect(api.getReportOutcomes.mock.lastCall[0].vehicleIds).toEqual([7, 9]);
    expect(TestBed.inject(Router).navigate).toHaveBeenLastCalledWith([], {
      relativeTo: TestBed.inject(ActivatedRoute),
      queryParams: {from: '2026-09-28', to: '2026-09-28', vehicleId: null, tonnage: '1.75'},
      queryParamsHandling: 'merge',
    });
  });

  it('clears an older preview before refreshing so it cannot export the wrong period', () => {
    const page = open({from: '2026-09-28', to: '2026-09-28'}) as any;
    const api = TestBed.inject(DispatchApiService) as any;
    Object.assign(api, previewApi());
    const pending = new Subject<any>();
    api.getReportSummary = vi.fn(() => pending);
    page.preview.set({summary: {from: '2026-09-01', to: '2026-09-30'}});
    (ReportHistory.prototype as any).createPreview.call(page);
    expect(page.loading()).toBe(true);
    expect(page.preview()).toBeNull();
    page.ngOnDestroy();
    expect(pending.observed).toBe(false);
  });

  it('renders every history tab and a retry action even when a required API fails', () => {
    const api = previewApi();
    api['getReportExceptions'] = vi.fn(() => throwError(() => ({status: 500})));
    TestBed.configureTestingModule({imports: [ReportHistory], providers: [
      provideNativeDateAdapter(),
      {provide: ActivatedRoute, useValue: {snapshot: {queryParamMap: convertToParamMap({from: '2026-09-28', to: '2026-09-28'})}}},
      {provide: Router, useValue: {navigate: vi.fn(() => Promise.resolve(true))}},
      {provide: DispatchApiService, useValue: api},
    ]});
    const fixture = TestBed.createComponent(ReportHistory);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelectorAll('.sheet-tabs button')).toHaveLength(fixture.componentInstance.sheets.length);
    expect(element.querySelector('.sheet-tabs')?.textContent).toContain('司機打卡');
    expect(element.querySelector('.sheet-tabs')?.textContent).toContain('異常案件');
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('異常案件');
    expect(element.querySelector('.empty-preview button')?.textContent).toContain('重新載入報表');
    expect(element.querySelector('.export-button')).toBeNull();
  });

  it('shows separate calendar fields for the start and end dates', () => {
    TestBed.configureTestingModule({imports: [ReportHistory], providers: [
      provideNativeDateAdapter(),
      {provide: ActivatedRoute, useValue: {snapshot: {queryParamMap: convertToParamMap({from: '2026-09-01', to: '2026-09-24'})}}},
      {provide: Router, useValue: {navigate: vi.fn(() => Promise.resolve(true))}},
      {provide: DispatchApiService, useValue: {}},
    ]});
    const fixture = TestBed.createComponent(ReportHistory);
    vi.spyOn(fixture.componentInstance as any, 'loadFilterOptions').mockImplementation(() => undefined);
    vi.spyOn(fixture.componentInstance as any, 'createPreview').mockImplementation(() => undefined);
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelectorAll('.date-field')).toHaveLength(2);
    expect(element.querySelector('input[aria-label="開始日期"]')).not.toBeNull();
    expect(element.querySelector('input[aria-label="結束日期"]')).not.toBeNull();
    expect(element.querySelectorAll('.date-field mat-datepicker-toggle')).toHaveLength(2);
    fixture.destroy();
  });

  it('shows recorded return times and counts without a return-completion percentage', () => {
    const api = previewApi();
    api['getReportPerformance'] = vi.fn(() => of({fleet: {startedTrips: 2, returnedTrips: 1, openTrips: 1,
      actualKm: 20, returnRate: 50}, trips: [
      {mileageId: 1, date: '2026-09-28', plateNumber: 'ABC-1234', startAt: '2026-09-28T08:00:00',
        endAt: '2026-09-28T17:45:00', status: 'RETURNED', actualKm: 20},
    ], warehouses: []}));
    TestBed.configureTestingModule({imports: [ReportHistory], providers: [
      provideNativeDateAdapter(),
      {provide: ActivatedRoute, useValue: {snapshot: {queryParamMap: convertToParamMap({
        from: '2026-09-28', to: '2026-09-28', sheet: 'vehicles'})}}},
      {provide: Router, useValue: {navigate: vi.fn(() => Promise.resolve(true))}},
      {provide: DispatchApiService, useValue: api},
    ]});
    const fixture = TestBed.createComponent(ReportHistory);
    fixture.detectChanges();
    const content = (fixture.nativeElement as HTMLElement).querySelector('.sheet-content')?.textContent ?? '';
    expect(content).toContain('已收車 1 趟');
    expect(content).toContain('尚未收車 1 趟');
    expect(content).toContain('實際收車時間');
    expect(content).toContain('2026-09-28 17:45:00');
    expect(content).not.toContain('收車完成率');
    fixture.destroy();
  });

  it('opens the requested sheet and date range', () => {
    const page = open({from: '2026-09-21', to: '2026-09-27', sheet: 'warehouses'});
    expect(page.from()).toBe('2026-09-21');
    expect(page.to()).toBe('2026-09-27');
    expect(page.selectedSheet()).toBe('warehouses');
    expect(page.activePeriod()).toBe('week');
  });

  it('opens unfinished-order details instead of the other-open exception metric', () => {
    const page = open({from: '2026-09-28', to: '2026-10-04', sheet: 'orders', metric: 'unsettled'});
    expect(page.selectedSheet()).toBe('orders');
    expect(page.metric()).toBe('unsettled');
    expect((page as any).metricOptions().map((item: {id: string}) => item.id)).toContain('unsettled');
  });

  it('lists and exports only unfinished orders within the same date, warehouse, store and driver filters', () => {
    const page = open({from: '2026-09-28', to: '2026-10-04', sheet: 'orders', metric: 'unsettled', warehouseId: '1'}) as any;
    page.storeId.set(10); page.driverId.set(20);
    const statuses = ['PENDING_CONFIRM', 'CONFIRMED', 'LOADED', 'IN_DELIVERY', 'COMPLETED', 'NO_SIGNATURE', 'FAILED', 'CANCELLED'];
    const orders = statuses.map((status, index) => ({id: index + 1, orderNumber: 'O-' + status, deliveryDate: '2026-09-28',
      storeId: 10, warehouseId: 1, assignedDriverId: 20, status, boxCount: 15, notes: ''}));
    const data = {orders: [...orders,
      {...orders[0], orderNumber: 'BEFORE', deliveryDate: '2026-09-27'},
      {...orders[0], orderNumber: 'AFTER', deliveryDate: '2026-10-05'},
      {...orders[0], orderNumber: 'OTHER-WAREHOUSE', warehouseId: 2},
      {...orders[0], orderNumber: 'OTHER-STORE', storeId: 11},
      {...orders[0], orderNumber: 'OTHER-DRIVER', assignedDriverId: 21}],
      storeDirectory: [{id: 10, name: '測試門市'}], routes: {routes: []}};
    const expected = ['O-CONFIRMED', 'O-IN_DELIVERY', 'O-LOADED', 'O-PENDING_CONFIRM'];
    expect(page.orderRows(data).map((row: any) => row.orderNumber)).toEqual(expected);
    expect(page.orderExportRows(data).slice(1).map((row: any[]) => row[1])).toEqual(expected);
    page.metric.set('');
    expect(page.orderRows(data)).toHaveLength(8); // Unfiltered history must keep terminal orders.
  });

  it('shows no unfinished-order rows when all orders have a terminal status', () => {
    const page = open({from: '2026-09-28', to: '2026-09-28', sheet: 'orders', metric: 'unsettled'}) as any;
    const data = {orders: ['COMPLETED', 'NO_SIGNATURE', 'FAILED', 'CANCELLED'].map((status, index) => ({
      id: index + 1, orderNumber: 'O-' + index, deliveryDate: '2026-09-28', status,
    })), storeDirectory: [], routes: {routes: []}};
    expect(page.orderRows(data)).toEqual([]);
    expect(page.orderExportRows(data)).toHaveLength(1);
  });

  it('filters displayed and exported orders by all vehicles in a tonnage group', () => {
    const page = open({from: '2026-09-28', to: '2026-09-28', tonnage: '1.75'}) as any;
    page.vehicles.set([{id: 7, vehicleType: '1.75噸貨車'}, {id: 9, vehicleType: '1.75噸冷藏車'},
      {id: 8, vehicleType: '3.5噸貨車'}]);
    const order = (id: number, assignedVehicleId?: number) => ({
      id, orderNumber: `O-${id}`, deliveryDate: '2026-09-28', status: 'CONFIRMED',
      storeId: 10, warehouseId: 1, boxCount: 10, assignedVehicleId,
    });
    const data = {orders: [order(1, 7), order(2, 8), order(3)], storeDirectory: [], routes: {routes: [
      {vehicleId: 9, deliveryOrder: [{orderId: 3}]},
    ]}};

    expect(page.orderRows(data).map((row: any) => row.orderNumber)).toEqual(['O-1', 'O-3']);
    expect(page.orderExportRows(data).slice(1).map((row: any[]) => row[1])).toEqual(['O-1', 'O-3']);
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
        'getReportVehicles', 'getReportWarehouses', 'getReportStores', 'getReportExceptions', 'getReportPreTrip'];
      for (const name of reads) api[name] = vi.fn(() => of({}));
      api.getReportSummary = vi.fn((query: any) => of({from: query.from, to: query.to}));
      api.getReportOutcomes = vi.fn((query: any) => of({from: query.from, to: query.to, orders: []}));
      api.getOrders = vi.fn(() => of([]));
      api.getStores = vi.fn(() => of([]));
      page.createPreview.mockImplementation(() => (ReportHistory.prototype as any).createPreview.call(page));
      page.setPeriod('day');
      const query = {period: 'CUSTOM', from: '2026-09-28', to: '2026-09-28', warehouseId: 12,
        storeId: undefined, driverId: undefined, vehicleIds: undefined};
      for (const name of reads) expect(api[name]).toHaveBeenLastCalledWith(query);
      expect(api.getReportSummary).toHaveBeenLastCalledWith(query);
      expect(api.getReportOutcomes).toHaveBeenLastCalledWith({...query, includeDetails: true});
      expect(page.preview().summary).toMatchObject({from: query.from, to: query.to});
      expect(page.selectedSheet()).toBe('attendance');
      expect(page.metric()).toBe('late');
      expect(TestBed.inject(Router).navigate).toHaveBeenLastCalledWith([], {
        relativeTo: TestBed.inject(ActivatedRoute), queryParams: {from: query.from, to: query.to, vehicleId: null, tonnage: null},
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

  it('drills into overdue unfinished orders and keeps a late completed order in the same history', () => {
    const page = open({sheet: 'delivery-quality', metric: 'overdue-unsettled'}) as any;
    const data = report([
      row(1, {status: 'IN_DELIVERY', delivered: false, deliveredAt: null, windowEnd: '2026-09-28T18:00:00'}),
      row(2, {status: 'COMPLETED', deliveredAt: '2026-09-28T19:00:00', windowEnd: '2026-09-28T18:00:00'}),
      row(3, {status: 'COMPLETED', deliveredAt: '2026-09-28T17:00:00', windowEnd: '2026-09-28T18:00:00'}),
      row(4, {status: 'COMPLETED', deliveredAt: null, windowEnd: '2026-09-28T18:00:00'}),
      row(5, {status: 'NO_SIGNATURE', noSignature: true, delivered: false, windowEnd: '2026-09-28T18:00:00'}),
      row(6, {status: 'PENDING_CONFIRM', delivered: false, deliveredAt: null, windowEnd: '2026-09-28T18:00:00'}),
    ]);
    expect(page.metric()).toBe('overdue-unsettled');
    expect(page.outcomeRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).toEqual([1, 2, 6]);
    expect(page.overdueResult(data.outcomes.orders[0])).toBe('到期未交付');
    expect(page.overdueResult(data.outcomes.orders[1])).toBe('逾期才交付');
    page.metric.set('');
    expect(page.outcomeRows(data).map((r: ReportOrderOutcomeDto) => r.orderId)).not.toContain(6);
  });

  it('keeps resolved cases when drilling into a cumulative chart and exports the same classification', () => {
    const page = open({sheet: 'exceptions', metric: 'all-loading-mismatch'}) as any;
    expect(page.metric()).toBe('all-loading-mismatch');
    const data = {orders: [], exceptions: {cases: [
      {exceptionId: 1, type: 'LOADING_MISMATCH', status: 'OPEN'},
      {exceptionId: 2, type: 'LOADING_MISMATCH', status: 'CLOSED'},
      {exceptionId: 3, type: 'NO_SIGNATURE', status: 'CLOSED'},
      {exceptionId: 4, type: 'DRIVER_REPORT', status: 'OPEN'},
    ]}};
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([1, 2]);
    expect(page.exceptionExportRows(data)).toHaveLength(3);
    page.metric.set('all-no-signature');
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([3]);
    page.metric.set('');
    expect(page.exceptionRows(data).map((row: any) => row.exceptionId)).toEqual([1, 2, 3, 4]);
  });

  const loadingCaseReport = () => ({orders: [
    {id: 10, orderNumber: 'ORIGINAL', deliveryDate: '2026-08-31', items: [
      {id: 101, productCode: 'MILK', itemName: '鮮乳', expectedQuantity: 15, loadedQuantity: 12, unit: '瓶', sequence: 1,
        checkedAt: '2026-09-25T08:15:00.701', loadingNotes: '少一籃'},
      {id: 102, productCode: 'EGG', itemName: '雞蛋', expectedQuantity: 6, loadedQuantity: 6, unit: '盒', sequence: 2},
    ]},
    {id: 11, orderNumber: 'FOLLOW-UP', deliveryDate: '2026-09-26', items: [
      {id: 103, itemName: '重送單尚未點交', expectedQuantity: 15, loadedQuantity: null, unit: '瓶'},
    ]},
  ], exceptions: {cases: [
    {exceptionId: 1, orderId: 10, orderNumber: 'ORIGINAL', followUpOrderId: 11, type: 'LOADING_MISMATCH', status: 'OPEN',
      createdAt: '2026-09-25T09:00:00', description: '商品或箱數點交不符'},
    {exceptionId: 2, orderId: 11, orderNumber: 'FOLLOW-UP', type: 'NO_SIGNATURE', status: 'OPEN'},
    {exceptionId: 3, orderId: 12, orderNumber: 'OLD', type: 'LOADING_MISMATCH', status: 'CLOSED'},
  ]}});

  it('joins the original order by ID even when its delivery date is outside the case query, without duplicating case counts', () => {
    const page = open({from: '2026-09-25', to: '2026-09-25', sheet: 'exceptions'}) as any;
    const data = loadingCaseReport();
    const before = JSON.stringify(data);
    const cases = page.exceptionRows(data);
    expect(cases).toHaveLength(3);
    expect(cases[0].loadingItemSummary).toBe('鮮乳：缺少 3 瓶');
    expect(cases[0].loadingItems.map((item: any) => item.itemName)).toEqual(['鮮乳', '雞蛋']);
    expect(cases[1].loadingItems).toBeUndefined();
    expect(cases[2].loadingItemSummary).toBe('原訂單商品明細未載入');
    expect(page.exceptionStatusCount(cases, 'OPEN')).toBe(2);
    expect(JSON.stringify(data)).toBe(before);
  });

  it('keeps current status filters and exports the same original-product evidence with numeric quantities', () => {
    const page = open({sheet: 'exceptions', metric: 'loading-mismatch'}) as any;
    const data = loadingCaseReport();
    const summary = page.exceptionExportRows(data);
    expect(summary).toHaveLength(2);
    expect(summary[0][8]).toBe('不符商品');
    expect(summary[1][8]).toBe('鮮乳：缺少 3 瓶');
    const items = page.loadingExceptionExportRows(data);
    expect(items).toHaveLength(3);
    expect(items[1].slice(4, 11)).toEqual(['MILK', '鮮乳', 15, 12, 3, '瓶', '數量不足']);
    expect(items[2].slice(4, 11)).toEqual(['EGG', '雞蛋', 6, 6, 0, '盒', '相符']);
    expect(items.flat()).not.toContain('重送單尚未點交');
    page.metric.set('open');
    expect(page.exceptionExportRows(data)).toHaveLength(3);
    expect(page.loadingExceptionExportRows(data)).toHaveLength(3);
  });

  it('exports a clear missing-evidence row for old cases without substituting zero quantities', () => {
    const page = open({sheet: 'exceptions'}) as any;
    const data = {orders: [{id: 12, items: []}], exceptions: {cases: [{exceptionId: 3, orderId: 12,
      type: 'LOADING_MISMATCH', orderNumber: 'OLD', status: 'CLOSED', description: '舊資料只有箱數不符'}]}};
    expect(page.exceptionRows(data)[0].loadingItemSummary).toBe('商品點交明細未記錄');
    const exported = page.loadingExceptionExportRows(data);
    expect(exported[1].slice(4, 10)).toEqual([null, null, null, null, null, null]);
    expect(exported[1][10]).toBe('商品點交明細未記錄');
  });

  it('shows a failed handoff time from the original product checks without inventing successful loading', () => {
    const page = open({sheet: 'loading-quality', metric: 'mismatched'}) as any;
    const source = loadingCaseReport();
    const outcome = row(10, {loadingMatched: false, loadingMismatch: true, loadedAt: null, items: []});
    const data = {...source, outcomes: {orders: [outcome]}};
    expect(page.loadingRecordedAt(outcome, data)).toBe('2026-09-25T08:15:00.701');
    expect(page.loadingResult(outcome)).toBe('點交不符');
    expect(page.loadingItemsForOutcome(outcome, data)[0].missingQuantity).toBe(3);
    const exported = page.loadingOutcomeExportRows(data);
    expect(exported[1][5]).toBe('2026-09-25T08:15:00.701');
    expect(exported[4].slice(1, 8)).toEqual(['MILK', '鮮乳', 15, 12, 3, '瓶', '數量不足']);
    expect(outcome.loadedAt).toBeNull();
  });

  it('uses returned item evidence as fallback and keeps unknown actual quantities and time blank', () => {
    const page = open({sheet: 'loading-quality'}) as any;
    const outcome = row(10, {items: [{productCode: 'MILK', itemName: '鮮乳', expectedQuantity: 15,
      loadedQuantity: null, unit: '瓶', notes: '未點交'}]});
    const data = {orders: [], outcomes: {orders: [outcome]}};
    expect(page.loadingRecordedAt(outcome, data)).toBeNull();
    expect(page.loadingItemsForOutcome(outcome, data)[0]).toMatchObject({missingQuantity: null, notes: '未點交', status: 'NOT_RECORDED'});
    expect(page.loadingOutcomeExportRows(data)[4].slice(3, 6)).toEqual([15, null, null]);
  });

  it('renders the exact missing product and an expandable item breakdown, without truncating the cause', () => {
    TestBed.configureTestingModule({imports: [ReportHistory], providers: [provideNativeDateAdapter(),
      {provide: ActivatedRoute, useValue: {snapshot: {queryParamMap: convertToParamMap({sheet: 'exceptions', from: '2026-09-25', to: '2026-09-25'})}}},
      {provide: Router, useValue: {navigate: vi.fn(() => Promise.resolve(true))}},
      {provide: DispatchApiService, useValue: {}},
    ]});
    const fixture = TestBed.createComponent(ReportHistory);
    const page = fixture.componentInstance;
    vi.spyOn(page as any, 'loadFilterOptions').mockImplementation(() => undefined);
    vi.spyOn(page as any, 'createPreview').mockImplementation(() => undefined);
    page.selectedSheet.set('exceptions');
    page.preview.set(loadingCaseReport() as any);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.exception-table > .table-head')?.textContent).toContain('不符商品');
    expect(element.querySelector('.loading-item-summary')?.textContent).toBe('鮮乳：缺少 3 瓶');
    const details = element.querySelector('.loading-exception-details') as HTMLDetailsElement;
    expect(details.querySelector('summary')?.getAttribute('aria-label')).toContain('ORIGINAL');
    expect(details.querySelector('.has-item-mismatch')?.textContent).toContain('鮮乳');
    expect(details.querySelector('.has-item-mismatch .item-missing-quantity')?.textContent).toBe('3');
    expect(details.querySelector('.table-head')?.textContent).toContain('應點數量實點數量缺少數量');
    expect(details.querySelector('.item-loading-note')?.textContent).toBe('少一籃');
    expect(element.querySelector('.exception-copy')?.textContent).toBe('ORIGINAL');
    expect(element.textContent).not.toContain('重送單尚未點交');
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

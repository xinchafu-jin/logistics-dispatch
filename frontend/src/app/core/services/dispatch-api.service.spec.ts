import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { forkJoin } from 'rxjs';
import { DispatchApiService } from './dispatch-api.service';

describe('DispatchApiService', () => {
  let service: DispatchApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(DispatchApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('uses the direct list endpoints exposed by every backend controller', () => {
    forkJoin({
      drivers: service.getDrivers(),
      vehicles: service.getVehicles(),
      stores: service.getStores(),
      warehouses: service.getWarehouses(),
      orders: service.getOrders(),
    }).subscribe((result) => {
      expect(result).toEqual({
        drivers: [],
        vehicles: [],
        stores: [],
        warehouses: [],
        orders: [],
      });
    });

    for (const endpoint of [
      '/api/drivers',
      '/api/vehicles',
      '/api/stores',
      '/api/warehouses',
      '/api/orders',
    ]) {
      const request = httpTesting.expectOne(endpoint);
      expect(request.request.method).toBe('GET');
      request.flush([]);
    }
  });

  it('reads the live fleet through the administrator GPS endpoint', () => {
    service.getLiveFleet().subscribe((pings) => expect(pings).toEqual([]));

    const request = httpTesting.expectOne('/api/fleet/live');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('uses the administrator sticky-note CRUD endpoints', () => {
    const note = {title: '回訪', content: '下午聯絡門市', color: '#dff3ee', sortOrder: 0};
    service.getAdminStickyNotes().subscribe();
    service.createAdminStickyNote(note).subscribe();
    service.updateAdminStickyNote(9, {...note, content: '明早聯絡門市'}).subscribe();
    service.deleteAdminStickyNote(9).subscribe();

    const list = httpTesting.expectOne(
      (request) => request.url === '/api/admin-sticky-notes' && request.method === 'GET',
    );
    expect(list.request.method).toBe('GET');
    list.flush([]);

    const create = httpTesting.expectOne(
      (request) => request.url === '/api/admin-sticky-notes' && request.method === 'POST',
    );
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(note);
    create.flush({id: 9, ...note, createdAt: '', updatedAt: '', version: 0});

    const update = httpTesting.expectOne(
      (request) => request.url === '/api/admin-sticky-notes/9' && request.method === 'PUT',
    );
    expect(update.request.method).toBe('PUT');
    expect(update.request.body.content).toBe('明早聯絡門市');
    update.flush({id: 9, ...note, content: '明早聯絡門市', createdAt: '', updatedAt: '', version: 1});

    const remove = httpTesting.expectOne(
      (request) => request.url === '/api/admin-sticky-notes/9' && request.method === 'DELETE',
    );
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });

  it('reads due delivery exceptions and confirms one through the administrator contract', () => {
    service.getPendingExceptionConfirmations().subscribe((incidents) => expect(incidents).toEqual([]));
    service.confirmExceptionCase(47).subscribe();

    const pending = httpTesting.expectOne('/api/exceptions/pending-confirmation');
    expect(pending.request.method).toBe('GET');
    pending.flush([]);

    const confirm = httpTesting.expectOne('/api/exceptions/47/confirm');
    expect(confirm.request.method).toBe('PATCH');
    expect(confirm.request.body).toBeNull();
    confirm.flush({ id: 47, status: 'CLOSED' });
  });

  it('uses driver application and emergency leave review contracts', () => {
    service.getPendingDriverAccountApplicationCount().subscribe();
    service.getPendingDriverAccountApplications().subscribe();
    service.approveDriverAccountApplication(17).subscribe();
    service.rejectDriverAccountApplication(18, '資料不完整').subscribe();
    service.getPendingEmergencyLeaveRequests().subscribe();
    service.getEmergencyLeaveReplacementCandidates(31).subscribe();
    service.approveEmergencyLeaveRequest(31, 9).subscribe();
    service.rejectEmergencyLeaveRequest(32, '請補充請假原因').subscribe();

    const count = httpTesting.expectOne('/api/driver-account-applications/pending/count');
    expect(count.request.method).toBe('GET');
    count.flush({ count: 2 });

    const applications = httpTesting.expectOne('/api/driver-account-applications/pending');
    expect(applications.request.method).toBe('GET');
    applications.flush([]);

    const approveApplication = httpTesting.expectOne('/api/driver-account-applications/17/approve');
    expect(approveApplication.request.method).toBe('PATCH');
    expect(approveApplication.request.body).toBeNull();
    approveApplication.flush({ id: 17, status: 'APPROVED' });

    const rejectApplication = httpTesting.expectOne('/api/driver-account-applications/18/reject');
    expect(rejectApplication.request.method).toBe('PATCH');
    expect(rejectApplication.request.body).toEqual({ reason: '資料不完整' });
    rejectApplication.flush({ id: 18, status: 'REJECTED' });

    const pendingLeaves = httpTesting.expectOne('/api/emergency-leave-requests/pending');
    expect(pendingLeaves.request.method).toBe('GET');
    pendingLeaves.flush([]);

    const candidates = httpTesting.expectOne(
      '/api/emergency-leave-requests/31/replacement-candidates',
    );
    expect(candidates.request.method).toBe('GET');
    candidates.flush([]);

    const approveLeave = httpTesting.expectOne('/api/emergency-leave-requests/31/approve');
    expect(approveLeave.request.method).toBe('PATCH');
    expect(approveLeave.request.body).toEqual({ replacementDriverId: 9 });
    approveLeave.flush({ id: 31, status: 'APPROVED' });

    const rejectLeave = httpTesting.expectOne('/api/emergency-leave-requests/32/reject');
    expect(rejectLeave.request.method).toBe('PATCH');
    expect(rejectLeave.request.body).toEqual({ reason: '請補充請假原因' });
    rejectLeave.flush({ id: 32, status: 'REJECTED' });
  });

  it('uses the regular leave review, history, calendar, and partial-day contracts', () => {
    const partial = {
      driverId: 8,
      workDate: '2026-09-28',
      leaveType: 'ANNUAL' as const,
      leaveStart: '13:00',
      leaveEnd: '17:00',
      reason: '家庭行程',
    };

    service.getPendingLeaveRequests().subscribe();
    service.getDriverMonthlyLeaveSummary(8, '2026-09').subscribe();
    service.getLeaveRequestHistory(23).subscribe();
    service.approveLeaveRequest(23, '已安排代班').subscribe();
    service.rejectLeaveRequest(24, '當日人力不足，請改期').subscribe();
    service.createPlannedPartialLeave(partial).subscribe();

    const pending = httpTesting.expectOne('/api/leave-requests/pending');
    expect(pending.request.method).toBe('GET');
    pending.flush([]);

    const monthly = httpTesting.expectOne(
      (request) =>
        request.url === '/api/leave-requests/drivers/8/monthly' &&
        request.params.get('month') === '2026-09',
    );
    expect(monthly.request.method).toBe('GET');
    monthly.flush({ driverId: 8, month: '2026-09', records: [] });

    const history = httpTesting.expectOne('/api/leave-requests/23/history');
    expect(history.request.method).toBe('GET');
    history.flush([]);

    const approve = httpTesting.expectOne('/api/leave-requests/23/approve');
    expect(approve.request.method).toBe('PATCH');
    expect(approve.request.body).toEqual({ reason: '已安排代班' });
    approve.flush({ id: 23, status: 'APPROVED', decisionReason: '已安排代班' });

    const reject = httpTesting.expectOne('/api/leave-requests/24/reject');
    expect(reject.request.method).toBe('PATCH');
    expect(reject.request.body).toEqual({ reason: '當日人力不足，請改期' });
    reject.flush({ id: 24, status: 'REJECTED', decisionReason: '當日人力不足，請改期' });

    const createPartial = httpTesting.expectOne('/api/leave-requests/planned-partial');
    expect(createPartial.request.method).toBe('POST');
    expect(createPartial.request.body).toEqual(partial);
    createPartial.flush({ id: 25, status: 'APPROVED', decisionReason: '主管預排' });
  });

  it('uses batch order, administrator, and per-driver GPS contracts', () => {
    const order = {
      orderNumber: 'DO-BATCH-001',
      storeId: 3,
      warehouseId: 1,
      boxCount: 4,
      notes: '',
      deliveryDate: '2026-09-04',
      status: 'PENDING_CONFIRM' as const,
    };

    service.createOrdersBatch([order]).subscribe();
    service
      .createAdminUser({
        account: 'dispatch-admin',
        password: 'Password1',
        name: '調度主管',
        phone: '0912345678',
      })
      .subscribe();
    service.getFleetDriverLatest(8).subscribe();
    service.getFleetDriverCurrent(8).subscribe();
    service.getFleetDriverHistory(8, '2026-09-04T08:00', '2026-09-04T18:00').subscribe();

    const batchRequest = httpTesting.expectOne('/api/orders/batch');
    expect(batchRequest.request.method).toBe('POST');
    expect(batchRequest.request.body).toEqual({ orders: [order] });
    batchRequest.flush([{ id: 21, ...order }]);

    const adminRequest = httpTesting.expectOne('/api/admin-users');
    expect(adminRequest.request.method).toBe('POST');
    expect(adminRequest.request.body).toEqual({
      account: 'dispatch-admin',
      password: 'Password1',
      name: '調度主管',
      phone: '0912345678',
    });
    adminRequest.flush({ id: 3, account: 'dispatch-admin', name: '調度主管', phone: '0912345678' });

    const latestRequest = httpTesting.expectOne('/api/fleet/drivers/8/gps/latest');
    expect(latestRequest.request.method).toBe('GET');
    latestRequest.flush({
      id: 1,
      driverId: 8,
      lat: 22.99,
      lng: 120.2,
      timestamp: '2026-09-04T09:00',
    });

    const currentRequest = httpTesting.expectOne('/api/fleet/drivers/8/gps/current');
    expect(currentRequest.request.method).toBe('GET');
    currentRequest.flush({
      id: 1,
      driverId: 8,
      lat: 22.99,
      lng: 120.2,
      timestamp: '2026-09-04T09:00',
    });

    const historyRequest = httpTesting.expectOne(
      (request) =>
        request.urlWithParams ===
          '/api/fleet/drivers/8/gps/history?from=2026-09-04T08:00&to=2026-09-04T18:00' &&
        request.method === 'GET',
    );
    historyRequest.flush([]);
  });

  it('writes order status using the backend PUT contract', () => {
    service
      .updateOrder(12, {
        orderNumber: 'DO-001',
        storeId: 3,
        warehouseId: 1,
        boxCount: 4,
        notes: '',
        deliveryDate: '2026-08-19',
        status: 'CONFIRMED',
      })
      .subscribe();

    const request = httpTesting.expectOne('/api/orders/12');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.status).toBe('CONFIRMED');
    request.flush({
      id: 12,
      orderNumber: 'DO-001',
      storeId: 3,
      boxCount: 4,
      notes: '',
      deliveryDate: '2026-08-19',
      status: 'CONFIRMED',
    });
  });

  it('creates and deletes orders through the backend CRUD contract', () => {
    const order = {
      orderNumber: 'DO-002',
      storeId: 3,
      warehouseId: 1,
      boxCount: 4,
      notes: '',
      deliveryDate: '2026-08-23',
      status: 'PENDING_CONFIRM' as const,
    };

    service.createOrder(order).subscribe();
    service.deleteOrder(13).subscribe();

    const createRequest = httpTesting.expectOne('/api/orders');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual(order);
    createRequest.flush({ id: 13, ...order });

    const deleteRequest = httpTesting.expectOne('/api/orders/13');
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);
  });

  it('updates driver and store status through the backend PATCH contracts', () => {
    service.updateDriverStatus(8, { isActive: false }).subscribe();
    service.updateStoreStatus(12, { status: 'SUSPENDED' }).subscribe();

    const driverRequest = httpTesting.expectOne('/api/drivers/8/status');
    expect(driverRequest.request.method).toBe('PATCH');
    expect(driverRequest.request.body).toEqual({ isActive: false });
    driverRequest.flush({
      id: 8,
      account: 'driver-08',
      name: '王小明',
      workStart: '08:00',
      workEnd: '17:00',
      restDuration: 60,
      isActive: false,
    });

    const storeRequest = httpTesting.expectOne('/api/stores/12/status');
    expect(storeRequest.request.method).toBe('PATCH');
    expect(storeRequest.request.body).toEqual({ status: 'SUSPENDED' });
    storeRequest.flush({
      id: 12,
      storeCode: 'ST-012',
      name: '永康門市',
      lat: 23,
      lng: 120,
      receivingStart: '09:00',
      receivingEnd: '18:00',
      status: 'SUSPENDED',
    });
  });

  it('uses the driver schedule endpoints with the backend request shapes', () => {
    service.getScheduleMonth('2026-09').subscribe();
    service.generateScheduleMonth('2026-09').subscribe();
    service.getScheduleMonthShifts(41).subscribe();
    service
      .updateDriverShift(86, {
        shiftType: 'WORK',
        workStart: '08:30',
        workEnd: '17:30',
        overtimeMinutes: 30,
        changeReason: '支援月初配送量',
      })
      .subscribe();
    service.syncScheduleDrivers(41).subscribe();
    service.updateDriverShiftsBatch(41, [{
      id: 86,
      scheduleMonthId: 41,
      driverId: 3,
      workDate: '2026-09-01',
      shiftType: 'WORK',
      workStart: '08:30',
      workEnd: '17:30',
      overtimeMinutes: 0,
      changeReason: '批次套用',
      lastModifiedAt: null,
      version: 2,
    }]).subscribe();
    service.markDriverShiftLeave(87, {reason: '已核准特休'}).subscribe();
    service.publishScheduleMonth(41).subscribe();

    const getMonth = httpTesting.expectOne(
      (request) =>
        request.urlWithParams === '/api/driver-schedules/months?month=2026-09' &&
        request.method === 'GET',
    );
    expect(getMonth.request.method).toBe('GET');
    getMonth.flush({id: 41, scheduleMonth: '2026-09-01', status: 'DRAFT'});

    const createMonth = httpTesting.expectOne(
      (request) =>
        request.urlWithParams === '/api/driver-schedules/months?month=2026-09' &&
        request.method === 'POST',
    );
    expect(createMonth.request.method).toBe('POST');
    expect(createMonth.request.body).toBeNull();
    createMonth.flush({id: 41, scheduleMonth: '2026-09-01', status: 'DRAFT'});

    const shifts = httpTesting.expectOne('/api/driver-schedules/months/41/shifts');
    expect(shifts.request.method).toBe('GET');
    shifts.flush([]);

    const updateShift = httpTesting.expectOne('/api/driver-schedules/shifts/86');
    expect(updateShift.request.method).toBe('PUT');
    expect(updateShift.request.body).toEqual({
      shiftType: 'WORK',
      workStart: '08:30',
      workEnd: '17:30',
      overtimeMinutes: 30,
      changeReason: '支援月初配送量',
    });
    updateShift.flush({id: 86, shiftType: 'WORK'});

    const syncDrivers = httpTesting.expectOne('/api/driver-schedules/months/41/sync-drivers');
    expect(syncDrivers.request.method).toBe('POST');
    expect(syncDrivers.request.body).toBeNull();
    syncDrivers.flush([]);

    const batch = httpTesting.expectOne('/api/driver-schedules/months/41/shifts/batch');
    expect(batch.request.method).toBe('PUT');
    expect(batch.request.body).toHaveLength(1);
    batch.flush([]);

    const leave = httpTesting.expectOne('/api/driver-schedules/shifts/87/leave');
    expect(leave.request.method).toBe('PATCH');
    expect(leave.request.body).toEqual({reason: '已核准特休'});
    leave.flush({id: 87, shiftType: 'LEAVE'});

    const publish = httpTesting.expectOne('/api/driver-schedules/months/41/publish');
    expect(publish.request.method).toBe('POST');
    expect(publish.request.body).toBeNull();
    publish.flush({id: 41, scheduleMonth: '2026-09-01', status: 'PUBLISHED'});
  });

  it('uses route metrics, fuel price, and report contracts from the GPS and mileage backend', () => {
    service.getRouteMetrics(77).subscribe();
    service.getLatestFuelPrice().subscribe();
    service.syncFuelPrice().subscribe();
    service.getFuelPriceHistory('2026-09-01', '2026-09-30').subscribe();
    service.getReportSummary({period: 'THIS_WEEK'}).subscribe();
    service.getReportAttendance({period: 'THIS_WEEK'}).subscribe();
    service.getReportRoutes({period: 'THIS_WEEK'}).subscribe();
    service.getReportDrivers({period: 'THIS_WEEK'}).subscribe();
    service.getReportVehicles({period: 'THIS_WEEK'}).subscribe();
    service.getReportWarehouses({period: 'THIS_WEEK'}).subscribe();
    service.getReportStores({period: 'THIS_WEEK'}).subscribe();
    service.getReportExceptions({period: 'THIS_WEEK'}).subscribe();

    const routeMetrics = httpTesting.expectOne('/api/dispatch/routes/77/metrics');
    expect(routeMetrics.request.method).toBe('GET');
    routeMetrics.flush({routeId: 77});

    const fuel = httpTesting.expectOne('/api/fuel-prices/latest');
    expect(fuel.request.method).toBe('GET');
    fuel.flush({});

    const sync = httpTesting.expectOne('/api/fuel-prices/sync');
    expect(sync.request.method).toBe('POST');
    sync.flush({});

    const history = httpTesting.expectOne((request) => request.url === '/api/fuel-prices/history');
    expect(history.request.params.get('from')).toBe('2026-09-01');
    expect(history.request.params.get('to')).toBe('2026-09-30');
    history.flush([]);

    for (const endpoint of [
      '/api/reports/summary', '/api/reports/attendance', '/api/reports/routes', '/api/reports/drivers',
      '/api/reports/vehicles', '/api/reports/warehouses', '/api/reports/stores', '/api/reports/exceptions',
    ]) {
      const request = httpTesting.expectOne((candidate) => candidate.url === endpoint);
      expect(request.request.params.get('period')).toBe('THIS_WEEK');
      request.flush({from: '2026-09-15', to: '2026-09-21'});
    }
  });

  it('withdraws the whole day publication with only the date, like publish', () => {
    service.withdrawDispatch('2026-09-28').subscribe((boards) => expect(boards).toEqual([]));

    const request = httpTesting.expectOne('/api/dispatch/withdraw?date=2026-09-28');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush([]);
  });

  it('deletes a driver through the backend DELETE contract', () => {
    service.deleteDriver(8).subscribe();

    const request = httpTesting.expectOne('/api/drivers/8');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });

  it('writes vehicles through the backend CRUD contract', () => {
    const vehicle = {
      warehouseId: 1,
      plateNumber: 'ABC-1234',
      vehicleType: '常溫貨車',
      capacity: 80,
      status: 'AVAILABLE' as const,
    };

    service.createVehicle(vehicle).subscribe();
    service.updateVehicle(6, { ...vehicle, status: 'MAINTENANCE' }).subscribe();
    service.deleteVehicle(6).subscribe();

    const createRequest = httpTesting.expectOne('/api/vehicles');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual(vehicle);
    createRequest.flush({ id: 6, ...vehicle });

    const updateRequest = httpTesting.expectOne(
      (request) => request.url === '/api/vehicles/6' && request.method === 'PUT',
    );
    expect(updateRequest.request.body.status).toBe('MAINTENANCE');
    updateRequest.flush({ id: 6, ...vehicle, status: 'MAINTENANCE' });

    const deleteRequest = httpTesting.expectOne(
      (request) => request.url === '/api/vehicles/6' && request.method === 'DELETE',
    );
    deleteRequest.flush(null);
  });

  it('deletes a store through the backend DELETE contract', () => {
    service.deleteStore(12).subscribe();

    const request = httpTesting.expectOne('/api/stores/12');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });

  it('writes warehouses through the backend CRUD contract', () => {
    const warehouse = {
      warehouseCode: 'WH-001',
      name: '高雄倉庫',
      lat: 23,
      lng: 120,
      isActive: true,
    };

    service.createWarehouse(warehouse).subscribe();
    service.updateWarehouse(5, { ...warehouse, name: '高雄中央倉庫' }).subscribe();
    service.deleteWarehouse(5).subscribe();

    const createRequest = httpTesting.expectOne('/api/warehouses');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual(warehouse);
    createRequest.flush({ id: 5, ...warehouse });

    const updateRequest = httpTesting.expectOne(
      (request) => request.url === '/api/warehouses/5' && request.method === 'PUT',
    );
    expect(updateRequest.request.method).toBe('PUT');
    expect(updateRequest.request.body.name).toBe('高雄中央倉庫');
    updateRequest.flush({ id: 5, ...warehouse, name: '高雄中央倉庫' });

    const deleteRequest = httpTesting.expectOne(
      (request) => request.url === '/api/warehouses/5' && request.method === 'DELETE',
    );
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);
  });
});

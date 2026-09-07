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

    const leave = httpTesting.expectOne('/api/driver-schedules/shifts/87/leave');
    expect(leave.request.method).toBe('PATCH');
    expect(leave.request.body).toEqual({reason: '已核准特休'});
    leave.flush({id: 87, shiftType: 'LEAVE'});

    const publish = httpTesting.expectOne('/api/driver-schedules/months/41/publish');
    expect(publish.request.method).toBe('POST');
    expect(publish.request.body).toBeNull();
    publish.flush({id: 41, scheduleMonth: '2026-09-01', status: 'PUBLISHED'});
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
      name: '台南倉庫',
      lat: 23,
      lng: 120,
      isActive: true,
    };

    service.createWarehouse(warehouse).subscribe();
    service.updateWarehouse(5, { ...warehouse, name: '台南中央倉庫' }).subscribe();
    service.deleteWarehouse(5).subscribe();

    const createRequest = httpTesting.expectOne('/api/warehouses');
    expect(createRequest.request.method).toBe('POST');
    expect(createRequest.request.body).toEqual(warehouse);
    createRequest.flush({ id: 5, ...warehouse });

    const updateRequest = httpTesting.expectOne(
      (request) => request.url === '/api/warehouses/5' && request.method === 'PUT',
    );
    expect(updateRequest.request.method).toBe('PUT');
    expect(updateRequest.request.body.name).toBe('台南中央倉庫');
    updateRequest.flush({ id: 5, ...warehouse, name: '台南中央倉庫' });

    const deleteRequest = httpTesting.expectOne(
      (request) => request.url === '/api/warehouses/5' && request.method === 'DELETE',
    );
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);
  });
});

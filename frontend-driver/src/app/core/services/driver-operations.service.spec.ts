import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DriverOperationsService } from './driver-operations.service';

describe('DriverOperationsService', () => {
  let service: DriverOperationsService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    service = TestBed.inject(DriverOperationsService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('uses the driver attendance and GPS endpoints provided by the backend', () => {
    service.getTodayAttendance().subscribe();
    service.clockIn().subscribe();
    service.startBreak().subscribe();
    service.clockOut().subscribe();
    service.uploadGps({ lat: 22.6273, lng: 120.3014 }).subscribe();

    const today = httpTesting.expectOne('/api/driver/attendance/today');
    expect(today.request.method).toBe('GET');
    today.flush({});

    for (const path of [
      '/api/driver/attendance/clock-in',
      '/api/driver/attendance/break',
      '/api/driver/attendance/clock-out',
    ]) {
      const request = httpTesting.expectOne(path);
      expect(request.request.method).toBe('POST');
      request.flush({});
    }

    const gps = httpTesting.expectOne('/api/driver/gps');
    expect(gps.request.method).toBe('POST');
    expect(gps.request.body).toEqual({ lat: 22.6273, lng: 120.3014 });
    gps.flush(null);
  });

  it('requests only published driver shifts in the selected date range', () => {
    service.getPublishedShifts('2026-09-01', '2026-09-30').subscribe();

    const request = httpTesting.expectOne(
      (candidate) =>
        candidate.url === '/api/driver/shifts' &&
        candidate.params.get('from') === '2026-09-01' &&
        candidate.params.get('to') === '2026-09-30',
    );
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('requests the signed-in driver\'s published tasks for today', () => {
    service.getTodayTasks().subscribe();

    const request = httpTesting.expectOne('/api/driver/tasks/today');
    expect(request.request.method).toBe('GET');
    request.flush({ date: '2026-09-02', driverId: 1, driverName: '測試司機', routes: [] });
  });

  it('uses the road route and emergency leave contracts', () => {
    const route = {
      fromLat: 22.6273,
      fromLng: 120.3014,
      toLat: 22.6401,
      toLng: 120.3022,
    };

    service.gpsRoute(route).subscribe();
    service.submitEmergencyLeave({ reason: '身體不適，需要返回休息。' }).subscribe();
    service.getEmergencyLeaves().subscribe();

    const routeRequest = httpTesting.expectOne('/api/driver/route');
    expect(routeRequest.request.method).toBe('POST');
    expect(routeRequest.request.body).toEqual(route);
    routeRequest.flush({
      path: [[22.6273, 120.3014], [22.6401, 120.3022]],
      distance: 1500,
      duration: 240,
    });

    const leaveRequest = httpTesting.expectOne(
      (request) =>
        request.url === '/api/driver/emergency-leave-requests' && request.method === 'POST',
    );
    expect(leaveRequest.request.method).toBe('POST');
    expect(leaveRequest.request.body).toEqual({ reason: '身體不適，需要返回休息。' });
    leaveRequest.flush({ id: 4, status: 'PENDING' });

    const leavesRequest = httpTesting.expectOne(
      (request) =>
        request.url === '/api/driver/emergency-leave-requests' && request.method === 'GET',
    );
    expect(leavesRequest.request.method).toBe('GET');
    leavesRequest.flush([]);
  });

  it('uses the delivery and mileage endpoints with the backend request shapes', () => {
    service.arrive({ orderId: 41 }).subscribe();
    service.deliver({
      orderId: 41,
      boxCount: 12,
      notes: '已交貨',
    }).subscribe();
    service.noSignature({
      orderId: 42,
      notes: '現場無人',
    }).subscribe();
    service.startMileage({ odometer: 18_400 }).subscribe();
    service.endMileage({ odometer: 18_438 }).subscribe();
    service.recalculateMileage().subscribe();
    service.reportException({orderId: 41, description: '外箱破損，已拍照存證。'}).subscribe();
    service.uploadDeliveryPhoto(new File(['proof'], 'proof.jpg', {type: 'image/jpeg'})).subscribe();

    const arrive = httpTesting.expectOne('/api/driver/arrive');
    expect(arrive.request.body).toEqual({ orderId: 41 });
    arrive.flush({});

    const deliver = httpTesting.expectOne('/api/driver/deliver');
    expect(deliver.request.body).toEqual({
      orderId: 41,
      boxCount: 12,
      notes: '已交貨',
    });
    deliver.flush({});

    const noSignature = httpTesting.expectOne('/api/driver/no-signature');
    expect(noSignature.request.body).toEqual({
      orderId: 42,
      notes: '現場無人',
    });
    noSignature.flush({});

    for (const [path, odometer] of [
      ['/api/driver/mileage/start', 18_400],
      ['/api/driver/mileage/end', 18_438],
    ] as const) {
      const request = httpTesting.expectOne(path);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ odometer });
      request.flush({});
    }

    const recalculate = httpTesting.expectOne('/api/driver/mileage/recalculate');
    expect(recalculate.request.method).toBe('POST');
    expect(recalculate.request.body).toEqual({});
    recalculate.flush({});

    const exception = httpTesting.expectOne('/api/driver/exception');
    expect(exception.request.body).toEqual({orderId: 41, description: '外箱破損，已拍照存證。'});
    exception.flush({});

    const photo = httpTesting.expectOne('/api/driver/delivery-photo');
    expect(photo.request.method).toBe('POST');
    expect(photo.request.body).toBeInstanceOf(FormData);
    photo.flush({url: '/uploads/delivery-photos/proof.jpg'});
  });

  it('uses the driver chat endpoints without sending a driver id', () => {
    service.getMessages().subscribe();
    service.getMessages(42).subscribe();
    service.sendMessage('國道塞車，晚 20 分鐘').subscribe();
    service.markMessagesRead().subscribe();

    // 第一次打開：不能帶 afterId，帶成 "undefined" 後端會回 400
    const firstLoad = httpTesting.expectOne(
      (request) =>
        request.method === 'GET' && request.url === '/api/driver/messages' && !request.params.has('afterId'),
    );
    expect(firstLoad.request.method).toBe('GET');
    firstLoad.flush([]);

    const catchUp = httpTesting.expectOne(
      (request) => request.url === '/api/driver/messages' && request.params.get('afterId') === '42',
    );
    expect(catchUp.request.method).toBe('GET');
    catchUp.flush([]);

    const send = httpTesting.expectOne(
      (request) => request.url === '/api/driver/messages' && request.method === 'POST',
    );
    // 只送內容：driverId、寄件人、時間都由後端決定
    expect(send.request.body).toEqual({content: '國道塞車，晚 20 分鐘'});
    send.flush({});

    const read = httpTesting.expectOne('/api/driver/messages/read');
    expect(read.request.method).toBe('POST');
    read.flush(1);
  });
});

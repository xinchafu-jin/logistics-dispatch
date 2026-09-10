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

  it('uses the delivery and mileage endpoints with the backend request shapes', () => {
    service.arrive({ orderId: 41 }).subscribe();
    service.deliver({
      orderId: 41,
      boxCount: 12,
      photo: 'https://upload.example.test/proofs/41.jpg',
      notes: '已交貨',
    }).subscribe();
    service.noSignature({
      orderId: 42,
      photo: 'https://upload.example.test/proofs/42.jpg',
      notes: '現場無人',
    }).subscribe();
    service.startMileage({ odometer: 18_400 }).subscribe();
    service.endMileage({ odometer: 18_438 }).subscribe();

    const arrive = httpTesting.expectOne('/api/driver/arrive');
    expect(arrive.request.body).toEqual({ orderId: 41 });
    arrive.flush({});

    const deliver = httpTesting.expectOne('/api/driver/deliver');
    expect(deliver.request.body).toEqual({
      orderId: 41,
      boxCount: 12,
      photo: 'https://upload.example.test/proofs/41.jpg',
      notes: '已交貨',
    });
    deliver.flush({});

    const noSignature = httpTesting.expectOne('/api/driver/no-signature');
    expect(noSignature.request.body).toEqual({
      orderId: 42,
      photo: 'https://upload.example.test/proofs/42.jpg',
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
  });
});

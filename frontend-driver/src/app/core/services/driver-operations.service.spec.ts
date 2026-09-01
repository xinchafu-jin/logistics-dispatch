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
    service.uploadGps({ lat: 22.9971, lng: 120.2125 }).subscribe();

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
    expect(gps.request.body).toEqual({ lat: 22.9971, lng: 120.2125 });
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
});

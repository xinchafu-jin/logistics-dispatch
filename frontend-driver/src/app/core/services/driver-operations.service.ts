import {HttpClient, HttpParams} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {Observable} from 'rxjs';
import {
  ArriveRequest,
  AttendanceRecordDto,
  DeliverRequest,
  DeliveryRecordResponse,
  DriverTasksResponse,
  DriverShiftDto,
  GpsPingRequest,
  MileageLogResponse,
  MileageRequest,
  NoSignatureRequest, GpsRouteRequest, GpsRouteResponse,
} from './driver-operations.models';

@Injectable({providedIn: 'root'})
export class DriverOperationsService {
  private readonly http = inject(HttpClient);

  getTodayAttendance(): Observable<AttendanceRecordDto> {
    return this.http.get<AttendanceRecordDto>('/api/driver/attendance/today');
  }

  clockIn(): Observable<AttendanceRecordDto> {
    return this.http.post<AttendanceRecordDto>('/api/driver/attendance/clock-in', {});
  }

  startBreak(): Observable<AttendanceRecordDto> {
    return this.http.post<AttendanceRecordDto>('/api/driver/attendance/break', {});
  }

  clockOut(): Observable<AttendanceRecordDto> {
    return this.http.post<AttendanceRecordDto>('/api/driver/attendance/clock-out', {});
  }

  uploadGps(position: GpsPingRequest): Observable<void> {
    return this.http.post<void>('/api/driver/gps', position);
  }

  getPublishedShifts(from: string, to: string): Observable<DriverShiftDto[]> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<DriverShiftDto[]>('/api/driver/shifts', {params});
  }

  getTodayTasks(): Observable<DriverTasksResponse> {
    return this.http.get<DriverTasksResponse>('/api/driver/tasks/today');
  }

  arrive(request: ArriveRequest): Observable<DeliveryRecordResponse> {
    return this.http.post<DeliveryRecordResponse>('/api/driver/arrive', request);
  }

  deliver(request: DeliverRequest): Observable<DeliveryRecordResponse> {
    return this.http.post<DeliveryRecordResponse>('/api/driver/deliver', request);
  }

  noSignature(request: NoSignatureRequest): Observable<DeliveryRecordResponse> {
    return this.http.post<DeliveryRecordResponse>('/api/driver/no-signature', request);
  }

  startMileage(request: MileageRequest): Observable<MileageLogResponse> {
    return this.http.post<MileageLogResponse>('/api/driver/mileage/start', request);
  }

  endMileage(request: MileageRequest): Observable<MileageLogResponse> {
    return this.http.post<MileageLogResponse>('/api/driver/mileage/end', request);
  }

  gpsRoute(request: GpsRouteRequest): Observable<GpsRouteResponse> {
    return this.http.post<GpsRouteResponse>('/api/driver/route', request);
  }

}

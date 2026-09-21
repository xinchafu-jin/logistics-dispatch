import {HttpClient, HttpParams} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {Observable} from 'rxjs';
import {
  ArriveRequest,
  AttendanceRecordDto,
  DeliverRequest,
  DeliveryRecordResponse,
  DriverExceptionRequest,
  DriverProfileDto,
  DriverTasksResponse,
  DriverShiftDto,
  GpsPingRequest,
  GpsRouteRequest,
  GpsRouteResponse,
  EmergencyLeaveRequest,
  EmergencyLeaveResponse,
  MileageLogResponse,
  MileageRequest,
  NoSignatureRequest,
  PhotoUploadResponse,
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

  getProfile(): Observable<DriverProfileDto> {
    return this.http.get<DriverProfileDto>('/api/driver/profile');
  }

  uploadProfilePhoto(file: File): Observable<DriverProfileDto> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<DriverProfileDto>('/api/driver/profile/photo', formData);
  }

  getPublishedShifts(from: string, to: string): Observable<DriverShiftDto[]> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<DriverShiftDto[]>('/api/driver/shifts', {params});
  }

  getTodayTasks(): Observable<DriverTasksResponse> {
    return this.http.get<DriverTasksResponse>('/api/driver/tasks/today');
  }

  submitEmergencyLeave(request: EmergencyLeaveRequest): Observable<EmergencyLeaveResponse> {
    return this.http.post<EmergencyLeaveResponse>('/api/driver/emergency-leave-requests', request);
  }

  getEmergencyLeaves(): Observable<EmergencyLeaveResponse[]> {
    return this.http.get<EmergencyLeaveResponse[]>('/api/driver/emergency-leave-requests');
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

  uploadDeliveryPhoto(file: File): Observable<PhotoUploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<PhotoUploadResponse>('/api/driver/delivery-photo', formData);
  }

  reportException(request: DriverExceptionRequest): Observable<DeliveryRecordResponse> {
    return this.http.post<DeliveryRecordResponse>('/api/driver/exception', request);
  }

  startMileage(request: MileageRequest): Observable<MileageLogResponse> {
    return this.http.post<MileageLogResponse>('/api/driver/mileage/start', request);
  }

  endMileage(request: MileageRequest): Observable<MileageLogResponse> {
    return this.http.post<MileageLogResponse>('/api/driver/mileage/end', request);
  }

  recalculateMileage(): Observable<MileageLogResponse> {
    return this.http.post<MileageLogResponse>('/api/driver/mileage/recalculate', {});
  }

  gpsRoute(request: GpsRouteRequest): Observable<GpsRouteResponse> {
    return this.http.post<GpsRouteResponse>('/api/driver/route', request);
  }

}

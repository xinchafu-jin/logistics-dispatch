import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AttendanceRecordDto,
  DriverShiftDto,
  GpsPingRequest,
} from './driver-operations.models';

@Injectable({ providedIn: 'root' })
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
    return this.http.get<DriverShiftDto[]>('/api/driver/shifts', { params });
  }
}

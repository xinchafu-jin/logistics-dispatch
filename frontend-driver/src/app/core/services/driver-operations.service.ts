import {HttpClient, HttpParams} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {Observable} from 'rxjs';
import {
  ArriveRequest,
  AttendanceRecordDto,
  DeliverRequest,
  DeliveryRecordResponse,
  DriverExceptionRequest,
  DriverMessageDto,
  DriverMessageRequest,
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

  startMileage(request: MileageRequest, photo: File): Observable<MileageLogResponse> {
    const formData = new FormData();
    formData.append('odometer', String(request.odometer));
    formData.append('photo', photo);
    return this.http.post<MileageLogResponse>('/api/driver/mileage/start', formData);
  }

  endMileage(request: MileageRequest, photo: File): Observable<MileageLogResponse> {
    const formData = new FormData();
    formData.append('odometer', String(request.odometer));
    formData.append('photo', photo);
    return this.http.post<MileageLogResponse>('/api/driver/mileage/end', formData);
  }

  recalculateMileage(): Observable<MileageLogResponse> {
    return this.http.post<MileageLogResponse>('/api/driver/mileage/recalculate', {});
  }

  gpsRoute(request: GpsRouteRequest): Observable<GpsRouteResponse> {
    return this.http.post<GpsRouteResponse>('/api/driver/route', request);
  }

  // ── 司機聊天室 ─────────────────────────────────────────
  // driverId 不用帶：後端一律從登入 token 取，只能讀寫自己的對話

  /**
   * 讀取自己與調度中心的對話，一律由舊到新。
   * 不帶 afterId：最近 50 則，打開聊天時用；帶 afterId：只回比它新的，重連或回到前景時補抓用。
   */
  getMessages(afterId?: number): Observable<DriverMessageDto[]> {
    // 沒有 afterId 就不能帶這個參數，帶成 "undefined" 字串後端會回 400
    let params = new HttpParams();
    if (afterId !== undefined) {
      params = params.set('afterId', afterId);
    }
    return this.http.get<DriverMessageDto[]>('/api/driver/messages', {params});
  }

  /** 發訊息給調度中心。回傳存好的那一則（含 id），直接放進清單，之後推播收到同一則時用 id 去重 */
  sendMessage(content: string): Observable<DriverMessageDto> {
    const request: DriverMessageRequest = {content};
    return this.http.post<DriverMessageDto>('/api/driver/messages', request);
  }

  /**
   * 把調度中心的回覆標成已讀，回傳這次標了幾筆。
   * 只在司機真的看著聊天畫面時呼叫；在別的畫面收到訊息時不要呼叫，否則紅點永遠亮不起來。
   */
  markMessagesRead(): Observable<number> {
    return this.http.post<number>('/api/driver/messages/read', {});
  }
}

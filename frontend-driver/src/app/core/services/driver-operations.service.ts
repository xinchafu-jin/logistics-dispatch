import {HttpClient, HttpParams} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {Observable} from 'rxjs';
import {
  ArriveRequest,
  AttendanceRecordDto,
  DeliverRequest,
  DeliveryRecordResponse,
  DriverCaseDto,
  DriverCaseRequest,
  DriverLeaveHistoryResponse,
  DriverLeaveBatchResponse,
  DriverMakeupLeaveRequest,
  DriverMakeupLeaveBatchRequest,
  DriverPlannedLeaveBatchRequest,
  DriverLeaveRequest,
  DriverLeaveRequestResponse,
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
  LoadingRequest,
  LoadingResponse,
  MileageLogResponse,
  MileageRequest,
  NoSignatureRequest,
  PhotoUploadResponse,
  PreTripInspectionRequest,
  PreTripInspectionResult,
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

  // ── 出車前安全檢查 ─────────────────────────────────────
  // 通過之後才能記出車里程和點交；能不能出車由後端判定

  getPreTripInspection(routeId: number): Observable<PreTripInspectionResult> {
    const params = new HttpParams().set('routeId', routeId);
    return this.http.get<PreTripInspectionResult>('/api/driver/pre-trip', {params});
  }

  /**
   * 檢查內容放 request（JSON），照片放檔案欄位：酒測器照片必填；行車紀錄器照片通過時必填
   * （同一張證明行車紀錄器有開、也拍到出車里程）；故障照片選填。
   * 通過的話後端同一個交易就記下出車里程，不用再另外呼叫 startMileage。
   * request 要包成 application/json 的 Blob，後端的 @RequestPart 才會用 JSON 解析
   */
  submitPreTripInspection(
    request: PreTripInspectionRequest,
    alcoholPhoto: File,
    dashcamPhoto: File | null,
    faultPhoto: File | null,
  ): Observable<PreTripInspectionResult> {
    const formData = new FormData();
    formData.append('request', new Blob([JSON.stringify(request)], {type: 'application/json'}));
    formData.append('alcoholPhoto', alcoholPhoto);
    if (dashcamPhoto) {
      formData.append('dashcamPhoto', dashcamPhoto);
    }
    if (faultPhoto) {
      formData.append('faultPhoto', faultPhoto);
    }
    return this.http.post<PreTripInspectionResult>('/api/driver/pre-trip', formData);
  }

  /** 照片要帶登入 token 才讀得到，不能直接放在 <img src>，所以拿成 Blob 再轉網址 */
  getPreTripPhoto(inspectionId: number, kind: 'alcohol' | 'fault'): Observable<Blob> {
    return this.http.get(`/api/driver/pre-trip/${inspectionId}/photos/${kind}`, {responseType: 'blob'});
  }

  submitEmergencyLeave(request: EmergencyLeaveRequest): Observable<EmergencyLeaveResponse> {
    return this.http.post<EmergencyLeaveResponse>('/api/driver/emergency-leave-requests', request);
  }

  submitLeaveRequest(request: DriverLeaveRequest): Observable<DriverLeaveRequestResponse> {
    return this.http.post<DriverLeaveRequestResponse>('/api/driver/leave-requests', request);
  }

  submitPlannedLeaveBatches(
    request: DriverPlannedLeaveBatchRequest,
  ): Observable<DriverLeaveBatchResponse[]> {
    return this.http.post<DriverLeaveBatchResponse[]>('/api/driver/leave-requests/planned-batches', request);
  }

  submitMakeupLeave(request: DriverMakeupLeaveRequest): Observable<DriverLeaveRequestResponse> {
    return this.http.post<DriverLeaveRequestResponse>('/api/driver/leave-requests/makeup', request);
  }

  submitMakeupLeaveBatch(request: DriverMakeupLeaveBatchRequest): Observable<DriverLeaveRequestResponse[]> {
    return this.http.post<DriverLeaveRequestResponse[]>('/api/driver/leave-requests/makeup-batch', request);
  }

  uploadLeaveEvidencePhoto(file: File): Observable<PhotoUploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<PhotoUploadResponse>('/api/driver/leave-requests/evidence-photo', formData);
  }

  getLeaveRequests(month?: string, unreadOnly = false): Observable<DriverLeaveRequestResponse[]> {
    let params = new HttpParams();
    if (month) params = params.set('month', month);
    if (unreadOnly) params = params.set('unreadOnly', true);
    return this.http.get<DriverLeaveRequestResponse[]>('/api/driver/leave-requests', {params});
  }

  markLeaveRequestRead(id: number): Observable<DriverLeaveRequestResponse> {
    return this.http.post<DriverLeaveRequestResponse>(`/api/driver/leave-requests/${id}/read`, {});
  }

  getLeaveRequestHistory(id: number): Observable<DriverLeaveHistoryResponse[]> {
    return this.http.get<DriverLeaveHistoryResponse[]>(`/api/driver/leave-requests/${id}/history`);
  }

  getEmergencyLeaves(): Observable<EmergencyLeaveResponse[]> {
    return this.http.get<EmergencyLeaveResponse[]>('/api/driver/emergency-leave-requests');
  }

  loading(request: LoadingRequest): Observable<LoadingResponse> {
    return this.http.post<LoadingResponse>('/api/driver/loading', request);
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

  // ── 例外回報案件（後端 DriverPortalController 的 /api/driver/cases）──
  // 跟聊天一樣不帶 driverId：後端從 token 取，並檢查案件是不是這位司機的，別人的案件一律回「找不到案件」

  /** 自己的案件，含每件的未讀數；支援中心打開、WebSocket 連上時各抓一次 */
  getCases(): Observable<DriverCaseDto[]> {
    return this.http.get<DriverCaseDto[]>('/api/driver/cases');
  }

  /** 建立案件，回傳存好的案件（含 id），前端直接切到這件案件的對話 */
  createCase(request: DriverCaseRequest): Observable<DriverCaseDto> {
    return this.http.post<DriverCaseDto>('/api/driver/cases', request);
  }

  /** 案件對話，一律由舊到新；afterId 的用法跟 getMessages 一樣 */
  getCaseMessages(caseId: number, afterId?: number): Observable<DriverMessageDto[]> {
    let params = new HttpParams();
    if (afterId !== undefined) {
      params = params.set('afterId', afterId);
    }
    return this.http.get<DriverMessageDto[]>(`/api/driver/cases/${caseId}/messages`, {params});
  }

  /** 在案件裡留言；已結案的案件後端要擋，不能只靠前端把輸入框關掉 */
  sendCaseMessage(caseId: number, content: string): Observable<DriverMessageDto> {
    const request: DriverMessageRequest = {content};
    return this.http.post<DriverMessageDto>(`/api/driver/cases/${caseId}/messages`, request);
  }

  /** 把這件案件裡調度中心的回覆標成已讀；只標這一串，不能動到一般對話或其他案件 */
  markCaseMessagesRead(caseId: number): Observable<number> {
    return this.http.post<number>(`/api/driver/cases/${caseId}/messages/read`, {});
  }
}

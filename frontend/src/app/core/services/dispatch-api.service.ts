import {HttpClient, HttpParams} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {Observable} from 'rxjs';
import {
  AdminUserCreateRequest,
  AdminUserDto,
  AiApiKeyRequest,
  AiApiKeyStatusDto,
  AiChatReply,
  AiChatRequest,
  AiPendingActionDto,
  DispatchResultDto,
  DriverAccountApplicationDto,
  DriverDto,
  DriverMessageDto,
  DriverMessageRequest,
  DriverMessageSummaryDto,
  DriverShiftDto,
  DriverShiftUpdateRequest,
  DriverStatusPayload,
  ExceptionCaseDto,
  EmergencyLeaveDto,
  EmergencyLeaveReplacementCandidateDto,
  FuelPriceDto,
  GpsPingDto,
  LeaveRequest,
  OrderDto,
  ReassignRequest,
  ReportCollectionDto,
  ReportQuery,
  ReportSummaryDto,
  RouteMetricsDto,
  ScheduleMonthDto,
  StoreDto,
  StoreStatusPayload,
  TemplateDto,
  TemplateRequest,
  VehicleDto,
  WarehouseDto,
  OptimizeSlotsRequest,
} from './dispatch-api.models';

const API_ROOT = '/api';

@Injectable({
  providedIn: 'root',
})
export class DispatchApiService {
  private readonly http = inject(HttpClient);

  getDrivers(): Observable<DriverDto[]> {
    return this.http.get<DriverDto[]>(`${API_ROOT}/drivers`);
  }

  // ── 司機月班表 ────────────────────────────────────────

  getScheduleMonth(month: string): Observable<ScheduleMonthDto> {
    const params = new HttpParams().set('month', month);

    return this.http.get<ScheduleMonthDto>(`${API_ROOT}/driver-schedules/months`, {params});
  }

  /** 建立指定月份的草稿；後端對同月份為冪等操作。 */
  generateScheduleMonth(month: string): Observable<ScheduleMonthDto> {
    const params = new HttpParams().set('month', month);

    return this.http.post<ScheduleMonthDto>(`${API_ROOT}/driver-schedules/months`, null, {params});
  }

  getScheduleMonthShifts(scheduleMonthId: number): Observable<DriverShiftDto[]> {
    return this.http.get<DriverShiftDto[]>(
      `${API_ROOT}/driver-schedules/months/${scheduleMonthId}/shifts`,
    );
  }

  updateDriverShift(
    shiftId: number,
    request: DriverShiftUpdateRequest,
  ): Observable<DriverShiftDto> {
    return this.http.put<DriverShiftDto>(`${API_ROOT}/driver-schedules/shifts/${shiftId}`, request);
  }

  updateDriverShiftsBatch(
    scheduleMonthId: number,
    updates: DriverShiftDto[],
  ): Observable<DriverShiftDto[]> {
    return this.http.put<DriverShiftDto[]>(
      `${API_ROOT}/driver-schedules/months/${scheduleMonthId}/shifts/batch`,
      updates,
    );
  }

  syncScheduleDrivers(scheduleMonthId: number): Observable<DriverShiftDto[]> {
    return this.http.post<DriverShiftDto[]>(
      `${API_ROOT}/driver-schedules/months/${scheduleMonthId}/sync-drivers`,
      null,
    );
  }

  markDriverShiftLeave(shiftId: number, request: LeaveRequest): Observable<DriverShiftDto> {
    return this.http.patch<DriverShiftDto>(
      `${API_ROOT}/driver-schedules/shifts/${shiftId}/leave`,
      request,
    );
  }

  getRouteMetrics(routeId: number): Observable<RouteMetricsDto> {
    return this.http.get<RouteMetricsDto>(`${API_ROOT}/dispatch/routes/${routeId}/metrics`);
  }

  getLatestFuelPrice(): Observable<FuelPriceDto> {
    return this.http.get<FuelPriceDto>(`${API_ROOT}/fuel-prices/latest`);
  }

  syncFuelPrice(): Observable<FuelPriceDto> {
    return this.http.post<FuelPriceDto>(`${API_ROOT}/fuel-prices/sync`, null);
  }

  getFuelPriceHistory(from?: string, to?: string): Observable<FuelPriceDto[]> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<FuelPriceDto[]>(`${API_ROOT}/fuel-prices/history`, {params});
  }

  getReportSummary(query: ReportQuery): Observable<ReportSummaryDto> {
    return this.http.get<ReportSummaryDto>(`${API_ROOT}/reports/summary`, {params: this.reportParams(query)});
  }

  getReportAttendance(query: ReportQuery): Observable<ReportCollectionDto> {
    return this.http.get<ReportCollectionDto>(`${API_ROOT}/reports/attendance`, {params: this.reportParams(query)});
  }

  getReportRoutes(query: ReportQuery): Observable<ReportCollectionDto> {
    return this.http.get<ReportCollectionDto>(`${API_ROOT}/reports/routes`, {params: this.reportParams(query)});
  }

  getReportDrivers(query: ReportQuery): Observable<ReportCollectionDto> {
    return this.http.get<ReportCollectionDto>(`${API_ROOT}/reports/drivers`, {params: this.reportParams(query)});
  }

  getReportVehicles(query: ReportQuery): Observable<ReportCollectionDto> {
    return this.http.get<ReportCollectionDto>(`${API_ROOT}/reports/vehicles`, {params: this.reportParams(query)});
  }

  getReportWarehouses(query: ReportQuery): Observable<ReportCollectionDto> {
    return this.http.get<ReportCollectionDto>(`${API_ROOT}/reports/warehouses`, {params: this.reportParams(query)});
  }

  getReportStores(query: ReportQuery): Observable<ReportCollectionDto> {
    return this.http.get<ReportCollectionDto>(`${API_ROOT}/reports/stores`, {params: this.reportParams(query)});
  }

  getReportExceptions(query: ReportQuery): Observable<ReportCollectionDto> {
    return this.http.get<ReportCollectionDto>(`${API_ROOT}/reports/exceptions`, {params: this.reportParams(query)});
  }

  publishScheduleMonth(scheduleMonthId: number): Observable<ScheduleMonthDto> {
    return this.http.post<ScheduleMonthDto>(
      `${API_ROOT}/driver-schedules/months/${scheduleMonthId}/publish`,
      null,
    );
  }

  /** 讀取隔日 06:00 後已進入主管待確認區的配送異常。 */
  getPendingExceptionConfirmations(): Observable<ExceptionCaseDto[]> {
    return this.http.get<ExceptionCaseDto[]>(`${API_ROOT}/exceptions/pending-confirmation`);
  }

  /** 確認異常後，後端會將後續訂單送入待排車。 */
  confirmExceptionCase(exceptionCaseId: number): Observable<ExceptionCaseDto> {
    return this.http.patch<ExceptionCaseDto>(
      `${API_ROOT}/exceptions/${exceptionCaseId}/confirm`,
      null,
    );
  }

  getDriver(id: number): Observable<DriverDto> {
    return this.http.get<DriverDto>(`${API_ROOT}/drivers/${id}`);
  }

  createDriver(driver: DriverDto): Observable<DriverDto> {
    return this.http.post<DriverDto>(`${API_ROOT}/drivers`, driver);
  }

  createAdminUser(request: AdminUserCreateRequest): Observable<AdminUserDto> {
    return this.http.post<AdminUserDto>(`${API_ROOT}/admin-users`, request);
  }

  getAiApiKeyStatus(): Observable<AiApiKeyStatusDto> {
    return this.http.get<AiApiKeyStatusDto>(`${API_ROOT}/admin-users/me/ai-api-key`);
  }

  saveAiApiKey(request: AiApiKeyRequest): Observable<AiApiKeyStatusDto> {
    return this.http.put<AiApiKeyStatusDto>(`${API_ROOT}/admin-users/me/ai-api-key`, request);
  }

  removeAiApiKey(): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/admin-users/me/ai-api-key`);
  }

  getPendingDriverAccountApplicationCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${API_ROOT}/driver-account-applications/pending/count`);
  }

  getPendingDriverAccountApplications(): Observable<DriverAccountApplicationDto[]> {
    return this.http.get<DriverAccountApplicationDto[]>(`${API_ROOT}/driver-account-applications/pending`);
  }

  approveDriverAccountApplication(applicationId: number): Observable<DriverAccountApplicationDto> {
    return this.http.patch<DriverAccountApplicationDto>(
      `${API_ROOT}/driver-account-applications/${applicationId}/approve`,
      null,
    );
  }

  rejectDriverAccountApplication(
    applicationId: number,
    reason: string,
  ): Observable<DriverAccountApplicationDto> {
    return this.http.patch<DriverAccountApplicationDto>(
      `${API_ROOT}/driver-account-applications/${applicationId}/reject`,
      { reason },
    );
  }

  getPendingEmergencyLeaveRequests(): Observable<EmergencyLeaveDto[]> {
    return this.http.get<EmergencyLeaveDto[]>(`${API_ROOT}/emergency-leave-requests/pending`);
  }

  getEmergencyLeaveReplacementCandidates(
    requestId: number,
  ): Observable<EmergencyLeaveReplacementCandidateDto[]> {
    return this.http.get<EmergencyLeaveReplacementCandidateDto[]>(
      `${API_ROOT}/emergency-leave-requests/${requestId}/replacement-candidates`,
    );
  }

  approveEmergencyLeaveRequest(
    requestId: number,
    replacementDriverId: number,
  ): Observable<EmergencyLeaveDto> {
    return this.http.patch<EmergencyLeaveDto>(
      `${API_ROOT}/emergency-leave-requests/${requestId}/approve`,
      { replacementDriverId },
    );
  }

  rejectEmergencyLeaveRequest(requestId: number, reason: string): Observable<EmergencyLeaveDto> {
    return this.http.patch<EmergencyLeaveDto>(
      `${API_ROOT}/emergency-leave-requests/${requestId}/reject`,
      { reason },
    );
  }

  updateDriver(id: number, driver: DriverDto): Observable<DriverDto> {
    return this.http.put<DriverDto>(`${API_ROOT}/drivers/${id}`, driver);
  }

  deleteDriver(id: number): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/drivers/${id}`);
  }

  updateDriverStatus(id: number, payload: DriverStatusPayload): Observable<DriverDto> {
    return this.http.patch<DriverDto>(`${API_ROOT}/drivers/${id}/status`, payload);
  }

  getVehicles(): Observable<VehicleDto[]> {
    return this.http.get<VehicleDto[]>(`${API_ROOT}/vehicles`);
  }

  getVehicle(id: number): Observable<VehicleDto> {
    return this.http.get<VehicleDto>(`${API_ROOT}/vehicles/${id}`);
  }

  createVehicle(vehicle: VehicleDto): Observable<VehicleDto> {
    return this.http.post<VehicleDto>(`${API_ROOT}/vehicles`, vehicle);
  }

  updateVehicle(id: number, vehicle: VehicleDto): Observable<VehicleDto> {
    return this.http.put<VehicleDto>(`${API_ROOT}/vehicles/${id}`, vehicle);
  }

  deleteVehicle(id: number): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/vehicles/${id}`);
  }

  getStores(): Observable<StoreDto[]> {
    return this.http.get<StoreDto[]>(`${API_ROOT}/stores`);
  }

  getStore(id: number): Observable<StoreDto> {
    return this.http.get<StoreDto>(`${API_ROOT}/stores/${id}`);
  }

  createStore(store: StoreDto): Observable<StoreDto> {
    return this.http.post<StoreDto>(`${API_ROOT}/stores`, store);
  }

  updateStore(id: number, store: StoreDto): Observable<StoreDto> {
    return this.http.put<StoreDto>(`${API_ROOT}/stores/${id}`, store);
  }

  updateStoreStatus(id: number, payload: StoreStatusPayload): Observable<StoreDto> {
    return this.http.patch<StoreDto>(`${API_ROOT}/stores/${id}/status`, payload);
  }

  deleteStore(id: number): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/stores/${id}`);
  }

  getWarehouses(): Observable<WarehouseDto[]> {
    return this.http.get<WarehouseDto[]>(`${API_ROOT}/warehouses`);
  }

  getWarehouse(id: number): Observable<WarehouseDto> {
    return this.http.get<WarehouseDto>(`${API_ROOT}/warehouses/${id}`);
  }

  createWarehouse(warehouse: WarehouseDto): Observable<WarehouseDto> {
    return this.http.post<WarehouseDto>(`${API_ROOT}/warehouses`, warehouse);
  }

  updateWarehouse(id: number, warehouse: WarehouseDto): Observable<WarehouseDto> {
    return this.http.put<WarehouseDto>(`${API_ROOT}/warehouses/${id}`, warehouse);
  }

  deleteWarehouse(id: number): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/warehouses/${id}`);
  }

  getOrders(): Observable<OrderDto[]> {
    return this.http.get<OrderDto[]>(`${API_ROOT}/orders`);
  }

  getOrder(id: number): Observable<OrderDto> {
    return this.http.get<OrderDto>(`${API_ROOT}/orders/${id}`);
  }

  createOrder(order: OrderDto): Observable<OrderDto> {
    return this.http.post<OrderDto>(`${API_ROOT}/orders`, order);
  }

  updateOrder(id: number, order: OrderDto): Observable<OrderDto> {
    return this.http.put<OrderDto>(`${API_ROOT}/orders/${id}`, order);
  }

  deleteOrder(id: number): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/orders/${id}`);
  }

  createOrdersBatch(orders: OrderDto[]): Observable<OrderDto[]> {
    return this.http.post<OrderDto[]>(`${API_ROOT}/orders/batch`, { orders });
  }

  /**
   * 執行排車。注意這不是唯讀操作：後端會清掉當天既有的草稿路線、
   * 寫入新的 routes，並把排進去的訂單填上 route_id（狀態仍維持 CONFIRMED，靠 route_id 區分排了沒）。
   *
   * 參數走 query string 而不是 request body，所以 post() 的第二個引數是 null。
   *
   * @param vehicleIds 不傳則由後端自動取該倉所有可用車，交給 OR-Tools 決定出幾台
   */
  optimizeDispatch(
    date: string,
    warehouseId: number,
    vehicleIds?: number[],
  ): Observable<DispatchResultDto> {
    let params = new HttpParams().set('date', date).set('warehouseId', warehouseId);

    if (vehicleIds?.length) {
      params = params.set('vehicleIds', vehicleIds.join(','));
    }

    return this.http.post<DispatchResultDto>(`${API_ROOT}/dispatch/optimize`, null, {params});
  }

  /**
   * 拖曳改派：把調度員排出來的分派送給後端。
   *
   * 後端不會跑 OR-Tools、也不會調整送去的配送順序，只負責重算里程與裝載率，
   * 然後回傳跟 board 相同形狀的完整結果，前端拿去整包重畫即可。
   *
   * 這支跟 optimize 一樣會寫入資料庫：清掉當天草稿後照送去的內容重建。
   */
  /**
   * 依看板上的格子自動排車：OR-Tools 只用格子裡的車，排完帶入格子的司機，
   * 排不進去的訂單留在待排單區。回應的 notices 說明配車與沒帶入的原因。
   */
  optimizeSlots(request: OptimizeSlotsRequest): Observable<DispatchResultDto> {
    return this.http.post<DispatchResultDto>(`${API_ROOT}/dispatch/optimize/slots`, request);
  }

  /**
   * 排門市順序：從這個倉出發，這些門市一台車最順的跑法（OSRM 距離 + OR-Tools）。
   * 只算順序，不看訂單、不寫資料庫。
   */
  sequenceStores(warehouseId: number, storeIds: number[]): Observable<{storeIds: number[]}> {
    return this.http.post<{storeIds: number[]}>(`${API_ROOT}/dispatch/store-sequence`, {warehouseId, storeIds});
  }

  reassignDispatch(request: ReassignRequest): Observable<DispatchResultDto> {
    return this.http.post<DispatchResultDto>(`${API_ROOT}/dispatch/reassign`, request);
  }

  /**
   * 讀取某天已排定的路線，不會觸發重新排車。當天沒排過則 routes 為空陣列。
   */
  getDispatchBoard(date: string, warehouseId: number): Observable<DispatchResultDto> {
    const params = new HttpParams().set('date', date).set('warehouseId', warehouseId);

    return this.http.get<DispatchResultDto>(`${API_ROOT}/dispatch/board`, {params});
  }

  /**
   * 發布當天全部倉庫的排班：草稿路線翻成 PUBLISHED，司機端才查得到任務。
   *
   * 不帶 warehouseId —— 發布是整天一次的動作，不像排車是一次一倉。
   * 任一條路線沒指派司機就整批擋下（後端全有或全無），錯誤訊息會指出車牌。
   * 回傳每個有路線的倉庫各一包看板，前端要挑出目前正在看的那一倉。
   */
  publishDispatch(date: string): Observable<DispatchResultDto[]> {
    const params = new HttpParams().set('date', date);

    return this.http.post<DispatchResultDto[]>(`${API_ROOT}/dispatch/publish`, null, {params});
  }

  /**
   * 撤回發布：PUBLISHED 翻回 DRAFT。
   *
   * 撤回本身不刪東西，但路線會重新落入排車的清除範圍 ——
   * 撤回後再排車，這批路線就會被整批刪掉重建。
   */
  withdrawDispatch(date: string): Observable<DispatchResultDto[]> {
    const params = new HttpParams().set('date', date);

    return this.http.post<DispatchResultDto[]>(`${API_ROOT}/dispatch/withdraw`, null, {params});
  }

  // ── 常配編組 ──────────────────────────────────────────

  getTemplates(): Observable<TemplateDto[]> {
    return this.http.get<TemplateDto[]>(`${API_ROOT}/dispatch/templates`);
  }

  createTemplate(request: TemplateRequest): Observable<TemplateDto> {
    return this.http.post<TemplateDto>(`${API_ROOT}/dispatch/templates`, request);
  }

  /** 覆蓋既有編組。後端會把舊的路線與停靠點整批刪掉重建。 */
  updateTemplate(id: number, request: TemplateRequest): Observable<TemplateDto> {
    return this.http.put<TemplateDto>(`${API_ROOT}/dispatch/templates/${id}`, request);
  }

  deleteTemplate(id: number): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/dispatch/templates/${id}`);
  }

  /**
   * 套用編組到指定日期：依編組的車輛與門市撈當天訂單組成路線。
   *
   * 跟 optimize / reassign 一樣是寫入操作，會清掉當天該倉的草稿路線並重建。
   * 編組可跨倉，所以回傳的是「每個有排到路線的倉庫各一包」，
   * 前端要自己挑出目前正在看的那一倉。
   */
  applyTemplate(templateId: number, date: string): Observable<DispatchResultDto[]> {
    const params = new HttpParams().set('date', date);

    return this.http.post<DispatchResultDto[]>(
      `${API_ROOT}/dispatch/templates/${templateId}/apply`,
      null,
      {params},
    );
  }

  /**
   * 目前工作中、且 GPS 未超過後端新鮮度門檻（預設 10 分鐘）的司機位置。
   * 沒有 date 參數 —— api-contract.md 上寫的那個是舊文件，controller 沒有這個查詢條件，
   * 永遠只回「現在」這一刻的位置。
   */
  getLiveFleet(): Observable<GpsPingDto[]> {
    return this.http.get<GpsPingDto[]>(`${API_ROOT}/fleet/live`);
  }

  getFleetDriverLatest(driverId: number): Observable<GpsPingDto> {
    return this.http.get<GpsPingDto>(`${API_ROOT}/fleet/drivers/${driverId}/gps/latest`);
  }

  getFleetDriverCurrent(driverId: number): Observable<GpsPingDto> {
    return this.http.get<GpsPingDto>(`${API_ROOT}/fleet/drivers/${driverId}/gps/current`);
  }

  getFleetDriverHistory(driverId: number, from: string, to: string): Observable<GpsPingDto[]> {
    const params = new HttpParams().set('from', from).set('to', to);
    return this.http.get<GpsPingDto[]>(`${API_ROOT}/fleet/drivers/${driverId}/gps/history`, {
      params,
    });
  }

  /**
   * 送一句話給 AI 助理。後端同步等模型跑完所有工具才回應，十幾秒是常態，
   * 等待期間畫面要鎖住輸入，兩句同時送會打亂同一段對話記憶的順序。
   */
  chatWithAi(message: string): Observable<AiChatReply> {
    const request: AiChatRequest = {message};
    return this.http.post<AiChatReply>(`${API_ROOT}/ai/chat`, request);
  }

  /** 讀取待執行清單；開啟聊天室或重新整理後用來還原清單 */
  getAiPlan(): Observable<AiPendingActionDto[]> {
    return this.http.get<AiPendingActionDto[]>(`${API_ROOT}/ai/plan`);
  }

  /**
   * 整批執行待執行清單。後端按日期與倉庫分組各呼叫一次 reassign，全有或全無；
   * 回傳每組各一包看板。失敗回 400，清單保留在後端，可以修正後再按一次。
   */
  confirmAiPlan(): Observable<DispatchResultDto[]> {
    return this.http.post<DispatchResultDto[]>(`${API_ROOT}/ai/plan/confirm`, null);
  }

  /** 整批放棄待執行清單，不執行任何動作 */
  clearAiPlan(): Observable<void> {
    return this.http.delete<void>(`${API_ROOT}/ai/plan`);
  }

  /**
   * 刪除待執行清單中的單一項目，回傳刪除後的整份清單，前端直接整包取代。
   * id 是清單項目的 UUID（AiPendingActionDto.id），不是訂單或車輛的 id；
   * 找不到時後端不報錯，照樣回傳最新清單。跟 clearAiPlan 分開寫，漏傳 id 才不會變成整份清掉。
   */
  removeAiPlanAction(id: string): Observable<AiPendingActionDto[]> {
    return this.http.delete<AiPendingActionDto[]>(`${API_ROOT}/ai/plan/${id}`);
  }

  // ── 司機聊天室 ─────────────────────────────────────────

  /**
   * 讀取某位司機的對話，一律由舊到新。
   * 不帶 afterId：最近 50 則，打開對話時用；帶 afterId：只回比它新的，重連補抓用。
   */
  getDriverMessages(driverId: number, afterId?: number): Observable<DriverMessageDto[]> {
    // 沒有 afterId 就不能帶這個參數，帶成 "undefined" 字串後端會回 400
    let params = new HttpParams();
    if (afterId !== undefined) {
      params = params.set('afterId', afterId);
    }
    return this.http.get<DriverMessageDto[]>(`${API_ROOT}/drivers/${driverId}/messages`, {params});
  }

  /** 回覆某位司機。回傳存好的那一則（含 id），直接放進清單，之後推播收到同一則時用 id 去重 */
  sendDriverMessage(driverId: number, content: string): Observable<DriverMessageDto> {
    const request: DriverMessageRequest = {content};
    return this.http.post<DriverMessageDto>(`${API_ROOT}/drivers/${driverId}/messages`, request);
  }

  /** 把這位司機發的未讀訊息標成已讀，回傳這次標了幾筆。已讀是所有管理員共用的 */
  markDriverMessagesRead(driverId: number): Observable<number> {
    return this.http.post<number>(`${API_ROOT}/drivers/${driverId}/messages/read`, {});
  }

  /** 紅點：每位司機有幾則未讀。只列有未讀的司機，用 driverId 對到司機名單 */
  getDriverMessageSummary(): Observable<DriverMessageSummaryDto[]> {
    return this.http.get<DriverMessageSummaryDto[]>(`${API_ROOT}/drivers/messages/summary`);
  }

  private reportParams(query: ReportQuery): HttpParams {
    return Object.entries(query).reduce((params, [key, value]) => {
      return value === undefined || value === null ? params : params.set(key, String(value));
    }, new HttpParams());
  }
}

import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  DispatchResultDto,
  DriverDto,
  DriverStatusPayload,
  OrderDto,
  ReassignRequest,
  StoreDto,
  StoreStatusPayload,
  TemplateDto,
  TemplateRequest,
  VehicleDto,
  WarehouseDto,
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

  getDriver(id: number): Observable<DriverDto> {
    return this.http.get<DriverDto>(`${API_ROOT}/drivers/${id}`);
  }

  createDriver(driver: DriverDto): Observable<DriverDto> {
    return this.http.post<DriverDto>(`${API_ROOT}/drivers`, driver);
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

    return this.http.post<DispatchResultDto>(`${API_ROOT}/dispatch/optimize`, null, { params });
  }

  /**
   * 拖曳改派：把調度員排出來的分派送給後端。
   *
   * 後端不會跑 OR-Tools、也不會調整送去的配送順序，只負責重算里程與裝載率，
   * 然後回傳跟 board 相同形狀的完整結果，前端拿去整包重畫即可。
   *
   * 這支跟 optimize 一樣會寫入資料庫：清掉當天草稿後照送去的內容重建。
   */
  reassignDispatch(request: ReassignRequest): Observable<DispatchResultDto> {
    return this.http.post<DispatchResultDto>(`${API_ROOT}/dispatch/reassign`, request);
  }

  /**
   * 讀取某天已排定的路線，不會觸發重新排車。當天沒排過則 routes 為空陣列。
   */
  getDispatchBoard(date: string, warehouseId: number): Observable<DispatchResultDto> {
    const params = new HttpParams().set('date', date).set('warehouseId', warehouseId);

    return this.http.get<DispatchResultDto>(`${API_ROOT}/dispatch/board`, { params });
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

    return this.http.post<DispatchResultDto[]>(`${API_ROOT}/dispatch/publish`, null, { params });
  }

  /**
   * 撤回發布：PUBLISHED 翻回 DRAFT。
   *
   * 撤回本身不刪東西，但路線會重新落入排車的清除範圍 ——
   * 撤回後再排車，這批路線就會被整批刪掉重建。
   */
  withdrawDispatch(date: string): Observable<DispatchResultDto[]> {
    const params = new HttpParams().set('date', date);

    return this.http.post<DispatchResultDto[]>(`${API_ROOT}/dispatch/withdraw`, null, { params });
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
      { params },
    );
  }
}

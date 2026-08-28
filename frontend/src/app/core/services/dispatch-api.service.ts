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
   * 寫入新的 routes，並把排進去的訂單狀態改成 SCHEDULED。
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
}

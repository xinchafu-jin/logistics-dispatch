import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  DriverDto,
  DriverStatusPayload,
  OrderDto,
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
}

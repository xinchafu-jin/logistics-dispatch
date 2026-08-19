export type DriverStatus = 'ACTIVE' | 'INACTIVE';
export type VehicleStatus = 'AVAILABLE' | 'MAINTENANCE' | 'RETIRED';
export type StoreStatus = 'ACTIVE' | 'SUSPENDED';
export type OrderStatus =
  | 'PENDING_CONFIRM'
  | 'CONFIRMED'
  | 'SCHEDULED'
  | 'MODIFY'
  | 'PUBLISHED'
  | 'IN_DELIVERY'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED';

export interface DriverDto {
  id?: number;
  account: string;
  password?: string;
  name: string;
  phone?: string;
  workStart: string;
  workEnd: string;
  restDuration: number;
  maxOvertimeMinutes?: number;
  isActive: boolean;
}

export interface VehicleDto {
  id?: number;
  plateNumber: string;
  vehicleType?: string;
  capacity: number;
  fuelConsumption?: number;
  status: VehicleStatus;
}

export interface StoreDto {
  id?: number;
  storeCode: string;
  name: string;
  address?: string;
  lat: number;
  lng: number;
  contactName?: string;
  phone?: string;
  receivingStart: string;
  receivingEnd: string;
  notes?: string;
  status: StoreStatus;
}

export interface WarehouseDto {
  id?: number;
  warehouseCode: string;
  name: string;
  address?: string;
  lat: number;
  lng: number;
  phone?: string;
  isActive: boolean;
}

export interface OrderDto {
  id?: number;
  orderNumber: string;
  storeId: number;
  sourceVendor?: string;
  itemDescription?: string;
  boxCount: number;
  notes: string;
  deliveryDate: string;
  status: OrderStatus;
  assignedVehicleId?: number;
  assignedDriverId?: number;
  sequence?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface DriverStatusPayload {
  isActive: boolean;
}

export interface StoreStatusPayload {
  status: StoreStatus;
}

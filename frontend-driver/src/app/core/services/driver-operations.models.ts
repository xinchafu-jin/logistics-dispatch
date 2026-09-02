export type AttendanceStatus = 'WORKING' | 'ON_BREAK' | 'CLOCKED_OUT';

export interface AttendanceRecordDto {
  id: number;
  driverShiftId: number;
  driverId: number;
  workDate: string;
  clockInAt: string | null;
  clockOutAt: string | null;
  breakUsed: boolean;
  breakStartedAt: string | null;
  breakEndsAt: string | null;
  remainingBreakSeconds: number;
  status: AttendanceStatus;
  gpsAllowed: boolean;
}

export type ShiftType = 'UNASSIGNED' | 'WORK' | 'DAY_OFF' | 'LEAVE';

export interface DriverShiftDto {
  id: number;
  scheduleMonthId: number;
  driverId: number;
  workDate: string;
  shiftType: ShiftType;
  workStart: string | null;
  workEnd: string | null;
  overtimeMinutes: number;
  changeReason: string | null;
  lastModifiedAt: string;
  version: number;
}

export interface GpsPingRequest {
  lat: number;
  lng: number;
}

export interface ArriveRequest {
  orderId: number;
}

export interface DeliverRequest {
  orderId: number;
  boxCount: number;
  photo: string;
  notes?: string;
}

export interface NoSignatureRequest {
  orderId: number;
  photo: string;
  notes?: string;
}

export interface DeliveryRecordResponse {
  id: number;
  orderId: number;
  orderStatus: DriverTaskOrderStatus;
  arrivedAt: string | null;
  deliveredAt: string | null;
  deliveredBoxCount: number | null;
  photoUrl: string | null;
  notes: string | null;
  noSignature: boolean;
  exceptionCaseId: number | null;
}

export interface MileageRequest {
  odometer: number;
}

export interface MileageLogResponse {
  id: number;
  driverId: number;
  date: string;
  startOdometer: number | null;
  endOdometer: number | null;
  startTime: string | null;
  endTime: string | null;
  actualDistance: number | null;
  actualDurationMinutes: number | null;
}

export interface DriverTasksResponse {
  date: string;
  driverId: number;
  driverName: string;
  routes: DriverRouteTask[];
}

export interface DriverRouteTask {
  routeId: number;
  status: string;
  warehouse: DriverTaskWarehouse;
  vehicle: DriverTaskVehicle;
  totalDistance: number | null;
  estimatedFuelCost: number | null;
  estimatedWorkMinutes: number | null;
  loadRate: number | null;
  stopCount: number;
  totalBoxes: number;
  stops: DriverTaskStop[];
}

export interface DriverTaskWarehouse {
  id: number;
  warehouseCode: string;
  name: string;
  address: string;
  lat: number | null;
  lng: number | null;
  phone: string | null;
}

export interface DriverTaskVehicle {
  id: number;
  plateNumber: string;
  vehicleType: string;
  capacity: number | null;
}

export interface DriverTaskStop {
  sequence: number;
  orderId: number;
  orderNumber: string;
  orderStatus: DriverTaskOrderStatus;
  expectedBoxCount: number;
  itemDescription: string | null;
  orderNotes: string | null;
  storeId: number;
  storeCode: string;
  storeName: string;
  address: string;
  lat: number | null;
  lng: number | null;
  contactName: string | null;
  phone: string | null;
  receivingStart: string | null;
  receivingEnd: string | null;
}

export type DriverTaskOrderStatus =
  | 'PENDING_CONFIRM'
  | 'CONFIRMED'
  | 'IN_DELIVERY'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED';

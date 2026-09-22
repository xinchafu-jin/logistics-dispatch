export type AttendanceStatus = 'WORKING' | 'ON_BREAK' | 'OVERTIME' | 'CLOCKED_OUT';

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
  emergencyLeaveRequestId?: number | null;
  emergencyLeaveStatus?: EmergencyLeaveStatus | null;
  earlyClockOutAllowed?: boolean;
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

export interface DriverProfileDto {
  id: number;
  account: string;
  name: string;
  phone: string | null;
  profilePhotoUrl: string | null;
  workStart: string;
  workEnd: string;
  restDuration: number;
  maxOvertimeMinutes: number | null;
  isActive: boolean;
}

export type EmergencyLeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface EmergencyLeaveRequest {
  reason: string;
}

export interface EmergencyLeaveResponse {
  id: number;
  driverId: number;
  driverName: string | null;
  workDate: string;
  attendanceRecordId: number | null;
  routeId: number | null;
  routeStatus: string | null;
  vehicleId: number | null;
  plateNumber: string | null;
  reason: string;
  status: EmergencyLeaveStatus;
  replacementDriverId: number | null;
  replacementDriverName: string | null;
  transferredOrderCount: number;
  gpsOverrideGranted: boolean;
  requestedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  rejectionReason: string | null;
  routeReassignedAt: string | null;
  clockedOutAt: string | null;
}

export interface GpsRouteRequest {
  fromLat: number;
  fromLng: number;
  toLat: number;
  toLng: number;
}

export interface GpsRouteResponse {
  /** 後端每個點固定提供：[緯度, 經度]；MapLibre 顯示前會由前端轉成 [經度, 緯度]。 */
  path: [number, number][];
  distance: number;
  duration: number;
}

export interface ArriveRequest {
  orderId: number;
}

export interface DeliverRequest {
  orderId: number;
  boxCount: number;
  shortageBoxCount?: number;
  damagedBoxCount?: number;
  replacementRequiredBoxCount?: number;
  photoUrl?: string;
  notes?: string;
}

export interface NoSignatureRequest {
  orderId: number;
  photoUrl?: string;
  notes?: string;
}

export interface DriverExceptionRequest {
  orderId: number;
  description: string;
}

export interface PhotoUploadResponse {
  url: string;
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
  gpsDistanceKm?: number | null;
  mileageSettledAt?: string | null;
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

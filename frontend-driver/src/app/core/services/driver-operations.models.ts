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

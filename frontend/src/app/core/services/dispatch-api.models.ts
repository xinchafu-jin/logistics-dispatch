export type DriverStatus = 'ACTIVE' | 'INACTIVE';
/** MAINTENANCE＝送維修（車禍、故障）；MINOR_MAINTENANCE／MAJOR_MAINTENANCE＝送小保／送大保 */
export type VehicleStatus = 'AVAILABLE' | 'MAINTENANCE' | 'MINOR_MAINTENANCE' | 'MAJOR_MAINTENANCE' | 'RETIRED';
export type StoreStatus = 'ACTIVE' | 'SUSPENDED';
export type ScheduleStatus = 'DRAFT' | 'PUBLISHED';
export type ShiftType = 'UNASSIGNED' | 'WORK' | 'DAY_OFF' | 'LEAVE';
export type LeaveType = 'SICK' | 'ANNUAL' | 'PERSONAL' | 'SPECIAL' | 'MENSTRUAL' | 'BEREAVEMENT' | 'ABSENT';
export type LeaveRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type LeaveSubmissionSource = 'DRIVER' | 'ADMIN' | 'SYSTEM';
export type LeaveRequestMode =
  | 'PREPLANNED'
  | 'TEMPORARY'
  | 'MAKEUP'
  | 'SYSTEM_NO_SHOW'
  | 'ADMIN_PLANNED_PARTIAL';
/** 路線的發布狀態。訂單層沒有「已發布」，發布是路線層的事。 */
export type RouteStatus = 'DRAFT' | 'PUBLISHED';
export type OrderStatus =
  | 'PENDING_CONFIRM'
  | 'CONFIRMED'
  | 'LOADED'
  | 'IN_DELIVERY'
  | 'NO_SIGNATURE'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED';
export type OrderType = 'NORMAL' | 'REPLENISHMENT';
export type ExceptionStatus = 'OPEN' | 'CLOSED';
export type ExceptionType =
  | 'NO_SIGNATURE'
  | 'SHORTAGE'
  | 'DAMAGE'
  | 'SHORTAGE_AND_DAMAGE'
  | 'DRIVER_REPORT'
  | 'PHONE_HANDLED'
  | 'LOADING_MISMATCH';
export type EmergencyLeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type AttendanceStatus = 'WORKING' | 'ON_BREAK' | 'OVERTIME' | 'CLOCKED_OUT';

/** 後端隔日送審的配送異常案件。 */
export interface ExceptionCaseDto {
  id: number;
  type: ExceptionType;
  status: ExceptionStatus;
  description: string;
  sourceOrderId: number | null;
  sourceOrderNumber: string | null;
  sourceOrderType: OrderType | null;
  deliveryRecordId: number | null;
  expectedBoxCount: number | null;
  deliveredBoxCount: number | null;
  shortageBoxCount: number | null;
  damagedBoxCount: number | null;
  replacementRequiredBoxCount: number | null;
  followUpOrderId: number | null;
  followUpOrderNumber: string | null;
  followUpOrderType: OrderType | null;
  followUpOrderStatus: OrderStatus | null;
  followUpDeliveryDate: string | null;
  reviewAvailableAt: string | null;
  queuedAt: string | null;
  createdAt: string | null;
  handledBy: string | null;
  handledAt: string | null;
  resolution: string | null;
}

export interface DriverDto {
  monthlyOvertimeMinutes?: number;
  monthlyUnsettledShifts?: number;
  id?: number;
  warehouseId?: number | null;
  warehouseName?: string | null;
  warehouseCode?: string | null;
  account: string;
  password?: string;
  name: string;
  phone?: string;
  /** 司機在司機端上傳的大頭照，例如 /uploads/driver-photos/xxx.jpg；沒上傳是 null。只讀，更新司機資料時不用帶 */
  profilePhotoUrl?: string | null;
  workStart: string;
  workEnd: string;
  restDuration: number;
  maxOvertimeMinutes?: number;
  isActive: boolean;
}

/** 建立後台管理員時送往 POST /api/admin-users 的資料。 */
export interface AdminUserCreateRequest {
  account: string;
  password: string;
  name: string;
  phone: string;
}

/** 後端回傳的管理員資料不包含密碼。 */
export interface AdminUserDto {
  id: number;
  account: string;
  name: string;
  phone: string;
}

/** 後端只回傳遮罩後的 API Key，完整內容不會再次傳回前端。 */
export interface AiApiKeyStatusDto {
  configured: boolean;
  maskedKey: string | null;
  updatedAt: string | null;
}

/** 只在儲存時送出，不寫入瀏覽器儲存空間。 */
export interface AiApiKeyRequest {
  apiKey: string;
}

export interface EmergencyLeaveDto {
  id: number;
  driverId: number;
  driverName: string | null;
  workDate: string;
  attendanceRecordId: number | null;
  routeId: number | null;
  routeStatus: RouteStatus | null;
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

export interface EmergencyLeaveReplacementCandidateDto {
  driverId: number;
  account: string;
  name: string;
  attendanceStatus: AttendanceStatus | null;
  clockedIn: boolean;
}

/** 每月司機班表主檔。 */
export interface ScheduleMonthDto {
  id: number;
  /** yyyy-MM-dd，永遠是該月份的第一天。 */
  scheduleMonth: string;
  status: ScheduleStatus;
  generatedAt: string;
  publishedAt: string | null;
}

/** 指定月份中，一位司機的一日班次。 */
export interface DriverShiftDto {
  id: number;
  scheduleMonthId: number;
  driverId: number;
  /** yyyy-MM-dd */
  workDate: string;
  shiftType: ShiftType;
  /** HH:mm 或 HH:mm:ss；休假與請假為 null。 */
  workStart: string | null;
  workEnd: string | null;
  overtimeMinutes: number;
  changeReason: string;
  lastModifiedAt: string | null;
  version: number | null;
}

/** 對應 PUT /api/driver-schedules/shifts/{shiftId}。 */
export interface DriverShiftUpdateRequest {
  shiftType: ShiftType;
  workStart: string | null;
  workEnd: string | null;
  overtimeMinutes: number;
  changeReason: string;
}

/** 一般請假申請與主管處理結果；requestReason 和 decisionReason 分開顯示。 */
export interface DriverLeaveRequestDto {
  id: number;
  batchId: string | null;
  requestMode: LeaveRequestMode;
  driverId: number;
  driverName: string;
  driverShiftId: number;
  workDate: string;
  requestedLeaveType: LeaveType;
  leaveType: LeaveType;
  fullDay: boolean;
  leaveStart: string | null;
  leaveEnd: string | null;
  requestReason: string;
  evidencePhotoUrl: string | null;
  status: LeaveRequestStatus;
  submissionSource: LeaveSubmissionSource;
  decisionReason: string | null;
  requestedAt: string;
  reviewedByAdminId: number | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  typeChangeReason: string | null;
  typeChangedAt: string | null;
  typeChangedBy: string | null;
  driverReadAt: string | null;
  lastUpdatedAt: string;
}

/** 同一司機、同假別、多個日期的預排請假，主管只能整組核准或退回。 */
export interface DriverLeaveBatchDto {
  batchId: string;
  driverId: number;
  driverName: string;
  leaveType: LeaveType;
  workDates: string[];
  requestReason: string;
  status: LeaveRequestStatus;
  decisionReason: string | null;
  requestedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  items: DriverLeaveRequestDto[];
}

export interface DriverLeaveHistoryDto {
  id: number;
  leaveRequestId: number;
  driverId: number;
  eventType: string;
  actorType: LeaveSubmissionSource;
  actorId: number;
  actorAccount: string;
  oldStatus: LeaveRequestStatus | null;
  newStatus: LeaveRequestStatus | null;
  oldLeaveType: LeaveType | null;
  newLeaveType: LeaveType | null;
  reason: string | null;
  occurredAt: string;
}

export interface DriverMonthlyLeaveSummaryDto {
  driverId: number;
  driverName: string;
  month: string;
  hasLeaveRecords: boolean;
  emptyMessage: string | null;
  records: DriverLeaveRequestDto[];
}

export interface PlannedPartialLeaveRequestDto {
  driverId: number;
  workDate: string;
  leaveType: Exclude<LeaveType, 'ABSENT'>;
  leaveStart: string;
  leaveEnd: string;
  reason: string;
}

/** 對應 PATCH /api/driver-schedules/shifts/{shiftId}/leave。 */
export interface LeaveRequest {
  reason: string;
  version?: number | null;
}

export interface FuelPriceDto {
  id: number;
  fuelType: 'DIESEL' | string;
  pricePerLiter: number;
  effectiveFrom: string;
  fetchedAt: string;
}

export interface RouteMetricsDto {
  routeId: number;
  date: string;
  plannedKm: number | null;
  /** 這台車的保養狀況；還有待配送的單時含「跑完這趟之後」的預估 */
  maintenance?: VehicleMaintenanceSummary | null;
  plannedDriveMinutes: number | null;
  plannedTotalMinutes: number | null;
  plannedFuelLiters: number | null;
  plannedFuelCost: number | null;
  gpsEstimatedKm: number | null;
  gpsEstimatedFuelLiters: number | null;
  gpsEstimatedFuelCost: number | null;
  remainingKm: number | null;
  remainingDriveMinutes: number | null;
  estimatedNextArrivalAt: string | null;
  estimatedReturnAt: string | null;
  mileageStatus: string | null;
  fuelStatus: string | null;
}

/** 對應 GET /api/dispatch/planned-paths：一條已發布路線各段的預定道路形狀（發布時後端存的） */
export interface PlannedPathDto {
  routeId: number;
  /** 依行駛順序：倉庫 → 各門市 → 回倉 */
  legs: PlannedPathLegDto[];
}

export interface PlannedPathLegDto {
  sequence: number;
  /** null＝回倉那一段 */
  toStoreId: number | null;
  /** [[經度, 緯度], ...]：GeoJSON／MapLibre 的順序，直接當 LineString 的 coordinates */
  path: [number, number][];
}

export type BackendReportPeriod = 'TODAY' | 'YESTERDAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'CUSTOM';

export interface ReportQuery {
  period?: BackendReportPeriod;
  date?: string;
  from?: string;
  to?: string;
  warehouseId?: number;
  driverId?: number;
  vehicleId?: number;
  storeId?: number;
  routeId?: number;
  includeDetails?: boolean;
}

export interface ReportDailySummaryDto {
  date: string;
  totalOrders: number;
  totalBoxes: number;
  completedOrders: number;
  completionEligibleOrders: number;
  completionRatePercent: number | null;
}

export interface ReportSummaryDto {
  from: string;
  to: string;
  totalOrders: number;
  totalBoxes: number;
  distinctStores: number;
  pendingConfirmationOrders: number;
  confirmedUnassignedOrders: number;
  assignedOrders: number;
  inDeliveryOrders: number;
  completedOrders: number;
  failedOrders: number;
  cancelledOrders: number;
  publishedRoutes: number;
  dispatchedDrivers: number;
  dispatchedVehicles: number;
  unassignedOrders: number;
  unassignedBoxes: number;
  completionEligibleOrders: number;
  completionRatePercent: number | null;
  dailyTrend: ReportDailySummaryDto[];
}

/** 其餘報表 API 的共同範圍欄位；各資料列由後端依端點回傳。 */
export interface ReportCollectionDto<T = Record<string, unknown>> {
  from: string;
  to: string;
  [key: string]: string | number | boolean | null | T[];
}

export interface ReportWorkforceDto {
  scheduledWorkShifts: number; excusedFullDayShifts: number; dueShifts: number;
  attendedShifts: number; onTimeShifts: number; lateShifts: number; missingClockInShifts: number;
  finishedShifts: number; overtimeShifts: number; overtimeMinutes: number; missingTimeShifts: number;
  attendanceRate: number | null; onTimeRate: number | null; overtimeRate: number | null;
}
export interface ReportFleetDto {
  startedTrips: number; returnedTrips: number; openTrips: number; invalidTrips: number;
  usedVehicles: number; distanceRecordedTrips: number; actualKm: number | null; returnRate: number | null;
}
export interface ReportWarehousePerformanceDto {
  warehouseId: number | null; warehouseName: string; workforce: ReportWorkforceDto; fleet: ReportFleetDto;
}
export interface ReportPerformanceDto {
  from: string; to: string; workforce: ReportWorkforceDto; fleet: ReportFleetDto;
  warehouses: ReportWarehousePerformanceDto[]; shifts: Record<string, unknown>[]; trips: Record<string, unknown>[];
}

export interface ReportDeliveryOutcomeDto {
  dueOrders: number; deliveredOrders: number; fullOrders: number; outstandingOrders: number;
  windowEligibleOrders: number; windowArrivals: number; lateArrivals: number; earlyArrivals: number;
  missingArrivalOrders: number; missingWindowOrders: number; missingQualityOrders: number;
  fullDeliveryRate: number | null; receivingWindowRate: number | null;
}
export interface ReportLoadingOutcomeDto {
  checkedOrders: number; matchedOrders: number; mismatchedOrders: number;
  missingLoadingOrders: number; dueUnassignedOrders: number; matchRate: number | null;
}
export interface ReportDeliveryProblemsDto {
  assessedOrders: number; affectedOrders: number; shortageOrders: number;
  damagedOrders: number; noSignatureOrders: number; issueRate: number | null;
}
export interface ReportRecoveryDto {
  attemptedOrders: number; recoveryOrders: number; attemptedRecoveryOrders: number;
  deliveredRecoveryOrders: number; outstandingRecoveryOrders: number; recoveryShare: number | null;
}
export interface ReportOrderOutcomeDto {
  orderType: string | null; parentOrderId: number | null; attempted: boolean; recovery: boolean; recoveryReason: string | null;
  orderId: number; orderNumber: string; date: string; warehouseId: number | null; warehouseName: string;
  storeId: number | null; storeName: string; driverId: number | null; status: string;
  due: boolean; delivered: boolean; full: boolean; withinWindow: boolean; late: boolean; early: boolean;
  missingArrival: boolean; missingQuality: boolean; assessed: boolean; shortage: boolean; damaged: boolean;
  noSignature: boolean; loadingMatched: boolean; loadingMismatch: boolean; missingLoading: boolean; dueUnassigned: boolean;
  windowStart: string | null; windowEnd: string | null; arrivedAt: string | null; deliveredAt: string | null; loadedAt: string | null;
  orderedBoxCount: number | null; expectedBoxCount: number | null; deliveredBoxCount: number | null; shortageBoxCount: number | null;
  damagedBoxCount: number | null; replacementRequiredBoxCount: number | null; loadingIssue: string | null;
  items: {productCode: string | null; itemName: string; expectedQuantity: number | null;
    loadedQuantity: number | null; unit: string; notes: string | null}[];
}
export interface ReportOutcomesDto {
  from: string; to: string; asOf: string; delivery: ReportDeliveryOutcomeDto;
  safety: {assignedRoutes: number; inspectedRoutes: number; passedRoutes: number; failedRoutes: number;
    missingInspectionRoutes: number; inspectionPassRate: number | null};
  loading: ReportLoadingOutcomeDto; problems: ReportDeliveryProblemsDto;
  warehouses: {warehouseId: number | null; warehouseName: string; delivery: ReportDeliveryOutcomeDto;
    loading: ReportLoadingOutcomeDto; problems: ReportDeliveryProblemsDto}[];
  daily: {date: string; dueOrders: number; fullOrders: number}[];
  recovery: ReportRecoveryDto; orders: ReportOrderOutcomeDto[];
}

export interface VehicleDto {
  id?: number;
  /** 車輛所屬倉庫，後端必填 */
  warehouseId: number;
  plateNumber: string;
  vehicleType?: string;
  capacity: number;
  fuelConsumption?: number;
  status: VehicleStatus;
  /** 這台車的保養與退役規則：三個一起填或都留白，隨時可以改（跟下面的基準不同） */
  minorMaintenanceIntervalKm?: number | null;
  majorMaintenanceIntervalKm?: number | null;
  retirementKm?: number | null;
  /** 目前的行車紀錄器里程；有值之後只能由出車、收車更新，空的（舊車）可以補一次 */
  currentOdometerKm?: number | null;
  /** 上次小保／大保時的行車紀錄器里程；有值之後只能由保養完成更新，空的可以補一次 */
  lastMinorMaintenanceKm?: number | null;
  lastMajorMaintenanceKm?: number | null;
  /** 後端唯讀：目前的保養狀況 */
  maintenance?: VehicleMaintenanceSummary | null;
}

/**
 * 一台車的保養狀況。剩下的公里數＝基準＋間隔－目前行車紀錄器里程，負數代表已經超過。
 * decision：BLOCKED 擋出車、WARNING 快到了、UNKNOWN 資料不齊算不出來（只提醒不擋）、NORMAL 正常
 */
export interface VehicleMaintenanceSummary {
  currentOdometerKm: number | null;
  minorRemainingKm: number | null;
  majorRemainingKm: number | null;
  retirementRemainingKm: number | null;
  warningKm: number;
  /** 這趟預計要跑的公里數（含回倉）；沒有要評估的趟次是 null */
  plannedKm: number | null;
  projectedMinorKm: number | null;
  projectedMajorKm: number | null;
  projectedRetirementKm: number | null;
  decision: 'NORMAL' | 'WARNING' | 'BLOCKED' | 'UNKNOWN';
  reasons: string[];
  minorCount: number;
  majorCount: number;
  repairCount: number;
  lastMinorAt: string | null;
  lastMajorAt: string | null;
  lastRepairAt: string | null;
}

/** 主管更正行車紀錄器里程與保養基準（打錯時用）：不帶或跟現在一樣就是不改，一定要寫原因 */
export interface VehicleMileageCorrectionRequest {
  currentOdometerKm?: number | null;
  lastMinorMaintenanceKm?: number | null;
  lastMajorMaintenanceKm?: number | null;
  reason: string;
}

/** 一筆里程更正紀錄：一次更正改到幾個數字就有幾筆 */
export interface VehicleMileageCorrection {
  id: number;
  field: 'CURRENT_ODOMETER' | 'MINOR_BASELINE' | 'MAJOR_BASELINE';
  /** 改之前是空的就是 null */
  oldKm: number | null;
  newKm: number;
  reason: string;
  /** 車在外面跑時一起改到出車讀數的那一趟 */
  mileageLogId: number | null;
  correctedBy: string | null;
  correctedAt: string;
}

/** 全車共用的保養設定；間隔和退役總里程在每台車上（VehicleDto） */
export interface VehicleMaintenanceSettings {
  /** 預估跑完這趟剩多少公里以內要提醒 */
  warningKm: number;
}

export interface VehicleMaintenanceRecord {
  id: number;
  vehicleId: number;
  type: 'MINOR' | 'MAJOR' | 'REPAIR';
  status: 'ACTIVE' | 'COMPLETED' | 'CANCELLED';
  sentAt: string | null;
  sentOdometerKm: number | null;
  completedAt: string | null;
  completedOdometerKm: number | null;
  cancelledAt: string | null;
  recordedBy: string | null;
  completedBy: string | null;
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
  /** 出貨倉庫，後端必填 */
  warehouseId: number;
  sourceVendor?: string;
  itemDescription?: string;
  /** 結構化商品明細；舊訂單沒有明細時可能是 null 或空陣列。 */
  items?: OrderItemDto[] | null;
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

/** 一張訂單的單一商品，送出時不帶 id 的項目會由後端建立。 */
export interface OrderItemDto {
  id?: number;
  productCode?: string;
  itemName: string;
  expectedQuantity: number;
  unit: string;
  sequence?: number;
  notes?: string;
  loadedQuantity?: number | null;
  checkedAt?: string | null;
  checkedByDriverId?: number | null;
  loadingNotes?: string | null;
}

/**
 * 排車結果。對應後端 DispatchResponse，
 * POST /api/dispatch/optimize 與 GET /api/dispatch/board 都回這個形狀。
 */
export interface DispatchResultDto {
  /** 配送日期，yyyy-MM-dd */
  date: string;
  warehouse: DispatchWarehouseDto;
  /** 每台有出車的車輛各一筆；沒被用到的車不會出現 */
  routes: RouteDto[];
  /** 裝不下、沒排進去的訂單，狀態維持 CONFIRMED */
  unassignedOrders: UnassignedOrderDto[];
  /** 依格子排車時才有：配了哪台車、哪位司機沒帶入、哪台車沒排到訂單 */
  notices?: string[];
  /**
   * 當天已在「其他倉庫」被指派的司機。
   *
   * 看板是按倉庫切的，但「一個司機一天只開一條路線」是跨倉的約束
   * （drivers 沒有 warehouse_id，兩倉共用同一個司機池）。少了這份清單，
   * 前端會把別倉已經用掉的司機也列成可選，選下去才被資料庫擋。
   *
   * 後端尚未實作，所以是選填；沒有這個欄位時當成空陣列。
   */
  driversTakenElsewhere?: DriverTakenDto[];
  /** 當天這一倉還沒確認的訂單（PENDING_CONFIRM）；確認後才會進 unassignedOrders */
  pendingConfirmOrders?: UnassignedOrderDto[];
}

/**
 * 看板日期列的一格：某一天全部倉庫合起來的狀態。後端每次依路線與訂單推算，不存資料庫。
 * UNRESOLVED＝日期已過還有單沒結束；CLOSED＝全部訂單都已結束。
 */
export type DispatchDayStatus =
  | 'EMPTY'
  | 'UNPLANNED'
  | 'DRAFT'
  | 'PUBLISHED'
  | 'IN_PROGRESS'
  | 'CLOSED'
  | 'UNRESOLVED';

export interface DispatchDayDto {
  /** yyyy-MM-dd */
  date: string;
  status: DispatchDayStatus;
  /** 是否仍有已發布路線；配送進度不代表發布狀態。 */
  published?: boolean;
  /** 有效訂單數，不含取消的單 */
  orderCount: number;
  pendingConfirmCount: number;
  /** 已確認、還沒排進路線 */
  unassignedCount: number;
  /** 已結束：完成、無人簽收、點交不符 */
  finishedCount: number;
}

/** 看板推播：只說哪一天變了，收到後自己重查 /days、/board */
export interface DispatchBoardPushDto {
  date: string | null;
  resourcesChanged?: boolean;
}

export type RouteDeviationEndReason = 'BACK_ON_ROUTE' | 'DELIVERING' | 'ON_BREAK' | 'TRIP_ENDED' | 'OFF_DUTY';

/** 對應 GET /api/fleet/route-deviations 與偏離推播裡的 deviation：一次偏離預定路線 */
export interface RouteDeviationDto {
  id: number;
  routeId: number;
  driverId: number;
  /** 偏離的是第幾段：倉庫 → 第一站是 1，最後一段是回倉 */
  legSequence: number;
  /** 確定偏離（連續第 3 筆超過 200 公尺）的時間 */
  startedAt: string;
  startLat: number;
  startLng: number;
  startDistanceMeters: number;
  /** 有值＝偏離超過 10 分鐘、已升級成警報；null＝提示 */
  escalatedAt: string | null;
  /** 有值＝已結束（進行中清單不會出現，只有 ENDED 推播會帶） */
  endedAt: string | null;
  endReason: RouteDeviationEndReason | null;
}

/** /topic/admin/route-deviations 的推播：STARTED、ESCALATED 要更新清單，ENDED 要從清單拿掉 */
export interface RouteDeviationPushDto {
  type: 'STARTED' | 'ESCALATED' | 'ENDED';
  deviation: RouteDeviationDto;
}

/** 當天已被其他倉庫排走的司機，附上排在哪裡好讓畫面說明原因 */
export interface DriverTakenDto {
  driverId: number;
  driverName: string;
  plateNumber: string;
  warehouseName: string;
}

/**
 * 司機 GPS 回報點。對應後端 GpsPingDTO，GET /api/fleet/live 用。
 *
 * 沒有司機姓名，只有 driverId —— 要顯示名字得自己拿 drivers() join。
 * timestamp 是後端用 LocalDateTime.now(Asia/Taipei) 產生，字串沒有時區標記
 * （例如 "2026-09-02T14:23:11"），用 `new Date()` 解析會被當成瀏覽器本地時間，
 * 只有在瀏覽器也是台北時區時才會算對。
 */
export interface GpsPingDto {
  id: number;
  driverId: number;
  lat: number;
  lng: number;
  timestamp: string;
}

/**
 * 排車結果裡的倉庫，是路線的起訖點。
 *
 * 這是精簡版，欄位比 WarehouseDto 少（沒有 phone / isActive），
 * 所以不共用同一個型別 —— 共用的話存取那兩個欄位會過編譯但執行時是 undefined。
 */
export interface DispatchWarehouseDto {
  id: number;
  warehouseCode: string;
  name: string;
  address: string | null;
  lat: number;
  lng: number;
}

/** 一台車某天的一條路線 */
export interface RouteDto {
  routeId: number;
  vehicleId: number;
  plateNumber: string;
  vehicleType: string | null;
  /** 車輛容量（箱） */
  capacity: number;
  /** 草稿階段為 null，發布前才指派 */
  driverId: number | null;
  driverName: string | null;
  stops: RouteStopDto[];
  /** 等於 stops.length */
  stopCount: number;
  /** 實際載運箱數 */
  loadedBoxes: number;
  /** 總里程（公尺） */
  totalDistance: number;
  /** 後端尚未實作（系統無油價設定），目前一律為 null */
  estimatedFuelCost: number | null;
  /** 後端尚未實作（需 OSRM durations），目前一律為 null */
  estimatedWorkMinutes: number | null;
  /** 裝載率 0~1，實際載運箱數 ÷ 車輛容量 */
  loadRate: number;
  /**
   * 發布狀態。發布後司機端才查得到任務，而且路線不再被排車清掉
   * （clearExistingDraftRoutes 只清 DRAFT，遇到 PUBLISHED 會擋下整個重排）。
   */
  status: RouteStatus;
}

/** 路線上的一個停靠點，等於一張訂單 */
export interface RouteStopDto {
  /** 建議配送順序，從 1 開始 */
  sequence: number;
  orderId: number;
  orderNumber: string;
  boxCount: number;
  itemDescription: string | null;
  storeId: number;
  storeCode: string;
  storeName: string;
  address: string | null;
  lat: number;
  lng: number;
  contactName: string | null;
  phone: string | null;
  /** 可收貨時間起，HH:mm:ss */
  receivingStart: string;
  /** 可收貨時間迄，HH:mm:ss */
  receivingEnd: string;
}

/** 沒排進任何路線的訂單 */
export interface UnassignedOrderDto {
  orderId: number;
  orderNumber: string;
  boxCount: number;
  storeId: number;
  storeCode: string;
  storeName: string;
  address: string | null;
  lat: number;
  lng: number;
}

/**
 * 拖曳改派的請求本體。對應後端 ReassignDTO，POST /api/dispatch/reassign。
 *
 * 沒出現在任何一條路線裡的當天訂單會被視為未排入，不需要另外傳。
 */
export interface ReassignRequest {
  /** 配送日期，yyyy-MM-dd */
  date: string;
  warehouseId: number;
  routes: RouteAssignment[];
}

/** 一台車要載哪些訂單、由誰開 */
export interface RouteAssignment {
  vehicleId: number;
  /**
   * 指派的司機，未指派為 null。
   *
   * 司機跟著改派整包送，是因為後端 reassign 會清掉當天草稿、整批重建路線 ——
   * 不一起帶的話，每拖一次訂單就會把已經指派好的司機清光。
   */
  driverId: number | null;
  /**
   * 陣列順序即配送順序，不另外傳 sequence。
   * 不能是空陣列 —— 後端會擋，空車道要在組請求時就濾掉。
   */
  orderIds: number[];
}

export interface DriverStatusPayload {
  isActive: boolean;
}

export interface StoreStatusPayload {
  status: StoreStatus;
}

/* ── 常配編組 ───────────────────────────────────────────────
 * 編組是「哪台車固定跑哪幾間門市」的樣板，不含訂單也不含司機：
 * 訂單綁日期、會取消，只能在套用當下才去撈；司機由調度員逐日指派。
 */

export interface TemplateStopDto {
  id: number;
  storeId: number;
  /** 停靠順序，套用時原封成為 orders.sequence */
  sequence: number;
}

/** 編組裡的一格：司機、車輛都是選填（至少一個）。stops 是這格固定跑的門市，依 sequence 排 */
export interface TemplateRouteDto {
  id: number;
  warehouseId: number;
  vehicleId: number | null;
  driverId: number | null;
  stops: TemplateStopDto[];
}

export interface TemplateDto {
  id: number;
  name: string;
  notes?: string;
  routes: TemplateRouteDto[];
}

/** 建立／修改編組。storeIds 的陣列順序就是停靠順序。 */
export interface TemplateRequest {
  name: string;
  notes?: string;
  routes: TemplateRouteRequest[];
}

export interface TemplateRouteRequest {
  warehouseId: number;
  vehicleId: number | null;
  driverId: number | null;
  /** 這格固定跑的門市，陣列順序就是停靠順序；同一間門市不能出現在兩格 */
  storeIds: number[];
}

/** POST /api/dispatch/optimize/slots：依看板上的格子自動排車 */
export interface OptimizeSlotsRequest {
  /** yyyy-MM-dd */
  date: string;
  warehouseId: number;
  /**
   * 司機、車輛都選填；只填司機的格子由後端配車。
   * orderIds 是格子裡已經有的訂單，自動排車時固定在這格的車上，只有待排單的訂單交給 OR-Tools 分配
   */
  slots: {driverId: number | null; vehicleId: number | null; orderIds: number[]}[];
}

/* ── AI 調度助理 ───────────────────────────────────────────────
 * 對話只會把動作加入待執行清單，調度員按確認才真正寫入。
 * 對話與清單都以登入者 JWT 區分，後端存在記憶體，重啟即消失。
 */

/** 對應後端 AiActionType */
export type AiActionType = 'ASSIGN_DRIVER' | 'MOVE_ORDER' | 'PUBLISH_DAY' | 'UNASSIGN_DRIVER';

/** POST /api/ai/chat 的請求本體 */
export interface AiChatRequest {
  message: string;
}

/** POST /api/ai/chat 的回應：助理回覆外加最新的待執行清單 */
export interface AiChatReply {
  /** 模型產生的文字，可能含 Markdown */
  reply: string;
  /** 整份清單而非本次新增的部分，前端直接整包取代 */
  pendingActions: AiPendingActionDto[];
}

/** 待執行清單中的一項動作。對應後端 PendingActionResponse。 */
export interface AiPendingActionDto {
  /**
   * 清單項目編號（UUID），加入清單時由後端產生，用於刪除單一項目與 @for 的 track。
   * 跟下面的業務 id 無關，畫面上不顯示。
   */
  id: string;
  type: AiActionType;
  /** 給人看的說明，姓名、車牌取自資料庫，確認視窗直接顯示這段 */
  summary: string;
  /** 配送日期，yyyy-MM-dd */
  date: string;
  /** PUBLISH_DAY 為 null：發布範圍是當天全部倉庫 */
  warehouseId: number | null;
  /** 只給分組標題顯示用；PUBLISH_DAY 為 null，畫面顯示「全部倉庫」 */
  warehouseName: string | null;
  /** ASSIGN_DRIVER、UNASSIGN_DRIVER 為該路線的車；MOVE_ORDER 為目標車；PUBLISH_DAY 為 null */
  vehicleId: number | null;
  /** ASSIGN_DRIVER 為被指派的司機；UNASSIGN_DRIVER 為要被取消的司機；其他為 null */
  driverId: number | null;
  /** 只有 MOVE_ORDER 有值 */
  orderId: number | null;
}

// ── 司機聊天室 ─────────────────────────────────────────

/** 對應後端 MessageSender：訊息是誰發的。ADMIN 代表「調度中心」，不分是哪一位管理員 */
export type MessageSender = 'DRIVER' | 'ADMIN';

/** 一則聊天訊息。對應後端 DriverMessageResponse */
export interface DriverMessageDto {
  /** 最後一則的 id 當下一次查詢的 afterId；前端合併清單時也用它去重 */
  id: number;
  /** 屬於哪位司機的對話串，不是寄件人 */
  driverId: number;
  senderType: MessageSender;
  content: string;
  createdAt: string;
  /** 對方讀到的時間；null 或沒有這個欄位都代表還沒讀 */
  readAt?: string | null;
  /** 屬於哪件例外回報案件的對話；null 或沒有這個欄位＝一般對話 */
  exceptionCaseId?: number | null;
}

/** POST /api/drivers/{driverId}/messages 的請求本體。對話屬於誰、誰發的、時間都由後端決定，只送內容 */
export interface DriverMessageRequest {
  content: string;
}

/** 紅點：某位司機有幾則還沒被管理員讀的訊息。對應後端 DriverMessageSummaryResponse */
export interface DriverMessageSummaryDto {
  driverId: number;
  unreadCount: number;
}

/** 後端 AdminStickyNoteResponse：登入主管自己的備忘錄，不綁定司機對話。 */
export interface AdminStickyNoteDto {
  id: number;
  title: string | null;
  content: string;
  color: string;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** POST/PUT /api/admin-sticky-notes 的請求內容。 */
export interface AdminStickyNoteRequestDto {
  title: string | null;
  content: string;
  color: string | null;
  sortOrder: number;
}

/**
 * 對應後端 DriverMessagePushType：MESSAGE＝新訊息，READ＝已讀，
 * CASE_OPENED／CASE_ACCEPTED／CASE_CLOSED＝司機回報案件建立、接收、結案
 */
export type DriverMessagePushType = 'MESSAGE' | 'READ' | 'CASE_OPENED' | 'CASE_ACCEPTED' | 'CASE_CLOSED';

/**
 * WebSocket 推播的內容。對應後端 DriverMessagePushResponse。
 * 管理員頻道 /topic/admin/driver-messages 會收到所有司機的訊息、已讀，以及案件的建立、接收、結案。
 */
export interface DriverMessagePushDto {
  type: DriverMessagePushType;
  /** 哪位司機的對話串；每種 type 都有（舊版 API 建的案件沒有司機，會是 null） */
  driverId: number;
  /** 新訊息本體；只有 MESSAGE 有。message.exceptionCaseId 有值＝案件的對話，不能併進一般對話 */
  message?: DriverMessageDto | null;
  /** 被讀的是哪一方發的訊息；只有 READ 有。DRIVER＝管理員讀了司機的訊息，ADMIN＝司機讀了調度中心的回覆 */
  readSenderType?: MessageSender | null;
  /** 標已讀的時間；只有 READ 有 */
  readAt?: string | null;
  /** READ 有值＝只標那件案件的對話，沒有＝一般對話；CASE_* 一定有值 */
  exceptionCaseId?: number | null;
  /** 案件本體；只有 CASE_* 有。推播裡的 unreadCount 固定 0，本機的未讀數要留著 */
  exceptionCase?: DriverCaseDto | null;
}

// ── 司機例外回報案件（對應後端 /api/exceptions/driver-cases）──────────────────
// 異常中心管案件本身（清單、接收、結案），聊天室管對話；兩邊的資料都從 DriverCasesService 來

/** 司機回報的分類；中文名稱見 driver-cases.service.ts 的 driverCaseCategoryLabel */
export type DriverCaseCategory =
  | 'VEHICLE'
  | 'ACCIDENT'
  | 'ROAD'
  | 'STORE'
  | 'GOODS'
  | 'PERSONAL'
  | 'SYSTEM'
  | 'OTHER';

/** 異常中心看到的一件司機回報。對應後端 AdminDriverCaseResponse */
export interface DriverCaseDto {
  id: number;
  /** 舊版 API 建的回報沒有分類，會是 null */
  category: DriverCaseCategory | null;
  status: ExceptionStatus;
  orderId: number | null;
  orderNumber: string | null;
  storeName: string | null;
  description: string;
  /** 司機說還能不能繼續配送；舊版回報是 null */
  canContinue: boolean | null;
  /** 交貨照片上傳 API 給的網址，公開路徑，可以直接放進 <img> */
  photoUrl: string | null;
  createdAt: string;
  /** 接收時間；null＝還沒有人接收（鈴鐺只列這種） */
  acceptedAt: string | null;
  handledAt: string | null;
  resolution: string | null;
  /** 司機發的、還沒被管理員讀的訊息數；推播裡固定 0 */
  unreadCount: number;
  /** 舊版回報沒有記司機，會是 null */
  driverId: number | null;
  driverName: string | null;
  routeId: number | null;
  vehiclePlateNumber: string | null;
  acceptedAdminId: number | null;
  acceptedAdminName: string | null;
  /** 結案的管理員名稱 */
  handledBy: string | null;
}

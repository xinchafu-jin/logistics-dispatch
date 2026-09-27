export type AttendanceStatus = 'WORKING' | 'ON_BREAK' | 'OVERTIME' | 'CLOCKED_OUT';
export type AttendancePunctualityStatus =
  | 'ON_TIME'
  | 'LATE_EXCUSED'
  | 'LATE'
  | 'LEAVE_REQUIRED'
  | 'LEAVE_COVERED';
export type LeaveType = 'SICK' | 'ANNUAL' | 'PERSONAL' | 'SPECIAL' | 'MENSTRUAL' | 'BEREAVEMENT' | 'ABSENT';
export type LeaveRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type LeaveRequestMode =
  | 'PREPLANNED'
  | 'TEMPORARY'
  | 'MAKEUP'
  | 'SYSTEM_NO_SHOW'
  | 'ADMIN_PLANNED_PARTIAL';

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
  punctualityStatus?: AttendancePunctualityStatus | null;
  lateMinutes?: number | null;
  lateExcused?: boolean | null;
  leaveRequired?: boolean | null;
  leaveRequiredMinutes?: number | null;
  coveredLeaveRequestId?: number | null;
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

export interface DriverLeaveRequest {
  workDate: string;
  leaveType: Exclude<LeaveType, 'ABSENT'>;
  leaveStart?: string | null;
  leaveEnd?: string | null;
  reason: string;
}

export interface DriverLeaveRequestResponse {
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
  submissionSource: 'DRIVER' | 'ADMIN' | 'SYSTEM';
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

export interface DriverPlannedLeaveGroupRequest {
  leaveType: Exclude<LeaveType, 'ABSENT'>;
  workDates: string[];
  reason: string;
}

export interface DriverPlannedLeaveBatchRequest {
  groups: DriverPlannedLeaveGroupRequest[];
}

export interface DriverLeaveBatchResponse {
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
  items: DriverLeaveRequestResponse[];
}

export interface DriverMakeupLeaveRequest {
  workDate: string;
  leaveType: Exclude<LeaveType, 'ABSENT'>;
  reason: string;
  evidencePhotoUrl?: string | null;
}

export interface DriverLeaveHistoryResponse {
  id: number;
  leaveRequestId: number;
  driverId: number;
  eventType: string;
  actorType: 'DRIVER' | 'ADMIN' | 'SYSTEM';
  actorId: number;
  actorAccount: string;
  oldStatus: LeaveRequestStatus | null;
  newStatus: LeaveRequestStatus | null;
  oldLeaveType: LeaveType | null;
  newLeaveType: LeaveType | null;
  reason: string | null;
  occurredAt: string;
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
  /** 逐一轉彎提示，依行駛順序：第一步是出發、最後一步是抵達。舊版後端沒有這個欄位 */
  steps?: GpsRouteStep[];
}

/** 一個轉彎動作。對應後端 GPSRouteResponse.Step */
export interface GpsRouteStep {
  /** OSRM 的動作類型：turn、continue、new name、fork、merge、on ramp、off ramp、roundabout、depart、arrive… */
  type: string;
  /** 方向：left、right、slight left、sharp right、straight、uturn；沒有時是 null */
  modifier: string | null;
  /** 轉進去之後的路名；沒有路名是空字串 */
  name: string;
  /** 後端組好的中文提示，例如「左轉進入復興一路」；距離由前端依即時位置補上 */
  instruction: string;
  /** 轉彎點 */
  lat: number;
  lng: number;
  /** 轉彎後到下一個轉彎點的距離，公尺 */
  distance: number;
  /** 圓環第幾個出口；不是圓環是 null */
  exit: number | null;
}

/** 倉庫點交的單一商品；每一項都要回傳是否核對與實點數量。 */
export interface LoadingItemRequest {
  orderItemId: number;
  checked: boolean;
  loadedQuantity?: number;
  notes?: string;
}

/** 倉庫點交：箱數與商品明細皆由後端比對、決定點交結果。 */
export interface LoadingRequest {
  orderId: number;
  loadedBoxCount: number;
  notes?: string;
  items?: LoadingItemRequest[];
}

/** 相符時 orderStatus 是 LOADED；不符時是 FAILED，並帶回異常單與明日補送單 */
export interface LoadingResponse {
  orderId: number;
  orderStatus: DriverTaskOrderStatus;
  loadedAt: string | null;
  exceptionCaseId: number | null;
  followUpOrderId: number | null;
  followUpOrderNumber: string | null;
  followUpDeliveryDate: string | null;
  checkedItemCount: number;
  totalItemCount: number;
  itemChecklistCompleted: boolean;
  items: LoadingItemResult[];
}

export interface LoadingItemResult {
  orderItemId: number;
  itemName: string;
  expectedQuantity: number;
  loadedQuantity: number;
  unit: string;
  matched: boolean;
  checkedAt: string | null;
  notes: string | null;
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
  loadedAt: string | null;
  loadingRequired: boolean;
  itemChecklistCompleted: boolean;
  items: DriverTaskOrderItem[];
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

export interface DriverTaskOrderItem {
  id: number;
  productCode: string | null;
  itemName: string;
  expectedQuantity: number;
  unit: string;
  sequence: number;
  notes: string | null;
  loadedQuantity: number | null;
  checked: boolean | null;
  checkedAt: string | null;
  loadingNotes: string | null;
}

export type DriverTaskOrderStatus =
  | 'PENDING_CONFIRM'
  | 'CONFIRMED'
  | 'LOADED'
  | 'IN_DELIVERY'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED';

// ── 司機聊天室（對應後端 DriverPortalController 的 /api/driver/messages）──────────

/** 對應後端 MessageSender：訊息是誰發的。ADMIN 代表「調度中心」，不分是哪一位管理員 */
export type MessageSender = 'DRIVER' | 'ADMIN';

/** 一則聊天訊息。對應後端 DriverMessageResponse */
export interface DriverMessageDto {
  /** 最後一則的 id 當下一次查詢的 afterId；合併清單時也用它去重 */
  id: number;
  /** 對話串屬於哪位司機；司機端永遠是自己 */
  driverId: number;
  senderType: MessageSender;
  content: string;
  createdAt: string;
  /** 對方讀到的時間；null 或沒有這個欄位都代表還沒讀 */
  readAt?: string | null;
  /** 屬於哪件案件的對話；null 或沒有這個欄位＝一般對話 */
  exceptionCaseId?: number | null;
}

/** POST /api/driver/messages 的請求本體。對話屬於誰、誰發的、時間都由後端決定，只送內容 */
export interface DriverMessageRequest {
  content: string;
}

/**
 * 對應後端 DriverMessagePushType：MESSAGE＝新訊息，READ＝已讀，
 * CASE_OPENED／CASE_CLOSED＝案件建立、結案（後端還沒做，先照約定接好）
 */
export type DriverMessagePushType = 'MESSAGE' | 'READ' | 'CASE_OPENED' | 'CASE_CLOSED';

/**
 * WebSocket 推播的內容，從私人頻道 /user/queue/messages 收到。對應後端 DriverMessagePushResponse。
 * 只會收到自己對話串的推播：後端依連線時的名牌（DRIVER:自己的 id）只送給本人。
 */
export interface DriverMessagePushDto {
  type: DriverMessagePushType;
  driverId: number;
  /** 新訊息本體；只有 MESSAGE 有 */
  message?: DriverMessageDto | null;
  /** 被讀的是哪一方發的訊息；只有 READ 有。DRIVER＝調度中心讀了你的訊息，ADMIN＝你在別的裝置讀了回覆 */
  readSenderType?: MessageSender | null;
  /** 標已讀的時間；只有 READ 有 */
  readAt?: string | null;
  /** READ 標的是哪一串；null 或沒有這個欄位＝一般對話。MESSAGE 改看 message.exceptionCaseId */
  exceptionCaseId?: number | null;
  /** 案件本體；只有 CASE_OPENED、CASE_CLOSED 有 */
  exceptionCase?: DriverCaseDto | null;
}

// ── 例外回報案件（對應後端 /api/driver/cases）──────────────────────────
// 後端還沒實作，這裡的欄位就是前後端的約定：後端照這個形狀回，前端不用再改

/** 司機可以選的分類。後端存進 exception_cases.category（VARCHAR），中文標籤與圖示只放在前端 */
export type DriverCaseCategory =
  | 'VEHICLE'
  | 'ACCIDENT'
  | 'ROAD'
  | 'STORE'
  | 'GOODS'
  | 'PERSONAL'
  | 'SYSTEM'
  | 'OTHER';

/** 對應 ExceptionStatus。資料庫只有這兩種；畫面上的「等待回覆／處理中」用 acceptedAt 推算，不另外加狀態 */
export type DriverCaseStatus = 'OPEN' | 'CLOSED';

/** POST /api/driver/cases 的請求本體。司機、路線、建立時間都由後端決定，不從前端收 */
export interface DriverCaseRequest {
  category: DriverCaseCategory;
  /** 跟某張單有關才帶；車輛、路況這類整台車的狀況是 null */
  orderId: number | null;
  /** 快選情境加上補充說明組成的一段文字，最多 1000 字 */
  description: string;
  /** 還能不能繼續配送；後台用來排序，不能繼續的排最前面 */
  canContinue: boolean;
  /** 先上傳 /api/driver/delivery-photo 拿到的網址；沒拍照是 null */
  photoUrl: string | null;
}

/** 一件案件。對應後端 DriverCaseResponse（GET /api/driver/cases、建立後的回應、推播都是這個形狀） */
export interface DriverCaseDto {
  id: number;
  category: DriverCaseCategory;
  status: DriverCaseStatus;
  orderId: number | null;
  orderNumber: string | null;
  storeName: string | null;
  description: string;
  canContinue: boolean;
  photoUrl: string | null;
  createdAt: string;
  /** 第一位管理員回覆的時間；null＝還在等調度中心回覆 */
  acceptedAt: string | null;
  /** 結案時間；OPEN 時是 null */
  handledAt: string | null;
  /** 後台結案時填的處理結果；OPEN 時是 null */
  resolution: string | null;
  /** 調度中心在這件案件發的、司機還沒讀的訊息數 */
  unreadCount: number;
}

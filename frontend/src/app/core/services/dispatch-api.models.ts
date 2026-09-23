export type DriverStatus = 'ACTIVE' | 'INACTIVE';
export type VehicleStatus = 'AVAILABLE' | 'MAINTENANCE' | 'RETIRED';
export type StoreStatus = 'ACTIVE' | 'SUSPENDED';
export type ScheduleStatus = 'DRAFT' | 'PUBLISHED';
export type ShiftType = 'UNASSIGNED' | 'WORK' | 'DAY_OFF' | 'LEAVE';
/** 路線的發布狀態。訂單層沒有「已發布」，發布是路線層的事。 */
export type RouteStatus = 'DRAFT' | 'PUBLISHED';
export type OrderStatus =
  | 'PENDING_CONFIRM'
  | 'CONFIRMED'
  | 'IN_DELIVERY'
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
  | 'PHONE_HANDLED';
export type DriverApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
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

/** 司機登入前提交的帳號申請。nationalId 只會被後端當成初始密碼雜湊。 */
export interface DriverAccountApplicationRequest {
  account: string;
  name: string;
  phone: string;
  nationalId: string;
}

export interface DriverAccountApplicationDto {
  id: number;
  account: string;
  name: string;
  phone: string;
  nationalIdMasked: string;
  status: DriverApplicationStatus;
  appliedAt: string;
  reviewedBy: string | null;
  reviewedAt: string | null;
  rejectionReason: string | null;
  approvedDriverId: number | null;
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

export interface VehicleDto {
  id?: number;
  /** 車輛所屬倉庫，後端必填 */
  warehouseId: number;
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
  /** 出貨倉庫，後端必填 */
  warehouseId: number;
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

export interface TemplateRouteDto {
  id: number;
  warehouseId: number;
  vehicleId: number;
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
  vehicleId: number;
  storeIds: number[];
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

/** 對應後端 DriverMessagePushType：MESSAGE＝新訊息，READ＝已讀 */
export type DriverMessagePushType = 'MESSAGE' | 'READ';

/**
 * WebSocket 推播的內容。對應後端 DriverMessagePushResponse。
 * 管理員頻道 /topic/admin/driver-messages 會收到所有司機的 MESSAGE 與 READ。
 */
export interface DriverMessagePushDto {
  type: DriverMessagePushType;
  /** 哪位司機的對話串；兩種 type 都有 */
  driverId: number;
  /** 新訊息本體；只有 MESSAGE 有 */
  message?: DriverMessageDto | null;
  /** 被讀的是哪一方發的訊息；只有 READ 有。DRIVER＝管理員讀了司機的訊息，ADMIN＝司機讀了調度中心的回覆 */
  readSenderType?: MessageSender | null;
  /** 標已讀的時間；只有 READ 有 */
  readAt?: string | null;
}

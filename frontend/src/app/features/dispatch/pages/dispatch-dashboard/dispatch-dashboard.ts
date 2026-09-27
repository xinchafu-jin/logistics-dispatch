import {
  DecimalPipe,
}
  from '@angular/common';
import {
  CdkDrag,
  CdkDragDrop,
  CdkDropList,
  CdkDropListGroup,
  moveItemInArray,
  transferArrayItem,
} from '@angular/cdk/drag-drop';
import {HttpErrorResponse} from '@angular/common/http';
import {Component, computed, DestroyRef, inject, OnInit, signal, TemplateRef, viewChild} from '@angular/core';
import {takeUntilDestroyed, toObservable, toSignal} from '@angular/core/rxjs-interop';
import {catchError, debounceTime, forkJoin, of, switchMap, timer} from 'rxjs';
import {LiveFleetMap, MapPoint, RouteLine} from '../../components/live-fleet-map/live-fleet-map';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DispatchBoardEventsService} from '../../../../core/services/dispatch-board-events.service';
import {DriverChatSocketService} from '../../../../core/services/driver-chat-socket.service';
import {
  DispatchDayDto,
  DispatchResultDto,
  DriverDto,
  DriverShiftDto,
  DriverTakenDto,
  GpsPingDto,
  OrderDto,
  OrderStatus,
  ReassignRequest,
  RouteMetricsDto,
  RouteStatus,
  RouteStopDto,
  ShiftType,
  StoreDto,
  TemplateDto,
  TemplateRouteRequest,
  UnassignedOrderDto,
  VehicleDto,
  WarehouseDto,
} from '../../../../core/services/dispatch-api.models';
import {MatSlideToggleModule} from '@angular/material/slide-toggle' ;
import {MatIconModule} from '@angular/material/icon';
import {MatButtonModule} from '@angular/material/button';
import {MatDialog, MatDialogModule} from '@angular/material/dialog';

/** 後端錯誤統一是 { message }，取得到就用它的文字，否則給個能辨識的替代。 */
function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const message = (error.error as { message?: string } | null)?.message;
    return message || `操作未完成（代碼 ${error.status}）`;
  }

  return '未知錯誤';
}

/**
 * 今天的本地日期（YYYY-MM-DD）。不能用 `new Date().toISOString().slice(0, 10)`——
 * toISOString 是轉成 UTC 再取日期，台灣時區凌晨 00:00~07:59 會被算成前一天，
 * 排車、看板就會拿錯誤日期去查詢，明明有今天的訂單卻顯示抓不到。
 */
function todayLocalDate(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/**
 * GPS 回報時間距現在幾分鐘。
 *
 * timestamp 是後端用 Asia/Taipei 產生、不帶時區標記的字串，`new Date()` 解析時
 * 會當成瀏覽器本地時間 —— 只有瀏覽器也在台北時區時這裡的差值才準確。
 */
function minutesAgo(timestamp: string): number {
  const elapsedMs = Date.now() - new Date(timestamp).getTime();
  return Math.max(0, Math.round(elapsedMs / 60_000));
}

/**
 * 看板上的一張訂單卡。
 *
 * 車道與未排入池共用同一個型別，跨欄拖曳才成立 —— transferArrayItem() 要求兩邊
 * 陣列同型別，而後端的 RouteStopDto 與 UnassignedOrderDto 欄位並不一致。
 * 這裡只取兩者共通、且看板顯示需要的欄位。
 */
interface BoardCard {
  orderId: number;
  orderNumber: string;
  storeId: number;
  storeCode: string;
  storeName: string;
  boxCount: number;
}

/**
 * 看板上的一格，代表今天的一趟車。
 *
 * 司機、車輛都是選填（以人為準）：已有路線的車一格，調度員另外填的人車也各一格，
 * 空格固定補成 3 的倍數，填滿一排就再長出 3 格（見 withEmptySlots）。
 * 有訂單的格子一定有車；沒車的格子不能拖入訂單，要先選車或交給自動排車配車。
 */
interface BoardRoute {
  /** 畫面用的穩定 key：格子可能還沒有路線、也還沒選車，不能拿 routeId 或 vehicleId 當 key */
  slotKey: string;
  /** 0 代表這格今天還沒有路線 */
  routeId: number;
  /** 還沒選車為 null */
  vehicleId: number | null;
  plateNumber: string;
  vehicleType: string | null;
  capacity: number;
  /** 指派的司機，未指派為 null。發布前必須指派，否則司機端查不到任務 */
  driverId: number | null;
  /** 上一次由後端算出的里程；拖曳後會失準，要等 reassign 重算 */
  totalDistance: number;
  /** 空槽沒有路線，一律當作草稿 */
  routeStatus: RouteStatus;
  /** 含有配送中、不能重新排程的訂單時，整條既有路線僅供檢視。 */
  hasLockedStops: boolean;
  /** 維修車保留在看板供辨識，但不可改派或拖曳。 */
  isMaintenance: boolean;
  cards: BoardCard[];
  /**
   * 這格編組的門市：只記錄，不動訂單。按「套用門市訂單」才會把這些門市的待排單拉進來，
   * 按「儲存編組」會存成編組的 stops。只存在畫面上，重新整理就沒了，跟還沒排單的人車格一樣。
   */
  storeIds: number[];
}

function toBoardCard(source: RouteStopDto | UnassignedOrderDto): BoardCard {
  return {
    orderId: source.orderId,
    orderNumber: source.orderNumber,
    storeId: source.storeId,
    storeCode: source.storeCode,
    storeName: source.storeName,
    boxCount: source.boxCount,
  };
}

/** 車道司機下拉的一個選項 */
interface DriverOption {
  id: number;
  name: string;
  /** 不是 null 就代表當天已排在別處，選單要 disabled 並顯示這句原因 */
  takenNote: string | null;
  /** 班表上不能出車的原因（休假、請假…）。仍然可以選，選了車道會標紅框，派出時才擋 */
  scheduleNote: string | null;
}

/** 門市標籤的 cdkDragData，用來跟訂單卡片分開（見 ordersOnly） */
const STORE_CHIP = 'store-chip';

/** 兩個 id 清單的成員相同（不管順序） */
function sameMembers(a: readonly number[], b: readonly number[]): boolean {
  return a.length === b.length && a.every((id) => b.includes(id));
}

/** 格子「加入門市」下拉的一個選項 */
interface StoreOption {
  id: number;
  name: string;
  /** 不是 null 就代表已在別格，選單要 disabled 並顯示這句原因（同一間門市只能在一格） */
  takenNote: string | null;
}

/** 格子車輛下拉的一個選項 */
interface VehicleOption {
  id: number;
  label: string;
  /** 不是 null 就代表已被別格選走，選單要 disabled 並顯示這句原因 */
  takenNote: string | null;
}

/** 已派出畫面右側「需要處理」的一項：配送異常、GPS 沒更新、還沒排進車的單 */
interface AttentionItem {
  key: string;
  title: string;
  detail: string;
  kind: 'exception' | 'gps' | 'pending';
}

/** 還沒結束、司機還要跑的單。已點交的貨在車上，也算還沒送 */
const UNFINISHED_ON_ROUTE: readonly OrderStatus[] = ['CONFIRMED', 'LOADED', 'IN_DELIVERY'];
/** 送不成的單：點交不符（FAILED）與無人簽收，都會開補送單，主管要看得到 */
const DELIVERY_PROBLEM: readonly OrderStatus[] = ['FAILED', 'NO_SIGNATURE'];

@Component({
  selector: 'app-dispatch-dashboard',
  imports: [
    LiveFleetMap, DecimalPipe, CdkDropListGroup, CdkDropList, CdkDrag,
    MatSlideToggleModule, MatIconModule, MatButtonModule, MatDialogModule,
  ],
  templateUrl: './dispatch-dashboard.html',
  styleUrl: './dispatch-dashboard.scss',
})
export class DispatchDashboard implements OnInit {
  readonly routes = signal<BoardRoute[]>([]);
  readonly unassigned = signal<BoardCard[]>([]);
  /** 還沒確認的單（PENDING_CONFIRM）：不能拖，要先按確認才會進待排單 */
  readonly pendingConfirm = signal<BoardCard[]>([]);
  /** 正在確認的那張單，按鈕顯示「確認中…」並擋住連點 */
  readonly confirmingOrderId = signal<number | null>(null);
  /** 看板上方的日期列：今天起到最後一天有單的日期，加上之前還沒結案的日子（後端 /dispatch/days） */
  readonly days = signal<DispatchDayDto[]>([]);
  /** 日期列跟著手指或滑鼠微移，放開時由 CSS 回彈，讓切日有明確的方向感。 */
  readonly daySwipeOffset = signal(0);
  readonly isDaySwipeDragging = signal(false);
  /** 當天已被其他倉庫排走的司機。後端還沒回這個欄位時是空陣列 */
  readonly driversTakenElsewhere = signal<DriverTakenDto[]>([]);
  private readonly routeMetricsByRouteId = signal<ReadonlyMap<number, RouteMetricsDto>>(new Map());
  /** 改派送出中，此時鎖住看板避免兩個請求互相覆蓋 */
  readonly saving = signal(false);
  readonly boardError = signal('');
  /** 動作列選的排車條件，optimize / board / reassign 三支 API 共用同一組值 */
  readonly dispatchDate = signal(todayLocalDate());
  /** 0 代表倉庫清單還沒載回來，尚未決定預設倉庫 */
  readonly warehouseId = signal(0);
  readonly optimizing = signal(false);
  private readonly api = inject(DispatchApiService);
  private readonly boardEvents = inject(DispatchBoardEventsService);
  private readonly socket = inject(DriverChatSocketService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = inject(MatDialog);
  // 按「＋」清空看板前的確認視窗，寫在 dispatch-dashboard.html 最下面的 <ng-template #clearBoardDialog>
  private readonly clearBoardDialog = viewChild.required<TemplateRef<unknown>>('clearBoardDialog');
  // 在編組分頁按「儲存編組」前的確認視窗，同樣寫在 html 最下面
  private readonly saveTemplateDialog = viewChild.required<TemplateRef<unknown>>('saveTemplateDialog');
  readonly dispatchResult = signal<DispatchResultDto | null>(null);
  readonly orders = signal<OrderDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly drivers = signal<DriverDto[]>([]);
  /** 排車不看是否已打卡，而是看今天已發布班表中的 WORK 班次。 */
  readonly scheduleLoadState = signal<'loading' | 'ready' | 'unavailable'>('loading');
  readonly scheduleMessage = signal('正在同步當日班表...');
  readonly shiftsByDriverId = signal<ReadonlyMap<number, DriverShiftDto>>(new Map());
  readonly vehicles = signal<VehicleDto[]>([]);
  readonly warehouses = signal<WarehouseDto[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly updatedAt = signal('--:--');

  // ── 常配編組 ──────────────────────────────────────────
  readonly templates = signal<TemplateDto[]>([]);
  /** 目前選中的編組分頁；0 代表沒有選任何編組 */
  readonly activeTemplateId = signal(0);
  /** 依格子自動排車、載入編組時，沒完全照要求完成的地方（配了哪台車、哪位司機沒帶入…） */
  readonly boardNotices = signal<string[]>([]);
  /** 格子 key 的流水號 */
  private slotSeq = 0;
  /** 目前看板屬於哪個倉、哪一天；換倉或換日期時不能沿用排到一半的格子 */
  private boardWarehouseId = 0;
  private boardDate = '';
  readonly templateName = signal('');
  readonly templateError = signal('');
  readonly templateBusy = signal(false);

  // ── 發布 ──────────────────────────────────────────────
  readonly publishing = signal(false);
  readonly publishError = signal('');
  private readonly publishedDates = signal<ReadonlySet<string>>(new Set());
  private daySwipe: {pointerId: number; startX: number; lastX: number} | null = null;
  private suppressDayCardClickUntil = 0;

  /** 發布是整天跨倉的動作；任何倉庫看到同一天已發布，就切成唯讀看板。 */
  readonly published = computed(() => {
    const date = this.dispatchDate();
    const dayStatus = this.days().find((day) => day.date === date)?.status;
    const dayWasPublished = ['PUBLISHED', 'IN_PROGRESS', 'CLOSED', 'UNRESOLVED'].includes(dayStatus ?? '');
    return (
      this.publishedDates().has(date) ||
      dayWasPublished ||
      this.routes().some((route) => route.routeStatus === 'PUBLISHED')
    );
  });

  readonly activeTemplate = computed(
    () => this.templates().find((item) => item.id === this.activeTemplateId()) ?? null,
  );
  readonly warehouseName = signal('高雄配送區');

  readonly tickerMessages = computed(() => {
    const orders = this.orders();
    const waitingSchedule = orders.filter((order) => order.status === 'CONFIRMED').length;
    // 已點交的貨已經在車上，跟報表一樣算進配送中
    const delivering = orders.filter((order) => order.status === 'IN_DELIVERY' || order.status === 'LOADED').length;

    return [
      `今日配送需求 ${orders.length} 筆`,
      `${waitingSchedule} 筆待排車，等待安排車輛`,
      `${delivering} 筆配送中，可開啟地圖的司機位置查看有效 GPS 回傳`,
      `已同步 ${this.drivers().length} 位司機與 ${this.vehicles().length} 台車輛`,
      `目前 ${this.warehouseName()} 已納入首頁資料來源`,
    ];
  });

  /** 排車、改派或發布進行中都先鎖住看板操作 */
  readonly busy = computed(() => this.optimizing() || this.saving() || this.publishing());

  // ── 地圖圖層 ──────────────────────────────────────────

  /** 地圖上的配送點開關。預設開著，否則進頁面看不到任何點，會以為地圖壞了 */
  readonly showMapPoints = signal(true);

  /** mat-slide-toggle 的 change 事件帶的是 MatSlideToggleChange，不是 DOM Event，直接收 checked */
  toggleMapPoints(checked: boolean): void {
    this.showMapPoints.set(checked);
  }

  /**
   * 地圖上的倉庫點。
   *
   * 取自倉庫清單而不是 dispatchResult().warehouse —— 清單在 loadDashboard 就回來了，
   * 看板還在載（或當天完全沒單）時倉庫仍畫得出來。
   */
  readonly mapWarehouse = computed<MapPoint | null>(() => {
    const warehouse = this.warehouses().find((item) => item.id === this.warehouseId());
    if (!warehouse?.id) {
      return null;
    }

    return {
      id: warehouse.id,
      label: warehouse.name,
      detail: warehouse.address ?? '',
      lat: warehouse.lat,
      lng: warehouse.lng,
    };
  });

  /**
   * 今天這一倉要配送的門市。看板本來就是用 dispatchDate + warehouseId 查的，
   * 所以車道上的卡片加上未排入池，就等於當天要送的門市。
   *
   * 用 routes()/unassigned() 而不是 dispatchResult()：前者是拖曳當下就更新的樂觀狀態，
   * 後者要等後端回應才換。代價是卡片沒有座標（BoardCard 只留 storeId），要回頭 join stores()。
   */
  readonly mapStores = computed<MapPoint[]>(() => {
    const storeById = new Map(
      this.stores()
        .filter((store) => store.id != null)
        .map((store) => [store.id!, store]),
    );

    // 同一間門市當天可能有多張訂單，以 storeId 去重並加總箱數；
    // 不去重就是同一個座標疊好幾個圈，只點得到最上面那一個。
    const boxesByStore = new Map<number, number>();
    for (const card of [...this.routes().flatMap((route) => route.cards), ...this.unassigned()]) {
      boxesByStore.set(card.storeId, (boxesByStore.get(card.storeId) ?? 0) + card.boxCount);
    }

    const points: MapPoint[] = [];
    for (const [storeId, boxCount] of boxesByStore) {
      const store = storeById.get(storeId);
      // 沒有座標的門市直接跳過：0 或 undefined 會畫到幾內亞灣，把自動框選拉到整個地球
      if (!store?.lat || !store?.lng) {
        continue;
      }

      points.push({
        id: storeId,
        label: store.name,
        detail: `${store.storeCode} · ${boxCount} 箱`,
        lat: store.lat,
        lng: store.lng,
      });
    }

    return points;
  });

  /** 司機路線圖層開關。預設關閉，一次全開線會疊在一起看不出誰是誰 */
  readonly showRouteLines = signal(false);

  toggleRouteLines(checked: boolean): void {
    this.showRouteLines.set(checked);
  }

  /**
   * 各司機的配送路線：倉庫出發，依派車順序直線連到各門市。
   *
   * 順序取 cards 的陣列順序，不取 sequence 欄位 —— 拖曳改的是陣列
   * （moveItemInArray / transferArrayItem），sequence 要等 reassign 回來才更新，
   * 照 sequence 畫會跟看板上看到的順序對不上。
   *
   * 只畫已指派司機的車道：這層就是「司機的路線」，沒司機就沒有主體可畫。
   */
  readonly routeLines = computed<RouteLine[]>(() => {
    const warehouse = this.mapWarehouse();
    if (!warehouse) {
      return [];
    }

    const storeById = new Map(
      this.stores()
        .filter((store) => store.id != null)
        .map((store) => [store.id!, store]),
    );
    const nameById = new Map(
      this.drivers()
        .filter((driver) => driver.id != null)
        .map((driver) => [driver.id!, driver.name]),
    );

    const lines: RouteLine[] = [];
    for (const route of this.routes()) {
      if (route.routeStatus !== 'PUBLISHED' || route.driverId === null || route.cards.length === 0) {
        continue;
      }

      const points: [number, number][] = [[warehouse.lat, warehouse.lng]];
      for (const card of route.cards) {
        const store = storeById.get(card.storeId);
        // 沒座標的門市跳過，理由同 mapStores：0 或 undefined 會把線拉到幾內亞灣
        if (!store?.lat || !store?.lng) {
          continue;
        }
        points.push([store.lat, store.lng]);
      }

      lines.push({
        id: route.routeId,
        label: `${nameById.get(route.driverId) ?? `司機 #${route.driverId}`} · ${route.plateNumber}`,
        points,
      });
    }

    return lines;
  });

  /**
   * 司機位置圖層開關。預設關閉 ——
   * 跟門市不同，這個一開就是持續輪詢的背景請求，不該預設一直跑。
   */
  readonly showDriverPoints = signal(false);

  toggleDriverPoints(checked: boolean): void {
    this.showDriverPoints.set(checked);
  }

  /**
   * 輪詢 /api/fleet/live。用 switchMap 接開關：關掉的瞬間換成 of([])，
   * 舊的 timer 訂閱被自動取消，不必自己管 clearInterval。
   *
   * catchError 刻意放在「每次請求」這層而不是整條 pipe 外層 —— 放外層的話，
   * 一次 401 或後端重啟就會讓整條 stream complete，之後永遠不會再重試。
   */
  /** 派出後的卡片要顯示每台車的定位，所以不只開圖層時要抓 */
  private readonly needLivePings = computed(() => this.showDriverPoints() || this.published());

  private readonly livePings = toSignal(
    toObservable(this.needLivePings).pipe(
      switchMap((on) =>
        on
          ? timer(0, 30_000).pipe(
              switchMap(() => this.api.getLiveFleet().pipe(catchError(() => of<GpsPingDto[]>([])))),
            )
          : of<GpsPingDto[]>([]),
      ),
    ),
    {initialValue: [] as GpsPingDto[]},
  );

  /**
   * 地圖上的司機點。GPS 回報只有 driverId，姓名要拿 drivers() 補回來。
   *
   * 地圖空白（沒有任何點）是正常狀態，不是壞掉 —— 後端只回工作中且
   * GPS 未過期（預設 10 分鐘內）的司機，司機端每 5 分鐘才傳一次，
   * 只要漏傳一次就會從清單消失。detail 附上分鐘數，讓調度員自己判斷新鮮度。
   */
  readonly mapDrivers = computed<MapPoint[]>(() => {
    const nameById = new Map(
      this.drivers()
        .filter((driver) => driver.id != null)
        .map((driver) => [driver.id!, driver.name]),
    );

    const storesById = new Map(
      this.stores().filter((store) => store.id != null).map((store) => [store.id!, store]),
    );
    const publishedDriverIds = new Set(
      this.routes()
        .filter((route) => route.routeStatus === 'PUBLISHED' && route.driverId !== null && !route.isMaintenance)
        .map((route) => route.driverId!),
    );
    const activeOrdersByDriver = new Map<number, OrderDto>();
    this.orders()
      .filter((order) =>
        order.deliveryDate === this.dispatchDate()
        && order.assignedDriverId != null
        && publishedDriverIds.has(order.assignedDriverId)
        // 少了 LOADED 的話，司機全部點交完出車後就會從即時地圖上消失
        && (order.status === 'CONFIRMED' || order.status === 'LOADED' || order.status === 'IN_DELIVERY'),
      )
      .sort((left, right) => (left.sequence ?? Number.MAX_SAFE_INTEGER) - (right.sequence ?? Number.MAX_SAFE_INTEGER))
      .forEach((order) => {
        if (!activeOrdersByDriver.has(order.assignedDriverId!)) {
          activeOrdersByDriver.set(order.assignedDriverId!, order);
        }
      });
    const routeByDriver = new Map(
      this.routes()
        .filter((route) => route.routeStatus === 'PUBLISHED' && route.driverId !== null && !route.isMaintenance)
        .map((route) => [route.driverId!, route]),
    );
    const metricsByRoute = this.routeMetricsByRouteId();

    return this.livePings()
      .filter((ping) => activeOrdersByDriver.has(ping.driverId))
      .map((ping) => {
        const order = activeOrdersByDriver.get(ping.driverId)!;
        const route = routeByDriver.get(ping.driverId);
        const metrics = route ? metricsByRoute.get(route.routeId) : undefined;
        const details = [
          `訂單 ${order.orderNumber}`,
          `預估抵達 ${this.formatEstimatedArrival(metrics?.estimatedNextArrivalAt)}`,
          `預估里程 ${this.formatKm(metrics?.remainingKm)}`,
          `預估油耗 ${this.formatFuel(metrics?.gpsEstimatedFuelLiters)}`,
          `${storesById.get(order.storeId)?.name ?? `門市 #${order.storeId}`} · ${minutesAgo(ping.timestamp)} 分鐘前回報`,
        ];
        return {
          id: ping.driverId,
          label: nameById.get(ping.driverId) ?? `司機 #${ping.driverId}`,
          detail: details[0],
          details,
          lat: ping.lat,
          lng: ping.lng,
        };
      });
  });

  ngOnInit(): void {
    this.loadDashboard();
    // 跟總覽分開打：編組載不到不該讓整個看板空白
    this.loadTemplates();
    // AI 清單在聊天面板確認後，資料庫已經換了，重讀一次才不會用舊畫面蓋回去
    this.boardEvents.boardChanged$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.reloadBoard());
    // 後端在訂單或路線 commit 後推「哪一天變了」。同一波操作可能連續推好幾則，等 0.5 秒沒有新的再重查一次
    this.socket.boardPushes$
      .pipe(debounceTime(500), takeUntilDestroyed(this.destroyRef))
      .subscribe((push) => this.onBoardPush(push.date));
    // 斷線期間的推播不會補發，重新連上時自己重查一次，免得畫面停在舊的狀態
    this.socket.connected$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.loadDays();
        if (this.warehouseId()) {
          this.refreshOrdersAndBoard();
        }
      });
    timer(15_000, 15_000)
      .pipe(
        switchMap(() => this.api.getOrders().pipe(catchError(() => of<OrderDto[] | null>(null)))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((orders) => {
        if (orders) {
          this.applySyncedOrders(orders);
        }
      });
  }

  onWarehouseChange(event: Event): void {
    this.warehouseId.set(Number((event.target as HTMLSelectElement).value));
    this.boardNotices.set([]);
    this.reloadBoard();
  }

  // ── 日期列 ────────────────────────────────────────────

  /** 點日期列的一格：換看那一天。班表也要換成那天的，司機選單的紅框才會對 */
  selectDay(date: string): void {
    if (date === this.dispatchDate() || this.busy()) {
      return;
    }
    this.dispatchDate.set(date);
    this.boardNotices.set([]);
    this.publishError.set('');
    this.loadScheduleEligibility();
    this.reloadBoard();
  }

  /** 日期卡仍可直接點；拖曳完成後那一次 click 不應覆蓋手勢切換的目標日期。 */
  selectDayFromCard(date: string): void {
    if (performance.now() < this.suppressDayCardClickUntil) {
      return;
    }
    this.selectDay(date);
  }

  /** 開始拖曳日期列。只接主要指標，避免雙指或右鍵誤切日期。 */
  startDaySwipe(event: PointerEvent): void {
    if (this.busy() || !event.isPrimary || (event.pointerType === 'mouse' && event.button !== 0)) {
      return;
    }

    const strip = event.currentTarget as HTMLElement;
    strip.setPointerCapture(event.pointerId);
    this.daySwipe = {pointerId: event.pointerId, startX: event.clientX, lastX: event.clientX};
    this.daySwipeOffset.set(0);
    this.isDaySwipeDragging.set(true);
  }

  /** 跟住拖曳但限制位移，短距離移動也要有明顯回饋。 */
  moveDaySwipe(event: PointerEvent): void {
    const swipe = this.daySwipe;
    if (!swipe || swipe.pointerId !== event.pointerId) {
      return;
    }

    swipe.lastX = event.clientX;
    const distance = event.clientX - swipe.startX;
    this.daySwipeOffset.set(Math.max(-96, Math.min(96, distance * 0.8)));
    if (Math.abs(distance) > 2) {
      event.preventDefault();
    }
  }

  finishDaySwipe(event: PointerEvent): void {
    this.completeDaySwipe(event, true);
  }

  cancelDaySwipe(event: PointerEvent): void {
    this.completeDaySwipe(event, false);
  }

  /** 鍵盤方向和手勢一致：左邊是往下一天，右邊是往前一天。 */
  handleDayPickerKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft') {
      this.selectRelativeDay(1);
    } else if (event.key === 'ArrowRight') {
      this.selectRelativeDay(-1);
    } else {
      return;
    }
    event.preventDefault();
  }

  private completeDaySwipe(event: PointerEvent, shouldSelectDay: boolean): void {
    const swipe = this.daySwipe;
    if (!swipe || swipe.pointerId !== event.pointerId) {
      return;
    }

    const strip = event.currentTarget as HTMLElement;
    if (strip.hasPointerCapture(event.pointerId)) {
      strip.releasePointerCapture(event.pointerId);
    }

    const distance = swipe.lastX - swipe.startX;
    this.daySwipe = null;
    this.isDaySwipeDragging.set(false);
    this.daySwipeOffset.set(0);

    if (!shouldSelectDay || Math.abs(distance) < 28) {
      return;
    }

    event.preventDefault();
    this.suppressDayCardClickUntil = performance.now() + 350;
    // 每次手勢只跨相鄰一天，避免拉長一點就跳過中間日期卡。
    this.selectRelativeDay(distance < 0 ? 1 : -1);
  }

  private selectRelativeDay(direction: 1 | -1): void {
    if (this.busy()) {
      return;
    }

    const index = this.days().findIndex((day) => day.date === this.dispatchDate());
    const next = this.days()[index + direction];
    if (next) {
      this.selectDay(next.date);
    }
  }

  /** 日期列失敗不擋看板：看板本身照樣能用，只是少了切換日期的入口 */
  private loadDays(): void {
    this.api.getDispatchDays().subscribe({
      next: (days) => this.days.set(days),
      error: () => this.days.set([]),
    });
  }

  /**
   * 收到「某一天變了」：日期列一律重查（每格都可能變）；變的是正在看的那天才重讀看板。
   * 自己正在存檔或排車時先不重讀：那個動作結束後本來就會重畫，這時插進來會蓋掉還沒存完的畫面。
   */
  private onBoardPush(date: string): void {
    this.loadDays();
    if (date === this.dispatchDate() && !this.busy()) {
      this.refreshOrdersAndBoard();
    }
  }

  /**
   * 訂單和看板一起重查。看板靠 orders() 判斷每張單能不能拖（見 applyDispatchResult），
   * 只重讀看板的話，剛確認或剛點交的單狀態還是舊的。
   */
  private refreshOrdersAndBoard(): void {
    this.api.getOrders().subscribe({
      next: (orders) => {
        this.applySyncedOrders(orders);
        this.reloadBoard();
      },
      error: () => this.reloadBoard(),
    });
  }

  /** 日期列一格的標題：9/26，今天、明天、後天另外標出來 */
  dayTitle(date: string): string {
    const [, month, day] = date.split('-').map(Number);
    const relative = this.relativeDayLabel(date);
    return relative ? `${month}/${day} ${relative}` : `${month}/${day}`;
  }

  private relativeDayLabel(date: string): string {
    const diff = Math.round(
      (new Date(`${date}T00:00:00`).getTime() - new Date(`${todayLocalDate()}T00:00:00`).getTime()) / 86_400_000,
    );
    return ({[-1]: '昨天', 0: '今天', 1: '明天', 2: '後天'} as Record<number, string>)[diff] ?? '';
  }

  /** 日期列一格的狀態文字：狀態加上最需要知道的那個數字 */
  dayStatusLabel(day: DispatchDayDto): string {
    const unfinished = day.orderCount - day.finishedCount;
    switch (day.status) {
      case 'EMPTY':
        return '沒有訂單';
      case 'UNPLANNED':
        return `未排 · ${day.orderCount} 單`;
      case 'DRAFT':
        return day.unassignedCount > 0 ? `草稿 · ${day.unassignedCount} 待排` : '草稿 · 已排完';
      case 'PUBLISHED':
        return day.unassignedCount > 0 ? `已發布 · +${day.unassignedCount} 待排` : '已發布';
      case 'IN_PROGRESS':
        return `配送中 · ${day.finishedCount}/${day.orderCount}`;
      case 'CLOSED':
        return '已結束';
      case 'UNRESOLVED':
        return `未結案 · ${unfinished} 單`;
    }
  }

  /** 工具列的日期：今天、明天這種相對說法比日期好認 */
  dispatchDateLabel(): string {
    const relative = this.relativeDayLabel(this.dispatchDate());
    return relative ? `${relative} ${this.dispatchDate()}` : this.dispatchDate();
  }

  // ── 待確認的單 ────────────────────────────────────────

  /** 在看板上直接確認：確認後這張單會從待確認移到待排單，就能拖或自動排車 */
  confirmPendingOrder(card: BoardCard): void {
    if (this.published() || this.busy() || this.confirmingOrderId() !== null) {
      return;
    }
    this.confirmingOrderId.set(card.orderId);
    this.boardError.set('');
    this.api.confirmOrder(card.orderId).subscribe({
      next: () => {
        this.confirmingOrderId.set(null);
        this.refreshOrdersAndBoard();
      },
      error: (error: unknown) => {
        this.boardError.set(`確認 ${card.orderNumber} 失敗：${describeError(error)}`);
        this.confirmingOrderId.set(null);
      },
    });
  }

  // ── 已派出：即時狀態 ──────────────────────────────────

  /** 派出後的卡片只畫有單的路線；空格、排到一半的人車格在派出後沒有意義 */
  readonly publishedRoutes = computed(() =>
    this.routes().filter((route) => route.routeId > 0 && route.cards.length > 0),
  );

  /** 進度圓點：完成綠、送不成紅、下一站藍、還沒到的灰 */
  routeProgressDotClass(route: BoardRoute, card: BoardCard): string {
    const status = this.boardCardStatus(card);
    if (status === 'COMPLETED') {
      return 'is-complete';
    }
    if (DELIVERY_PROBLEM.includes(status)) {
      return 'is-failed';
    }
    return this.routeNextCard(route)?.orderId === card.orderId ? 'is-current' : '';
  }

  routeProgressLabel(route: BoardRoute): string {
    const done = route.cards.filter((card) => !UNFINISHED_ON_ROUTE.includes(this.boardCardStatus(card))).length;
    return `${done}/${route.cards.length} 站`;
  }

  /** 下一站：照配送順序第一張還沒結束的單 */
  private routeNextCard(route: BoardRoute): BoardCard | undefined {
    return route.cards.find((card) => UNFINISHED_ON_ROUTE.includes(this.boardCardStatus(card)));
  }

  routeNextStop(route: BoardRoute): string {
    return this.routeNextCard(route)?.storeName ?? '全部送完';
  }

  routeHasProblem(route: BoardRoute): boolean {
    return route.cards.some((card) => DELIVERY_PROBLEM.includes(this.boardCardStatus(card)));
  }

  /**
   * 後端只回工作中、而且 10 分鐘內有回報的司機，所以查不到就代表沒在回報。
   * 還沒到配送日的路線司機根本還沒出門，不算異常。
   */
  routeGpsLabel(route: BoardRoute): string {
    if (this.dispatchDate() !== todayLocalDate()) {
      return '尚未出車';
    }
    const ping = this.livePings().find((item) => item.driverId === route.driverId);
    return ping ? `GPS ${minutesAgo(ping.timestamp)} 分鐘前` : 'GPS 超過 10 分鐘未更新';
  }

  routeEtaLabel(route: BoardRoute): string {
    return this.formatEstimatedArrival(this.routeMetricsByRouteId().get(route.routeId)?.estimatedNextArrivalAt);
  }

  routeRemainingKmLabel(route: BoardRoute): string {
    return this.formatKm(this.routeMetricsByRouteId().get(route.routeId)?.remainingKm);
  }

  /**
   * 派出後右側「需要處理」：送不成的單、今天在跑卻沒有 GPS 的司機、還沒排進車的單。
   * 都是調度員要動手的事，放同一個地方，不用在卡片之間找。
   */
  readonly dispatchAttention = computed<AttentionItem[]>(() => {
    const items: AttentionItem[] = [];
    const isToday = this.dispatchDate() === todayLocalDate();
    const pingDrivers = new Set(this.livePings().map((ping) => ping.driverId));

    for (const route of this.publishedRoutes()) {
      for (const card of route.cards) {
        if (DELIVERY_PROBLEM.includes(this.boardCardStatus(card))) {
          items.push({
            key: `order-${card.orderId}`,
            title: `${card.orderNumber} ${this.boardCardStatusLabel(card)}`,
            detail: `${route.plateNumber} · ${card.storeName}，補送單要到異常中心確認`,
            kind: 'exception',
          });
        }
      }
      if (isToday && route.driverId !== null && this.routeNextCard(route) && !pingDrivers.has(route.driverId)) {
        items.push({
          key: `gps-${route.driverId}`,
          title: `${this.driverName(route.driverId)} GPS 超過 10 分鐘未更新`,
          detail: `${route.plateNumber} · 下一站 ${this.routeNextStop(route)}`,
          kind: 'gps',
        });
      }
    }

    for (const card of this.pendingConfirm()) {
      items.push({key: `confirm-${card.orderId}`, title: `${card.orderNumber} 待確認`, detail: card.storeName, kind: 'pending'});
    }
    for (const card of this.unassigned()) {
      items.push({key: `pool-${card.orderId}`, title: `${card.orderNumber} 尚未排入車`, detail: card.storeName, kind: 'pending'});
    }
    return items;
  });

  private loadDashboard(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    forkJoin({
      orders: this.api.getOrders(),
      stores: this.api.getStores(),
      drivers: this.api.getDrivers(),
      vehicles: this.api.getVehicles(),
      warehouses: this.api.getWarehouses(),
    }).subscribe({
      next: ({orders, stores, drivers, vehicles, warehouses}) => {
        this.orders.set(orders);
        this.stores.set(stores);
        this.drivers.set(drivers);
        this.vehicles.set(vehicles);
        this.warehouses.set(warehouses);
        this.warehouseName.set(
          warehouses.find((warehouse) => warehouse.isActive)?.name ?? '高雄配送區',
        );
        this.updatedAt.set(this.formatCurrentTime());
        this.loading.set(false);

        // 預設倉庫要等清單回來才能決定，不能寫死 id。
        // 決定好之後才讀看板，否則會拿 warehouseId=0 去打 API。
        const defaultWarehouseId =
          warehouses.find((warehouse) => warehouse.isActive)?.id ?? warehouses[0]?.id ?? 0;
        this.warehouseId.set(defaultWarehouseId);
        if (defaultWarehouseId) {
          this.loadScheduleEligibility();
          this.reloadBoard();
        }
      },
      error: () => {
        this.errorMessage.set('暫時無法載入總覽資料，請稍後再試。');
        this.loading.set(false);
      },
    });
  }

  /** 背景同步回來的訂單：只換訂單（卡片狀態靠它），不動看板，避免干擾調度員正在拖曳的排車草稿 */
  private applySyncedOrders(orders: OrderDto[]): void {
    this.orders.set(orders);
    this.updatedAt.set(this.formatCurrentTime());
  }

  private formatCurrentTime(): string {
    return new Intl.DateTimeFormat('zh-TW', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date());
  }

  runOptimize(): void {
    // 排一次要 5 秒以上（OR-Tools 求解上限 + OSRM 矩陣查詢）。沒有這道防護的話，
    // 連點會讓兩個交易互相刪對方的草稿路線，第二次請求會失敗。
    if (this.busy()) {
      return;
    }

    if (this.published()) {
      return;
    }
    // 以人為準：選了司機或車的格子才拿去排；只選司機的格子由後端配車。
    // 格子裡已經有的訂單一起送，後端會把它們固定在這格的車上，不會被自動排車分到別台。
    // 格子全空時送空陣列，後端會自己挑這一倉需要的車數，配上當天能派的司機（DispatchSlotService.autoSlots）
    const slots = this.routes()
      .filter((lane) => lane.driverId !== null || lane.vehicleId !== null)
      .map((lane) => ({
        driverId: lane.driverId,
        vehicleId: lane.vehicleId,
        orderIds: lane.cards.filter((card) => this.isDispatchable(card)).map((card) => card.orderId),
      }));

    this.optimizing.set(true);
    this.boardError.set('');
    this.boardNotices.set([]);

    this.api.optimizeSlots({date: this.dispatchDate(), warehouseId: this.warehouseId(), slots}).subscribe({
      next: (result) => {
        this.applyDispatchResult(result);
        this.sortLaneStoresByRoute();
        this.boardNotices.set(result.notices ?? []);
        this.optimizing.set(false);
      },
      error: (err) => {
        console.error(err);
        this.boardError.set(err?.error?.message ?? '排車失敗，請稍後再試。');
        this.optimizing.set(false);
      },
    });
  }

  /**
   * 「套用門市訂單」：照每格門市清單的順序，把這些門市在待排單的訂單拉進那格，走 submitReassign 存檔。
   *
   * 門市順序就是配送順序：reassign 照送出去的順序存、不重排（DispatchService.reassign），
   * 所以調度員拖出來的、或按「排順序」算出來的門市順序，就是司機實際跑的順序。
   * 要今天的最佳解就按「自動排車」，那邊 OR-Tools 會重排停靠順序。
   *
   * 只從待排單拉，別格的單不搶；這格原本的單也照門市順序重排，不在清單裡的門市（手動拖進來的）接在最後。
   * 車裝滿就不再拉，剩下的留在待排單並提示。
   */
  applyStoreOrders(): void {
    if (this.busy() || this.published()) {
      return;
    }

    const notices: string[] = [];
    const pool = [...this.unassigned()];
    let pulled = 0;
    const lanes = this.routes().map((lane) => {
      if (lane.storeIds.length === 0) {
        return lane;
      }
      if (lane.vehicleId === null || lane.isMaintenance || lane.hasLockedStops) {
        const reason = lane.vehicleId === null ? '還沒選車' : lane.isMaintenance ? '車輛維修中' : '有配送中的訂單';
        notices.push(`${this.templateSlotLabel(lane.driverId, lane.vehicleId)} ${reason}，門市先不拉單`);
        return lane;
      }
      const cards = [...lane.cards];
      let room = lane.capacity - this.loadedBoxes(lane);
      for (const storeId of lane.storeIds) {
        const left: BoardCard[] = [];
        for (const card of pool.filter((item) => item.storeId === storeId)) {
          if (card.boxCount > room) {
            left.push(card);
            continue;
          }
          room -= card.boxCount;
          cards.push(card);
          pool.splice(pool.indexOf(card), 1);
          pulled++;
        }
        if (left.length > 0) {
          notices.push(`${lane.plateNumber} 裝滿，${left[0].storeName} ${left.length} 張留在待排單`);
        }
      }
      // sort 是穩定排序：同一間門市的單維持原本先後
      const rank = (card: BoardCard) => {
        const index = lane.storeIds.indexOf(card.storeId);
        return index < 0 ? lane.storeIds.length : index;
      };
      cards.sort((x, y) => rank(x) - rank(y));
      return {...lane, cards};
    });
    if (pulled === 0) {
      this.boardNotices.set(notices);
      this.boardError.set('格子裡的門市今天沒有待排的訂單。先在格子裡加入門市，或確認待排單裡有這些門市的單。');
      return;
    }

    this.routes.set(lanes);
    this.unassigned.set(pool);
    this.boardNotices.set(notices);
    this.submitReassign();
  }

  /** 正在「排順序」的格子；同時只排一格，排的時候那格的門市不能改 */
  readonly sequencingSlot = signal<string | null>(null);

  /** 「排順序」：這格的門市交給後端，照從倉庫出發最順的跑法重排。不看訂單、不寫資料庫 */
  sequenceLaneStores(route: BoardRoute): void {
    if (this.published() || this.busy() || route.storeIds.length < 2 || this.sequencingSlot() !== null) {
      return;
    }
    const sent = [...route.storeIds];
    this.sequencingSlot.set(route.slotKey);
    this.boardError.set('');
    this.api.sequenceStores(this.warehouseId(), sent).subscribe({
      next: ({storeIds}) => {
        this.routes.update((lanes) =>
          lanes.map((lane) =>
            // 排的時候門市被改過（格子被清掉、換倉）就不套用，免得蓋掉新的內容
            lane.slotKey === route.slotKey && sameMembers(lane.storeIds, sent) ? {...lane, storeIds} : lane,
          ),
        );
        this.sequencingSlot.set(null);
      },
      error: (error: unknown) => {
        this.boardError.set(describeError(error));
        this.sequencingSlot.set(null);
      },
    });
  }

  /** 拖曳門市標籤調整順序 */
  onStoreDrop(route: BoardRoute, event: CdkDragDrop<number[]>): void {
    if (this.published() || this.busy() || event.previousIndex === event.currentIndex) {
      return;
    }
    this.routes.update((lanes) =>
      lanes.map((lane) => {
        if (lane.slotKey !== route.slotKey) {
          return lane;
        }
        const storeIds = [...lane.storeIds];
        moveItemInArray(storeIds, event.previousIndex, event.currentIndex);
        return {...lane, storeIds};
      }),
    );
  }

  /**
   * 看板整個包在 cdkDropListGroup 裡，所有 drop list 會自動互通。
   * 門市標籤只能在自己那格排順序，訂單卡片也不能被拖進門市清單，兩邊各用一個 predicate 擋。
   */
  readonly ownStoreListOnly = (drag: CdkDrag, drop: CdkDropList) => drag.dropContainer === drop;
  readonly ordersOnly = (drag: CdkDrag) => drag.data !== STORE_CHIP;
  readonly storeChip = STORE_CHIP;

  /**
   * 自動排車後，門市照 OR-Tools 排出來的停靠順序重排，今天沒單的門市接在最後、維持原本的先後。
   * 門市順序就是配送順序，兩邊要一致；這時按「儲存編組」，存進去的也是算好的順序。
   */
  private sortLaneStoresByRoute(): void {
    this.routes.update((lanes) =>
      lanes.map((lane) => {
        if (lane.storeIds.length < 2) {
          return lane;
        }
        const visited = [...new Set(lane.cards.map((card) => card.storeId))].filter((id) => lane.storeIds.includes(id));
        const rest = lane.storeIds.filter((id) => !visited.includes(id));
        return {...lane, storeIds: [...visited, ...rest]};
      }),
    );
  }

  /** 這格「加入門市」的選項：營業中的門市，已在別格的 disabled 並附原因，這格已選的不列 */
  storeOptions(route: BoardRoute): StoreOption[] {
    const takenBy = new Map<number, string>();
    for (const lane of this.routes()) {
      if (lane.slotKey === route.slotKey) {
        continue;
      }
      const who = lane.driverId !== null ? this.driverName(lane.driverId) : lane.plateNumber || '其他格';
      for (const storeId of lane.storeIds) {
        takenBy.set(storeId, `已在${who}那格`);
      }
    }
    return this.stores()
      .filter(
        (store): store is StoreDto & {id: number} =>
          store.id != null && store.status === 'ACTIVE' && !route.storeIds.includes(store.id),
      )
      .map((store) => ({id: store.id, name: store.name, takenNote: takenBy.get(store.id) ?? null}));
  }

  /** 選了就加進這格的門市清單，下拉馬上歸回「＋ 加入門市」；只改畫面，不動訂單 */
  addLaneStore(route: BoardRoute, event: Event): void {
    const select = event.target as HTMLSelectElement;
    const storeId = Number(select.value);
    select.value = '';
    if (!storeId || this.published() || this.busy()) {
      return;
    }
    this.routes.update((lanes) =>
      lanes.map((lane) =>
        lane.slotKey === route.slotKey && !lane.storeIds.includes(storeId)
          ? {...lane, storeIds: [...lane.storeIds, storeId]}
          : lane,
      ),
    );
  }

  removeLaneStore(route: BoardRoute, storeId: number): void {
    if (this.published() || this.busy()) {
      return;
    }
    this.routes.update((lanes) =>
      lanes.map((lane) =>
        lane.slotKey === route.slotKey ? {...lane, storeIds: lane.storeIds.filter((id) => id !== storeId)} : lane,
      ),
    );
  }

  /** 車道目前實際載運的箱數。拖曳後會變動，所以用卡片重算而不是用後端給的值 */
  loadedBoxes(route: BoardRoute): number {
    return route.cards.reduce((sum, card) => sum + card.boxCount, 0);
  }

  /** 超過車輛容量，畫面上要標出來 */
  isOverloaded(route: BoardRoute): boolean {
    return this.loadedBoxes(route) > route.capacity;
  }

  boardCardStatus(card: BoardCard): OrderStatus {
    return this.orders().find((order) => order.id === card.orderId)?.status ?? 'CONFIRMED';
  }

  /**
   * 這張單還能不能改派：只有待送的可以。配送中、已完成、配送失敗的單照樣顯示在格子裡，
   * 但不能拖，也不送進 reassign / 自動排車（後端只收 CONFIRMED，送了會整批擋下）。
   * 不送也不會弄丟：後端重排時，有這種單的路線會原樣保留（DispatchService.clearExistingDraftRoutes）
   */
  isDispatchable(card: BoardCard): boolean {
    return this.boardCardStatus(card) === 'CONFIRMED';
  }

  boardCardStatusLabel(card: BoardCard): string {
    return this.orderStatusLabel(this.boardCardStatus(card));
  }

  boardCardDeliveryWindow(card: BoardCard): string {
    const store = this.stores().find((item) => item.id === card.storeId);
    if (!store?.receivingStart || !store.receivingEnd) {
      return '收貨時間未設定';
    }

    return `${store.receivingStart.slice(0, 5)} - ${store.receivingEnd.slice(0, 5)}`;
  }

  boardCardDescription(card: BoardCard): string {
    const order = this.orders().find((item) => item.id === card.orderId);
    return order?.itemDescription || order?.notes || '未填寫品項或備註';
  }

  routeDriverStatus(route: BoardRoute): string {
    if (route.isMaintenance) {
      return '車輛維修中';
    }
    if (route.driverId === null) {
      return '待指派司機';
    }

    const statuses = route.cards.map((card) => this.boardCardStatus(card));
    if (statuses.includes('IN_DELIVERY')) {
      return '配送中';
    }
    // 送過至少一站（完成、無人簽收、失敗都算），還有待送的就是在路上；待送的都沒了就是跑完了
    const started = statuses.some(
      (status) => status === 'COMPLETED' || status === 'NO_SIGNATURE' || status === 'FAILED',
    );
    // 已點交的貨還在車上沒送，跟還沒點交的一樣算待送
    if (statuses.includes('CONFIRMED') || statuses.includes('LOADED')) {
      return started ? '配送中' : '待出發';
    }
    if (started) {
      return '已完成';
    }

    return '尚無訂單';
  }

  private orderStatusLabel(status: OrderStatus): string {
    switch (status) {
      case 'PENDING_CONFIRM':
        return '待確認';
      case 'CONFIRMED':
        return '待調度';
      case 'LOADED':
        return '已點交';
      case 'IN_DELIVERY':
        return '配送中';
      case 'NO_SIGNATURE':
        return '無人簽收';
      case 'COMPLETED':
        return '已完成';
      case 'FAILED':
        return '配送失敗';
      case 'CANCELLED':
        return '已取消';
    }
  }

  /**
   * 這條車道的司機選項。已被佔用的不隱藏，改成 disabled 並附上原因 ——
   * 人憑空消失會讓調度員以為是系統壞了，寫明「已排在哪」才知道要去哪裡調整。
   *
   * 佔用有兩種來源：同一個倉的其他車道（看板上看得到），以及當天其他倉
   * （看板看不到，要靠後端的 driversTakenElsewhere 補）。
   *
   * 班表有問題的司機照樣列出並標上原因：草稿允許紅框，派出時才擋（DispatchGuardService）。
   * 停用的司機不列，除非他就是這條車道目前的司機，不然選單找不到對應選項會誤顯示成「未指派司機」。
   */
  driverOptions(route: BoardRoute): DriverOption[] {
    const takenHere = new Map<number, string>();
    for (const item of this.routes()) {
      if (item.slotKey !== route.slotKey && item.driverId !== null) {
        takenHere.set(item.driverId, item.plateNumber ? `已排在 ${item.plateNumber}` : '已在其他格');
      }
    }

    const takenElsewhere = new Map<number, string>();
    for (const taken of this.driversTakenElsewhere()) {
      takenElsewhere.set(taken.driverId, `已排在 ${taken.warehouseName} ${taken.plateNumber}`);
    }

    return this.drivers()
      .filter(
        (driver): driver is DriverDto & { id: number } =>
          driver.id != null && (driver.isActive || driver.id === route.driverId),
      )
      .map((driver) => ({
        id: driver.id,
        name: driver.name,
        takenNote:
          driver.id === route.driverId
            ? null
            : (takenHere.get(driver.id) ?? takenElsewhere.get(driver.id) ?? null),
        scheduleNote: this.driverScheduleNote(driver.id),
      }));
  }

  /**
   * 這格的車輛選項：這個倉目前能出車的車，已被別格選走的 disabled 並附原因。
   * 這格目前的車就算在維修也要列出，不然選單會誤顯示成「未選車」。
   */
  vehicleOptions(route: BoardRoute): VehicleOption[] {
    const usedElsewhere = new Map<number, string>();
    for (const lane of this.routes()) {
      if (lane.slotKey !== route.slotKey && lane.vehicleId !== null) {
        usedElsewhere.set(
          lane.vehicleId,
          lane.driverId !== null ? `已在 ${this.driverName(lane.driverId)} 那格` : '已被其他格選走',
        );
      }
    }
    return this.vehicles()
      .filter(
        (vehicle): vehicle is VehicleDto & {id: number} =>
          vehicle.id != null &&
          vehicle.warehouseId === this.warehouseId() &&
          (vehicle.status === 'AVAILABLE' || vehicle.id === route.vehicleId),
      )
      .map((vehicle) => ({
        id: vehicle.id,
        label: `${vehicle.plateNumber} · ${vehicle.capacity} 箱`,
        takenNote: usedElsewhere.get(vehicle.id) ?? null,
      }));
  }

  /** 選了司機、選了車或已經有訂單，就算填了 */
  private isLaneFilled(lane: BoardRoute): boolean {
    return lane.vehicleId !== null || lane.driverId !== null || lane.cards.length > 0;
  }

  private emptyLane(): BoardRoute {
    return {
      slotKey: `slot-${++this.slotSeq}`,
      routeId: 0,
      vehicleId: null,
      plateNumber: '',
      vehicleType: null,
      capacity: 0,
      driverId: null,
      totalDistance: 0,
      routeStatus: 'DRAFT',
      hasLockedStops: false,
      isMaintenance: false,
      cards: [],
      storeIds: [],
    };
  }

  /**
   * 填了的格子排前面，後面補空格，總數固定是 3 的倍數而且至少留一個空格：
   * 一開始 3 格，3 格都填了就變 6 格，以此類推。原本的空格沿用，key 不變畫面才不會閃。
   */
  private withEmptySlots(lanes: readonly BoardRoute[]): BoardRoute[] {
    const filled = lanes.filter((lane) => this.isLaneFilled(lane));
    const total = Math.max(3, Math.ceil((filled.length + 1) / 3) * 3);
    const result = [...filled];
    for (const lane of lanes) {
      if (!this.isLaneFilled(lane) && result.length < total) {
        // 人車都拿掉的格子存不進編組，門市也跟著清掉，不然會留著看不到的門市佔住「同一間只能在一格」
        result.push(lane.storeIds.length > 0 ? {...lane, storeIds: []} : lane);
      }
    }
    while (result.length < total) {
      result.push(this.emptyLane());
    }
    return result;
  }

  /** 把某格的車換成 vehicleId（null＝不選車），車牌、容量一起帶 */
  private withVehicle(lane: BoardRoute, vehicleId: number | null): BoardRoute {
    const vehicle = vehicleId === null ? undefined : this.vehicles().find((item) => item.id === vehicleId);
    return {
      ...lane,
      vehicleId,
      plateNumber: vehicle?.plateNumber ?? '',
      vehicleType: vehicle?.vehicleType ?? null,
      capacity: vehicle?.capacity ?? 0,
      isMaintenance: vehicle?.status === 'MAINTENANCE',
    };
  }

  /** 車道司機今天不能出車的原因；沒指派司機或可以出車時回傳 null。車道紅框與狀態標籤用 */
  routeScheduleProblem(route: BoardRoute): string | null {
    return route.driverId === null ? null : this.driverScheduleNote(route.driverId);
  }

  /** 還沒指派司機的車道數。發布前這個數字必須是 0 */
  readonly routesWithoutDriver = computed(
    // 只算「有載到訂單卻沒司機」的槽位；空槽本來就不需要司機
    () => this.routes().filter((route) => route.cards.length > 0 && route.driverId === null).length,
  );

  /**
   * 指派或清除司機。不另外開端點，直接走 reassign 整包送 ——
   * 因為 reassign 本來就會重建當天路線，司機沒跟著送就會被清掉。
   */
  onDriverChange(route: BoardRoute, event: Event): void {
    if (this.busy() || this.published()) {
      return;
    }

    const selected = (event.target as HTMLSelectElement).value;
    const driverId = selected === '' ? null : Number(selected);

    this.routes.update((routes) =>
      this.withEmptySlots(routes.map((item) => (item.slotKey === route.slotKey ? {...item, driverId} : item))),
    );

    // 還沒有訂單的格子只是排人車，存在畫面上就好；有訂單才是路線，要存進資料庫
    if (route.cards.length > 0) {
      this.submitReassign();
    }
  }

  /**
   * 選車或取消車。有訂單的格子換車會直接存（路線改由新車跑）；
   * 有訂單的格子不能取消車，路線一定要有車。
   */
  onVehicleChange(route: BoardRoute, event: Event): void {
    if (this.busy() || this.published()) {
      return;
    }

    const select = event.target as HTMLSelectElement;
    const vehicleId = select.value === '' ? null : Number(select.value);
    if (vehicleId === null && route.cards.length > 0) {
      this.boardError.set('這格已經有訂單，不能取消車輛；請先把訂單拖回待排單區。');
      select.value = String(route.vehicleId);
      return;
    }

    this.boardError.set('');
    this.routes.update((routes) =>
      this.withEmptySlots(
        routes.map((item) => (item.slotKey === route.slotKey ? this.withVehicle(item, vehicleId) : item)),
      ),
    );

    if (route.cards.length > 0) {
      this.submitReassign();
    }
  }

  onDrop(event: CdkDragDrop<BoardCard[]>): void {
    if (this.busy() || this.published()) {
      return;
    }

    if (event.previousContainer === event.container) {
      moveItemInArray(event.container.data, event.previousIndex, event.currentIndex);
    } else {
      transferArrayItem(
        event.previousContainer.data,
        event.container.data,
        event.previousIndex,
        event.currentIndex,
      );
    }

    // 上面兩個函式都是原地修改陣列，signal 持有的參考沒變就不會觸發重繪
    // （畫面上會看到卡片彈回原位）。這裡重建一層新參考通知 signal。
    this.routes.update((routes) => routes.map((route) => ({...route, cards: [...route.cards]})));
    this.unassigned.update((cards) => [...cards]);

    this.submitReassign();
  }

  /**
   * 把拖曳後的分派送給後端重算並存檔。
   *
   * 畫面已經先更新了（樂觀更新），所以拖完立刻看得到新的裝載率；
   * 但里程要等後端用 OSRM 算完回來才會正確。
   */
  private submitReassign(): void {
    if (this.published() || this.busy()) {
      return;
    }

    const request = this.buildReassignRequest();

    // 班表有問題照樣存：草稿允許紅框，派出時才擋（前端 publish 預檢＋後端 DispatchGuardService）

    // 訂單全部拖回待排單時 routes 是空陣列，照樣送：後端收到空陣列會清掉當天這個倉的草稿
    // （DispatchWorkflowService.reassign → clearDraftRoutes），畫面的「全空」才會真的寫進資料庫
    this.saving.set(true);
    this.boardError.set('');

    this.api.reassignDispatch(request).subscribe({
      next: (result) => {
        this.applyDispatchResult(result);
        this.saving.set(false);
      },
      error: (err) => {
        console.error(err);
        // 後端的 guard 會說明擋下的原因（已發布、配送中…），有就顯示，不然使用者不知道要怎麼處理
        const reason = err?.error?.message;
        this.boardError.set(
          reason ? `改派儲存失敗：${reason}（已還原成伺服器上的狀態）` : '改派儲存失敗，已還原成伺服器上的狀態。',
        );
        // 本地畫面已經被改過了，重讀一次以伺服器為準，避免留下沒存進去的假象
        this.reloadBoard();
      },
    });
  }

  private buildReassignRequest(): ReassignRequest {
    return {
      date: this.dispatchDate(),
      warehouseId: this.warehouseId(),
      routes: this.routes()
        // 只送待送的單；沒有待送單的格子不送：orderIds 有 @NotEmpty，沒載貨的車本來就不該有路線。
        // 已完成、配送失敗的單不送，後端會保留它們所在的路線
        .filter(
          (route): route is BoardRoute & {vehicleId: number} =>
            route.vehicleId !== null && route.cards.some((card) => this.isDispatchable(card)),
        )
        .map((route) => ({
          vehicleId: route.vehicleId,
          driverId: route.driverId,
          orderIds: route.cards.filter((card) => this.isDispatchable(card)).map((card) => card.orderId),
        })),
    };
  }

  // ── 發布 / 撤回 ───────────────────────────────────────

  /**
   * 發布當天全部倉庫的排班。後端採全有或全無：任一條路線沒指派司機就整批擋下，
   * 錯誤訊息會指出是哪幾台車，直接顯示給調度員。
   */
  publish(): void {
    if (this.published() || this.busy()) {
      return;
    }

    // 可以預先發布未來幾天；過去的日期發了司機也收不到（司機端只看當天）
    if (this.dispatchDate() < todayLocalDate()) {
      this.publishError.set('不能發布已經過去的日期。');
      return;
    }

    if (this.scheduleLoadState() !== 'ready') {
      this.publishError.set(this.scheduleMessage() || '尚未完成當日班表驗證，暫時不能發布。');
      return;
    }

    const warehouseIds = this.warehouses()
      .map((warehouse) => warehouse.id)
      .filter((warehouseId): warehouseId is number => warehouseId != null);
    if (warehouseIds.length === 0) {
      this.publishError.set('找不到可驗證的倉庫，暫時不能發布。');
      return;
    }

    this.publishing.set(true);
    this.publishError.set('');
    // 發布是跨倉動作，發布前逐倉讀取草稿，避免其他倉有休假／請假司機時仍一起送出。
    forkJoin(warehouseIds.map((warehouseId) => this.api.getDispatchBoard(this.dispatchDate(), warehouseId))).subscribe({
      next: (boards) => {
        const scheduleIssues = this.scheduleIssuesForDispatchBoards(boards);
        if (scheduleIssues.length > 0) {
          this.publishError.set(`無法發布排車：${scheduleIssues.join('；')}。`);
          this.publishing.set(false);
          return;
        }

        this.publishVerifiedDispatch();
      },
      error: (error: unknown) => {
        this.publishError.set(`無法驗證各倉班表資格：${describeError(error)}`);
        this.publishing.set(false);
      },
    });
  }

  private publishVerifiedDispatch(): void {
    this.api.publishDispatch(this.dispatchDate()).subscribe({
      next: (boards) => this.applyMyBoard(boards),
      error: (error: unknown) => {
        this.publishError.set(describeError(error));
        this.publishing.set(false);
      },
    });
  }

  /** 發布回傳跨倉的多包，挑出目前正在看的那一倉重繪。 */
  private applyMyBoard(boards: DispatchResultDto[]): void {
    const date = this.dispatchDate();
    this.publishedDates.update((dates) => new Set(dates).add(date));
    this.loadDays();
    const mine = boards.find((board) => board.warehouse.id === this.warehouseId());
    if (mine) {
      this.applyDispatchResult(mine);
    } else {
      this.reloadBoard();
    }
    this.publishing.set(false);
  }

  // ── 常配編組 ──────────────────────────────────────────

  private loadTemplates(): void {
    this.api.getTemplates().subscribe({
      next: (templates) => this.templates.set(templates),
      // 編組載不到不該擋住排車看板，靜默失敗即可
      error: () => this.templates.set([]),
    });
  }

  /**
   * 點分頁＝把編組的人車載進看板的格子。
   *
   * 只改畫面、不寫資料庫：編組是人車搭配的樣板，載進來之後要不要排車，由調度員按自動排車決定。
   * 已發布日期的編組入口會隱藏，方法本身也會拒絕載入。
   */
  selectTemplate(templateId: number): void {
    if (this.published() || this.busy()) {
      return;
    }
    this.templateError.set('');
    this.activeTemplateId.set(templateId);
    this.loadActiveTemplateIntoBoard();
  }

  /**
   * 把選中編組在目前倉庫的格子載進看板：人車，加上每格的門市清單。只改畫面、不拉單、不寫資料庫，
   * 要把門市的單拉進來按「套用門市訂單」。
   *
   * 看板上已經有同一組人車就沿用那一格，門市換成編組的；只撞到其中一個（人或車已經在別格）
   * 就略過這格，不去拆調度員排好的格子。
   * 門市已經在看板別格的也略過：同一間門市只能在一格，不然存編組時後端會擋。
   */
  loadActiveTemplateIntoBoard(): void {
    const template = this.activeTemplate();
    if (!template || this.busy()) {
      return;
    }
    if (this.published()) {
      this.templateError.set('這天已發布，看板僅供查看。');
      return;
    }

    const lanes = this.routes().filter((lane) => this.isLaneFilled(lane));
    const usedDrivers = new Set(lanes.map((lane) => lane.driverId).filter((id): id is number => id !== null));
    const usedVehicles = new Set(lanes.map((lane) => lane.vehicleId).filter((id): id is number => id !== null));
    const slots = template.routes.filter((slot) => slot.warehouseId === this.warehouseId());
    for (const slot of slots) {
      const sameIndex = lanes.findIndex((lane) => lane.driverId === slot.driverId && lane.vehicleId === slot.vehicleId);
      const storesElsewhere = new Set(
        lanes.filter((_, index) => index !== sameIndex).flatMap((lane) => lane.storeIds),
      );
      const storeIds = slot.stops.map((stop) => stop.storeId).filter((id) => !storesElsewhere.has(id));
      if (sameIndex >= 0) {
        lanes[sameIndex] = {...lanes[sameIndex], storeIds};
        continue;
      }
      if (
        (slot.driverId !== null && usedDrivers.has(slot.driverId)) ||
        (slot.vehicleId !== null && usedVehicles.has(slot.vehicleId))
      ) {
        // 人或車已經在別格，略過不提示；覆蓋編組時確認框會列出看板實際的人車
        continue;
      }
      lanes.push({...this.withVehicle({...this.emptyLane(), driverId: slot.driverId}, slot.vehicleId), storeIds});
      if (slot.driverId !== null) {
        usedDrivers.add(slot.driverId);
      }
      if (slot.vehicleId !== null) {
        usedVehicles.add(slot.vehicleId);
      }
    }

    this.routes.set(this.withEmptySlots(lanes));
    this.boardNotices.set(slots.length === 0 ? ['這個編組沒有目前倉庫的格子。'] : []);
  }

  /** 編組格子的顯示文字，例如「王小明 · TN-2001」；沒選的寫「未選司機」「未選車」 */
  templateSlotLabel(driverId: number | null, vehicleId: number | null): string {
    const who = driverId === null ? '未選司機' : this.driverName(driverId);
    const car = vehicleId === null ? '未選車' : this.vehicleLabel(vehicleId);
    return `${who} · ${car}`;
  }

  // ── 新增編組表單 ──────────────────────────────────────

  /**
   * 「＋」分頁＝從空白看板重新開始，同時也是新增編組：
   * 格子清成空的、訂單全部回待排單，人車直接在格子裡選，填名稱按「儲存編組」即可。
   *
   * 看板上有東西就先跳確認：清掉的是當天這個倉的草稿路線（訂單不會刪，回到待排單），
   * 以及還沒訂單、只存在畫面上的人車格。發布後不允許再進入新增編組流程。
   */
  openCreateTemplate(): void {
    if (this.busy() || this.published()) {
      return;
    }
    if (!this.routes().some((lane) => this.isLaneFilled(lane))) {
      this.clearBoard();
      return;
    }
    this.dialog.open(this.clearBoardDialog()).afterClosed().subscribe((ok) => {
      // 按取消是 false；點背景、按 Esc 是 undefined，只有按「直接離開」才是 true
      if (ok) {
        this.clearBoard();
      }
    });
  }

  private resetTemplateTab(): void {
    this.activeTemplateId.set(0);
    this.templateName.set('');
    this.templateError.set('');
  }

  /** 送空的 reassign 讓後端清掉當天這個倉的草稿，再用回傳結果重畫看板 */
  private clearBoard(): void {
    if (this.published() || this.busy()) {
      return;
    }
    this.resetTemplateTab();
    this.boardError.set('');
    this.boardNotices.set([]);
    // 先清掉畫面上的格子：applyDispatchResult 會保留排到一半的人車格，不清的話它們會留在新分頁上
    this.routes.set([]);
    this.saving.set(true);
    this.api.reassignDispatch({date: this.dispatchDate(), warehouseId: this.warehouseId(), routes: []}).subscribe({
      next: (result) => {
        this.applyDispatchResult(result);
        this.saving.set(false);
      },
      error: (error: unknown) => {
        this.boardError.set(describeError(error));
        this.reloadBoard();
      },
    });
  }

  updateTemplateName(event: Event): void {
    if (this.published() || this.busy()) {
      return;
    }
    this.templateName.set((event.target as HTMLInputElement).value);
  }

  /**
   * 看板格子裡的人車轉成編組內容，連同每格選的門市（格子的門市清單，不是今天的訂單）。
   * 存的是門市不是訂單：訂單綁日期，要用時按「套用門市訂單」依門市去待排單拉當天的單。
   */
  private boardSlotsAsTemplate(): TemplateRouteRequest[] {
    const warehouseId = this.warehouseId();
    return this.routes()
      .filter((lane) => lane.driverId !== null || lane.vehicleId !== null)
      .map((lane) => ({warehouseId, vehicleId: lane.vehicleId, driverId: lane.driverId, storeIds: [...lane.storeIds]}));
  }

  storeName(storeId: number): string {
    return this.stores().find((store) => store.id === storeId)?.name ?? `門市 #${storeId}`;
  }

  /**
   * 「儲存編組」唯一的入口：在「＋」分頁是新增，在編組分頁是覆蓋這個編組。
   * 判斷新增還是修改交給程式，調度員只要記得「排好就按儲存編組」。
   */
  saveTemplate(): void {
    if (this.templateBusy() || this.published() || this.busy()) {
      return;
    }
    const boardSlots = this.boardSlotsAsTemplate();
    if (boardSlots.length === 0) {
      this.templateError.set('看板上至少要有一格選了司機或車，才能儲存編組。');
      return;
    }

    const template = this.activeTemplate();
    if (template) {
      this.confirmOverwriteTemplate(template, boardSlots);
    } else {
      this.createTemplate(boardSlots);
    }
  }

  private createTemplate(routes: TemplateRouteRequest[]): void {
    if (this.published() || this.busy()) {
      return;
    }
    const name = this.templateName().trim();
    if (!name) {
      this.templateError.set('請填寫編組名稱。');
      return;
    }

    this.templateBusy.set(true);
    this.templateError.set('');
    this.api.createTemplate({name, routes}).subscribe({
      next: (created) => {
        this.templates.update((templates) => [...templates, created]);
        this.activeTemplateId.set(created.id);
        this.templateBusy.set(false);
      },
      error: (error: unknown) => {
        this.templateError.set(describeError(error));
        this.templateBusy.set(false);
      },
    });
  }

  /**
   * 覆蓋既有編組前先跳確認框，列出要存的人車。
   *
   * 點編組分頁是把人車「加」進看板，不是換掉看板：看板上本來就有的人或車不會載入，
   * 從別的編組分頁切過來也可能混著別組的人。確認框列出來，就是讓調度員在覆蓋前看得出來。
   *
   * 後端 update 會把舊的 template_routes 整批刪掉重建。看板只看得到目前這個倉，
   * 所以其他倉的格子要原樣帶回去，不然存一次就被刪掉。
   */
  private confirmOverwriteTemplate(template: TemplateDto, boardSlots: TemplateRouteRequest[]): void {
    if (this.published() || this.busy()) {
      return;
    }

    const otherWarehouseSlots: TemplateRouteRequest[] = template.routes
      .filter((slot) => slot.warehouseId !== this.warehouseId())
      .map((slot) => ({
        warehouseId: slot.warehouseId,
        vehicleId: slot.vehicleId,
        driverId: slot.driverId,
        storeIds: slot.stops.map((stop) => stop.storeId),
      }));

    const data = {
      name: template.name,
      // 例如「李冠廷 · TN-1001：東區門市 → 永康門市」，讓調度員看得到門市順序也會一起存
      labels: boardSlots.map((slot) => {
        const who = this.templateSlotLabel(slot.driverId, slot.vehicleId);
        return slot.storeIds.length === 0
          ? who
          : `${who}：${slot.storeIds.map((id) => this.storeName(id)).join(' → ')}`;
      }),
    };
    this.dialog.open(this.saveTemplateDialog(), {data}).afterClosed().subscribe((ok) => {
      // 按取消是 false；點背景、按 Esc 是 undefined，只有按「儲存」才是 true
      if (!ok || this.published() || this.busy()) {
        return;
      }
      this.templateBusy.set(true);
      this.templateError.set('');
      this.api
        .updateTemplate(template.id, {
          name: template.name,
          notes: template.notes,
          routes: [...otherWarehouseSlots, ...boardSlots],
        })
        .subscribe({
          next: (updated) => {
            this.templates.update((templates) =>
              templates.map((item) => (item.id === updated.id ? updated : item)),
            );
            this.templateBusy.set(false);
          },
          error: (error: unknown) => {
            this.templateError.set(describeError(error));
            this.templateBusy.set(false);
          },
        });
    });
  }

  deleteActiveTemplate(): void {
    const template = this.activeTemplate();
    if (!template || this.templateBusy() || this.published() || this.busy()) {
      return;
    }

    this.templateBusy.set(true);
    this.api.deleteTemplate(template.id).subscribe({
      next: () => {
        this.templates.update((templates) => templates.filter((item) => item.id !== template.id));
        this.activeTemplateId.set(0);
        this.templateBusy.set(false);
      },
      error: (error: unknown) => {
        this.templateError.set(describeError(error));
        this.templateBusy.set(false);
      },
    });
  }

  vehicleLabel(vehicleId: number): string {
    const vehicle = this.vehicles().find((item) => item.id === vehicleId);
    return vehicle ? vehicle.plateNumber : `車輛 #${vehicleId}`;
  }

  /** 讀取當月已發布班表，將選定日期的班次建立成司機 id 索引。 */
  private loadScheduleEligibility(): void {
    const targetDate = this.dispatchDate();
    this.scheduleLoadState.set('loading');
    this.scheduleMessage.set('正在同步當日班表...');
    this.shiftsByDriverId.set(new Map());

    this.api.getScheduleMonth(targetDate.slice(0, 7)).subscribe({
      next: (month) => {
        if (targetDate !== this.dispatchDate()) {
          return;
        }

        if (month.status !== 'PUBLISHED') {
          this.scheduleLoadState.set('unavailable');
          this.scheduleMessage.set('當月班表尚未發布，暫時不能指派司機。');
          return;
        }

        this.api.getScheduleMonthShifts(month.id).subscribe({
          next: (shifts) => {
            if (targetDate !== this.dispatchDate()) {
              return;
            }

            this.shiftsByDriverId.set(
              new Map(
                shifts
                  .filter((shift) => shift.workDate === targetDate)
                  .map((shift) => [shift.driverId, shift]),
              ),
            );
            this.scheduleLoadState.set('ready');
            this.scheduleMessage.set('');
          },
          error: (error: unknown) => this.markScheduleUnavailable(targetDate, error),
        });
      },
      error: (error: unknown) => this.markScheduleUnavailable(targetDate, error),
    });
  }

  private markScheduleUnavailable(targetDate: string, error: unknown): void {
    if (targetDate !== this.dispatchDate()) {
      return;
    }

    const isMissing = error instanceof HttpErrorResponse && error.status === 404;
    this.scheduleLoadState.set('unavailable');
    this.scheduleMessage.set(
      isMissing
        ? '找不到當月班表，暫時不能指派司機。'
        : `無法讀取當日班表，暫時不能指派司機（${describeError(error)}）。`,
    );
  }

  private driverScheduleNote(driverId: number): string | null {
    const driver = this.drivers().find((item) => item.id === driverId);
    if (!driver) {
      return '司機資料不存在';
    }
    if (!driver.isActive) {
      return '帳號已停用';
    }
    if (this.scheduleLoadState() === 'loading') {
      return '正在同步當日班表';
    }
    if (this.scheduleLoadState() === 'unavailable') {
      return this.scheduleMessage() || '當日班表不可用';
    }

    const shift = this.shiftsByDriverId().get(driverId);
    if (!shift) {
      return '當天未排班';
    }

    return this.shiftScheduleNote(shift.shiftType);
  }

  private shiftScheduleNote(shiftType: ShiftType): string | null {
    switch (shiftType) {
      case 'WORK':
        return null;
      case 'DAY_OFF':
        return '當天休假';
      case 'LEAVE':
        return '當天請假';
      case 'UNASSIGNED':
        return '當天尚未安排';
    }
  }

  private scheduleIssuesForDispatchBoards(boards: readonly DispatchResultDto[]): string[] {
    const assignedDriverIds = new Set(
      boards.flatMap((board) =>
        board.routes
          .filter((route) => route.driverId !== null)
          .map((route) => route.driverId!),
      ),
    );

    return [...assignedDriverIds].flatMap((driverId) => {
      const note = this.driverScheduleNote(driverId);
      return note ? [`${this.driverName(driverId)}${note}`] : [];
    });
  }

  protected driverName(driverId: number): string {
    return this.drivers().find((driver) => driver.id === driverId)?.name ?? `司機 #${driverId}`;
  }

  private reloadBoard(): void {
    this.api.getDispatchBoard(this.dispatchDate(), this.warehouseId()).subscribe({
      next: (result) => {
        this.applyDispatchResult(result);
        this.saving.set(false);
      },
      error: () => {
        this.boardError.set('無法重新讀取排車結果，請重新整理頁面。');
        this.saving.set(false);
      },
    });
  }

  private applyDispatchResult(result: DispatchResultDto): void {
    // 原始結果留著不動，之後要做「調整前後差異」時當作比較基準
    this.dispatchResult.set(result);

    // 格子：後端有路線的車各一格，再加上調度員排到一半、還沒有路線的人車格，最後補空格。
    const dispatchableOrderIds = new Set(
      this.orders()
        .filter((order) => order.status === 'CONFIRMED')
        .map((order) => order.id),
    );
    // 已點交、配送中的單：貨已經在車上，後端不讓撤回重排，所以有這種單的格子整格鎖住
    const deliveringOrderIds = new Set(
      this.orders()
        .filter((order) => order.status === 'LOADED' || order.status === 'IN_DELIVERY')
        .map((order) => order.id),
    );
    const vehicleById = new Map(
      this.vehicles()
        .filter((vehicle): vehicle is VehicleDto & {id: number} => vehicle.id != null)
        .map((vehicle) => [vehicle.id, vehicle]),
    );
    const isBoardVehicle = (vehicleId: number): boolean => {
      const vehicle = vehicleById.get(vehicleId);
      return vehicle !== undefined && vehicle.warehouseId === this.warehouseId() && vehicle.status !== 'RETIRED';
    };
    // 換倉或換日期時，排到一半的格子不能帶過來
    const previous =
      this.boardWarehouseId === this.warehouseId() && this.boardDate === result.date ? this.routes() : [];
    this.boardWarehouseId = this.warehouseId();
    this.boardDate = result.date;

    const serverLanes: BoardRoute[] = result.routes
      .filter((route) => isBoardVehicle(route.vehicleId))
      .map((route) => {
        const vehicle = vehicleById.get(route.vehicleId)!;
        const routeStops = route.stops ?? [];
        const isMaintenance = vehicle.status === 'MAINTENANCE';
        return {
          // 同一台車沿用原本的 key，畫面不會整格重畫
          slotKey: previous.find((lane) => lane.vehicleId === route.vehicleId)?.slotKey ?? `slot-${++this.slotSeq}`,
          routeId: route.routeId,
          vehicleId: route.vehicleId,
          plateNumber: vehicle.plateNumber,
          vehicleType: vehicle.vehicleType ?? null,
          capacity: vehicle.capacity,
          driverId: route.driverId ?? null,
          totalDistance: route.totalDistance ?? 0,
          routeStatus: route.status ?? 'DRAFT',
          hasLockedStops: routeStops.some((stop) => deliveringOrderIds.has(stop.orderId)),
          isMaintenance,
          // 路線上的單全部顯示，包含配送中、已完成、配送失敗：派出後要看得到送到哪裡。
          // 只有待送（CONFIRMED）的能拖、會送進 reassign，其他的只顯示（見 isDispatchable）
          cards: routeStops.map(toBoardCard),
          // 門市清單只在畫面上，後端不知道；沿用原本那格的。只選司機的格子被後端配了車，要用司機對回去
          storeIds:
            (previous.find((lane) => lane.vehicleId === route.vehicleId) ??
              previous.find((lane) => lane.vehicleId === null && lane.driverId !== null && lane.driverId === route.driverId))
              ?.storeIds ?? [],
        };
      });

    // 排到一半的人車格：這次結果沒有用到它的人或車，就留在畫面上（訂單以後端為準，一律清空）。
    // 例如 OR-Tools 沒用到的車、只排了人還沒排車的格子、訂單全被拖走的格子。
    const usedDrivers = new Set(
      serverLanes.map((lane) => lane.driverId).filter((id): id is number => id !== null),
    );
    const usedVehicles = new Set(serverLanes.map((lane) => lane.vehicleId));
    const draftLanes: BoardRoute[] = previous
      .filter((lane) => lane.driverId !== null || lane.vehicleId !== null)
      .filter((lane) => !(lane.driverId !== null && usedDrivers.has(lane.driverId)))
      .filter((lane) => !(lane.vehicleId !== null && usedVehicles.has(lane.vehicleId)))
      .map((lane) => ({
        ...lane,
        routeId: 0,
        cards: [],
        totalDistance: 0,
        routeStatus: 'DRAFT' as RouteStatus,
        hasLockedStops: false,
      }));
    this.routes.set(this.withEmptySlots([...serverLanes, ...draftLanes]));

    // 報廢車（或已調走的車）不畫成格子，但其中仍待排的訂單必須保留給其他車。
    const strandedOrders = result.routes
      .filter((route) => !isBoardVehicle(route.vehicleId))
      .flatMap((route) => route.stops)
      .filter((stop) => dispatchableOrderIds.has(stop.orderId));
    const unassignedByOrderId = new Map(
      [...result.unassignedOrders, ...strandedOrders]
        .filter((order) => dispatchableOrderIds.has(order.orderId))
        .map((order) => [order.orderId, order]),
    );
    this.unassigned.set([...unassignedByOrderId.values()].map(toBoardCard));
    this.pendingConfirm.set((result.pendingConfirmOrders ?? []).map(toBoardCard));
    this.driversTakenElsewhere.set(result.driversTakenElsewhere ?? []);
    this.loadRouteMetrics(this.routes());
    // 排車、改派、發布都會改到這天的狀態；推播斷線時日期列也不會停在舊的
    this.loadDays();
  }

  private loadRouteMetrics(routes: readonly BoardRoute[]): void {
    const routeIds = [...new Set(routes
      .filter((route) => route.routeId > 0 && route.driverId !== null && !route.isMaintenance)
      .map((route) => route.routeId))];
    if (routeIds.length === 0) {
      this.routeMetricsByRouteId.set(new Map());
      return;
    }

    forkJoin(routeIds.map((routeId) => this.api.getRouteMetrics(routeId).pipe(catchError(() => of(null))))).subscribe(
      (metrics) => {
        const next = new Map<number, RouteMetricsDto>();
        metrics.forEach((metric) => {
          if (metric) {
            next.set(metric.routeId, metric);
          }
        });
        this.routeMetricsByRouteId.set(next);
      },
    );
  }

  private formatEstimatedArrival(value: string | null | undefined): string {
    if (!value) {
      return '資料同步中';
    }
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? value
      : new Intl.DateTimeFormat('zh-TW', {hour: '2-digit', minute: '2-digit', hour12: false}).format(date);
  }

  private formatKm(value: number | null | undefined): string {
    return value == null ? '資料同步中' : `${value.toFixed(1)} km`;
  }

  private formatFuel(value: number | null | undefined): string {
    return value == null ? '資料同步中' : `${value.toFixed(1)} L`;
  }
}

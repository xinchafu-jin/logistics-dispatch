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
import {Component, computed, DestroyRef, effect, inject, OnInit, signal, TemplateRef, viewChild} from '@angular/core';
import {takeUntilDestroyed, toObservable, toSignal} from '@angular/core/rxjs-interop';
import {catchError, forkJoin, of, switchMap, timer} from 'rxjs';
import {LiveFleetMap, MapPoint, RouteLine} from '../../components/live-fleet-map/live-fleet-map';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DispatchBoardEventsService} from '../../../../core/services/dispatch-board-events.service';
import {DispatchHeaderService} from '../../../../core/services/dispatch-header.service';
import {
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

type TaskboardColumnId = 'pending' | 'confirmed' | 'delivering' | 'completed' | 'failed' | 'cancelled';

interface TaskboardColumn {
  id: TaskboardColumnId;
  label: string;
  detail: string;
  status: OrderStatus;
  count: number;
}

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
  /** 「套用門市訂單」送出中 */
  readonly applyingStores = signal(false);
  private readonly api = inject(DispatchApiService);
  private readonly boardEvents = inject(DispatchBoardEventsService);
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
  /** 即時看板只同步訂單，避免背景更新干擾調度員正在拖曳的排車草稿。 */
  readonly taskboardSyncing = signal(false);
  readonly taskboardSyncError = signal('');
  readonly taskboardLastSyncedAt = signal('--:--');

  // ── 常配編組 ──────────────────────────────────────────
  readonly templates = signal<TemplateDto[]>([]);
  /** 目前選中的編組分頁；0 代表沒有選任何編組 */
  readonly activeTemplateId = signal(0);
  /** 依格子自動排車、載入編組時，沒完全照要求完成的地方（配了哪台車、哪位司機沒帶入…） */
  readonly boardNotices = signal<string[]>([]);
  /** 格子 key 的流水號 */
  private slotSeq = 0;
  /** 目前看板屬於哪個倉；換倉時不能沿用上一個倉排到一半的格子 */
  private boardWarehouseId = 0;
  readonly templateName = signal('');
  readonly templateError = signal('');
  readonly templateBusy = signal(false);

  // ── 發布 ──────────────────────────────────────────────
  readonly publishing = signal(false);
  readonly publishError = signal('');

  /** 當天這個倉只要有一條路線已發布，整體就視為已發布狀態 */
  readonly published = computed(() =>
    this.routes().some((route) => route.routeStatus === 'PUBLISHED'),
  );

  readonly activeTemplate = computed(
    () => this.templates().find((item) => item.id === this.activeTemplateId()) ?? null,
  );
  readonly warehouseName = signal('高雄配送區');
  private readonly header = inject(DispatchHeaderService);
  // 倉庫名稱與更新時間顯示在頂部欄（dispatch-shell），這頁本身不再畫；兩個 signal 任一變了就同步過去
  private readonly syncHeaderMeta = effect(() => {
    this.header.meta.set(`${this.warehouseName()} · 資料更新於 ${this.updatedAt()}`);
  });

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

  /** 今日訂單的追蹤看板。六欄直接對應後端訂單狀態，只供主管查看。 */
  readonly taskboardColumns = computed<TaskboardColumn[]>(() => {
    const publishedOrderIds = new Set(
      this.routes()
        .filter((route) => route.routeStatus === 'PUBLISHED')
        .flatMap((route) => route.cards.map((card) => card.orderId)),
    );
    const orders = this.orders()
      .filter((order) =>
        order.deliveryDate === this.dispatchDate() && order.id != null && publishedOrderIds.has(order.id),
      )
      .sort((left, right) => (left.sequence ?? Number.MAX_SAFE_INTEGER) - (right.sequence ?? Number.MAX_SAFE_INTEGER));
    const columns: Array<Omit<TaskboardColumn, 'count'> & {matches: (order: OrderDto) => boolean}> = [
      {id: 'pending', label: '待確認', detail: '等待總部確認', status: 'PENDING_CONFIRM', matches: (order) => order.status === 'PENDING_CONFIRM'},
      {id: 'confirmed', label: '待調度', detail: '已確認等待出發', status: 'CONFIRMED', matches: (order) => order.status === 'CONFIRMED'},
      {id: 'delivering', label: '配送中', detail: '正在配送', status: 'IN_DELIVERY', matches: (order) => order.status === 'IN_DELIVERY'},
      {id: 'completed', label: '已完成', detail: '今日已簽收', status: 'COMPLETED', matches: (order) => order.status === 'COMPLETED'},
      {id: 'failed', label: '配送失敗', detail: '需要處理', status: 'FAILED', matches: (order) => order.status === 'FAILED'},
      {id: 'cancelled', label: '已取消', detail: '不再配送', status: 'CANCELLED', matches: (order) => order.status === 'CANCELLED'},
    ];

    return columns.map(({matches, ...column}) => ({
      ...column,
      count: orders.filter(matches).length,
    }));
  });

  /** 已派出的任務才進追蹤看板，草稿排車不會提前出現在主管畫面。 */
  readonly publishedTaskboardRoutes = computed(() =>
    this.routes().filter((route) => route.routeStatus === 'PUBLISHED' && route.cards.length > 0),
  );

  readonly publishedTemplateName = computed(() => this.activeTemplate()?.name ?? '今日手動排車');

  /** 排車中或改派儲存中都不該再觸發排車 */
  readonly busy = computed(() => this.optimizing() || this.applyingStores() || this.saving());

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
  private readonly livePings = toSignal(
    toObservable(this.showDriverPoints).pipe(
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
    // 離開這頁就清掉頂部欄的資訊，不然切到別頁還會顯示這頁的倉庫與更新時間
    this.destroyRef.onDestroy(() => this.header.meta.set(null));
    this.loadDashboard();
    // 跟總覽分開打：編組載不到不該讓整個看板空白
    this.loadTemplates();
    // AI 清單在聊天面板確認後，資料庫已經換了，重讀一次才不會用舊畫面蓋回去
    this.boardEvents.boardChanged$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.reloadBoard());
    timer(15_000, 15_000)
      .pipe(
        switchMap(() => this.api.getOrders().pipe(catchError(() => of<OrderDto[] | null>(null)))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((orders) => {
        if (orders) {
          this.applyTaskboardOrders(orders);
        }
      });
  }

  refreshTaskboard(): void {
    if (this.taskboardSyncing()) {
      return;
    }

    this.taskboardSyncing.set(true);
    this.taskboardSyncError.set('');
    this.api.getOrders().subscribe({
      next: (orders) => {
        this.applyTaskboardOrders(orders);
        this.taskboardSyncing.set(false);
      },
      error: () => {
        this.taskboardSyncError.set('暫時無法同步訂單狀態。');
        this.taskboardSyncing.set(false);
      },
    });
  }

  onWarehouseChange(event: Event): void {
    this.warehouseId.set(Number((event.target as HTMLSelectElement).value));
    this.boardNotices.set([]);
    this.reloadBoard();
  }

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

  private applyTaskboardOrders(orders: OrderDto[]): void {
    this.orders.set(orders);
    const syncedAt = this.formatCurrentTime();
    this.taskboardLastSyncedAt.set(syncedAt);
    this.updatedAt.set(syncedAt);
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
    // 格子裡已經有的訂單一起送，後端會把它們固定在這格的車上，不會被自動排車分到別台
    const slots = this.routes()
      .filter((lane) => lane.driverId !== null || lane.vehicleId !== null)
      .map((lane) => ({
        driverId: lane.driverId,
        vehicleId: lane.vehicleId,
        orderIds: lane.cards.map((card) => card.orderId),
      }));
    if (slots.length === 0) {
      this.boardError.set('先在格子裡選司機或車，再自動排車。');
      return;
    }

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
   * 「套用門市訂單」：把每格門市在待排單的訂單拉進那格，交給後端只排這些單。
   *
   * 停靠順序由後端算（OSRM 實際道路距離 + OR-Tools），不用調度員排：
   * 哪幾間門市今天有單每天都不一樣，最順的順序也跟著變，存死一個順序反而常常不是最佳解。
   *
   * 送 pinnedOnly，後端只排固定的單，其他待排單不動；格子原本就有的單也一起固定送出，
   * 因為後端排之前會先清掉當天草稿，沒送的單會被退回待排單。
   * 車裝滿就不再拉，剩下的留在待排單並提示；後端遇到固定的箱數超過容量會整批擋下。
   */
  applyStoreOrders(): void {
    if (this.busy() || this.published()) {
      return;
    }

    const notices: string[] = [];
    const pool = [...this.unassigned()];
    const orderIdsByLane = new Map<string, number[]>();
    let pulled = 0;
    for (const lane of this.routes()) {
      if (lane.vehicleId === null) {
        if (lane.storeIds.length > 0) {
          notices.push(`${this.templateSlotLabel(lane.driverId, lane.vehicleId)} 還沒選車，門市先不拉單`);
        }
        continue;
      }
      const orderIds = lane.cards.map((card) => card.orderId);
      if (!lane.isMaintenance && !lane.hasLockedStops) {
        let room = lane.capacity - this.loadedBoxes(lane);
        for (const storeId of lane.storeIds) {
          const left: BoardCard[] = [];
          for (const card of pool.filter((item) => item.storeId === storeId)) {
            if (card.boxCount > room) {
              left.push(card);
              continue;
            }
            room -= card.boxCount;
            orderIds.push(card.orderId);
            pool.splice(pool.indexOf(card), 1);
            pulled++;
          }
          if (left.length > 0) {
            notices.push(`${lane.plateNumber} 裝滿，${left[0].storeName} ${left.length} 張留在待排單`);
          }
        }
      }
      if (orderIds.length > 0) {
        orderIdsByLane.set(lane.slotKey, orderIds);
      }
    }
    if (pulled === 0) {
      this.boardNotices.set(notices);
      this.boardError.set('格子裡的門市今天沒有待排的訂單。先在格子裡加入門市，或確認待排單裡有這些門市的單。');
      return;
    }

    // 只送有單的格子：沒單的格子送出去，後端會替它配車、排不到單又多一句「沒有排到訂單」，
    // 沒送的人車格 applyDispatchResult 會原樣留在畫面上
    const slots = this.routes()
      .filter((lane) => orderIdsByLane.has(lane.slotKey))
      .map((lane) => ({driverId: lane.driverId, vehicleId: lane.vehicleId, orderIds: orderIdsByLane.get(lane.slotKey)!}));

    this.applyingStores.set(true);
    this.boardError.set('');
    this.boardNotices.set([]);
    this.api
      .optimizeSlots({date: this.dispatchDate(), warehouseId: this.warehouseId(), slots, pinnedOnly: true})
      .subscribe({
        next: (result) => {
          this.applyDispatchResult(result);
          this.sortLaneStoresByRoute();
          this.boardNotices.set([...notices, ...(result.notices ?? [])]);
          this.applyingStores.set(false);
        },
        error: (error: unknown) => {
          this.boardError.set(describeError(error));
          this.applyingStores.set(false);
        },
      });
  }

  /**
   * 門市照後端排出來的停靠順序重排，今天沒單的門市接在最後、維持原本的先後。
   * 這時按「儲存編組」，存進去的就是算好的順序，下次打開比較接近實際跑法。
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
    if (!storeId || this.published()) {
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
    if (this.published()) {
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
    if (statuses.includes('CONFIRMED')) {
      return '待出發';
    }
    if (statuses.includes('COMPLETED')) {
      return '已完成';
    }

    return '尚無訂單';
  }

  taskboardRouteProgress(route: BoardRoute): string {
    const completed = route.cards.filter((card) => this.boardCardStatus(card) === 'COMPLETED').length;
    return `${completed}/${route.cards.length} 已完成`;
  }

  taskboardEstimatedArrival(route: BoardRoute): string {
    return this.formatEstimatedArrival(this.routeMetricsByRouteId().get(route.routeId)?.estimatedNextArrivalAt);
  }

  taskboardRemainingKm(route: BoardRoute): string {
    return this.formatKm(this.routeMetricsByRouteId().get(route.routeId)?.remainingKm);
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
    if (this.saving() || this.published()) {
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
    if (this.saving() || this.published()) {
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
    if (this.saving() || this.published()) {
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
        this.boardError.set('改派儲存失敗，已還原成伺服器上的狀態。');
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
        // 空車道不送：orderIds 有 @NotEmpty，而且沒載貨的車本來就不該有路線；有訂單的格子一定有車
        .filter((route): route is BoardRoute & {vehicleId: number} => route.cards.length > 0 && route.vehicleId !== null)
        .map((route) => ({
          vehicleId: route.vehicleId,
          driverId: route.driverId,
          orderIds: route.cards.map((card) => card.orderId),
        })),
    };
  }

  // ── 發布 / 撤回 ───────────────────────────────────────

  /**
   * 發布當天全部倉庫的排班。後端採全有或全無：任一條路線沒指派司機就整批擋下，
   * 錯誤訊息會指出是哪幾台車，直接顯示給調度員。
   */
  publish(): void {
    if (this.publishing() || this.busy()) {
      return;
    }

    if (this.dispatchDate() !== todayLocalDate()) {
      this.publishError.set('只能發布今天的配送任務。');
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

  /** 撤回後路線回到草稿，才能再排車；代價是保護消失，下次排車會被清掉重建。 */
  withdraw(): void {
    if (this.publishing() || this.busy()) {
      return;
    }

    this.publishing.set(true);
    this.publishError.set('');
    this.api.withdrawDispatch(this.dispatchDate()).subscribe({
      next: (boards) => this.applyMyBoard(boards),
      error: (error: unknown) => {
        this.publishError.set(describeError(error));
        this.publishing.set(false);
      },
    });
  }

  /** 發布／撤回／套用編組都回傳跨倉的多包，挑出目前正在看的那一倉重繪 */
  private applyMyBoard(boards: DispatchResultDto[]): void {
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
   * 已派出的日子看板鎖定，分頁照樣能點開看、能編輯，只是不能載入，撤回後才能載入。
   */
  selectTemplate(templateId: number): void {
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
    if (!template || this.saving()) {
      return;
    }
    if (this.published()) {
      this.templateError.set('今天已派出，看板鎖定中；撤回後才能載入編組。');
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
   * 以及還沒訂單、只存在畫面上的人車格。已派出時看板鎖定，只切分頁不清。
   */
  openCreateTemplate(): void {
    if (this.saving()) {
      return;
    }
    if (this.published()) {
      this.resetTemplateTab();
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
    if (this.templateBusy()) {
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
      if (!ok) {
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
    if (!template || this.templateBusy()) {
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
      return '今天未排班';
    }

    return this.shiftScheduleNote(shift.shiftType);
  }

  private shiftScheduleNote(shiftType: ShiftType): string | null {
    switch (shiftType) {
      case 'WORK':
        return null;
      case 'DAY_OFF':
        return '今天休假';
      case 'LEAVE':
        return '今天請假';
      case 'UNASSIGNED':
        return '今天尚未安排';
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
    const vehicleById = new Map(
      this.vehicles()
        .filter((vehicle): vehicle is VehicleDto & {id: number} => vehicle.id != null)
        .map((vehicle) => [vehicle.id, vehicle]),
    );
    const isBoardVehicle = (vehicleId: number): boolean => {
      const vehicle = vehicleById.get(vehicleId);
      return vehicle !== undefined && vehicle.warehouseId === this.warehouseId() && vehicle.status !== 'RETIRED';
    };
    // 換倉時，上一個倉排到一半的格子不能帶過來
    const previous = this.boardWarehouseId === this.warehouseId() ? this.routes() : [];
    this.boardWarehouseId = this.warehouseId();

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
          hasLockedStops: routeStops.some((stop) => !dispatchableOrderIds.has(stop.orderId)),
          isMaintenance,
          cards: (isMaintenance
            ? routeStops
            : routeStops.filter((stop) => dispatchableOrderIds.has(stop.orderId)))
            .map(toBoardCard),
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
    this.driversTakenElsewhere.set(result.driversTakenElsewhere ?? []);
    this.loadRouteMetrics(this.routes());
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

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
import {Component, computed, DestroyRef, inject, OnInit, signal} from '@angular/core';
import {takeUntilDestroyed, toObservable, toSignal} from '@angular/core/rxjs-interop';
import {catchError, forkJoin, of, switchMap, timer} from 'rxjs';
import {LiveFleetMap, MapPoint, RouteLine} from '../../components/live-fleet-map/live-fleet-map';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
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
 * 看板上的一格車輛槽位。
 *
 * 每台車固定一格，沒排到訂單就是空槽（routeId 為 0）——不能只畫「已有路線的車」，
 * 否則空白盤面上完全沒有可拖曳的目標。
 */
interface BoardRoute {
  /** 0 代表這台車今天還沒有路線 */
  routeId: number;
  vehicleId: number;
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
  imports: [LiveFleetMap, DecimalPipe, CdkDropListGroup, CdkDropList, CdkDrag, MatSlideToggleModule, MatIconModule],
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
  private readonly api = inject(DispatchApiService);
  private readonly destroyRef = inject(DestroyRef);
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
  /** 加號分頁：空白編成表模式，盤面清空等使用者編 */
  readonly creatingTemplate = signal(false);
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

  readonly tickerMessages = computed(() => {
    const orders = this.orders();
    const waitingSchedule = orders.filter((order) => order.status === 'CONFIRMED').length;
    const delivering = orders.filter((order) => order.status === 'IN_DELIVERY').length;

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
      {id: 'failed', label: '配送異常', detail: '失敗或無人簽收', status: 'FAILED', matches: (order) => order.status === 'FAILED' || order.status === 'NO_SIGNATURE'},
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

  /** 這個倉庫今天能出的車。排車不給勾選，這裡只是讓調度員知道手上有什麼 */
  readonly availableVehicles = computed(() =>
    this.vehicles().filter(
      (vehicle) => vehicle.warehouseId === this.warehouseId() && vehicle.status === 'AVAILABLE',
    ),
  );

  readonly availableVehicleSummary = computed(() =>
    this.availableVehicles()
      .map((vehicle) => `${vehicle.plateNumber} ${vehicle.capacity}箱`)
      .join('、'),
  );

  /** 排車中或改派儲存中都不該再觸發排車 */
  readonly busy = computed(() => this.optimizing() || this.saving());

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
        && (order.status === 'CONFIRMED' || order.status === 'IN_DELIVERY'),
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
        const nextStore = storesById.get(order.storeId);
        const details = [
          `目前任務 ${nextStore?.name ?? `門市 #${order.storeId}`} · 訂單 ${order.orderNumber}`,
          `配送狀態 ${this.orderStatusLabel(order.status)}`,
          `預估抵達 ${this.formatEstimatedArrival(metrics?.estimatedNextArrivalAt)}`,
          `預估里程 ${this.formatKm(metrics?.remainingKm)}`,
          `預估油耗 ${this.formatFuel(metrics?.gpsEstimatedFuelLiters)}`,
          `${minutesAgo(ping.timestamp)} 分鐘前回報 GPS`,
        ];
        return {
          id: ping.driverId,
          label: nameById.get(ping.driverId) ?? `司機 #${ping.driverId}`,
          detail: details[0],
          details,
          lat: ping.lat,
          lng: ping.lng,
          heading: nextStore?.lat != null && nextStore.lng != null
            ? this.directionTo(ping.lat, ping.lng, nextStore.lat, nextStore.lng)
            : undefined,
        };
      });
  });

  ngOnInit(): void {
    this.loadDashboard();
    // 跟總覽分開打：編組載不到不該讓整個看板空白
    this.loadTemplates();
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

    this.optimizing.set(true);
    this.boardError.set('');

    // 不指定車輛，由後端取該倉所有可用車，交給 OR-Tools 決定實際出幾台
    this.api.optimizeDispatch(this.dispatchDate(), this.warehouseId()).subscribe({
      next: (result) => {
        this.applyDispatchResult(result);
        this.optimizing.set(false);
      },
      error: (err) => {
        console.error(err);
        this.boardError.set(err?.error?.message ?? '排車失敗，請稍後再試。');
        this.optimizing.set(false);
      },
    });
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

  private directionTo(fromLat: number, fromLng: number, toLat: number, toLng: number): number {
    const radians = (value: number) => value * Math.PI / 180;
    const longitudeDifference = radians(toLng - fromLng);
    const fromLatitude = radians(fromLat);
    const toLatitude = radians(toLat);
    const y = Math.sin(longitudeDifference) * Math.cos(toLatitude);
    const x = Math.cos(fromLatitude) * Math.sin(toLatitude)
      - Math.sin(fromLatitude) * Math.cos(toLatitude) * Math.cos(longitudeDifference);
    return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  }

  private orderStatusLabel(status: OrderStatus): string {
    switch (status) {
      case 'PENDING_CONFIRM':
        return '待確認';
      case 'CONFIRMED':
        return '待調度';
      case 'IN_DELIVERY':
        return '配送中';
      case 'COMPLETED':
        return '已完成';
      case 'FAILED':
        return '配送失敗';
      case 'NO_SIGNATURE':
        return '無人簽收';
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
   * 停用或當日無法排班的司機不會出現在今日調度，避免被重新指派。
   */
  driverOptions(route: BoardRoute): DriverOption[] {
    const takenHere = new Map<number, string>();
    for (const item of this.routes()) {
      if (item.routeId !== route.routeId && item.driverId !== null) {
        takenHere.set(item.driverId, `已排在 ${item.plateNumber}`);
      }
    }

    const takenElsewhere = new Map<number, string>();
    for (const taken of this.driversTakenElsewhere()) {
      takenElsewhere.set(taken.driverId, `已排在 ${taken.warehouseName} ${taken.plateNumber}`);
    }

    return this.drivers()
      .filter(
        (driver): driver is DriverDto & { id: number } =>
          driver.id != null && this.isDriverDispatchable(driver.id),
      )
      .map((driver) => ({
        id: driver.id,
        name: driver.name,
        takenNote:
          driver.id === route.driverId
            ? null
            : (takenHere.get(driver.id) ?? takenElsewhere.get(driver.id) ?? null),
      }));
  }

  /** 僅限啟用且當天有 WORK 班次的司機能出現在今日調度。 */
  isDriverDispatchable(driverId: number | null): boolean {
    return driverId !== null && this.driverScheduleNote(driverId) === null;
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
    if (this.saving()) {
      return;
    }

    const selected = (event.target as HTMLSelectElement).value;
    const driverId = selected === '' ? null : Number(selected);
    const scheduleNote = driverId === null ? null : this.driverScheduleNote(driverId);

    // DOM 的 disabled option 已防住一般操作；這道檢查則保護鍵盤操作與日後的其他呼叫點。
    if (driverId !== null && scheduleNote) {
      this.boardError.set(`無法指派司機：${this.driverName(driverId)}${scheduleNote}。`);
      (event.target as HTMLSelectElement).value = route.driverId === null ? '' : String(route.driverId);
      return;
    }

    this.routes.update((routes) =>
      routes.map((item) => (item.routeId === route.routeId ? {...item, driverId} : item)),
    );

    this.submitReassign();
  }

  onDrop(event: CdkDragDrop<BoardCard[]>): void {
    if (this.saving()) {
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

    const scheduleIssues = this.scheduleIssuesForRoutes(this.routes());
    if (scheduleIssues.length > 0) {
      this.boardError.set(`無法儲存排車：${scheduleIssues.join('；')}。`);
      // 拖曳採樂觀更新；資格不符時重讀伺服器資料，避免畫面留下未儲存的排列。
      this.reloadBoard();
      return;
    }

    // 全部訂單都被拖到未排入池時沒有東西可送。後端的 routes 有 @NotEmpty，
    // 送出去只會拿到 400，所以在這裡就停住。
    if (request.routes.length === 0) {
      this.boardError.set('至少要有一台車載到訂單才能儲存。');
      return;
    }

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
        // 空車道不送：後端會擋，而且沒載貨的車本來就不該有路線
        .filter((route) => route.cards.length > 0)
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
   * 點分頁＝直接套用該編組。
   *
   * 套用是破壞性的（清掉當天該倉草稿再重建），所以切換分頁會放棄
   * 目前看板上未儲存的手動調整 —— 這是刻意的：分頁代表「今天照這個編組跑」。
   */
  selectTemplate(templateId: number): void {
    this.creatingTemplate.set(false);
    this.templateError.set('');
    if (this.activeTemplateId() === templateId) {
      return;
    }

    this.activeTemplateId.set(templateId);
    this.applyActiveTemplate();
  }

  /**
   * 套用選中的編組。會清掉當天該倉的草稿路線並重建，
   * 所以按下去等於放棄目前看板上的手動調整。
   */
  applyActiveTemplate(): void {
    const templateId = this.activeTemplateId();
    if (!templateId || this.templateBusy()) {
      return;
    }

    this.templateBusy.set(true);
    this.templateError.set('');
    this.api.applyTemplate(templateId, this.dispatchDate()).subscribe({
      next: (boards) => {
        // 編組可跨倉，回傳是多包；挑出目前正在看的那一倉
        const mine = boards.find((board) => board.warehouse.id === this.warehouseId());
        if (mine) {
          this.applyDispatchResult(mine);
        } else {
          // 這個編組今天在本倉沒排到任何路線（門市都沒單）
          this.templateError.set('本倉當天沒有可排入的訂單。');
          this.reloadBoard();
        }
        this.templateBusy.set(false);
      },
      error: (error: unknown) => {
        this.templateError.set(describeError(error));
        this.templateBusy.set(false);
      },
    });
  }

  // ── 新增編組表單 ──────────────────────────────────────

  /**
   * 開一張空白編成表：把盤面上所有訂單收回未排入池，車輛槽位全部清空。
   *
   * 只改前端狀態、不打後端 —— reassign 的 routes 有 @NotEmpty，空盤送不出去。
   * 等使用者拖第一張卡（或按一鍵編組）才會真的寫進資料庫。
   */
  openCreateTemplate(): void {
    this.activeTemplateId.set(0);
    this.creatingTemplate.set(true);
    this.templateName.set('');
    this.templateError.set('');
    this.clearBoard();
  }

  closeCreateTemplate(): void {
    this.creatingTemplate.set(false);
    this.templateError.set('');
    this.reloadBoard();
  }

  updateTemplateName(event: Event): void {
    this.templateName.set((event.target as HTMLInputElement).value);
  }

  /** 把所有已排入的卡片收回未排入池，槽位留著但清空 */
  private clearBoard(): void {
    const pooled: BoardCard[] = [...this.unassigned()];
    for (const lane of this.routes()) {
      pooled.push(...lane.cards);
    }
    this.routes.update((lanes) => lanes.map((lane) => ({...lane, cards: [], driverId: null})));
    this.unassigned.set(pooled);
  }

  /**
   * 把目前盤面存成編組。
   *
   * 編組記的是「哪台車跑哪幾間門市」，所以只取卡片的 storeId；
   * 同一間門市在同一條線出現多張單時要去重，後端有
   * uk_template_stops(template_route_id, store_id) 擋重複。
   */
  saveTemplate(): void {
    const name = this.templateName().trim();
    if (!name) {
      this.templateError.set('請填寫編組名稱。');
      return;
    }

    const warehouseId = this.warehouseId();
    const routes: TemplateRouteRequest[] = this.routes()
      .filter((lane) => lane.cards.length > 0)
      .map((lane) => ({
        warehouseId,
        vehicleId: lane.vehicleId,
        storeIds: [...new Set(lane.cards.map((card) => card.storeId))],
      }));

    if (routes.length === 0) {
      this.templateError.set('至少要有一台車排到訂單，才能存成編組。');
      return;
    }

    this.templateBusy.set(true);
    this.api.createTemplate({name, routes}).subscribe({
      next: (created) => {
        this.templates.update((templates) => [...templates, created]);
        this.activeTemplateId.set(created.id);
        this.creatingTemplate.set(false);
        this.templateBusy.set(false);
      },
      error: (error: unknown) => {
        this.templateError.set(describeError(error));
        this.templateBusy.set(false);
      },
    });
  }

  /**
   * 用目前盤面覆蓋選中的編組。
   *
   * 後端 update 會把舊的 template_routes / template_stops 整批刪掉重建，
   * 所以這是「以現在的排法為準」，不是增量合併。
   */
  overwriteActiveTemplate(): void {
    const template = this.activeTemplate();
    if (!template || this.templateBusy()) {
      return;
    }

    const warehouseId = this.warehouseId();
    const routes: TemplateRouteRequest[] = this.routes()
      .filter((lane) => lane.cards.length > 0)
      .map((lane) => ({
        warehouseId,
        vehicleId: lane.vehicleId,
        storeIds: [...new Set(lane.cards.map((card) => card.storeId))],
      }));

    if (routes.length === 0) {
      this.templateError.set('盤面上沒有排到訂單的車，無法覆蓋編組。');
      return;
    }

    this.templateBusy.set(true);
    this.templateError.set('');
    this.api.updateTemplate(template.id, {name: template.name, routes}).subscribe({
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

  storeName(storeId: number): string {
    return this.stores().find((store) => store.id === storeId)?.name ?? `門市 #${storeId}`;
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

  private scheduleIssuesForRoutes(routes: readonly BoardRoute[]): string[] {
    const assignedDriverIds = new Set(
      routes
        .filter((route) => route.cards.length > 0 && route.driverId !== null)
        .map((route) => route.driverId!),
    );

    return [...assignedDriverIds].flatMap((driverId) => {
      const note = this.driverScheduleNote(driverId);
      return note ? [`${this.driverName(driverId)}${note}`] : [];
    });
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

    // 車輛＝固定槽位：只為這個倉可派車的車輛建立一格，後端有回路線的就填進去。
    const routeByVehicle = new Map(result.routes.map((route) => [route.vehicleId, route]));
    const dispatchableOrderIds = new Set(
      this.orders()
        .filter((order) => order.status === 'CONFIRMED')
        .map((order) => order.id),
    );
    const boardVehicles = this.vehicles().filter(
      (vehicle): vehicle is VehicleDto & {id: number} =>
        vehicle.id != null &&
        vehicle.warehouseId === this.warehouseId() &&
        vehicle.status !== 'RETIRED',
    );
    const boardVehicleIds = new Set(boardVehicles.map((vehicle) => vehicle.id));
    this.routes.set(
      boardVehicles.map((vehicle) => {
        const route = routeByVehicle.get(vehicle.id);
        const routeStops = route?.stops ?? [];
        return {
          routeId: route?.routeId ?? 0,
          vehicleId: vehicle.id,
          plateNumber: vehicle.plateNumber,
          vehicleType: vehicle.vehicleType ?? null,
          capacity: vehicle.capacity,
          driverId: route?.driverId ?? null,
          totalDistance: route?.totalDistance ?? 0,
          routeStatus: route?.status ?? 'DRAFT',
          hasLockedStops: routeStops.some((stop) => !dispatchableOrderIds.has(stop.orderId)),
          isMaintenance: vehicle.status === 'MAINTENANCE',
          cards: (vehicle.status === 'MAINTENANCE'
            ? routeStops
            : routeStops.filter((stop) => dispatchableOrderIds.has(stop.orderId)))
            .map(toBoardCard),
        };
      }),
    );
    // 報廢車不畫成車道，但其中仍待排的訂單必須保留給其他可派車輛。
    const strandedOrders = result.routes
      .filter((route) => !boardVehicleIds.has(route.vehicleId))
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

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
import {Component, computed, inject, OnInit, signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {MatInputModule} from '@angular/material/input';
import {MatSelectModule} from '@angular/material/select';
import {MatFormFieldModule} from '@angular/material/form-field';
import {forkJoin} from 'rxjs';
import {LiveFleetMap} from '../../components/live-fleet-map/live-fleet-map';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {
  DispatchResultDto,
  DriverDto,
  DriverTakenDto,
  OrderDto,
  OrderStatus,
  ReassignRequest,
  RouteStatus,
  RouteStopDto,
  StoreDto,
  TemplateDto,
  TemplateRouteRequest,
  UnassignedOrderDto,
  VehicleDto,
  VehicleStatus,
  WarehouseDto,
} from '../../../../core/services/dispatch-api.models';

/** 車輛狀態顯示用的中文，灰掉的槽位要說明為什麼不能用 */
const VEHICLE_STATUS_LABEL: Record<VehicleStatus, string> = {
  AVAILABLE: '可派車',
  MAINTENANCE: '維修中',
  RETIRED: '已報廢',
};

/** 後端錯誤統一是 { message }，取得到就用它的文字，否則給個能辨識的替代。 */
function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const message = (error.error as { message?: string } | null)?.message;
    return message || `後端回應 ${error.status}`;
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
  /** 維修／報廢的車不能排入，畫面上要灰掉並標出狀態 */
  status: VehicleStatus;
  /** 指派的司機，未指派為 null。發布前必須指派，否則司機端查不到任務 */
  driverId: number | null;
  /** 上一次由後端算出的里程；拖曳後會失準，要等 reassign 重算 */
  totalDistance: number;
  /** 空槽沒有路線，一律當作草稿 */
  routeStatus: RouteStatus;
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

interface SummaryCard {
  label: string;
  value: string;
  detail: string;
  tone: string;
}

interface PendingOrder {
  id: string;
  store: string;
  area: string;
  window: string;
  cargo: string;
  status: string;
  priority: string;
}

interface DashboardAlert {
  title: string;
  detail: string;
  tone: string;
}

@Component({
  selector: 'app-dispatch-dashboard',
  imports: [LiveFleetMap, DecimalPipe, CdkDropListGroup, CdkDropList, CdkDrag],
  templateUrl: './dispatch-dashboard.html',
  styleUrl: './dispatch-dashboard.scss',
})
export class DispatchDashboard implements OnInit {
  readonly routes = signal<BoardRoute[]>([]);
  readonly unassigned = signal<BoardCard[]>([]);
  /** 當天已被其他倉庫排走的司機。後端還沒回這個欄位時是空陣列 */
  readonly driversTakenElsewhere = signal<DriverTakenDto[]>([]);
  /** 改派送出中，此時鎖住看板避免兩個請求互相覆蓋 */
  readonly saving = signal(false);
  readonly boardError = signal('');
  /** 動作列選的排車條件，optimize / board / reassign 三支 API 共用同一組值 */
  readonly dispatchDate = signal(todayLocalDate());
  /** 0 代表倉庫清單還沒載回來，尚未決定預設倉庫 */
  readonly warehouseId = signal(0);
  readonly optimizing = signal(false);
  private readonly api = inject(DispatchApiService);
  readonly dispatchResult = signal<DispatchResultDto | null>(null);
  readonly orders = signal<OrderDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly drivers = signal<DriverDto[]>([]);
  readonly vehicles = signal<VehicleDto[]>([]);
  readonly warehouses = signal<WarehouseDto[]>([]);
  readonly pendingOrders = signal<PendingOrder[]>([]);
  readonly alerts = signal<DashboardAlert[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly updatedAt = signal('--:--');

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

  statusLabel(status: VehicleStatus): string {
    return VEHICLE_STATUS_LABEL[status];
  }

  readonly activeTemplate = computed(
    () => this.templates().find((item) => item.id === this.activeTemplateId()) ?? null,
  );
  readonly warehouseName = signal('台南配送區');

  readonly tickerMessages = computed(() => {
    const orders = this.orders();
    const waitingSchedule = orders.filter((order) => order.status === 'CONFIRMED').length;
    const delivering = orders.filter((order) => order.status === 'IN_DELIVERY').length;

    return [
      `今日配送需求 ${orders.length} 筆`,
      `${waitingSchedule} 筆待排車，資料來自 OrderController`,
      `${delivering} 筆配送中，後端目前未提供即時 GPS Controller`,
      `已同步 ${this.drivers().length} 位司機與 ${this.vehicles().length} 台車輛`,
      `目前 ${this.warehouseName()} 已納入首頁資料來源`,
    ];
  });

  readonly summaryCards = computed<SummaryCard[]>(() => {
    const orders = this.orders();
    const pendingConfirm = orders.filter((order) => order.status === 'PENDING_CONFIRM').length;
    const waitingSchedule = orders.filter((order) => order.status === 'CONFIRMED').length;
    const delivering = orders.filter((order) => order.status === 'IN_DELIVERY').length;

    return [
      {
        label: '待總部確認',
        value: String(pendingConfirm),
        detail: '來自後端 PENDING_CONFIRM',
        tone: 'accent',
      },
      {
        label: '待排車',
        value: String(waitingSchedule),
        detail: '來自後端 CONFIRMED',
        tone: 'default',
      },
      {
        label: '配送中',
        value: String(delivering),
        detail: '來自後端 IN_DELIVERY',
        tone: 'default',
      },
      {
        label: '資源提醒',
        value: String(this.alerts().length),
        detail: '由司機與車輛狀態計算',
        tone: 'warning',
      },
    ];
  });

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

  ngOnInit(): void {
    this.loadDashboard();
    // 跟總覽分開打：編組載不到不該讓整個看板空白
    this.loadTemplates();
  }

  onDateChange(event: Event): void {
    this.dispatchDate.set((event.target as HTMLInputElement).value);
    this.reloadBoard();
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
        this.pendingOrders.set(this.toPendingOrders(orders, stores));
        this.alerts.set(this.toAlerts(drivers, vehicles));
        this.warehouseName.set(
          warehouses.find((warehouse) => warehouse.isActive)?.name ?? '台南配送區',
        );
        this.updatedAt.set(this.formatCurrentTime());
        this.loading.set(false);

        // 預設倉庫要等清單回來才能決定，不能寫死 id。
        // 決定好之後才讀看板，否則會拿 warehouseId=0 去打 API。
        const defaultWarehouseId =
          warehouses.find((warehouse) => warehouse.isActive)?.id ?? warehouses[0]?.id ?? 0;
        this.warehouseId.set(defaultWarehouseId);
        if (defaultWarehouseId) {
          this.reloadBoard();
        }
      },
      error: () => {
        this.errorMessage.set('無法取得後台總覽資料，請確認後端服務與登入狀態。');
        this.loading.set(false);
      },
    });
  }

  private toPendingOrders(orders: OrderDto[], stores: StoreDto[]): PendingOrder[] {
    return orders
      .filter((order) => order.status !== 'COMPLETED' && order.status !== 'CANCELLED')
      .slice(0, 3)
      .map((order) => {
        const store = stores.find((item) => item.id === order.storeId);
        const address = store?.address || '';
        return {
          id: order.orderNumber,
          store: store?.name ?? `門市 #${order.storeId}`,
          area: this.extractArea(address),
          window: store
            ? `${this.formatTime(store.receivingStart)} - ${this.formatTime(store.receivingEnd)}`
            : '尚未提供收貨時段',
          cargo: `${order.boxCount} 箱`,
          status: this.orderStatusLabel(order.status),
          priority: order.status === 'PENDING_CONFIRM' ? '待確認' : '一般',
        };
      });
  }

  private toAlerts(drivers: DriverDto[], vehicles: VehicleDto[]): DashboardAlert[] {
    const alerts: DashboardAlert[] = [];
    const inactiveDrivers = drivers.filter((driver) => !driver.isActive);
    const maintenanceVehicles = vehicles.filter((vehicle) => vehicle.status === 'MAINTENANCE');

    if (inactiveDrivers.length > 0) {
      alerts.push({
        title: '司機狀態提醒',
        detail: `${inactiveDrivers.length} 位司機目前標記為停職。`,
        tone: 'warning',
      });
    }
    if (maintenanceVehicles.length > 0) {
      alerts.push({
        title: '車輛保養提醒',
        detail: `${maintenanceVehicles.length} 台車輛目前標記為保養。`,
        tone: 'critical',
      });
    }
    if (alerts.length === 0) {
      alerts.push({
        title: '目前沒有資源提醒',
        detail: '後端目前尚未提供即時異常 Controller。',
        tone: 'normal',
      });
    }
    return alerts;
  }

  private orderStatusLabel(status: OrderStatus): string {
    const labels: Record<OrderStatus, string> = {
      PENDING_CONFIRM: '待總部確認',
      CONFIRMED: '待排車',
      IN_DELIVERY: '配送中',
      COMPLETED: '已完成',
      CANCELLED: '已取消',
      FAILED: '配送失敗',
    };
    return labels[status];
  }

  private extractArea(address: string): string {
    return address.match(/台南市([^\s]+區)/)?.[1] ?? '台南配送區';
  }

  private formatTime(value: string): string {
    return value?.slice(0, 5) || '--:--';
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
        this.boardError.set(err?.error?.message ?? '排車失敗，請確認後端與 OSRM 服務。');
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

  /**
   * 這條車道的司機選項。已被佔用的不隱藏，改成 disabled 並附上原因 ——
   * 人憑空消失會讓調度員以為是系統壞了，寫明「已排在哪」才知道要去哪裡調整。
   *
   * 佔用有兩種來源：同一個倉的其他車道（看板上看得到），以及當天其他倉
   * （看板看不到，要靠後端的 driversTakenElsewhere 補）。
   *
   * 自己這條已指派的司機永遠可選，否則 select 找不到對應 option，
   * 畫面會退回第一個選項（看起來像沒指派）。
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
      .filter((driver): driver is DriverDto & {id: number} => driver.id != null && driver.isActive)
      .map((driver) => ({
        id: driver.id,
        name: driver.name,
        takenNote:
          driver.id === route.driverId
            ? null
            : (takenHere.get(driver.id) ?? takenElsewhere.get(driver.id) ?? null),
      }));
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

    this.publishing.set(true);
    this.publishError.set('');
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
    this.routes.update((lanes) => lanes.map((lane) => ({ ...lane, cards: [], driverId: null })));
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
    this.api.createTemplate({ name, routes }).subscribe({
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
    this.api.updateTemplate(template.id, { name: template.name, routes }).subscribe({
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

    // 車輛＝固定槽位：這個倉的每台車都給一格，後端有回路線的就填進去。
    // 維修／報廢的車也畫出來（灰掉），讓調度員知道為什麼少了一台可用車。
    const routeByVehicle = new Map(result.routes.map((route) => [route.vehicleId, route]));
    this.routes.set(
      this.vehicles()
        .filter((vehicle) => vehicle.id != null && vehicle.warehouseId === this.warehouseId())
        .map((vehicle) => {
          const route = routeByVehicle.get(vehicle.id!);
          return {
            routeId: route?.routeId ?? 0,
            vehicleId: vehicle.id!,
            plateNumber: vehicle.plateNumber,
            vehicleType: vehicle.vehicleType ?? null,
            capacity: vehicle.capacity,
            status: vehicle.status,
            driverId: route?.driverId ?? null,
            totalDistance: route?.totalDistance ?? 0,
            routeStatus: route?.status ?? 'DRAFT',
            cards: route ? route.stops.map(toBoardCard) : [],
          };
        }),
    );
    this.unassigned.set(result.unassignedOrders.map(toBoardCard));
    this.driversTakenElsewhere.set(result.driversTakenElsewhere ?? []);
  }
}

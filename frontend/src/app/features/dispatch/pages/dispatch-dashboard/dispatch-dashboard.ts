import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { LiveFleetMap } from '../../components/live-fleet-map/live-fleet-map';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {
  DriverDto,
  OrderDto,
  OrderStatus,
  StoreDto,
  VehicleDto,
  WarehouseDto,
} from '../../../../core/services/dispatch-api.models';

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
  imports: [LiveFleetMap],
  templateUrl: './dispatch-dashboard.html',
  styleUrl: './dispatch-dashboard.scss',
})
export class DispatchDashboard implements OnInit {
  private readonly api = inject(DispatchApiService);

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
  readonly warehouseName = signal('台南配送區');

  readonly tickerMessages = computed(() => {
    const orders = this.orders();
    const waitingSchedule = orders.filter((order) =>
      ['CONFIRMED', 'SCHEDULED', 'PUBLISHED'].includes(order.status),
    ).length;
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
    const waitingSchedule = orders.filter((order) =>
      ['CONFIRMED', 'SCHEDULED', 'PUBLISHED'].includes(order.status),
    ).length;
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
        detail: '來自 CONFIRMED / SCHEDULED / PUBLISHED',
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

  ngOnInit(): void {
    this.loadDashboard();
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
      next: ({ orders, stores, drivers, vehicles, warehouses }) => {
        this.orders.set(orders);
        this.stores.set(stores);
        this.drivers.set(drivers);
        this.vehicles.set(vehicles);
        this.warehouses.set(warehouses);
        this.pendingOrders.set(this.toPendingOrders(orders, stores));
        this.alerts.set(this.toAlerts(drivers, vehicles));
        this.warehouseName.set(warehouses.find((warehouse) => warehouse.isActive)?.name ?? '台南配送區');
        this.updatedAt.set(this.formatCurrentTime());
        this.loading.set(false);
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
      SCHEDULED: '待排車',
      MODIFY: '資料待補',
      PUBLISHED: '已發布',
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
}

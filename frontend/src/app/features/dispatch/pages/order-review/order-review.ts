import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { LucideClipboardCheck, LucideMapPinned, LucideSearch, LucideTruck } from '@lucide/angular';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {
  OrderDto,
  OrderStatus as BackendOrderStatus,
  StoreDto,
} from '../../../../core/services/dispatch-api.models';

type OrderReviewStatus =
  | '待總部確認'
  | '資料待補'
  | '待排車'
  | '配送中'
  | '已完成'
  | '已取消'
  | '配送失敗';
type FilterKey = 'all' | OrderReviewStatus;

interface DeliveryOrder {
  id: string;
  backendId: number;
  store: string;
  area: string;
  address: string;
  deliveryWindow: string;
  cargo: string;
  service: string;
  contact: string;
  createdAt: string;
  status: OrderReviewStatus;
  note?: string;
  raw: OrderDto;
}

@Component({
  selector: 'app-order-review',
  imports: [LucideClipboardCheck, LucideMapPinned, LucideSearch, LucideTruck],
  templateUrl: './order-review.html',
  styleUrl: './order-review.scss',
})
export class OrderReview implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly filters: { key: FilterKey; label: string }[] = [
    { key: 'all', label: '全部' },
    { key: '待總部確認', label: '待確認' },
    { key: '資料待補', label: '待補件' },
    { key: '待排車', label: '待排車' },
    { key: '配送中', label: '配送中' },
    { key: '已完成', label: '已完成' },
  ];

  readonly orders = signal<DeliveryOrder[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly activeFilter = signal<FilterKey>('all');
  readonly searchTerm = signal('');
  readonly selectedOrderId = signal('');
  readonly actionMessage = signal('確認資料後，可將配送需求送入待排車佇列。');
  readonly loading = signal(true);
  readonly errorMessage = signal('');

  readonly filteredOrders = computed(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.orders().filter((order) => {
      const matchesFilter = filter === 'all' || order.status === filter;
      const searchSource = `${order.id} ${order.store} ${order.area}`.toLowerCase();
      return matchesFilter && (!term || searchSource.includes(term));
    });
  });

  readonly selectedOrder = computed(() => {
    const orders = this.filteredOrders();
    return orders.find((order) => order.id === this.selectedOrderId()) ?? orders[0] ?? null;
  });

  readonly reviewCount = computed(
    () => this.orders().filter((order) => order.status === '待總部確認').length,
  );
  readonly supplementCount = computed(
    () => this.orders().filter((order) => order.status === '資料待補').length,
  );

  ngOnInit(): void {
    this.loadOrders();
  }

  setFilter(filter: FilterKey): void {
    this.activeFilter.set(filter);
    this.syncSelectedOrder();
  }

  updateSearch(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
    this.syncSelectedOrder();
  }

  selectOrder(orderId: string): void {
    this.selectedOrderId.set(orderId);
    this.actionMessage.set('確認資料後，可將配送需求送入待排車佇列。');
  }

  approveSelected(): void {
    const selected = this.selectedOrder();
    if (!selected || !this.canReview(selected)) {
      return;
    }

    this.updateOrderStatus(
      selected,
      'CONFIRMED',
      `${selected.id} 已核准，等待調度人員安排車輛與司機。`,
    );
  }

  requestSupplement(): void {
    const selected = this.selectedOrder();
    if (!selected || !this.canReview(selected)) {
      return;
    }

    this.updateOrderStatus(
      selected,
      'MODIFY',
      `${selected.id} 已標記為待補件，將由客服通知店家補齊資料。`,
    );
  }

  canReview(order: DeliveryOrder): boolean {
    return order.status === '待總部確認';
  }

  private loadOrders(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    forkJoin({
      orders: this.api.getOrders(),
      stores: this.api.getStores(),
    }).subscribe({
      next: ({ orders, stores }) => {
        this.stores.set(stores);
        this.orders.set(orders.flatMap((order) => this.toDeliveryOrder(order, stores)));
        this.syncSelectedOrder();
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('無法取得訂單與門市資料，請確認後端服務是否正在執行。');
        this.loading.set(false);
      },
    });
  }

  private updateOrderStatus(
    selected: DeliveryOrder,
    status: BackendOrderStatus,
    successMessage: string,
  ): void {
    if (selected.backendId <= 0) {
      return;
    }

    this.actionMessage.set('正在將訂單狀態寫回後端...');
    this.api
      .updateOrder(selected.backendId, {
        ...selected.raw,
        notes: selected.raw.notes ?? '',
        status,
      })
      .subscribe({
        next: (updated) => {
          this.orders.update((orders) =>
            orders.map((order) =>
              order.backendId === selected.backendId
                ? this.toDeliveryOrder(updated, this.stores())
                : order,
            ),
          );
          this.actionMessage.set(successMessage);
        },
        error: () => {
          this.actionMessage.set('訂單狀態更新失敗，請檢查後端回應與訂單資料。');
        },
      });
  }

  private toDeliveryOrder(order: OrderDto, stores: StoreDto[]): DeliveryOrder {
    const store = stores.find((item) => item.id === order.storeId);
    const storeName = store?.name ?? `門市 #${order.storeId}`;
    const address = store?.address || '尚未提供地址';

    return {
      id: order.orderNumber,
      backendId: order.id ?? 0,
      store: storeName,
      area: this.extractArea(address),
      address,
      deliveryWindow: store
        ? `${this.formatTime(store.receivingStart)} - ${this.formatTime(store.receivingEnd)}`
        : '尚未提供收貨時段',
      cargo: `${order.boxCount} 箱`,
      service: order.itemDescription || order.sourceVendor || '一般配送',
      contact: store
        ? `${store.contactName || '未提供聯絡人'} / ${store.phone || '未提供電話'}`
        : '尚未提供門市聯絡資訊',
      createdAt: this.formatTimestamp(order.createdAt),
      status: this.toReviewStatus(order.status),
      note: order.notes || store?.notes,
      raw: order,
    };
  }

  private toReviewStatus(status: BackendOrderStatus): OrderReviewStatus {
    switch (status) {
      case 'PENDING_CONFIRM':
        return '待總部確認';
      case 'MODIFY':
        return '資料待補';
      case 'CONFIRMED':
      case 'SCHEDULED':
      case 'PUBLISHED':
        return '待排車';
      case 'IN_DELIVERY':
        return '配送中';
      case 'COMPLETED':
        return '已完成';
      case 'CANCELLED':
        return '已取消';
      case 'FAILED':
        return '配送失敗';
    }
  }

  private extractArea(address: string): string {
    return address.match(/台南市([^\s]+區)/)?.[1] ?? '台南配送區';
  }

  private formatTime(value: string): string {
    return value?.slice(0, 5) || '--:--';
  }

  private formatTimestamp(value?: string): string {
    return value ? value.replace('T', ' ').slice(0, 16) : '尚未提供';
  }

  private syncSelectedOrder(): void {
    this.selectedOrderId.set(this.filteredOrders()[0]?.id ?? '');
  }
}

import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import {MatIconModule} from '@angular/material/icon';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {
  OrderImportResult,
  OrderImportService,
} from '../../../../core/services/order-import.service';
import {
  OrderDto,
  OrderStatus as BackendOrderStatus,
  StoreDto,
  WarehouseDto,
} from '../../../../core/services/dispatch-api.models';

type OrderReviewStatus =
  | '待總部確認'
  | '待排車'
  | '配送中'
  | '已完成'
  | '已取消'
  | '配送失敗';
type FilterKey = 'all' | OrderReviewStatus;
type OrderFormMode = 'create' | 'edit' | null;

/** 匯入失敗的單筆訂單，row 是 Excel 上的列號。 */
interface ImportFailure {
  row: number;
  orderNumber: string;
  message: string;
}

/**
 * Excel 匯入的狀態機。刻意跟 activeForm 分開，兩者互不影響：
 * 新增／編輯表單那一套完全不必知道匯入的存在。
 * 用 union 而不是好幾個獨立 signal，是為了讓「解析中卻已經有結果」這種狀態組不出來。
 */
type ImportState =
  | { stage: 'idle' }
  | { stage: 'parsing'; fileName: string }
  | { stage: 'preview'; fileName: string; result: OrderImportResult }
  | { stage: 'saving'; fileName: string; result: OrderImportResult; done: number }
  | { stage: 'done'; fileName: string; succeeded: number; failures: ImportFailure[] }
  | { stage: 'failed'; fileName: string; message: string };

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

/**
 * 今天的本地日期（YYYY-MM-DD）。不能用 `new Date().toISOString().slice(0, 10)`——
 * toISOString 是轉成 UTC 再取日期，台灣時區凌晨 00:00~07:59 會被算成前一天。
 */
function todayLocalDate(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/**
 * 目前一般訂單 API 仍要求前端送出 orderNumber，尚未提供自動編號端點。
 * 因此在建立表單時以日期與隨機碼產生低碰撞編號；後端建立後仍會以唯一鍵做最後檢查。
 */
function generateOrderNumber(): string {
  const date = todayLocalDate().replaceAll('-', '');
  const random = globalThis.crypto?.randomUUID?.().replaceAll('-', '').slice(0, 8).toUpperCase()
    ?? Math.random().toString(36).slice(2, 10).toUpperCase();

  return `DO-${date}-${random}`;
}

function emptyOrder(storeId = 0, warehouseId = 0): OrderDto {
  return {
    orderNumber: generateOrderNumber(),
    storeId,
    warehouseId,
    sourceVendor: '',
    itemDescription: '',
    boxCount: 1,
    notes: '',
    deliveryDate: todayLocalDate(),
    status: 'PENDING_CONFIRM',
  };
}

/** 後端錯誤統一是 ApiResponse { success, message }，取得到就用它的文字。 */
function describeError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const message = (error.error as { message?: string } | null)?.message;
    return message || `後端回應 ${error.status}`;
  }

  return '未知錯誤';
}

@Component({
  selector: 'app-order-review',
  imports: [
    MatIconModule,
  ],
  templateUrl: './order-review.html',
  styleUrl: './order-review.scss',
})
export class OrderReview implements OnInit {
  private readonly api = inject(DispatchApiService);
  private readonly importer = inject(OrderImportService);

  readonly filters: { key: FilterKey; label: string }[] = [
    { key: 'all', label: '全部' },
    { key: '待總部確認', label: '待確認' },
    { key: '待排車', label: '待排車' },
    { key: '配送中', label: '配送中' },
    { key: '已完成', label: '已完成' },
  ];

  readonly orders = signal<DeliveryOrder[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly warehouses = signal<WarehouseDto[]>([]);
  readonly activeFilter = signal<FilterKey>('all');
  readonly searchTerm = signal('');
  readonly selectedOrderId = signal('');
  readonly actionMessage = signal('確認資料後，可將配送需求送入待排車佇列。');
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly activeForm = signal<OrderFormMode>(null);
  readonly orderForm = signal<OrderDto>(emptyOrder());
  readonly editingOrderId = signal<number | null>(null);
  readonly formError = signal('');
  readonly isSaving = signal(false);
  readonly deleteTarget = signal<DeliveryOrder | null>(null);
  readonly isDeleting = signal(false);
  readonly importState = signal<ImportState>({ stage: 'idle' });

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

  /** 建單時的預設倉庫：第一個啟用中的倉庫，沒有就退回第一筆 */
  readonly defaultWarehouseId = computed(() => {
    const warehouses = this.warehouses();
    return warehouses.find((warehouse) => warehouse.isActive)?.id ?? warehouses[0]?.id ?? 0;
  });

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

  openCreateOrder(): void {
    const firstStoreId = this.stores()[0]?.id ?? 0;
    if (!firstStoreId) {
      this.actionMessage.set('請先透過人車資源頁新增店家，再建立配送訂單。');
      return;
    }

    const warehouseId = this.defaultWarehouseId();
    if (!warehouseId) {
      this.actionMessage.set('請先建立倉庫，再建立配送訂單。');
      return;
    }

    this.orderForm.set(emptyOrder(firstStoreId, warehouseId));
    this.editingOrderId.set(null);
    this.formError.set('');
    this.activeForm.set('create');
  }

  openEditOrder(order: DeliveryOrder): void {
    this.orderForm.set({ ...order.raw });
    this.editingOrderId.set(order.backendId);
    this.formError.set('');
    this.activeForm.set('edit');
  }

  closeForm(): void {
    if (!this.isSaving()) {
      this.activeForm.set(null);
      this.editingOrderId.set(null);
      this.formError.set('');
    }
  }

  updateOrderText(
    field: 'sourceVendor' | 'itemDescription' | 'notes' | 'deliveryDate',
    event: Event,
  ): void {
    const value = (event.target as HTMLInputElement).value;
    this.orderForm.update((order) => ({ ...order, [field]: value }));
  }

  updateOrderNumber(field: 'storeId' | 'boxCount' | 'warehouseId', event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.orderForm.update((order) => ({ ...order, [field]: value }));
  }

  updateOrderStatusFromForm(event: Event): void {
    const status = (event.target as HTMLSelectElement).value as BackendOrderStatus;
    this.orderForm.update((order) => ({ ...order, status }));
  }

  submitOrder(): void {
    const order = this.orderForm();
    const editingId = this.editingOrderId();

    if (!order.orderNumber.trim() || !order.deliveryDate) {
      this.formError.set('請填寫訂單編號與配送日期。');
      return;
    }

    if (!Number.isInteger(order.storeId) || order.storeId <= 0) {
      this.formError.set('請選擇有效店家。');
      return;
    }

    if (!Number.isInteger(order.warehouseId) || order.warehouseId <= 0) {
      this.formError.set('請選擇出貨倉庫。');
      return;
    }

    if (!Number.isInteger(order.boxCount) || order.boxCount < 1) {
      this.formError.set('箱數至少要是 1。');
      return;
    }

    this.isSaving.set(true);
    this.formError.set('');
    const request =
      this.activeForm() === 'edit' && editingId !== null
        ? this.api.updateOrder(editingId, order)
        : this.api.createOrder(order);

    request.subscribe({
      next: (savedOrder) => {
        const saved = this.toDeliveryOrder(savedOrder, this.stores());
        if (this.activeForm() === 'edit') {
          this.orders.update((orders) =>
            orders.map((item) => (item.backendId === saved.backendId ? saved : item)),
          );
        } else {
          this.orders.update((orders) => [saved, ...orders]);
        }
        this.selectedOrderId.set(saved.id);
        this.actionMessage.set(`已將 ${saved.id} 儲存到後端。`);
        this.isSaving.set(false);
        this.activeForm.set(null);
        this.editingOrderId.set(null);
      },
      error: () => {
        this.formError.set('儲存失敗，請檢查訂單欄位與後端回應。');
        this.isSaving.set(false);
      },
    });
  }

  requestDeleteOrder(order: DeliveryOrder): void {
    this.deleteTarget.set(order);
  }

  cancelDelete(): void {
    if (!this.isDeleting()) {
      this.deleteTarget.set(null);
    }
  }

  confirmDelete(): void {
    const order = this.deleteTarget();
    if (!order) {
      return;
    }

    this.isDeleting.set(true);
    this.api.deleteOrder(order.backendId).subscribe({
      next: () => {
        this.orders.update((orders) => orders.filter((item) => item.backendId !== order.backendId));
        this.syncSelectedOrder();
        this.actionMessage.set(`${order.id} 已從後端刪除。`);
        this.deleteTarget.set(null);
        this.isDeleting.set(false);
      },
      error: () => {
        this.actionMessage.set('訂單刪除失敗，請確認後端回應。');
        this.isDeleting.set(false);
      },
    });
  }

  // ===== Excel 匯入 =====

  /** 選好檔案就直接開始解析：parsing → preview（或 failed）。 */
  async onImportFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // 清掉 value 才能重選同一個檔案再次觸發 change。
    input.value = '';

    if (!file) {
      return;
    }

    // 兩個 modal 不疊在一起；這是匯入唯一會碰到既有狀態的地方。
    this.activeForm.set(null);
    this.importState.set({ stage: 'parsing', fileName: file.name });

    try {
      const result = await this.importer.parse(file, {
        stores: this.stores(),
        warehouses: this.warehouses(),
        defaultWarehouseId: this.defaultWarehouseId(),
        existingOrderNumbers: this.orders().map((order) => order.raw.orderNumber),
      });
      this.importState.set({ stage: 'preview', fileName: file.name, result });
    } catch (error) {
      this.importState.set({
        stage: 'failed',
        fileName: file.name,
        message: error instanceof Error ? error.message : '檔案讀取失敗。',
      });
    }
  }

  /** 後端以單一 transaction 建立本次通過前端驗證的所有訂單。 */
  confirmImport(): void {
    const state = this.importState();

    if (state.stage !== 'preview' || state.result.validRows.length === 0) {
      return;
    }

    const { fileName, result } = state;
    this.importState.set({ stage: 'saving', fileName, result, done: 0 });

    this.api.createOrdersBatch(result.validRows.map((row) => row.data)).subscribe({
      next: (createdOrders) => {
        const saved = createdOrders.map((order) => this.toDeliveryOrder(order, this.stores()));
        this.orders.update((orders) => [...saved, ...orders]);
        this.syncSelectedOrder();
        this.actionMessage.set(`已從 ${fileName} 批次匯入 ${saved.length} 筆訂單。`);
        this.importState.set({ stage: 'done', fileName, succeeded: saved.length, failures: [] });
      },
      error: (error: unknown) => {
        const message = describeError(error);
        const failures = result.validRows.map((row) => ({
          row: row.row,
          orderNumber: row.data.orderNumber,
          message,
        }));
        this.actionMessage.set(`${fileName} 未完成匯入，後端未建立任何訂單。`);
        this.importState.set({ stage: 'done', fileName, succeeded: 0, failures });
      },
    });
  }

  /** 預覽表格顯示用：把解析出來的 storeId 換回看得懂的門市名稱。 */
  storeLabel(storeId: number): string {
    return this.stores().find((store) => store.id === storeId)?.name ?? '—';
  }

  /** 一行把整組匯入狀態清乾淨，不會殘留上一次的預覽結果。 */
  closeImport(): void {
    if (this.importState().stage !== 'saving') {
      this.importState.set({ stage: 'idle' });
    }
  }

  async downloadImportTemplate(): Promise<void> {
    const url = URL.createObjectURL(await this.importer.createTemplateBlob());
    const link = document.createElement('a');
    link.href = url;
    link.download = '訂單匯入範本.xlsx';
    link.click();
    URL.revokeObjectURL(url);
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

  canReview(order: DeliveryOrder): boolean {
    return order.status === '待總部確認';
  }

  private loadOrders(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    forkJoin({
      orders: this.api.getOrders(),
      stores: this.api.getStores(),
      warehouses: this.api.getWarehouses(),
    }).subscribe({
      next: ({ orders, stores, warehouses }) => {
        this.stores.set(stores);
        this.warehouses.set(warehouses);
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
      case 'CONFIRMED':
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
    return address.match(/高雄市([^\s]+區)/)?.[1] ?? '高雄配送區';
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

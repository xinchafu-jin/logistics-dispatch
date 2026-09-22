import {Component, computed, inject, OnInit, signal} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {MatIconModule} from '@angular/material/icon';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {OrderDto, OrderStatus, StoreDto} from '../../../../core/services/dispatch-api.models';

@Component({
  selector: 'app-report-history',
  imports: [MatIconModule],
  templateUrl: './report-history.html',
  styleUrl: './report-history.scss',
})
export class ReportHistory implements OnInit {
  private readonly api = inject(DispatchApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly orders = signal<OrderDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly from = signal(this.today());
  readonly to = signal(this.today());
  readonly loading = signal(true);
  readonly errorMessage = signal('');

  readonly records = computed(() => {
    const storesById = new Map(this.stores().filter((store) => store.id != null).map((store) => [store.id!, store]));
    return this.orders()
      .filter((order) => order.deliveryDate >= this.from() && order.deliveryDate <= this.to())
      .sort((left, right) => left.deliveryDate.localeCompare(right.deliveryDate) || left.orderNumber.localeCompare(right.orderNumber))
      .map((order) => ({order, store: storesById.get(order.storeId)}));
  });

  readonly completedCount = computed(() => this.records().filter(({order}) => order.status === 'COMPLETED').length);
  readonly activeCount = computed(() => this.records().filter(({order}) => order.status === 'IN_DELIVERY').length);
  readonly exceptionCount = computed(() => this.records().filter(({order}) => order.status === 'FAILED').length);

  ngOnInit(): void {
    const query = this.route.snapshot.queryParamMap;
    this.from.set(query.get('from') ?? this.from());
    this.to.set(query.get('to') ?? this.to());
    this.loadRecords();
  }

  protected updateFrom(event: Event): void {
    this.from.set((event.target as HTMLInputElement).value);
  }

  protected updateTo(event: Event): void {
    this.to.set((event.target as HTMLInputElement).value);
  }

  protected applyRange(): void {
    if (!this.from() || !this.to() || this.from() > this.to()) {
      this.errorMessage.set('請確認查詢起訖日期。');
      return;
    }

    void this.router.navigate([], {relativeTo: this.route, queryParams: {from: this.from(), to: this.to()}});
    this.errorMessage.set('');
  }

  protected statusLabel(status: OrderStatus): string {
    return {
      PENDING_CONFIRM: '待確認',
      CONFIRMED: '待排車',
      IN_DELIVERY: '配送中',
      COMPLETED: '已完成',
      CANCELLED: '已取消',
      FAILED: '配送失敗',
    }[status];
  }

  private loadRecords(): void {
    this.loading.set(true);
    this.api.getOrders().subscribe({
      next: (orders) => {
        this.orders.set(orders);
        this.api.getStores().subscribe({
          next: (stores) => {
            this.stores.set(stores);
            this.loading.set(false);
          },
          error: () => {
            this.errorMessage.set('暫時無法取得門市資料。');
            this.loading.set(false);
          },
        });
      },
      error: () => {
        this.errorMessage.set('暫時無法取得歷史訂單。');
        this.loading.set(false);
      },
    });
  }

  private today(): string {
    return new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Taipei'}).format(new Date());
  }
}

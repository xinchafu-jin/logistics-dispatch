import {Component, inject, OnInit, signal} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {forkJoin} from 'rxjs';
import {MatIconModule} from '@angular/material/icon';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DriverDto, OrderDto, ReportCollectionDto, ReportQuery, ReportSummaryDto, StoreDto, WarehouseDto} from '../../../../core/services/dispatch-api.models';

type PreviewSheet = 'overview' | 'orders' | 'routes' | 'attendance' | 'vehicles' | 'warehouses' | 'stores' | 'exceptions' | 'notes';
interface ReportRow {
  [key: string]: unknown;
  actualKm?: unknown;
  actualMileageStatus?: unknown;
  assignedOrders?: unknown;
  assignedRoutes?: unknown;
  averageLoadRatePercent?: unknown;
  boxes?: unknown;
  clockInAt?: unknown;
  clockOutAt?: unknown;
  clockSpanMinutes?: unknown;
  completedOrders?: unknown;
  damagedBoxes?: unknown;
  createdAt?: unknown;
  damagedRatePercent?: unknown;
  dataStatus?: unknown;
  date?: unknown;
  deliveryOrder?: unknown;
  description?: unknown;
  differenceKm?: unknown;
  distinctStores?: unknown;
  driverId?: unknown;
  driverName?: unknown;
  exceptionId?: unknown;
  failedOrders?: unknown;
  mileageComparisonStatus?: unknown;
  noSignatureAttempts?: unknown;
  orderId?: unknown;
  orderNumber?: unknown;
  orders?: unknown;
  plannedFuelCost?: unknown;
  plannedKm?: unknown;
  plannedLoadRatePercent?: unknown;
  plateNumber?: unknown;
  publishedPlannedKm?: unknown;
  publishedRoutes?: unknown;
  replacementRequiredBoxes?: unknown;
  resolution?: unknown;
  resolutionMinutes?: unknown;
  routeId?: unknown;
  routes?: unknown;
  scheduledEndAt?: unknown;
  scheduledStartAt?: unknown;
  scheduledWorkDays?: unknown;
  sequence?: unknown;
  shiftId?: unknown;
  shiftType?: unknown;
  shortageBoxes?: unknown;
  status?: unknown;
  storeId?: unknown;
  storeName?: unknown;
  startedTrips?: unknown;
  type?: unknown;
  unassignedConfirmedOrders?: unknown;
  vehicleId?: unknown;
  warehouseId?: unknown;
  warehouseName?: unknown;
  workDate?: unknown;
}
interface ReportPreview {
  summary: ReportSummaryDto;
  attendance: ReportCollectionDto;
  routes: ReportCollectionDto;
  drivers: ReportCollectionDto;
  vehicles: ReportCollectionDto;
  warehouses: ReportCollectionDto;
  stores: ReportCollectionDto;
  exceptions: ReportCollectionDto;
  orders: OrderDto[];
  storeDirectory: StoreDto[];
}

const SHEETS: ReadonlyArray<{id: PreviewSheet; label: string; icon: string}> = [
  {id: 'overview', label: '營運總覽', icon: 'dashboard'},
  {id: 'orders', label: '訂單明細', icon: 'receipt_long'},
  {id: 'routes', label: '路線里程油費', icon: 'route'},
  {id: 'attendance', label: '司機出勤', icon: 'badge'},
  {id: 'vehicles', label: '車輛使用', icon: 'local_shipping'},
  {id: 'warehouses', label: '倉庫門市', icon: 'warehouse'},
  {id: 'stores', label: '門市表現', icon: 'storefront'},
  {id: 'exceptions', label: '異常案件', icon: 'warning_amber'},
  {id: 'notes', label: '欄位說明', icon: 'help_outline'},
];

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

  readonly sheets = SHEETS;
  readonly from = signal(this.today());
  readonly to = signal(this.today());
  readonly warehouseId = signal<number | null>(null);
  readonly storeId = signal<number | null>(null);
  readonly driverId = signal<number | null>(null);
  readonly warehouses = signal<WarehouseDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly drivers = signal<DriverDto[]>([]);
  readonly preview = signal<ReportPreview | null>(null);
  readonly selectedSheet = signal<PreviewSheet>('overview');
  readonly loading = signal(false);
  readonly exporting = signal(false);
  readonly errorMessage = signal('');

  ngOnInit(): void {
    const query = this.route.snapshot.queryParamMap;
    this.from.set(query.get('from') ?? this.from());
    this.to.set(query.get('to') ?? this.to());
    this.loadFilterOptions();
    this.createPreview();
  }

  protected updateDate(bound: 'from' | 'to', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    if (bound === 'from') this.from.set(value);
    else this.to.set(value);
  }

  protected updateFilter(kind: 'warehouse' | 'store' | 'driver', event: Event): void {
    const raw = (event.target as HTMLSelectElement).value;
    const value = raw ? Number(raw) : null;
    if (kind === 'warehouse') this.warehouseId.set(value);
    if (kind === 'store') this.storeId.set(value);
    if (kind === 'driver') this.driverId.set(value);
  }

  protected selectSheet(sheet: PreviewSheet): void {
    this.selectedSheet.set(sheet);
  }

  protected createPreview(): void {
    if (!this.validRange()) return;

    this.loading.set(true);
    this.errorMessage.set('');
    const query = this.reportQuery();
    void this.router.navigate([], {relativeTo: this.route, queryParams: {from: this.from(), to: this.to()}, queryParamsHandling: 'merge'});
    forkJoin({
      summary: this.api.getReportSummary(query),
      attendance: this.api.getReportAttendance(query),
      routes: this.api.getReportRoutes(query),
      drivers: this.api.getReportDrivers(query),
      vehicles: this.api.getReportVehicles(query),
      warehouses: this.api.getReportWarehouses(query),
      stores: this.api.getReportStores(query),
      exceptions: this.api.getReportExceptions(query),
      orders: this.api.getOrders(),
      storeDirectory: this.api.getStores(),
    }).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.loading.set(false);
      },
      error: () => {
        this.preview.set(null);
        this.errorMessage.set('目前無法產生預覽，請確認後端服務與登入狀態。');
        this.loading.set(false);
      },
    });
  }

  protected async exportWorkbook(): Promise<void> {
    const preview = this.preview();
    if (!preview) return;

    this.exporting.set(true);
    this.errorMessage.set('');
    try {
      const xlsx = await import('xlsx');
      const workbook = xlsx.utils.book_new();
      this.appendSheet(xlsx, workbook, '匯出說明', [
        ['主管歷史報表'],
        ['查詢期間', `${preview.summary.from} 至 ${preview.summary.to}`],
        ['倉庫', this.selectedWarehouseLabel()],
        ['門市', this.selectedStoreLabel()],
        ['司機', this.selectedDriverLabel()],
        ['匯出時間', new Intl.DateTimeFormat('zh-TW', {dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Taipei'}).format(new Date())],
      ]);
      this.appendSheet(xlsx, workbook, '營運總覽', this.overviewExportRows(preview.summary));
      this.appendSheet(xlsx, workbook, '訂單明細', this.orderExportRows(preview));
      this.appendSheet(xlsx, workbook, '路線里程油費', this.routeExportRows(preview));
      this.appendSheet(xlsx, workbook, '司機出勤', this.attendanceExportRows(preview));
      this.appendSheet(xlsx, workbook, '車輛使用', this.vehicleExportRows(preview));
      this.appendSheet(xlsx, workbook, '倉庫門市', this.warehouseExportRows(preview));
      this.appendSheet(xlsx, workbook, '異常案件', this.exceptionExportRows(preview));
      this.appendSheet(xlsx, workbook, '欄位說明', this.notesExportRows());
      const buffer = xlsx.write(workbook, {bookType: 'xlsx', type: 'array'}) as ArrayBuffer;
      const href = URL.createObjectURL(new Blob([buffer], {type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}));
      const link = document.createElement('a');
      link.href = href;
      link.download = `主管歷史報表_${preview.summary.from}_${preview.summary.to}.xlsx`;
      link.click();
      URL.revokeObjectURL(href);
    } catch {
      this.errorMessage.set('匯出失敗，請稍後再試。');
    } finally {
      this.exporting.set(false);
    }
  }

  protected rows(collection: ReportCollectionDto, key: string): ReportRow[] {
    const value = collection[key];
    return this.asRows(value);
  }

  protected rowsFrom(value: unknown): ReportRow[] {
    return this.asRows(value);
  }

  protected orderRows(preview: ReportPreview): ReportRow[] {
    const storesById = new Map(preview.storeDirectory.filter((store) => store.id != null).map((store) => [store.id!, store]));
    const routeByOrderId = new Map<number, ReportRow>();
    for (const route of this.rows(preview.routes, 'routes')) {
      for (const routeOrder of this.asRows(route.deliveryOrder)) {
        const orderId = routeOrder.orderId;
        if (typeof orderId === 'number') routeByOrderId.set(orderId, route);
      }
    }
    return preview.orders
      .filter((order) => order.deliveryDate >= this.from() && order.deliveryDate <= this.to())
      .filter((order) => this.warehouseId() === null || order.warehouseId === this.warehouseId())
      .filter((order) => this.storeId() === null || order.storeId === this.storeId())
      .filter((order) => this.driverId() === null || order.assignedDriverId === this.driverId())
      .sort((left, right) => left.deliveryDate.localeCompare(right.deliveryDate) || left.orderNumber.localeCompare(right.orderNumber))
      .map((order) => {
        const route = order.id == null ? undefined : routeByOrderId.get(order.id);
        return {
          date: order.deliveryDate,
          orderNumber: order.orderNumber,
          sequence: order.sequence,
          storeName: storesById.get(order.storeId)?.name ?? `門市 #${order.storeId}`,
          boxes: order.boxCount,
          status: this.orderStatusLabel(order.status),
          routeId: route?.routeId,
          driverName: route?.driverName,
          plateNumber: route?.plateNumber,
          warehouseName: route?.warehouseName,
        };
      });
  }

  protected value(value: unknown, fallback = '--'): string {
    if (value === null || value === undefined || value === '') return fallback;
    if (typeof value === 'number') return Number.isInteger(value) ? `${value}` : value.toFixed(1);
    if (typeof value === 'boolean') return value ? '是' : '否';
    return String(value).replace('T', ' ');
  }

  protected percent(value: unknown): string {
    return typeof value === 'number' ? `${value.toFixed(1)}%` : '--';
  }

  protected sheetTitle(): string {
    return this.sheets.find((sheet) => sheet.id === this.selectedSheet())?.label ?? '';
  }

  private loadFilterOptions(): void {
    forkJoin({warehouses: this.api.getWarehouses(), stores: this.api.getStores(), drivers: this.api.getDrivers()}).subscribe({
      next: ({warehouses, stores, drivers}) => {
        this.warehouses.set(warehouses);
        this.stores.set(stores);
        this.drivers.set(drivers);
      },
    });
  }

  private validRange(): boolean {
    if (!this.from() || !this.to() || this.from() > this.to()) {
      this.errorMessage.set('請確認查詢起訖日期。');
      return false;
    }
    return true;
  }

  private reportQuery(): ReportQuery {
    return {
      period: 'CUSTOM', from: this.from(), to: this.to(),
      warehouseId: this.warehouseId() ?? undefined,
      storeId: this.storeId() ?? undefined,
      driverId: this.driverId() ?? undefined,
    };
  }

  private appendSheet(xlsx: typeof import('xlsx'), workbook: ReturnType<typeof xlsx.utils.book_new>, name: string, rows: unknown[][]): void {
    const sheet = xlsx.utils.aoa_to_sheet(rows);
    sheet['!freeze'] = {xSplit: 0, ySplit: rows.length > 1 ? 1 : 0};
    const widthCount = Math.max(...rows.map((row) => row.length), 1);
    sheet['!cols'] = Array.from({length: widthCount}, (_, column) => ({wch: Math.min(32, Math.max(12, ...rows.map((row) => String(row[column] ?? '').length + 2)))}));
    xlsx.utils.book_append_sheet(workbook, sheet, name);
  }

  private overviewExportRows(summary: ReportSummaryDto): unknown[][] {
    return [
      ['查詢期間', '訂單數', '箱數', '已完成', '配送中', '配送失敗', '完成率', '已發布路線', '出車司機', '出車車輛'],
      [summary.from, summary.totalOrders, summary.totalBoxes, summary.completedOrders, summary.inDeliveryOrders, summary.failedOrders, this.percent(summary.completionRatePercent), summary.publishedRoutes, summary.dispatchedDrivers, summary.dispatchedVehicles],
      [], ['日期', '訂單數', '箱數', '已完成', '完成率'],
      ...summary.dailyTrend.map((day) => [day.date, day.totalOrders, day.totalBoxes, day.completedOrders, this.percent(day.completionRatePercent)]),
    ];
  }

  private orderExportRows(preview: ReportPreview): unknown[][] {
    const data = this.orderRows(preview).map((order) => [order.date, order.orderNumber, order.sequence, order.storeName, order.boxes, order.status, order.routeId, order.driverName, order.plateNumber, order.warehouseName]);
    return [['配送日期', '訂單編號', '順序', '門市', '箱數', '狀態', '路線', '司機', '車牌', '出貨倉庫'], ...data];
  }

  private routeExportRows(preview: ReportPreview): unknown[][] {
    return [['日期', '路線', '倉庫', '司機', '車牌', '門市數', '訂單數', '箱數', '裝載率', '預估公里', '實際公里', '里程差異', '預估油費', '里程狀態'], ...this.rows(preview.routes, 'routes').map((row) => [row.date, row.routeId, row.warehouseName, row.driverName, row.plateNumber, row.distinctStores, row.orders, row.boxes, this.percent(row.plannedLoadRatePercent), row.plannedKm, row.actualKm, row.differenceKm, row.plannedFuelCost, row.mileageComparisonStatus])];
  }

  private attendanceExportRows(preview: ReportPreview): unknown[][] {
    return [
      ['日期', '司機', '班別', '預定上班', '打卡上班', '預定下班', '打卡下班', '工作分鐘', '資料狀態'],
      ...this.rows(preview.attendance, 'shifts').map((row) => [row.workDate, row.driverName, row.shiftType, row.scheduledStartAt, row.clockInAt, row.scheduledEndAt, row.clockOutAt, row.clockSpanMinutes, row.dataStatus]),
      [],
      ['司機工作摘要'],
      ['司機', '排班天數', '出車趟數', '已發布路線', '訂單數', '已完成', '失敗', '預估公里', '實際公里'],
      ...this.rows(preview.drivers, 'drivers').map((row) => [row.driverName, row.scheduledWorkDays, row.startedTrips, row.publishedRoutes, row.assignedOrders, row.completedOrders, row.failedOrders, row.plannedKm, row.actualKm]),
    ];
  }

  private vehicleExportRows(preview: ReportPreview): unknown[][] {
    return [['車牌', '倉庫', '出車路線', '已發布路線', '訂單數', '箱數', '門市數', '平均裝載率', '預估公里', '實際公里', '里程狀態'], ...this.rows(preview.vehicles, 'vehicles').map((row) => [row.plateNumber, row.warehouseId, row.assignedRoutes, row.publishedRoutes, row.orders, row.boxes, row.distinctStores, this.percent(row.averageLoadRatePercent), row.publishedPlannedKm, row.actualKm, row.actualMileageStatus])];
  }

  private warehouseExportRows(preview: ReportPreview): unknown[][] {
    const warehouses = this.rows(preview.warehouses, 'warehouses').map((row) => ['倉庫', row.warehouseName, row.orders, row.boxes, row.distinctStores, row.routes, row.publishedRoutes, row.unassignedConfirmedOrders, row.completedOrders, this.percent(row.averageLoadRatePercent)]);
    const stores = this.rows(preview.stores, 'stores').map((row) => ['門市', row.storeName, row.orders, row.boxes, '--', '--', '--', '--', row.completedOrders, this.percent(row.damagedRatePercent)]);
    return [['類型', '名稱', '訂單數', '箱數', '門市數', '路線數', '已發布路線', '待排訂單', '已完成', '補充指標'], ...warehouses, ...stores];
  }

  private exceptionExportRows(preview: ReportPreview): unknown[][] {
    return [['建立時間', '異常類型', '狀態', '訂單', '倉庫', '門市', '司機', '路線', '異常原因', '處理結果', '處理時間分鐘'], ...this.rows(preview.exceptions, 'cases').map((row) => [row.createdAt, row.type, row.status, row.orderNumber, row.warehouseId, row.storeId, row.driverId, row.routeId, row.description, row.resolution, row.resolutionMinutes])];
  }

  private notesExportRows(): unknown[][] {
    return [['欄位', '說明'], ['預估公里與油費', '依發布路線的規劃資料計算，沒有資料時留白。'], ['實際公里', '由行車里程紀錄提供，沒有完成紀錄時留白。'], ['完成率', '已完成訂單除以可計算完成率的訂單。'], ['司機與車輛', '只在後端能對應到已發布路線或異常案件時顯示。'], ['異常原因', '依異常案件的描述與處理結果顯示，不會用預設文字代替。']];
  }

  private asRows(value: unknown): ReportRow[] {
    return Array.isArray(value) ? value.filter((row): row is ReportRow => typeof row === 'object' && row !== null) : [];
  }

  private orderStatusLabel(status: OrderDto['status']): string {
    return {
      PENDING_CONFIRM: '待確認',
      CONFIRMED: '待排車',
      IN_DELIVERY: '配送中',
      COMPLETED: '已完成',
      CANCELLED: '已取消',
      FAILED: '配送失敗',
    }[status];
  }

  private selectedWarehouseLabel(): string { return this.warehouses().find((warehouse) => warehouse.id === this.warehouseId())?.name ?? '全部倉庫'; }
  private selectedStoreLabel(): string { return this.stores().find((store) => store.id === this.storeId())?.name ?? '全部門市'; }
  private selectedDriverLabel(): string { return this.drivers().find((driver) => driver.id === this.driverId())?.name ?? '全部司機'; }
  private today(): string { return new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Taipei'}).format(new Date()); }
}

import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { forkJoin, Observable } from 'rxjs';
import {MatIconModule} from '@angular/material/icon';
import {MatSelectModule} from '@angular/material/select';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { AdminThemeService } from '../../../../core/theme/admin-theme.service';
import {
  AdminUserCreateRequest,
  AdminUserDto,
  DriverDto,
  StoreDto,
  StoreStatus,
  VehicleDto,
  VehicleMaintenanceRecord,
  VehicleMaintenanceSummary,
  VehicleMileageCorrection,
  WarehouseDto,
} from '../../../../core/services/dispatch-api.models';

type ResourceView = 'vehicles' | 'drivers' | 'stores' | 'warehouses';
type VehicleResourceStatus = '待派車' | '保養中' | '維修中' | '已退役';
/** 行車紀錄器里程與保養基準：新增時可以填；編輯時只有原本是空的（舊車）才能補一次 */
type VehicleMileageField = 'currentOdometerKm' | 'lastMinorMaintenanceKm' | 'lastMajorMaintenanceKm';
/** 這台車的保養與退役規則：三個一起填或都留白，隨時可以改 */
type VehicleMaintenanceRuleField = 'minorMaintenanceIntervalKm' | 'majorMaintenanceIntervalKm' | 'retirementKm';
/** 更正里程的輸入框；用字串，送出時再轉數字，留白＝不改 */
interface MileageCorrectionForm {
  currentOdometerKm: string;
  lastMinorMaintenanceKm: string;
  lastMajorMaintenanceKm: string;
  reason: string;
}
type StoreResourceStatus = '營業中' | '暫停營業';
type WarehouseResourceStatus = '啟用' | '停用';
type ResourceForm =
  | 'admin'
  | 'driver'
  | 'edit-driver'
  | 'vehicle'
  | 'edit-vehicle'
  | 'store'
  | 'edit-store'
  | 'warehouse'
  | 'edit-warehouse'
  | null;

type DeleteTargetKind = 'vehicle' | 'store' | 'warehouse';

interface DeleteTarget {
  kind: DeleteTargetKind;
  id: number;
  name: string;
}

const deleteTargetLabels: Record<DeleteTargetKind, string> = {
  vehicle: '車輛',
  store: '店家',
  warehouse: '倉庫',
};

interface StoreResource {
  backendId?: number;
  isActive: boolean;
  id: string;
  name: string;
  status: StoreResourceStatus;
  address: string;
  location: string;
  contact: string;
  hours: string;
}

interface WarehouseResource {
  backendId?: number;
  isActive: boolean;
  id: string;
  name: string;
  status: WarehouseResourceStatus;
  address: string;
  location: string;
  phone: string;
}

function emptyAdminUser(): AdminUserCreateRequest {
  return {
    account: '',
    password: '',
    name: '',
    phone: '',
  };
}

function emptyDriver(warehouseId?: number): DriverDto {
  return {
    warehouseId,
    account: '',
    password: '',
    name: '',
    phone: '',
    workStart: '08:00',
    workEnd: '17:00',
    restDuration: 60,
    maxOvertimeMinutes: 0,
    isActive: true,
  };
}

function emptyStore(): StoreDto {
  return {
    storeCode: '',
    name: '',
    address: '',
    lat: 22.6273,
    lng: 120.3014,
    contactName: '',
    phone: '',
    receivingStart: '09:00',
    receivingEnd: '18:00',
    notes: '',
    status: 'ACTIVE',
  };
}

function emptyWarehouse(): WarehouseDto {
  return {
    warehouseCode: '',
    name: '',
    address: '',
    lat: 22.6273,
    lng: 120.3014,
    phone: '',
    isActive: true,
  };
}

function emptyVehicle(warehouseId = 0): VehicleDto {
  return {
    warehouseId,
    plateNumber: '',
    vehicleType: '',
    capacity: 0,
    fuelConsumption: undefined,
    status: 'AVAILABLE',
    minorMaintenanceIntervalKm: null,
    majorMaintenanceIntervalKm: null,
    retirementKm: null,
    currentOdometerKm: null,
    lastMinorMaintenanceKm: null,
    lastMajorMaintenanceKm: null,
  };
}

/** 更正里程的輸入框先放目前的值，主管只改打錯的那格 */
function correctionFormOf(vehicle: VehicleDto): MileageCorrectionForm {
  const text = (value: number | null | undefined) => (value == null ? '' : String(value));
  return {
    currentOdometerKm: text(vehicle.currentOdometerKm),
    lastMinorMaintenanceKm: text(vehicle.lastMinorMaintenanceKm),
    lastMajorMaintenanceKm: text(vehicle.lastMajorMaintenanceKm),
    reason: '',
  };
}

interface VehicleResource {
  backendId?: number;
  id: string;
  type: string;
  capacity: string;
  status: VehicleResourceStatus;
  driver: string;
  assignment: string;
  inspection: string;
  /** 保養狀況給清單上色：blocked 紅、warning 黃、unknown 灰 */
  maintenanceTone: 'normal' | 'warning' | 'blocked' | 'unknown';
}

@Component({
  selector: 'app-resource-overview',
  imports: [
    MatIconModule,
    MatSelectModule,
    DatePipe,
    DecimalPipe,
  ],
  templateUrl: './resource-overview.html',
  styleUrl: './resource-overview.scss',
})
export class ResourceOverview implements OnInit {
  private readonly api = inject(DispatchApiService);
  // 下拉選單的選項面板開在 body 底下，吃不到後台深淺色：mat-select 的 panelClass 要帶 theme.dialogPanelClass()
  protected readonly theme = inject(AdminThemeService);

  readonly activeView = signal<ResourceView>('vehicles');
  readonly activeFilter = signal('all');
  readonly searchTerm = signal('');
  readonly drivers = signal<DriverDto[]>([]);
  readonly driverWarehouseFilter = signal<number | 'all'>('all');
  readonly editingDriverId = signal<number | null>(null);
  readonly vehicles = signal<VehicleDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly warehouses = signal<WarehouseDto[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly updatedAt = signal('--:--');
  readonly activeForm = signal<ResourceForm>(null);
  readonly adminForm = signal<AdminUserCreateRequest>(emptyAdminUser());
  readonly driverForm = signal<DriverDto>(emptyDriver());
  readonly storeForm = signal<StoreDto>(emptyStore());
  readonly warehouseForm = signal<WarehouseDto>(emptyWarehouse());
  readonly vehicleForm = signal<VehicleDto>(emptyVehicle());
  readonly editingVehicleId = signal<number | null>(null);
  /** 開啟編輯時的原始資料：判斷里程、基準原本是不是空的（空的才能補一次） */
  private readonly vehicleOriginal = signal<VehicleDto | null>(null);
  readonly maintenanceHistory = signal<VehicleMaintenanceRecord[]>([]);
  readonly historyLoading = signal(false);
  readonly historyError = signal('');

  // ── 主管更正里程（打錯時用，在編輯車輛的表單裡展開） ─────
  readonly correctionOpen = signal(false);
  readonly correctionForm = signal<MileageCorrectionForm>(correctionFormOf(emptyVehicle()));
  readonly correctionSaving = signal(false);
  readonly correctionError = signal('');
  readonly mileageCorrections = signal<VehicleMileageCorrection[]>([]);

  // ── 全車共用的保養提醒設定（獨立的視窗，不放進 activeForm） ─────
  readonly settingsOpen = signal(false);
  readonly settingsLoading = signal(false);
  readonly settingsSaving = signal(false);
  readonly settingsError = signal('');
  readonly warningKm = signal('500');
  readonly editingStoreId = signal<number | null>(null);
  readonly editingWarehouseId = signal<number | null>(null);
  readonly formError = signal('');
  readonly isSaving = signal(false);
  readonly changingStoreStatusId = signal<number | null>(null);
  readonly deleteTarget = signal<DeleteTarget | null>(null);
  readonly isDeleting = signal(false);

  readonly vehicleFilters = ['all', '待派車', '保養中', '維修中', '已退役'];
  readonly storeFilters = ['all', '營業中', '暫停營業'];
  readonly warehouseFilters = ['all', '啟用', '停用'];
  readonly driverFilters = ['all', '在職', '停用', '待設定倉庫'];
  readonly activeDriverCount = computed(() => this.drivers().filter(driver => driver.isActive).length);
  readonly visibleDrivers = computed(() => {
    const term = this.searchTerm().trim().toLowerCase();
    const status = this.activeFilter();
    const warehouseId = this.driverWarehouseFilter();
    return this.drivers().filter(driver => {
      const warehouse = this.driverWarehouse(driver);
      return (warehouseId === 'all' || driver.warehouseId === warehouseId)
        && (status === 'all' || (status === '在職' && driver.isActive) || (status === '停用' && !driver.isActive)
          || (status === '待設定倉庫' && driver.warehouseId == null))
        && (!term || `${driver.name} ${driver.account} ${driver.phone ?? ''} ${warehouse?.name ?? ''} ${warehouse?.warehouseCode ?? ''}`.toLowerCase().includes(term));
    });
  });

  driverWarehouse(driver: DriverDto): WarehouseDto | undefined {
    return this.warehouses().find(warehouse => warehouse.id === driver.warehouseId);
  }

  readonly visibleVehicles = computed<VehicleResource[]>(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.vehicles()
      .map((vehicle) => this.toVehicleResource(vehicle))
      .filter((vehicle) => {
        const matchesFilter = filter === 'all' || vehicle.status === filter;
        const source =
          `${vehicle.id} ${vehicle.type} ${vehicle.driver} ${vehicle.assignment}`.toLowerCase();
        return matchesFilter && (!term || source.includes(term));
      });
  });

  readonly visibleStores = computed<StoreResource[]>(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.stores()
      .map((store) => this.toStoreResource(store))
      .filter((store) => {
        const matchesFilter = filter === 'all' || store.status === filter;
        const source = `${store.id} ${store.name} ${store.address} ${store.contact}`.toLowerCase();
        return matchesFilter && (!term || source.includes(term));
      });
  });

  readonly visibleWarehouses = computed<WarehouseResource[]>(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.warehouses()
      .map((warehouse) => this.toWarehouseResource(warehouse))
      .filter((warehouse) => {
        const matchesFilter = filter === 'all' || warehouse.status === filter;
        const source =
          `${warehouse.id} ${warehouse.name} ${warehouse.address} ${warehouse.phone}`.toLowerCase();
        return matchesFilter && (!term || source.includes(term));
      });
  });

  readonly currentFilters = computed(() => {
    if (this.activeView() === 'drivers') return this.driverFilters;
    if (this.activeView() === 'stores') {
      return this.storeFilters;
    }

    if (this.activeView() === 'warehouses') {
      return this.warehouseFilters;
    }

    return this.vehicleFilters;
  });

  readonly availableVehicleCount = computed(
    () => this.vehicles().filter((vehicle) => vehicle.status === 'AVAILABLE').length,
  );
  readonly attentionResourceCount = computed(
    () =>
      this.vehicles().filter((vehicle) => this.isInMaintenance(vehicle.status)).length +
      this.stores().filter((store) => store.status === 'SUSPENDED').length +
      this.warehouses().filter((warehouse) => !warehouse.isActive).length,
  );

  /** 新增車輛時的預設倉庫：第一個啟用中的倉庫，沒有就退回第一筆 */
  readonly defaultWarehouseId = computed(() => {
    const warehouses = this.warehouses();
    return warehouses.find((warehouse) => warehouse.isActive)?.id ?? warehouses[0]?.id ?? 0;
  });

  ngOnInit(): void {
    this.loadResources();
  }

  setView(view: ResourceView): void {
    this.activeView.set(view);
    this.activeFilter.set('all');
    this.searchTerm.set('');
    this.driverWarehouseFilter.set('all');
  }

  openCreateAdmin(): void {
    this.adminForm.set(emptyAdminUser());
    this.formError.set('');
    this.activeForm.set('admin');
  }

  openCreateDriver(): void {
    this.setView('drivers');
    if (!this.defaultWarehouseId()) {
      this.errorMessage.set('請先建立倉庫，再新增司機。');
      return;
    }
    this.driverForm.set(emptyDriver(this.defaultWarehouseId()));
    this.editingDriverId.set(null);
    this.formError.set('');
    this.activeForm.set('driver');
  }

  openEditDriver(driver: DriverDto): void {
    if (driver.id == null) return;
    const {password, ...editable} = driver;
    this.driverForm.set({...editable});
    this.editingDriverId.set(driver.id);
    this.formError.set('');
    this.activeForm.set('edit-driver');
  }

  updateDriverWarehouse(warehouseId: number): void {
    this.driverForm.update(form => ({...form, warehouseId}));
  }

  updateDriverNumber(field: 'restDuration' | 'maxOvertimeMinutes', event: Event): void {
    this.driverForm.update(form => ({...form, [field]: Number((event.target as HTMLInputElement).value)}));
  }

  updateDriverActive(event: Event): void {
    this.driverForm.update(form => ({...form, isActive: (event.target as HTMLInputElement).checked}));
  }

  openCreateStore(): void {
    this.activeView.set('stores');
    this.storeForm.set(emptyStore());
    this.editingStoreId.set(null);
    this.formError.set('');
    this.activeForm.set('store');
  }

  openCreateVehicle(): void {
    this.activeView.set('vehicles');

    const warehouseId = this.defaultWarehouseId();
    if (!warehouseId) {
      this.errorMessage.set('請先建立倉庫，再新增車輛。');
      return;
    }

    this.vehicleForm.set(emptyVehicle(warehouseId));
    this.editingVehicleId.set(null);
    this.vehicleOriginal.set(null);
    this.maintenanceHistory.set([]);
    this.mileageCorrections.set([]);
    this.correctionOpen.set(false);
    this.formError.set('');
    this.activeForm.set('vehicle');
  }

  openEditVehicle(vehicle: VehicleResource): void {
    if (vehicle.backendId === undefined) {
      this.errorMessage.set('找不到車輛編號，無法編輯。');
      return;
    }

    const source = this.vehicles().find((item) => item.id === vehicle.backendId);
    if (!source) {
      this.errorMessage.set('找不到車輛資料，請重新載入後再試。');
      return;
    }

    this.vehicleForm.set({ ...source });
    this.editingVehicleId.set(vehicle.backendId);
    this.vehicleOriginal.set(source);
    this.correctionOpen.set(false);
    this.formError.set('');
    this.activeForm.set('edit-vehicle');
    this.loadMaintenanceHistory(vehicle.backendId);
    this.loadMileageCorrections(vehicle.backendId);
  }

  /**
   * 行車紀錄器里程與保養基準能不能填：新增車輛時都可以；
   * 編輯時只有原本是空的（功能上線前的舊車）能補一次，有值之後只能由出車、收車、保養完成更新（後端也會擋）
   */
  canFillMileage(field: VehicleMileageField): boolean {
    const original = this.vehicleOriginal();
    return original === null || original[field] == null;
  }

  // ── 主管更正里程 ──────────────────────────────────────

  /** 編輯時，里程或基準至少有一個已經有值才需要更正；都還是空的就直接在上面補 */
  canCorrectMileage(): boolean {
    const original = this.vehicleOriginal();
    return original !== null && (
      original.currentOdometerKm != null
      || original.lastMinorMaintenanceKm != null
      || original.lastMajorMaintenanceKm != null
    );
  }

  openMileageCorrection(): void {
    const original = this.vehicleOriginal();
    if (!original) {
      return;
    }
    this.correctionForm.set(correctionFormOf(original));
    this.correctionError.set('');
    this.correctionOpen.set(true);
  }

  closeMileageCorrection(): void {
    if (!this.correctionSaving()) {
      this.correctionOpen.set(false);
    }
  }

  updateCorrectionField(field: keyof MileageCorrectionForm, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.correctionForm.update((form) => ({...form, [field]: value}));
  }

  /**
   * 送出更正：留白或跟現在一樣的格子不改（後端也照這個規則），原因一定要寫。
   * 成功後只換掉表單裡的里程、基準和保養狀況，主管在上面改到一半的其他欄位不會被蓋掉
   */
  submitMileageCorrection(): void {
    const vehicleId = this.editingVehicleId();
    if (vehicleId === null || this.correctionSaving()) {
      return;
    }
    const input = this.correctionForm();
    const reason = input.reason.trim();
    if (!reason) {
      this.correctionError.set('請寫更正的原因。');
      return;
    }
    const texts = [input.currentOdometerKm, input.lastMinorMaintenanceKm, input.lastMajorMaintenanceKm]
      .map((text) => text.trim());
    if (texts.some((text) => text !== '' && !(Number.isInteger(Number(text)) && Number(text) >= 0))) {
      this.correctionError.set('里程要是 0 以上的整數。');
      return;
    }
    const [current, minor, major] = texts.map((text) => (text === '' ? null : Number(text)));

    this.correctionSaving.set(true);
    this.correctionError.set('');
    this.api.correctVehicleMileage(vehicleId, {
      currentOdometerKm: current,
      lastMinorMaintenanceKm: minor,
      lastMajorMaintenanceKm: major,
      reason,
    }).subscribe({
      next: (vehicle) => {
        this.correctionSaving.set(false);
        this.correctionOpen.set(false);
        this.vehicles.update((items) => items.map((item) => (item.id === vehicle.id ? vehicle : item)));
        this.vehicleOriginal.set(vehicle);
        this.vehicleForm.update((form) => ({
          ...form,
          currentOdometerKm: vehicle.currentOdometerKm,
          lastMinorMaintenanceKm: vehicle.lastMinorMaintenanceKm,
          lastMajorMaintenanceKm: vehicle.lastMajorMaintenanceKm,
          maintenance: vehicle.maintenance,
        }));
        this.loadMileageCorrections(vehicleId);
      },
      error: (error: unknown) => {
        this.correctionError.set(this.errorText(error, '更正失敗，請稍後再試。'));
        this.correctionSaving.set(false);
      },
    });
  }

  correctionFieldLabel(correction: VehicleMileageCorrection): string {
    if (correction.field === 'MINOR_BASELINE') {
      return '小保基準';
    }
    if (correction.field === 'MAJOR_BASELINE') {
      return '大保基準';
    }
    return '行車紀錄器里程';
  }

  private loadMileageCorrections(vehicleId: number): void {
    this.mileageCorrections.set([]);
    this.api.getVehicleMileageCorrections(vehicleId).subscribe({
      next: (corrections) => this.mileageCorrections.set(corrections),
      // 更正紀錄只是參考，載不到也不擋編輯
      error: () => this.mileageCorrections.set([]),
    });
  }

  isInMaintenance(status: VehicleDto['status']): boolean {
    return status === 'MAINTENANCE' || status === 'MINOR_MAINTENANCE' || status === 'MAJOR_MAINTENANCE';
  }

  /** 進行中的送修取消：不計次數、不更新基準，車輛改回可用 */
  cancelVehicleMaintenance(): void {
    const vehicleId = this.editingVehicleId();
    if (vehicleId === null || this.isSaving()) {
      return;
    }
    this.isSaving.set(true);
    this.formError.set('');
    this.api.cancelMaintenance(vehicleId).subscribe({
      next: () => {
        this.isSaving.set(false);
        this.reloadVehicle(vehicleId);
      },
      error: (error: unknown) => {
        this.formError.set(this.errorText(error, '取消送修失敗，請稍後再試。'));
        this.isSaving.set(false);
      },
    });
  }

  maintenanceTypeLabel(record: VehicleMaintenanceRecord): string {
    if (record.type === 'MINOR') {
      return '小保';
    }
    if (record.type === 'MAJOR') {
      return '大保';
    }
    return '維修';
  }

  maintenanceStatusLabel(record: VehicleMaintenanceRecord): string {
    if (record.status === 'ACTIVE') {
      return '進行中';
    }
    if (record.status === 'COMPLETED') {
      return '已完成';
    }
    return '已取消';
  }

  /** 剩下的公里數：負數寫成「已超過」，算不出來寫「待補資料」 */
  remainingKm(value: number | null): string {
    if (value === null) {
      return '待補資料';
    }
    if (value < 0) {
      return `已超過 ${(-value).toLocaleString('zh-TW')} km`;
    }
    return `${value.toLocaleString('zh-TW')} km`;
  }

  // ── 全車共用的保養提醒設定 ────────────────────────────

  openMaintenanceSettings(): void {
    this.settingsOpen.set(true);
    this.settingsLoading.set(true);
    this.settingsError.set('');
    this.api.getMaintenanceSettings().subscribe({
      next: (settings) => {
        this.warningKm.set(String(settings.warningKm));
        this.settingsLoading.set(false);
      },
      error: (error: unknown) => {
        this.settingsError.set(this.errorText(error, '設定載入失敗，請關閉後再試。'));
        this.settingsLoading.set(false);
      },
    });
  }

  closeMaintenanceSettings(): void {
    if (!this.settingsSaving()) {
      this.settingsOpen.set(false);
    }
  }

  updateWarningKm(event: Event): void {
    this.warningKm.set((event.target as HTMLInputElement).value.trim());
  }

  saveMaintenanceSettings(): void {
    if (this.settingsSaving()) {
      return;
    }
    // 留白時 Number('') 是 0，要先擋掉，不然會存成「剩 0 公里才提醒」
    const warningKm = Number(this.warningKm());
    if (this.warningKm() === '' || !Number.isInteger(warningKm) || warningKm < 0) {
      this.settingsError.set('提前提醒公里數要是 0 以上的整數。');
      return;
    }

    this.settingsSaving.set(true);
    this.settingsError.set('');
    this.api.saveMaintenanceSettings({warningKm}).subscribe({
      next: () => {
        this.settingsSaving.set(false);
        this.settingsOpen.set(false);
        // 提醒範圍一改，每台車是「提醒」還是「正常」可能跟著變，重抓車輛
        this.api.getVehicles().subscribe((vehicles) => this.vehicles.set(vehicles));
      },
      error: (error: unknown) => {
        this.settingsError.set(this.errorText(error, '設定儲存失敗，請稍後再試。'));
        this.settingsSaving.set(false);
      },
    });
  }

  private loadMaintenanceHistory(vehicleId: number): void {
    this.historyLoading.set(true);
    this.historyError.set('');
    this.maintenanceHistory.set([]);
    this.api.getMaintenanceHistory(vehicleId).subscribe({
      next: (history) => {
        this.maintenanceHistory.set(history);
        this.historyLoading.set(false);
      },
      error: () => {
        this.historyError.set('保養紀錄載入失敗。');
        this.historyLoading.set(false);
      },
    });
  }

  /** 取消送修後重抓這台車：狀態、保養狀況、歷史都變了 */
  private reloadVehicle(vehicleId: number): void {
    this.api.getVehicle(vehicleId).subscribe((vehicle) => {
      this.vehicles.update((items) => items.map((item) => (item.id === vehicle.id ? vehicle : item)));
      this.vehicleForm.set({...vehicle});
      this.vehicleOriginal.set(vehicle);
      this.loadMaintenanceHistory(vehicleId);
    });
  }

  /** 後端的錯誤訊息（例如「小保基準已經有紀錄…」）直接顯示，拿不到才用預設的 */
  private errorText(error: unknown, fallback: string): string {
    const body = error instanceof HttpErrorResponse ? error.error : (error as {error?: {message?: unknown}} | null)?.error;
    if (typeof body?.message === 'string') {
      return body.message;
    }
    return fallback;
  }

  openEditStore(store: StoreResource): void {
    if (store.backendId === undefined) {
      this.errorMessage.set('找不到店家編號，無法編輯。');
      return;
    }

    const source = this.stores().find((item) => item.id === store.backendId);
    if (!source) {
      this.errorMessage.set('找不到店家資料，請重新載入後再試。');
      return;
    }

    this.storeForm.set({ ...source });
    this.editingStoreId.set(store.backendId);
    this.formError.set('');
    this.activeForm.set('edit-store');
  }

  openCreateWarehouse(): void {
    this.activeView.set('warehouses');
    this.warehouseForm.set(emptyWarehouse());
    this.editingWarehouseId.set(null);
    this.formError.set('');
    this.activeForm.set('warehouse');
  }

  openEditWarehouse(warehouse: WarehouseResource): void {
    if (warehouse.backendId === undefined) {
      this.errorMessage.set('找不到倉庫編號，無法編輯。');
      return;
    }

    const source = this.warehouses().find((item) => item.id === warehouse.backendId);
    if (!source) {
      this.errorMessage.set('找不到倉庫資料，請重新載入後再試。');
      return;
    }

    this.warehouseForm.set({ ...source });
    this.editingWarehouseId.set(warehouse.backendId);
    this.formError.set('');
    this.activeForm.set('edit-warehouse');
  }

  closeForm(): void {
    if (!this.isSaving() && !this.correctionSaving()) {
      this.activeForm.set(null);
      this.correctionOpen.set(false);
      this.editingVehicleId.set(null);
      this.editingDriverId.set(null);
      this.editingStoreId.set(null);
      this.editingWarehouseId.set(null);
      this.formError.set('');
    }
  }

  updateAdminText(field: keyof AdminUserCreateRequest, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.adminForm.update((form) => ({ ...form, [field]: value }));
  }

  updateDriverText(field: 'account' | 'name' | 'phone' | 'password' | 'workStart' | 'workEnd', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.driverForm.update((form) => ({...form, [field]: value}));
  }

  updateVehicleText(field: 'plateNumber' | 'vehicleType', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.vehicleForm.update((form) => ({ ...form, [field]: value }));
  }

  updateVehicleNumber(field: 'capacity' | 'fuelConsumption', event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.vehicleForm.update((form) => ({
      ...form,
      [field]: value === '' && field === 'fuelConsumption' ? undefined : Number(value),
    }));
  }

  /**
   * 保養間隔、行車紀錄器里程、保養基準：留白＝null。
   * 間隔留白就是清掉；里程和基準有值之後輸入框唯讀，留白代表後端不改或還沒有
   */
  updateVehicleOptionalNumber(field: VehicleMaintenanceRuleField | VehicleMileageField, event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.vehicleForm.update((form) => ({...form, [field]: value === '' ? null : Number(value)}));
  }

  updateVehicleWarehouse(event: Event): void {
    const warehouseId = Number((event.target as HTMLSelectElement).value);
    this.vehicleForm.update((form) => ({ ...form, warehouseId }));
  }

  updateVehicleStatus(event: Event): void {
    const status = (event.target as HTMLSelectElement).value as VehicleDto['status'];
    this.vehicleForm.update((form) => ({ ...form, status }));
  }

  updateStoreText(
    field:
      | 'storeCode'
      | 'name'
      | 'address'
      | 'contactName'
      | 'phone'
      | 'receivingStart'
      | 'receivingEnd'
      | 'notes',
    event: Event,
  ): void {
    const value = (event.target as HTMLInputElement).value;
    this.storeForm.update((form) => ({ ...form, [field]: value }));
  }

  updateStoreNumber(field: 'lat' | 'lng', event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.storeForm.update((form) => ({ ...form, [field]: value }));
  }

  updateStoreStatus(event: Event): void {
    const status = (event.target as HTMLSelectElement).value as StoreStatus;
    this.storeForm.update((form) => ({ ...form, status }));
  }

  updateWarehouseText(field: 'warehouseCode' | 'name' | 'address' | 'phone', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.warehouseForm.update((form) => ({ ...form, [field]: value }));
  }

  updateWarehouseNumber(field: 'lat' | 'lng', event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.warehouseForm.update((form) => ({ ...form, [field]: value }));
  }

  updateWarehouseActive(event: Event): void {
    const isActive = (event.target as HTMLInputElement).checked;
    this.warehouseForm.update((form) => ({ ...form, isActive }));
  }

  submitAdmin(): void {
    const admin = this.adminForm();
    const account = admin.account.trim();
    const name = admin.name.trim();
    const phone = admin.phone.trim();

    if (!account || !name || !phone || !admin.password) {
      this.formError.set('請填寫主管姓名、手機號碼、登入帳號與密碼。');
      return;
    }

    if (admin.password.length < 8 || admin.password.length > 12) {
      this.formError.set('主管密碼長度必須介於 8 到 12 個字元。');
      return;
    }

    this.saveResource(
      this.api.createAdminUser({ account, name, phone, password: admin.password }),
      () => {
        this.updatedAt.set(this.formatCurrentTime());
      },
    );
  }

  submitDriver(): void {
    const driver = this.driverForm();
    const editingId = this.editingDriverId();
    const isEditing = this.activeForm() === 'edit-driver';
    const password = driver.password?.trim() ?? '';

    if (!driver.account.trim() || !driver.name.trim() || !driver.phone?.trim()) {
      this.formError.set('請填寫司機姓名、手機號碼與登入帳號。');
      return;
    }

    if (!/^09\d{8}$/.test(driver.phone.trim())) {
      this.formError.set('請輸入 09 開頭的 10 位數手機號碼。');
      return;
    }

    if (!Number.isInteger(driver.warehouseId) || driver.warehouseId! <= 0) {
      this.formError.set('請選擇司機的所屬倉庫。');
      return;
    }
    if (!driver.workStart || !driver.workEnd) {
      this.formError.set('請填寫上班與下班時間。'); return;
    }
    if (![driver.restDuration, driver.maxOvertimeMinutes ?? 0].every(value => Number.isInteger(value) && value >= 0)) {
      this.formError.set('休息時間與加班上限需為 0 或正整數。'); return;
    }
    if (!isEditing && !/^[A-Z][12]\d{8}$/.test(password)) {
      this.formError.set('請填寫大寫的台灣身分證字號作為初始密碼。'); return;
    }
    if (isEditing && editingId === null) {
      this.formError.set('找不到要修改的司機。'); return;
    }
    const {password: ignoredPassword, warehouseName, warehouseCode, profilePhotoUrl, ...fields} = driver;
    const payload: DriverDto = {...fields, account: driver.account.trim(), name: driver.name.trim(), phone: driver.phone.trim()};
    if (!isEditing) payload.password = password;
    this.saveResource(isEditing ? this.api.updateDriver(editingId!, payload) : this.api.createDriver(payload), saved => {
      this.drivers.update(items => isEditing ? items.map(item => item.id === saved.id ? saved : item) : [...items, saved]);
      this.updatedAt.set(this.formatCurrentTime());
    });
  }

  submitVehicle(): void {
    const vehicle = this.vehicleForm();
    const editingId = this.editingVehicleId();
    const isEditing = this.activeForm() === 'edit-vehicle';

    if (!vehicle.plateNumber.trim()) {
      this.formError.set('請填寫車牌號碼。');
      return;
    }

    if (!Number.isInteger(vehicle.warehouseId) || vehicle.warehouseId <= 0) {
      this.formError.set('請選擇所屬倉庫。');
      return;
    }

    if (!Number.isFinite(vehicle.capacity) || vehicle.capacity < 0) {
      this.formError.set('車輛容量必須是 0 或正整數。');
      return;
    }

    const ruleError = this.maintenanceRuleError(vehicle);
    if (ruleError) {
      this.formError.set(ruleError);
      return;
    }

    if (isEditing) {
      if (editingId === null) {
        this.formError.set('找不到要修改的車輛。');
        return;
      }

      this.saveResource(this.api.updateVehicle(editingId, vehicle), (updatedVehicle) => {
        this.vehicles.update((items) =>
          items.map((item) => (item.id === updatedVehicle.id ? updatedVehicle : item)),
        );
        this.updatedAt.set(this.formatCurrentTime());
      });
      return;
    }

    this.saveResource(this.api.createVehicle(vehicle), (createdVehicle) => {
      this.vehicles.update((items) => [...items, createdVehicle]);
      this.updatedAt.set(this.formatCurrentTime());
    });
  }

  /**
   * 保養間隔和退役總里程三個一起填或都留白（分開填容易漏掉退役總里程，那台車就永遠不會因為該退役被擋），
   * 要是正整數，大保間隔不能比小保短。後端也會擋，這裡先在送出前講清楚
   */
  private maintenanceRuleError(vehicle: VehicleDto): string {
    const minor = vehicle.minorMaintenanceIntervalKm ?? null;
    const major = vehicle.majorMaintenanceIntervalKm ?? null;
    const retirement = vehicle.retirementKm ?? null;
    if (minor === null && major === null && retirement === null) {
      return '';
    }
    if (minor === null || major === null || retirement === null) {
      return '小保間隔、大保間隔、退役總里程要一起填，或都留白。';
    }
    if ([minor, major, retirement].some((value) => !Number.isInteger(value) || value <= 0)) {
      return '保養間隔和退役總里程要是正整數。';
    }
    if (major < minor) {
      return '大保間隔不能比小保短。';
    }
    return '';
  }

  requestDeleteVehicle(vehicle: VehicleResource): void {
    if (vehicle.backendId === undefined) {
      this.errorMessage.set('找不到車輛編號，無法刪除。');
      return;
    }

    this.deleteTarget.set({ kind: 'vehicle', id: vehicle.backendId, name: vehicle.id });
  }

  submitStore(): void {
    const store = this.storeForm();
    const editingId = this.editingStoreId();
    const isEditing = this.activeForm() === 'edit-store';
    if (!store.storeCode.trim() || !store.name.trim()) {
      this.formError.set('請填寫店家代碼與店家名稱。');
      return;
    }

    if (isEditing) {
      if (editingId === null) {
        this.formError.set('找不到要修改的店家。');
        return;
      }

      this.saveResource(this.api.updateStore(editingId, store), (updatedStore) => {
        this.stores.update((items) =>
          items.map((item) => (item.id === updatedStore.id ? updatedStore : item)),
        );
        this.updatedAt.set(this.formatCurrentTime());
      });
      return;
    }

    this.saveResource(this.api.createStore(store), (createdStore) => {
      this.stores.update((items) => [...items, createdStore]);
      this.updatedAt.set(this.formatCurrentTime());
    });
  }

  updateStoreStatusFromList(store: StoreResource, event: Event): void {
    if (store.backendId === undefined) {
      this.errorMessage.set('找不到店家編號，無法更新狀態。');
      return;
    }

    const input = event.target as HTMLInputElement;
    const status: StoreStatus = input.checked ? 'ACTIVE' : 'SUSPENDED';
    this.changingStoreStatusId.set(store.backendId);

    this.api.updateStoreStatus(store.backendId, { status }).subscribe({
      next: (updatedStore) => {
        this.stores.update((items) =>
          items.map((item) => (item.id === updatedStore.id ? updatedStore : item)),
        );
        this.updatedAt.set(this.formatCurrentTime());
        this.changingStoreStatusId.set(null);
      },
      error: () => {
        input.checked = store.isActive;
        this.errorMessage.set('店家狀態更新失敗，請稍後再試。');
        this.changingStoreStatusId.set(null);
      },
    });
  }

  requestDeleteStore(store: StoreResource): void {
    if (store.backendId === undefined) {
      this.errorMessage.set('找不到店家編號，無法刪除。');
      return;
    }

    this.deleteTarget.set({ kind: 'store', id: store.backendId, name: store.name });
  }

  submitWarehouse(): void {
    const warehouse = this.warehouseForm();
    const editingId = this.editingWarehouseId();
    const isEditing = this.activeForm() === 'edit-warehouse';
    if (!warehouse.warehouseCode.trim() || !warehouse.name.trim()) {
      this.formError.set('請填寫倉庫代碼與倉庫名稱。');
      return;
    }

    if (isEditing) {
      if (editingId === null) {
        this.formError.set('找不到要修改的倉庫。');
        return;
      }

      this.saveResource(this.api.updateWarehouse(editingId, warehouse), (updatedWarehouse) => {
        this.warehouses.update((items) =>
          items.map((item) => (item.id === updatedWarehouse.id ? updatedWarehouse : item)),
        );
        this.updatedAt.set(this.formatCurrentTime());
      });
      return;
    }

    this.saveResource(this.api.createWarehouse(warehouse), (createdWarehouse) => {
      this.warehouses.update((items) => [...items, createdWarehouse]);
      this.updatedAt.set(this.formatCurrentTime());
    });
  }

  requestDeleteWarehouse(warehouse: WarehouseResource): void {
    if (warehouse.backendId === undefined) {
      this.errorMessage.set('找不到倉庫編號，無法刪除。');
      return;
    }

    this.deleteTarget.set({ kind: 'warehouse', id: warehouse.backendId, name: warehouse.name });
  }

  cancelDelete(): void {
    if (!this.isDeleting()) {
      this.deleteTarget.set(null);
    }
  }

  confirmDelete(): void {
    const target = this.deleteTarget();
    if (!target) {
      return;
    }

    this.isDeleting.set(true);
    const request = this.deleteRequest(target);

    request.subscribe({
      next: () => {
        this.removeDeletedResource(target);

        this.updatedAt.set(this.formatCurrentTime());
        this.deleteTarget.set(null);
        this.isDeleting.set(false);
      },
      error: () => {
        this.errorMessage.set(`刪除${this.deleteTargetLabel(target)}失敗，請稍後再試。`);
        this.isDeleting.set(false);
      },
    });
  }

  deleteTargetLabel(target: DeleteTarget): string {
    return deleteTargetLabels[target.kind];
  }

  stopEvent(event: Event): void {
    event.stopPropagation();
  }

  setFilter(filter: string): void {
    this.activeFilter.set(filter);
  }

  updateSearch(event: Event): void {
    this.searchTerm.set((event.target as HTMLInputElement).value);
  }

  clearSearch(input: HTMLInputElement): void {
    input.value = '';
    this.searchTerm.set('');
  }

  private loadResources(): void {
    this.loading.set(true);
    this.errorMessage.set('');

    forkJoin({
      drivers: this.api.getDrivers(),
      vehicles: this.api.getVehicles(),
      stores: this.api.getStores(),
      warehouses: this.api.getWarehouses(),
    }).subscribe({
      next: ({ drivers, vehicles, stores, warehouses }) => {
        this.drivers.set(drivers);
        this.vehicles.set(vehicles);
        this.stores.set(stores);
        this.warehouses.set(warehouses);
        this.updatedAt.set(this.formatCurrentTime());
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('暫時無法載入資源資料，請稍後再試。');
        this.loading.set(false);
      },
    });
  }

  private deleteRequest(target: DeleteTarget): Observable<void> {
    switch (target.kind) {
      case 'vehicle':
        return this.api.deleteVehicle(target.id);
      case 'store':
        return this.api.deleteStore(target.id);
      case 'warehouse':
        return this.api.deleteWarehouse(target.id);
    }
  }

  private removeDeletedResource(target: DeleteTarget): void {
    switch (target.kind) {
      case 'vehicle':
        this.vehicles.update((items) => items.filter((vehicle) => vehicle.id !== target.id));
        return;
      case 'store':
        this.stores.update((items) => items.filter((store) => store.id !== target.id));
        return;
      case 'warehouse':
        this.warehouses.update((items) => items.filter((warehouse) => warehouse.id !== target.id));
    }
  }

  private toStoreResource(store: StoreDto): StoreResource {
    return {
      backendId: store.id,
      isActive: store.status === 'ACTIVE',
      id: store.storeCode,
      name: store.name,
      status: store.status === 'ACTIVE' ? '營業中' : '暫停營業',
      address: store.address || '未提供地址',
      location: `${store.lat.toFixed(5)}, ${store.lng.toFixed(5)}`,
      contact: `${store.contactName || '未提供聯絡人'} · ${store.phone || '未提供電話'}`,
      hours: `${this.formatTime(store.receivingStart)} - ${this.formatTime(store.receivingEnd)}`,
    };
  }

  private toWarehouseResource(warehouse: WarehouseDto): WarehouseResource {
    return {
      backendId: warehouse.id,
      isActive: warehouse.isActive,
      id: warehouse.warehouseCode,
      name: warehouse.name,
      status: warehouse.isActive ? '啟用' : '停用',
      address: warehouse.address || '未提供地址',
      location: `${warehouse.lat.toFixed(5)}, ${warehouse.lng.toFixed(5)}`,
      phone: warehouse.phone || '未提供電話',
    };
  }

  private toVehicleResource(vehicle: VehicleDto): VehicleResource {
    return {
      backendId: vehicle.id,
      id: vehicle.plateNumber,
      type: vehicle.vehicleType || '未設定車型',
      capacity: `${vehicle.capacity} 箱容量`,
      status: this.toVehicleStatus(vehicle.status),
      driver: '未提供',
      assignment: '尚未提供配送任務',
      inspection: this.maintenanceNote(vehicle),
      maintenanceTone: this.maintenanceTone(vehicle.maintenance),
    };
  }

  /** 清單上那一行小字：保養狀況優先，沒資料才顯示油耗 */
  private maintenanceNote(vehicle: VehicleDto): string {
    if (vehicle.status === 'MINOR_MAINTENANCE') {
      return '送小保中';
    }
    if (vehicle.status === 'MAJOR_MAINTENANCE') {
      return '送大保中';
    }
    if (vehicle.status === 'MAINTENANCE') {
      return '送維修中（車禍／故障）';
    }
    const maintenance = vehicle.maintenance;
    if (maintenance && maintenance.decision !== 'NORMAL' && maintenance.reasons.length > 0) {
      return maintenance.reasons.join('、');
    }
    if (maintenance && maintenance.minorRemainingKm !== null) {
      return `距離小保 ${this.remainingKm(maintenance.minorRemainingKm)}`;
    }
    if (vehicle.fuelConsumption === undefined || vehicle.fuelConsumption === null) {
      return '尚未提供油耗資料';
    }
    return `平均油耗 ${vehicle.fuelConsumption}`;
  }

  private maintenanceTone(maintenance: VehicleMaintenanceSummary | null | undefined): VehicleResource['maintenanceTone'] {
    if (maintenance?.decision === 'BLOCKED') {
      return 'blocked';
    }
    if (maintenance?.decision === 'WARNING') {
      return 'warning';
    }
    if (maintenance?.decision === 'UNKNOWN') {
      return 'unknown';
    }
    return 'normal';
  }

  private toVehicleStatus(status: VehicleDto['status']): VehicleResourceStatus {
    if (status === 'MINOR_MAINTENANCE' || status === 'MAJOR_MAINTENANCE') {
      return '保養中';
    }
    if (status === 'MAINTENANCE') {
      return '維修中';
    }
    if (status === 'RETIRED') {
      return '已退役';
    }
    return '待派車';
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

  private saveResource<T extends AdminUserDto | DriverDto | VehicleDto | StoreDto | WarehouseDto>(
    request: Observable<T>,
    onSuccess: (value: T) => void,
  ): void {
    this.isSaving.set(true);
    this.formError.set('');

    request.subscribe({
      next: (value) => {
        onSuccess(value as T);
        this.isSaving.set(false);
        this.activeForm.set(null);
        this.editingVehicleId.set(null);
        this.editingStoreId.set(null);
        this.editingWarehouseId.set(null);
      },
      error: (error: unknown) => {
        this.formError.set(this.errorText(error, '儲存失敗，請確認欄位內容後再試。'));
        this.isSaving.set(false);
      },
    });
  }
}

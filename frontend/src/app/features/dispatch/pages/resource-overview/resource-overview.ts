import { Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, debounceTime, exhaustMap, filter, forkJoin, interval, Observable, of } from 'rxjs';
import { DriverChatSocketService } from '../../../../core/services/driver-chat-socket.service';
import { VehicleMaintenancePanel } from '../../components/vehicle-maintenance-panel/vehicle-maintenance-panel';
import { VehicleResource, VehicleResourceCard } from '../../components/vehicle-resource-card/vehicle-resource-card';
import { TonnageFilter, TonnageSelection } from '../../components/tonnage-filter/tonnage-filter';
import { MaintenanceRulesEditor } from '../../components/maintenance-rules-editor/maintenance-rules-editor';
import {MatIconModule} from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { AdminThemeService } from '../../../../core/theme/admin-theme.service';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {
  AdminUserCreateRequest,
  AdminUserDto,
  DriverDto,
  StoreDto,
  StoreStatus,
  VehicleDto,
  VehicleMaintenanceRecord,
  WarehouseDto,
} from '../../../../core/services/dispatch-api.models';

type ResourceView = 'vehicles' | 'drivers' | 'stores' | 'warehouses';
type VehicleResourceStatus = '待派車' | '維修中' | '小保中' | '大保中' | '已退役';
type VehicleTonnageFilter = TonnageSelection;
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

function emptyDriver(warehouseId = 0): DriverDto {
  return {
    account: '',
    password: '',
    warehouseId,
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
    currentOdometerKm: 0,
    status: 'AVAILABLE',
  };
}

@Component({
  selector: 'app-resource-overview',
  imports: [
    MatIconModule, MatSelectModule,
    VehicleMaintenancePanel, VehicleResourceCard, TonnageFilter, MaintenanceRulesEditor, DatePipe, DecimalPipe,
  ],
  templateUrl: './resource-overview.html',
  styleUrl: './resource-overview.scss',
})
export class ResourceOverview implements OnInit {
  protected readonly theme = inject(AdminThemeService);
  private readonly api = inject(DispatchApiService);
  private readonly socket = inject(DriverChatSocketService);
  private readonly destroyRef = inject(DestroyRef);
  readonly rulesOpen = signal(false);
  readonly maintenanceHistory = signal<VehicleMaintenanceRecord[]>([]);
  readonly historyLoading = signal(false);
  readonly historyError = signal('');
  readonly vehicleTonnages = computed(() => [...new Set(this.vehicles().map(v => v.tonnage).filter((t): t is number => t != null))].sort((a, b) => a - b));

  readonly activeView = signal<ResourceView>('vehicles');
  readonly activeFilter = signal('all');
  readonly vehicleTonnageFilter = signal<VehicleTonnageFilter>('all');
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
  readonly editingStoreId = signal<number | null>(null);
  readonly editingWarehouseId = signal<number | null>(null);
  readonly formError = signal('');
  readonly isSaving = signal(false);
  readonly changingStoreStatusId = signal<number | null>(null);
  readonly deleteTarget = signal<DeleteTarget | null>(null);
  readonly isDeleting = signal(false);

  readonly vehicleFilters = ['all', '待派車', '維修中', '小保中', '大保中', '已退役'];
  readonly storeFilters = ['all', '營業中', '暫停營業'];
  readonly warehouseFilters = ['all', '啟用', '停用'];
  readonly driverFilters = ['all', '在職', '停用', '待設定倉庫'];
  readonly activeDriverCount = computed(() => this.drivers().filter(driver => driver.isActive).length);
  readonly visibleDrivers = computed(() => {
    const term = this.searchTerm().trim().toLowerCase();
    const status = this.activeFilter();
    const warehouseId = this.driverWarehouseFilter();
    return this.drivers().filter(driver => {
      const warehouse = this.warehouses().find(item => item.id === driver.warehouseId);
      return (warehouseId === 'all' || driver.warehouseId === warehouseId)
        && (status === 'all' || (status === '在職' && driver.isActive)
          || (status === '停用' && !driver.isActive) || (status === '待設定倉庫' && driver.warehouseId == null))
        && (!term || `${driver.name} ${driver.account} ${driver.phone ?? ''} ${warehouse?.name ?? ''} ${warehouse?.warehouseCode ?? ''}`.toLowerCase().includes(term));
    });
  });

  driverWarehouse(driver: DriverDto): WarehouseDto | undefined {
    return this.warehouses().find(warehouse => warehouse.id === driver.warehouseId);
  }

  readonly visibleVehicles = computed<VehicleResource[]>(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();
    const tonnage = this.vehicleTonnageFilter();
    // 完整車號只查該車，避免 CAR-0001 同時命中 CAR-00010 等前綴相同的車號。
    const hasExactPlate = !!term && this.vehicles().some(
      vehicle => vehicle.plateNumber.trim().toLowerCase() === term,
    );

    return this.vehicles()
      .filter(vehicle => tonnage === 'all' || vehicle.tonnage === tonnage)
      .map((vehicle) => this.toVehicleResource(vehicle))
      .filter((vehicle) => {
        const matchesFilter = filter === 'all' || vehicle.status === filter;
        const source =
          `${vehicle.id} ${vehicle.type} ${vehicle.driver} ${vehicle.assignment}`.toLowerCase();
        const matchesSearch = !term || (hasExactPlate
          ? vehicle.id.trim().toLowerCase() === term
          : source.includes(term));
        return matchesFilter && matchesSearch;
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

  readonly searchPlaceholder = computed(() => {
    if (this.activeView() === 'drivers') return '搜尋司機姓名、帳號、電話或倉庫';
    if (this.activeView() === 'stores') return '搜尋店家名稱、編號或聯絡資訊';
    if (this.activeView() === 'warehouses') return '搜尋倉庫名稱、編號或地址';
    return '搜尋車號或車型';
  });

  readonly searchResultCount = computed(() => {
    if (this.activeView() === 'drivers') return this.visibleDrivers().length;
    if (this.activeView() === 'stores') return this.visibleStores().length;
    if (this.activeView() === 'warehouses') return this.visibleWarehouses().length;
    return this.visibleVehicles().length;
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
      this.vehicles().filter((vehicle) => vehicle.status !== 'AVAILABLE' && vehicle.status !== 'RETIRED' || vehicle.maintenance?.decision === 'WARNING' || vehicle.maintenance?.decision === 'BLOCKED' || vehicle.maintenance?.decision === 'UNKNOWN').length +
      this.stores().filter((store) => store.status === 'SUSPENDED').length +
      this.warehouses().filter((warehouse) => !warehouse.isActive).length,
  );

  /** 新增車輛時的預設倉庫：第一個啟用中的倉庫，沒有就退回第一筆 */
  readonly defaultWarehouseId = computed(() => {
    const warehouses = this.warehouses();
    return warehouses.find((warehouse) => warehouse.isActive)?.id ?? warehouses[0]?.id ?? 0;
  });

  ngOnInit(): void {
    this.socket.boardPushes$.pipe(filter(p => !!p.resourcesChanged), debounceTime(300), takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadResources());
    this.socket.connected$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadResources());
    this.loadResources();
    interval(60_000).pipe(
      filter(() => this.activeView() === 'drivers' && !this.loading() && !document.hidden),
      exhaustMap(() => this.api.getDrivers().pipe(catchError(() => of(null)))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(drivers => { if (drivers) this.drivers.set(drivers); });
  }

  setView(view: ResourceView): void {
    this.activeView.set(view);
    this.activeFilter.set('all');
    this.vehicleTonnageFilter.set('all');
    this.driverWarehouseFilter.set('all');
    this.searchTerm.set('');
  }

  updateVehicleTonnageFilter(value: TonnageSelection): void {
    this.vehicleTonnageFilter.set(typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 'all');
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
    const { password, ...editable } = driver;
    this.driverForm.set({ ...editable });
    this.editingDriverId.set(driver.id);
    this.formError.set('');
    this.activeForm.set('edit-driver');
  }

  updateDriverWarehouse(warehouseId: number): void {
    this.driverForm.update(form => ({ ...form, warehouseId }));
  }

  updateDriverNumber(field: 'restDuration' | 'maxOvertimeMinutes', event: Event): void {
    this.driverForm.update(form => ({ ...form, [field]: Number((event.target as HTMLInputElement).value) }));
  }

  updateDriverActive(event: Event): void {
    this.driverForm.update(form => ({ ...form, isActive: (event.target as HTMLInputElement).checked }));
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
    this.formError.set('');
    this.activeForm.set('edit-vehicle');
    this.loadHistory(vehicle.backendId);
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
    if (!this.isSaving()) {
      this.activeForm.set(null);
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

  updateVehicleNumber(field: 'capacity' | 'fuelConsumption' | 'tonnage' | 'currentOdometerKm' | 'lastMinorMaintenanceKm' | 'lastMajorMaintenanceKm', event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.vehicleForm.update((form) => ({
      ...form,
      [field]: value === '' && field !== 'capacity' ? undefined : Number(value),
    }));
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
    if (this.isSaving()) return;
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
    // 既有後端規則：主管建立司機時以身分證字號作為初始密碼。
    if (!isEditing && !/^[A-Z][12]\d{8}$/.test(password)) {
      this.formError.set('請填寫大寫的台灣身分證字號作為初始密碼。'); return;
    }
    if (isEditing && editingId === null) { this.formError.set('找不到要修改的司機。'); return; }
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

    if (isEditing) {
      if (editingId === null) {
        this.formError.set('找不到要修改的車輛。');
        return;
      }

      // 省略唯讀里程欄位，避免司機收車後用編輯視窗的舊快照覆寫資料庫。
      const { currentOdometerKm, lastMinorMaintenanceKm, lastMajorMaintenanceKm, maintenance, ...editable } = vehicle;
      this.saveResource(this.api.updateVehicle(editingId, editable), (updatedVehicle) => {
        this.vehicles.update((items) =>
          items.map((item) => (item.id === updatedVehicle.id ? updatedVehicle : item)),
        );
        this.updatedAt.set(this.formatCurrentTime());
      });
      return;
    }

    if (!Number.isInteger(vehicle.currentOdometerKm) || vehicle.currentOdometerKm! < 0) {
      this.formError.set('請填初始實際總里程，公里數需為 0 或正整數。'); return;
    }
    this.saveResource(this.api.createVehicle(vehicle), (createdVehicle) => {
      this.vehicles.update((items) => [...items, createdVehicle]);
      this.updatedAt.set(this.formatCurrentTime());
    });
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
    this.searchTerm.set('');
    input.value = '';
    input.focus();
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
      next: ({ vehicles, stores, warehouses, drivers }) => {
        this.drivers.set(drivers);
        const previous = this.vehicles().find(v => v.id === this.editingVehicleId());
        this.vehicles.set(vehicles);
        const latest = vehicles.find(v => v.id === this.editingVehicleId());
        if (this.activeForm() === 'edit-vehicle' && latest) {
          // 司機收車時即使主管正開著編輯視窗，唯讀數字也必須更新；保留尚未儲存的其他欄位。
          this.vehicleForm.update(form => ({
            ...form,
            status: form.status === previous?.status ? latest.status : form.status,
            currentOdometerKm: latest.currentOdometerKm,
            lastMinorMaintenanceKm: latest.lastMinorMaintenanceKm,
            lastMajorMaintenanceKm: latest.lastMajorMaintenanceKm,
            maintenance: latest.maintenance,
          }));
          this.loadHistory(latest.id!);
        }
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
      maintenance: vehicle.maintenance,
      backendId: vehicle.id,
      id: vehicle.plateNumber,
      type: vehicle.vehicleType || '未設定車型',
      capacity: `${vehicle.capacity} 箱`,
      status: this.toVehicleStatus(vehicle.status),
      driver: '未提供',
      assignment: '尚未提供配送任務',
      inspection:
        vehicle.status === 'MAINTENANCE'
          ? '維修中（車禍／故障，不計大小保）'
          : vehicle.fuelConsumption === undefined
            ? '尚未提供油耗資料'
            : `平均油耗 ${vehicle.fuelConsumption}`,
    };
  }

  private toVehicleStatus(status: VehicleDto['status']): VehicleResourceStatus {
    if (status === 'MINOR_MAINTENANCE') return '小保中';
    if (status === 'MAJOR_MAINTENANCE') return '大保中';
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

  rulesSaved(): void { this.rulesOpen.set(false); this.loadResources(); }

  baselineLocked(): boolean {
    return this.activeForm() === 'edit-vehicle' || this.vehicleForm().currentOdometerKm === 0;
  }

  private loadHistory(id: number): void {
    this.historyLoading.set(true); this.historyError.set(''); this.maintenanceHistory.set([]);
    this.api.getMaintenanceHistory(id).subscribe({
      next: history => { if (this.editingVehicleId() === id) this.maintenanceHistory.set(history); this.historyLoading.set(false); },
      error: () => { this.historyError.set('保養與維修紀錄載入失敗，請重新開啟車輛。'); this.historyLoading.set(false); },
    });
  }

  cancelVehicleMaintenance(): void {
    const id = this.editingVehicleId();
    if (id === null || this.isSaving()) return;
    this.isSaving.set(true);
    this.api.cancelMaintenance(id).subscribe({
      next: () => { this.isSaving.set(false); this.closeForm(); this.loadResources(); },
      error: (e: HttpErrorResponse) => { this.formError.set(e.error?.message || '取消保養失敗。'); this.isSaving.set(false); },
    });
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
        this.editingDriverId.set(null);
        this.editingStoreId.set(null);
        this.editingWarehouseId.set(null);
      },
      error: (e: HttpErrorResponse) => {
        this.formError.set(e.error?.message || '儲存失敗，請確認欄位內容後再試。');
        this.isSaving.set(false);
      },
    });
  }
}

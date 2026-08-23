import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { forkJoin, Observable } from 'rxjs';
import {
  LucidePlus,
  LucidePencil,
  LucideSearch,
  LucideTrash2,
  LucideTriangleAlert,
  LucideTruck,
  LucideX,
} from '@lucide/angular';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import {
  DriverDto,
  StoreDto,
  StoreStatus,
  VehicleDto,
  WarehouseDto,
} from '../../../../core/services/dispatch-api.models';

type ResourceView = 'drivers' | 'vehicles' | 'stores' | 'warehouses';
type DriverResourceStatus = '可排班' | '停職';
type VehicleResourceStatus = '待派車' | '保養排程' | '已退役';
type StoreResourceStatus = '營業中' | '暫停營業';
type WarehouseResourceStatus = '啟用' | '停用';
type ResourceForm =
  | 'driver'
  | 'edit-driver'
  | 'vehicle'
  | 'edit-vehicle'
  | 'store'
  | 'edit-store'
  | 'warehouse'
  | 'edit-warehouse'
  | null;

interface DeleteTarget {
  kind: 'vehicle' | 'store' | 'warehouse';
  id: number;
  name: string;
}

interface DriverResource {
  backendId?: number;
  isActive: boolean;
  id: string;
  name: string;
  license: string;
  status: DriverResourceStatus;
  vehicle: string;
  assignment: string;
  hours: string;
}

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

function emptyDriver(): DriverDto {
  return {
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
    lat: 22.9971,
    lng: 120.2125,
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
    lat: 22.9971,
    lng: 120.2125,
    phone: '',
    isActive: true,
  };
}

function emptyVehicle(): VehicleDto {
  return {
    plateNumber: '',
    vehicleType: '',
    capacity: 0,
    fuelConsumption: undefined,
    status: 'AVAILABLE',
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
}

@Component({
  selector: 'app-resource-overview',
  imports: [
    LucidePlus,
    LucidePencil,
    LucideSearch,
    LucideTrash2,
    LucideTriangleAlert,
    LucideTruck,
    LucideX,
  ],
  templateUrl: './resource-overview.html',
  styleUrl: './resource-overview.scss',
})
export class ResourceOverview implements OnInit {
  private readonly api = inject(DispatchApiService);

  readonly activeView = signal<ResourceView>('drivers');
  readonly activeFilter = signal('all');
  readonly searchTerm = signal('');
  readonly drivers = signal<DriverDto[]>([]);
  readonly vehicles = signal<VehicleDto[]>([]);
  readonly stores = signal<StoreDto[]>([]);
  readonly warehouses = signal<WarehouseDto[]>([]);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly updatedAt = signal('--:--');
  readonly activeForm = signal<ResourceForm>(null);
  readonly driverForm = signal<DriverDto>(emptyDriver());
  readonly storeForm = signal<StoreDto>(emptyStore());
  readonly warehouseForm = signal<WarehouseDto>(emptyWarehouse());
  readonly vehicleForm = signal<VehicleDto>(emptyVehicle());
  readonly editingDriverId = signal<number | null>(null);
  readonly editingVehicleId = signal<number | null>(null);
  readonly editingStoreId = signal<number | null>(null);
  readonly editingWarehouseId = signal<number | null>(null);
  readonly formError = signal('');
  readonly isSaving = signal(false);
  readonly changingDriverStatusId = signal<number | null>(null);
  readonly changingStoreStatusId = signal<number | null>(null);
  readonly deleteTarget = signal<DeleteTarget | null>(null);
  readonly isDeleting = signal(false);

  readonly driverFilters = ['all', '可排班', '停職'];
  readonly vehicleFilters = ['all', '待派車', '保養排程', '已退役'];
  readonly storeFilters = ['all', '營業中', '暫停營業'];
  readonly warehouseFilters = ['all', '啟用', '停用'];

  readonly visibleDrivers = computed<DriverResource[]>(() => {
    const filter = this.activeFilter();
    const term = this.searchTerm().trim().toLowerCase();

    return this.drivers()
      .map((driver) => this.toDriverResource(driver))
      .filter((driver) => {
        const matchesFilter = filter === 'all' || driver.status === filter;
        const source =
          `${driver.id} ${driver.name} ${driver.vehicle} ${driver.assignment}`.toLowerCase();
        return matchesFilter && (!term || source.includes(term));
      });
  });

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
        const source = `${warehouse.id} ${warehouse.name} ${warehouse.address} ${warehouse.phone}`.toLowerCase();
        return matchesFilter && (!term || source.includes(term));
      });
  });

  readonly currentFilters = computed(() => {
    if (this.activeView() === 'drivers') {
      return this.driverFilters;
    }

    if (this.activeView() === 'stores') {
      return this.storeFilters;
    }

    if (this.activeView() === 'warehouses') {
      return this.warehouseFilters;
    }

    return this.vehicleFilters;
  });

  readonly activeDriverCount = computed(
    () => this.drivers().filter((driver) => driver.isActive).length,
  );
  readonly availableVehicleCount = computed(
    () => this.vehicles().filter((vehicle) => vehicle.status === 'AVAILABLE').length,
  );
  readonly attentionResourceCount = computed(
    () =>
      this.drivers().filter((driver) => !driver.isActive).length +
      this.vehicles().filter((vehicle) => vehicle.status === 'MAINTENANCE').length +
      this.stores().filter((store) => store.status === 'SUSPENDED').length +
      this.warehouses().filter((warehouse) => !warehouse.isActive).length,
  );

  ngOnInit(): void {
    this.loadResources();
  }

  setView(view: ResourceView): void {
    this.activeView.set(view);
    this.activeFilter.set('all');
    this.searchTerm.set('');
  }

  openCreateDriver(): void {
    this.activeView.set('drivers');
    this.driverForm.set(emptyDriver());
    this.editingDriverId.set(null);
    this.editingVehicleId.set(null);
    this.editingStoreId.set(null);
    this.editingWarehouseId.set(null);
    this.formError.set('');
    this.activeForm.set('driver');
  }

  openEditDriver(driver: DriverResource): void {
    if (driver.backendId === undefined) {
      this.errorMessage.set('找不到司機編號，無法編輯。');
      return;
    }

    const source = this.drivers().find((item) => item.id === driver.backendId);
    if (!source) {
      this.errorMessage.set('找不到司機資料，請重新載入後再試。');
      return;
    }

    this.driverForm.set({ ...source, password: '' });
    this.editingDriverId.set(driver.backendId);
    this.formError.set('');
    this.activeForm.set('edit-driver');
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
    this.vehicleForm.set(emptyVehicle());
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
      this.editingDriverId.set(null);
      this.editingVehicleId.set(null);
      this.editingStoreId.set(null);
      this.editingWarehouseId.set(null);
      this.formError.set('');
    }
  }

  updateDriverText(
    field: 'account' | 'password' | 'name' | 'phone' | 'workStart' | 'workEnd',
    event: Event,
  ): void {
    const value = (event.target as HTMLInputElement).value;
    this.driverForm.update((form) => ({ ...form, [field]: value }));
  }

  updateDriverNumber(field: 'restDuration' | 'maxOvertimeMinutes', event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim();
    this.driverForm.update((form) => ({
      ...form,
      [field]: value === '' ? undefined : Number(value),
    }));
  }

  updateDriverActive(event: Event): void {
    const isActive = (event.target as HTMLInputElement).checked;
    this.driverForm.update((form) => ({ ...form, isActive }));
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

  updateWarehouseText(
    field: 'warehouseCode' | 'name' | 'address' | 'phone',
    event: Event,
  ): void {
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

  submitDriver(): void {
    const driver = this.driverForm();
    const password = driver.password?.trim() ?? '';
    const editingId = this.editingDriverId();
    const isEditing = this.activeForm() === 'edit-driver';

    if (!driver.account.trim() || !driver.name.trim()) {
      this.formError.set('請填寫司機帳號與姓名。');
      return;
    }

    if (!isEditing && !password) {
      this.formError.set('新增司機時必須設定初始密碼。');
      return;
    }

    if (password && password.length < 8) {
      this.formError.set('司機密碼至少需要 8 個字元。');
      return;
    }

    if (isEditing) {
      if (editingId === null) {
        this.formError.set('找不到要修改的司機。');
        return;
      }

      this.saveResource(
        this.api.updateDriver(editingId, { ...driver, password: password || undefined }),
        (updatedDriver) => {
          this.drivers.update((items) =>
            items.map((item) => (item.id === updatedDriver.id ? updatedDriver : item)),
          );
          this.updatedAt.set(this.formatCurrentTime());
        },
      );
      return;
    }

    this.saveResource(this.api.createDriver({ ...driver, password }), (createdDriver) => {
      this.drivers.update((items) => [...items, createdDriver]);
      this.updatedAt.set(this.formatCurrentTime());
    });
  }

  updateDriverStatus(driver: DriverResource, event: Event): void {
    if (driver.backendId === undefined) {
      this.errorMessage.set('找不到司機編號，無法更新狀態。');
      return;
    }

    const input = event.target as HTMLInputElement;
    const isActive = input.checked;
    this.changingDriverStatusId.set(driver.backendId);

    this.api.updateDriverStatus(driver.backendId, { isActive }).subscribe({
      next: (updatedDriver) => {
        this.drivers.update((items) =>
          items.map((item) => (item.id === updatedDriver.id ? updatedDriver : item)),
        );
        this.updatedAt.set(this.formatCurrentTime());
        this.changingDriverStatusId.set(null);
      },
      error: () => {
        input.checked = driver.isActive;
        this.errorMessage.set('司機狀態更新失敗，請確認後端服務。');
        this.changingDriverStatusId.set(null);
      },
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

    if (!Number.isFinite(vehicle.capacity) || vehicle.capacity < 0) {
      this.formError.set('車輛容量必須是 0 或正整數。');
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
        this.errorMessage.set('店家狀態更新失敗，請確認後端服務。');
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
    const request =
      target.kind === 'vehicle'
        ? this.api.deleteVehicle(target.id)
        : target.kind === 'store'
          ? this.api.deleteStore(target.id)
          : this.api.deleteWarehouse(target.id);

    request.subscribe({
      next: () => {
        if (target.kind === 'vehicle') {
          this.vehicles.update((items) => items.filter((vehicle) => vehicle.id !== target.id));
        } else if (target.kind === 'store') {
          this.stores.update((items) => items.filter((store) => store.id !== target.id));
        } else {
          this.warehouses.update((items) =>
            items.filter((warehouse) => warehouse.id !== target.id),
          );
        }

        this.updatedAt.set(this.formatCurrentTime());
        this.deleteTarget.set(null);
        this.isDeleting.set(false);
      },
      error: () => {
        this.errorMessage.set(
          `刪除${target.kind === 'vehicle' ? '車輛' : target.kind === 'store' ? '店家' : '倉庫'}失敗，請確認後端資料。`,
        );
        this.isDeleting.set(false);
      },
    });
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
        this.errorMessage.set('無法取得資源資料，請確認後端服務是否正在執行。');
        this.loading.set(false);
      },
    });
  }

  private toDriverResource(driver: DriverDto): DriverResource {
    const id =
      driver.id === undefined ? driver.account : `DR-${String(driver.id).padStart(3, '0')}`;
    return {
      backendId: driver.id,
      isActive: driver.isActive,
      id,
      name: driver.name,
      license: `帳號 ${driver.account}`,
      status: driver.isActive ? '可排班' : '停職',
      vehicle: '未提供',
      assignment: '尚未提供配送任務',
      hours: `${this.formatTime(driver.workStart)} - ${this.formatTime(driver.workEnd)}`,
    };
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
      inspection:
        vehicle.status === 'MAINTENANCE'
          ? '目前標記為保養'
          : vehicle.fuelConsumption === undefined
            ? '尚未提供油耗資料'
            : `平均油耗 ${vehicle.fuelConsumption}`,
    };
  }

  private toVehicleStatus(status: VehicleDto['status']): VehicleResourceStatus {
    if (status === 'MAINTENANCE') {
      return '保養排程';
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

  private saveResource<T extends DriverDto | VehicleDto | StoreDto | WarehouseDto>(
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
        this.editingDriverId.set(null);
        this.editingVehicleId.set(null);
        this.editingStoreId.set(null);
        this.editingWarehouseId.set(null);
      },
      error: () => {
        this.formError.set('儲存失敗，請確認欄位內容與後端服務。');
        this.isSaving.set(false);
      },
    });
  }
}

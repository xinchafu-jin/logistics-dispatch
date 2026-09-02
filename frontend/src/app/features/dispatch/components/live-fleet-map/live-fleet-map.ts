import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  computed,
  inject,
  signal, input, effect,
} from '@angular/core';
import * as L from 'leaflet';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DriverDto} from '../../../../core/services/dispatch-api.models';

interface FleetDriver {
  id: string;
  name: string;
  isActive: boolean;
}

/** 地圖上的一個點。倉庫與門市共用同一個型別，差別只在畫出來的樣式 */
export interface MapPoint {
  id: number;
  label: string;   // 名稱
  detail: string;  // tooltip 第二行
  lat: number;
  lng: number;
}

@Component({
  selector: 'app-live-fleet-map',
  imports: [],
  templateUrl: './live-fleet-map.html',
  styleUrl: './live-fleet-map.scss',
})
export class LiveFleetMap implements AfterViewInit, OnDestroy {
  @ViewChild('mapCanvas') private readonly mapCanvas?: ElementRef<HTMLDivElement>;
  /** 今天這一倉的倉庫位置，還沒載到時為 null */
  readonly warehousePoint = input<MapPoint | null>(null);
  /**
   * 今天要配送的門市。兩個 input 都給預設值是必要的 ——
   * fleet-monitor 用同一支元件但不傳資料，改成 input.required() 那頁會直接壞掉。
   */
  readonly storePoints = input<MapPoint[]>([]);
  /** 圖層開關。關掉只是不畫，資料仍在，重開不必重新取得 */
  readonly showPoints = input(true);
  /** 司機即時位置，由 dashboard 輪詢 /api/fleet/live 後 join 姓名產生 */
  readonly driverPoints = input<MapPoint[]>([]);
  /** 司機圖層開關，跟 showPoints 分開：門市是靜態資料，司機位置背後是持續輪詢 */
  readonly showDriverPoints = input(false);
  readonly drivers = signal<FleetDriver[]>([]);
  readonly selectedDriverId = signal<string | null>(null);
  readonly selectedDriver = computed(() =>
    this.drivers().find((driver) => driver.id === this.selectedDriverId()),
  );
  readonly activeDriverCount = computed(
    () => this.drivers().filter((driver) => driver.isActive).length,
  );

  private readonly api = inject(DispatchApiService);
  private map?: L.Map;
  private resizeObserver?: ResizeObserver;
  private markerLayer?: L.LayerGroup;
  /**
   * 地圖是否已建立。用 signal 而不是判斷 this.map，是因為 effect 會早於
   * ngAfterViewInit 執行：那時直接 return 掉之後 input 沒再變動，effect 就不會再跑，
   * 點永遠畫不出來。讀這個 signal 才能讓「地圖建好」這件事本身觸發重畫。
   */
  private readonly mapReady = signal(false);
  /** 已自動框過範圍的倉庫 id，0 代表還沒框過。換倉庫才重框，理由見 fitOnce */
  private fittedWarehouseId = 0;

  constructor() {
    effect(() => {
      const warehouse = this.warehousePoint();
      const stores = this.storePoints();
      const visible = this.showPoints();
      const driverPoints = this.driverPoints();
      const driversVisible = this.showDriverPoints();
      if (!this.mapReady()) return;
      this.drawPoints(warehouse, stores, visible, driverPoints, driversVisible);
    });
  }

  ngAfterViewInit(): void {
    const canvas = this.mapCanvas?.nativeElement;
    if (!canvas) {
      return;
    }

    this.map = L.map(canvas, {
      attributionControl: false,
      zoomControl: false,
      preferCanvas: true,
    }).setView([23.006, 120.219], 13);

    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      attribution: '&copy; OpenStreetMap contributors &copy; CARTO',
      maxZoom: 19,
      subdomains: 'abcd',
    }).addTo(this.map);
    L.control.zoom({position: 'bottomright'}).addTo(this.map);

    this.resizeObserver = new ResizeObserver(() => this.map?.invalidateSize());
    this.resizeObserver.observe(canvas);
    requestAnimationFrame(() => this.map?.invalidateSize());
    this.mapReady.set(true);
    this.loadDrivers();
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.map?.remove();
  }

  selectDriver(id: string): void {
    this.selectedDriverId.set(id);
  }

  /**
   * 重畫倉庫與門市。
   *
   * 整層清掉重建，不逐點比對差異 —— 一個倉一天的門市是十位數等級，重畫成本可以忽略，
   * 但省下維護「哪些點該新增／移除」的狀態，拖曳改派時也不會殘留舊點。
   */
  private drawPoints(
    warehouse: MapPoint | null,
    stores: MapPoint[],
    visible: boolean,
    driverPoints: MapPoint[],
    driversVisible: boolean,
  ): void {
    const map = this.map;
    if (!map) {
      return;
    }

    this.markerLayer ??= L.layerGroup().addTo(map);
    this.markerLayer.clearLayers();

    // 用 circleMarker 而不是 L.marker()：預設 marker 的 icon 圖片路徑是相對 leaflet.css
    // 解析的，打包後常 404，畫面沒有點但不一定看得到錯誤。circleMarker 是向量，不碰圖檔。
    //
    // 顏色只能寫死：地圖開了 preferCanvas，圈畫在 canvas 上而不是 DOM，CSS 變數碰不到它。
    // 這三個色值分別對應 styles.scss 的 --accent、側欄暖色提示、以及司機專用的亮綠。
    if (visible && warehouse) {
      L.circleMarker([warehouse.lat, warehouse.lng], {
        radius: 10,
        weight: 2,
        color: '#e0ad76',
        fillColor: '#e0ad76',
        fillOpacity: 0.9,
      })
        .bindTooltip(`倉庫｜${warehouse.label}`, {direction: 'top'})
        .addTo(this.markerLayer);
    }

    if (visible) {
      for (const store of stores) {
        L.circleMarker([store.lat, store.lng], {
          radius: 6,
          weight: 2,
          color: '#63d1c6',
          fillColor: '#0b0e0f',
          fillOpacity: 0.9,
        })
          .bindTooltip(`${store.label}<br>${store.detail}`, {direction: 'top'})
          .addTo(this.markerLayer);
      }
    }

    if (driversVisible) {
      for (const driver of driverPoints) {
        L.circleMarker([driver.lat, driver.lng], {
          radius: 7,
          weight: 3,
          color: '#7ee787',
          fillColor: '#0b0e0f',
          fillOpacity: 0.85,
        })
          .bindTooltip(`司機｜${driver.label}<br>${driver.detail}`, {direction: 'top'})
          .addTo(this.markerLayer);
      }
    }

    // 自動框選不把司機算進去：司機位置每 30 秒變動，框選只在換倉庫時算一次，
    // 兩者週期對不上；而且司機一旦跑到台南以外，範圍會被拉到失去意義。
    if (visible) {
      this.fitOnce(map, warehouse, stores);
    }
  }

  /**
   * 自動框出所有點，但同一個倉庫只框一次。
   *
   * 每次重畫都 fitBounds 的話，拖一張卡片地圖就跳回全景，使用者放大在看的區域會被蓋掉；
   * 圖層關掉再打開也一樣。換倉庫才需要重新框。
   */
  private fitOnce(map: L.Map, warehouse: MapPoint | null, stores: MapPoint[]): void {
    const points = warehouse ? [warehouse, ...stores] : stores;
    const warehouseId = warehouse?.id ?? 0;
    if (points.length === 0 || warehouseId === this.fittedWarehouseId) {
      return;
    }

    this.fittedWarehouseId = warehouseId;
    map.fitBounds(
      L.latLngBounds(points.map((point) => [point.lat, point.lng] as L.LatLngTuple)),
      {padding: [56, 56], maxZoom: 14},
    );
  }

  private loadDrivers(): void {
    this.api.getDrivers().subscribe({
      next: (drivers) => {
        this.drivers.set(drivers.map((driver) => this.toFleetDriver(driver)));
        this.selectedDriverId.set(this.drivers()[0]?.id ?? null);
      },
      error: () => {
        this.drivers.set([]);
        this.selectedDriverId.set(null);
      },
    });
  }

  private toFleetDriver(driver: DriverDto): FleetDriver {
    const id = driver.id ? `DR-${String(driver.id).padStart(3, '0')}` : driver.account;
    return {id, name: driver.name, isActive: driver.isActive};
  }
}

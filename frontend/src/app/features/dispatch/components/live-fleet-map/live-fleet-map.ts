import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  signal, input, effect,
} from '@angular/core';
import * as L from 'leaflet';

const OSM_TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';

/**
 * 做一個圓形文字徽章。Leaflet 的 divIcon 不會經過 Angular 的元件生命週期，
 * 因此不放外來 SVG 圖示，直接以「倉／店」區分標記。
 *
 * divIcon 是 DOM 不是 canvas，所以顏色交給 scss 的 .map-badge 管，
 * 不必像 circleMarker 那樣把色碼寫死在 TS 裡。
 */
function badgeIcon(kind: 'warehouse' | 'store', size: number): L.DivIcon {
  const label = kind === 'warehouse' ? '倉' : '店';

  return L.divIcon({
    className: '',   // 清掉 leaflet 預設的白底方框
    html: `<span class="map-badge map-badge--${kind}" aria-hidden="true">${label}</span>`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],      // 徽章中心對準座標
    tooltipAnchor: [0, -size / 2],
  });
}

function driverIcon(heading?: number): L.DivIcon {
  const direction = Number.isFinite(heading) ? heading : 0;

  return L.divIcon({
    className: '',
    html: `<span class="map-driver-marker" style="--driver-heading:${direction}deg" aria-hidden="true"><i></i></span>`,
    iconSize: [30, 30],
    iconAnchor: [15, 15],
    tooltipAnchor: [0, -17],
  });
}

/** 地圖上的一個點。倉庫與門市共用同一個型別，差別只在畫出來的樣式 */
export interface MapPoint {
  id: number;
  label: string;   // 名稱
  detail: string;  // tooltip 第二行
  /** 額外資訊會同時出現在地圖提示與右側定位清單。 */
  details?: string[];
  lat: number;
  lng: number;
  /** 指向下一個配送點的角度，0 度為正北。 */
  heading?: number;
}

/** 一位司機當天的配送路線，倉庫出發依派車順序連到各門市 */
export interface RouteLine {
  id: number;                   // routeId，同時決定配色
  label: string;                // tooltip：司機名
  points: [number, number][];   // [緯度, 經度]，Leaflet 順序
}

/**
 * 路線配色。避開既有的三個寫死色：倉庫 #e0ad76、門市 #63d1c6、司機 #7ee787，
 * 否則線會跟點混成同一色。超過六條就從頭循環。
 */
const ROUTE_LINE_COLORS = ['#7aa2f7', '#f7768e', '#bb9af7', '#e0af68', '#2ac3de', '#9ece6a'];

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
  /** 司機的配送路線，由 dashboard 依看板車道算出 */
  readonly routeLines = input<RouteLine[]>([]);
  /** 路線圖層開關 */
  readonly showRouteLines = input(false);
  private map?: L.Map;
  private resizeObserver?: ResizeObserver;
  private markerLayer?: L.LayerGroup;
  /**
   * 路線自己一層，不跟 markerLayer 共用：markerLayer 每次重畫都整層 clearLayers()，
   * 混在一起的話點一更新（司機位置每 30 秒一次）就會把線一併清掉。
   */
  private lineLayer?: L.LayerGroup;
  private routeSvgRenderer?: L.SVG;
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

    // 路線分開一個 effect：它只依賴 routeLines/showRouteLines，
    // 跟點畫在一起的話，司機位置每 30 秒一輪詢就會連帶重畫所有線。
    effect(() => {
      const lines = this.routeLines();
      const linesVisible = this.showRouteLines();
      if (!this.mapReady()) return;
      this.drawLines(lines, linesVisible);
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
    }).setView([22.6273, 120.3014], 13);

    L.tileLayer(OSM_TILE_URL, {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19,
      subdomains: 'abc',
    }).addTo(this.map);
    L.control.zoom({position: 'bottomright'}).addTo(this.map);

    this.resizeObserver = new ResizeObserver(() => {
      const activeMap = this.map;
      if (activeMap?.getContainer().isConnected) {
        activeMap.invalidateSize();
      }
    });
    this.resizeObserver.observe(canvas);
    requestAnimationFrame(() => this.map?.invalidateSize());
    this.mapReady.set(true);
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.map?.remove();
    this.map = undefined;
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

    // 倉庫與門市用 divIcon 圖示，不用 L.marker() 的預設圖釘：
    // 預設圖釘的 icon 圖片路徑是相對 leaflet.css 解析的，打包後常 404，
    // 畫面沒有點但不一定看得到錯誤。divIcon 是自己給的 HTML，不碰圖檔。
    if (visible && warehouse) {
      L.marker([warehouse.lat, warehouse.lng], {
        icon: badgeIcon('warehouse', 34),
        keyboard: false,
      })
        .bindTooltip(`倉庫｜${this.escapeTooltip(warehouse.label)}`, {direction: 'top'})
        .addTo(this.markerLayer);
    }

    if (visible) {
      for (const store of stores) {
        L.marker([store.lat, store.lng], {
          icon: badgeIcon('store', 26),
          keyboard: false,
        })
          .bindTooltip(this.tooltipContent(store), {direction: 'top'})
          .addTo(this.markerLayer);
      }
    }

    // 司機點改成 divIcon，才可以顯示朝下一站前進的箭頭與脈衝效果。
    if (driversVisible) {
      for (const driver of driverPoints) {
        L.marker([driver.lat, driver.lng], {
          icon: driverIcon(driver.heading),
          keyboard: false,
        })
          .bindTooltip(this.driverTooltipContent(driver), {
            className: 'driver-map-tooltip',
            direction: 'top',
            opacity: 1,
          })
          .addTo(this.markerLayer);
      }
    }

    // 自動框選不把司機算進去：司機位置每 30 秒變動，框選只在換倉庫時算一次，
    // 兩者週期對不上；而且司機一旦跑到高雄以外，範圍會被拉到失去意義。
    if (visible) {
      this.fitOnce(map, warehouse, stores);
    }
  }

  private tooltipContent(point: MapPoint): string {
    return [point.label, ...(point.details ?? [point.detail])]
      .filter(Boolean)
      .map((value) => this.escapeTooltip(value))
      .join('<br>');
  }

  private driverTooltipContent(point: MapPoint): string {
    const [task = point.detail, ...details] = point.details ?? [point.detail];
    return `<div class="driver-map-tooltip__content"><strong>${this.escapeTooltip(point.label)}</strong><span>${this.escapeTooltip(task)}</span>${details.map((detail) => `<small>${this.escapeTooltip(detail)}</small>`).join('')}</div>`;
  }

  private escapeTooltip(value: string): string {
    return value.replace(/[&<>'"]/g, (character) => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      "'": '&#39;',
      '"': '&quot;',
    })[character]!);
  }

  /**
   * 重畫司機路線。
   *
   * 直線連點，不走實際道路 —— 線會穿過建物與港灣，長度也不等於里程，
   * 畫面上要標距離請用看板算出來的 totalDistance。
   */
  private drawLines(lines: RouteLine[], visible: boolean): void {
    const map = this.map;
    if (!map) {
      return;
    }

    this.lineLayer ??= L.layerGroup().addTo(map);
    this.routeSvgRenderer ??= L.svg().addTo(map);
    this.lineLayer.clearLayers();
    if (!visible) {
      return;
    }

    lines.forEach((line, index) => {
      // 一個點連不成線，補上倉庫後至少要兩點才畫
      if (line.points.length < 2) {
        return;
      }

      L.polyline(line.points, {
        color: ROUTE_LINE_COLORS[index % ROUTE_LINE_COLORS.length],
        className: 'route-flow-line',
        weight: 3,
        opacity: 0.85,
        renderer: this.routeSvgRenderer,
      })
        .bindTooltip(line.label, {sticky: true})
        .addTo(this.lineLayer!);
    });
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

}

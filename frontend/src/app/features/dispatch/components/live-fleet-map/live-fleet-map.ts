import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
  signal, input, effect,
} from '@angular/core';
import {MatIconModule} from '@angular/material/icon';
import type * as maplibregl from 'maplibre-gl';

const OPEN_FREE_MAP_STYLE = 'https://tiles.openfreemap.org/styles/liberty';
const FLEET_ROUTE_SOURCE_ID = 'dispatch-fleet-routes';
const FLEET_ROUTE_LAYER_ID = 'dispatch-fleet-route-lines';

/** 地圖上的一個點。倉庫與門市共用同一個型別，差別只在畫出來的樣式 */
export interface MapPoint {
  id: number;
  label: string;   // 名稱
  detail: string;  // tooltip 第二行
  /** 額外資訊會同時出現在地圖提示與右側定位清單。 */
  details?: string[];
  lat: number;
  lng: number;
}

/** 一位司機當天的配送路線，倉庫出發依派車順序連到各門市 */
export interface RouteLine {
  id: number;                   // routeId，同時決定配色
  label: string;                // tooltip：司機名
  points: [number, number][];   // [緯度, 經度]
}

/**
 * 路線配色。避開既有的三個寫死色：倉庫 #e0ad76、門市 #63d1c6、司機 #7ee787，
 * 否則線會跟點混成同一色。超過六條就從頭循環。
 */
const ROUTE_LINE_COLORS = ['#7aa2f7', '#f7768e', '#bb9af7', '#e0af68', '#2ac3de', '#9ece6a'];

@Component({
  selector: 'app-live-fleet-map',
  imports: [MatIconModule],
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
  readonly resizable = input(false);
  protected readonly mapHeight = signal(430);
  protected readonly isResizingMap = signal(false);
  private maplibre: typeof import('maplibre-gl') | null = null;
  private map?: maplibregl.Map;
  private resizeObserver?: ResizeObserver;
  private pointMarkers: maplibregl.Marker[] = [];
  private pointPopups: maplibregl.Popup[] = [];
  private routeHoverPopup?: maplibregl.Popup;
  /**
   * 地圖是否已建立。用 signal 而不是判斷 this.map，是因為 effect 會早於
   * ngAfterViewInit 執行：那時直接 return 掉之後 input 沒再變動，effect 就不會再跑，
   * 點永遠畫不出來。讀這個 signal 才能讓「地圖建好」這件事本身觸發重畫。
   */
  private readonly mapReady = signal(false);
  /** 已自動框過範圍的倉庫 id，0 代表還沒框過。換倉庫才重框，理由見 fitOnce */
  private fittedWarehouseId = 0;
  private resizeStart: {pointerY: number; height: number} | null = null;

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
    void this.initializeMap();
  }

  private async initializeMap(): Promise<void> {
    const canvas = this.mapCanvas?.nativeElement;
    if (!canvas) {
      return;
    }

    await this.loadMapLibreStyles();
    const maplibregl = await import('maplibre-gl');
    maplibregl.setWorkerUrl('/maplibre/maplibre-gl-worker.mjs');
    this.maplibre = maplibregl;
    this.map = new maplibregl.Map({
      container: canvas,
      center: [120.3014, 22.6273],
      zoom: 12,
      maxZoom: 19,
      maxPitch: 0,
      dragRotate: true,
      touchZoomRotate: true,
      touchPitch: false,
      pitchWithRotate: false,
      attributionControl: {},
      style: OPEN_FREE_MAP_STYLE,
    });
    this.map.addControl(
      new maplibregl.NavigationControl({showCompass: true, showZoom: false, visualizePitch: false}),
      'top-left',
    );

    this.resizeObserver = new ResizeObserver(() => {
      const activeMap = this.map;
      if (activeMap?.getContainer().isConnected) {
        activeMap.resize();
      }
    });
    this.resizeObserver.observe(canvas);
    requestAnimationFrame(() => this.map?.resize());
    this.map.once('load', () => this.mapReady.set(true));
  }

  private loadMapLibreStyles(): Promise<void> {
    if (document.querySelector('link[data-maplibre-style]')) return Promise.resolve();

    return new Promise((resolve, reject) => {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = '/maplibre/maplibre-gl.css';
      link.dataset['maplibreStyle'] = '';
      link.onload = () => resolve();
      link.onerror = () => reject(new Error('MapLibre 樣式載入失敗'));
      document.head.append(link);
    });
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.routeHoverPopup?.remove();
    this.pointMarkers.forEach((marker) => marker.remove());
    this.pointPopups.forEach((popup) => popup.remove());
    this.map?.remove();
    this.map = undefined;
  }

  protected startMapResize(event: PointerEvent): void {
    if (!this.resizable()) return;
    event.preventDefault();
    this.resizeStart = {pointerY: event.clientY, height: this.mapHeight()};
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    this.isResizingMap.set(true);
  }

  protected resizeMap(event: PointerEvent): void {
    if (!this.resizeStart) return;
    this.mapHeight.set(this.clampMapHeight(this.resizeStart.height + event.clientY - this.resizeStart.pointerY));
  }

  protected finishMapResize(event: PointerEvent): void {
    const handle = event.currentTarget as HTMLElement;
    if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
    this.resizeStart = null;
    this.isResizingMap.set(false);
  }

  protected adjustMapHeight(event: KeyboardEvent): void {
    const step = 40;
    if (event.key === 'ArrowDown') this.mapHeight.update((height) => this.clampMapHeight(height + step));
    else if (event.key === 'ArrowUp') this.mapHeight.update((height) => this.clampMapHeight(height - step));
    else if (event.key === 'Home') this.mapHeight.set(300);
    else if (event.key === 'End') this.mapHeight.set(this.maxMapHeight());
    else return;
    event.preventDefault();
  }

  protected maxMapHeight(): number {
    return typeof window === 'undefined'
      ? 760
      : Math.max(430, Math.min(760, Math.floor(window.innerHeight * 0.75)));
  }

  private clampMapHeight(height: number): number {
    return Math.max(300, Math.min(this.maxMapHeight(), height));
  }

  /**
   * 重畫倉庫、門市與司機標記。
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

    this.pointMarkers.forEach((marker) => marker.remove());
    this.pointPopups.forEach((popup) => popup.remove());
    this.pointMarkers = [];
    this.pointPopups = [];

    if (visible && warehouse) {
      this.addPointMarker(warehouse, 'warehouse', `倉庫｜${warehouse.label}`);
    }

    if (visible) {
      for (const store of stores) {
        this.addPointMarker(store, 'store', this.tooltipContent(store));
      }
    }

    if (driversVisible) {
      for (const driver of driverPoints) {
        this.addPointMarker(driver, 'driver', `司機｜${this.tooltipContent(driver)}`);
      }
    }

    // 自動框選不把司機算進去：司機位置每 30 秒變動，框選只在換倉庫時算一次，
    // 兩者週期對不上；而且司機一旦跑到高雄以外，範圍會被拉到失去意義。
    if (visible) {
      this.fitOnce(map, warehouse, stores);
    }
  }

  private addPointMarker(point: MapPoint, kind: 'warehouse' | 'store' | 'driver', content: string): void {
    const maplibregl = this.maplibre;
    const map = this.map;
    if (!maplibregl || !map) return;

    const element = document.createElement('span');
    element.className = kind === 'driver'
      ? 'fleet-driver-marker'
      : `map-badge map-badge--${kind}`;
    if (kind !== 'driver') element.textContent = kind === 'warehouse' ? '倉' : '店';
    element.setAttribute('aria-hidden', 'true');

    const popup = new maplibregl.Popup({closeButton: false, closeOnClick: false, offset: 16})
      .setHTML(content);
    const marker = new maplibregl.Marker({element, anchor: 'center'})
      .setLngLat([point.lng, point.lat])
      .addTo(map);
    element.addEventListener('mouseenter', () => {
      if (!popup.isOpen()) popup.addTo(map);
    });
    element.addEventListener('mouseleave', () => popup.remove());
    this.pointMarkers.push(marker);
    this.pointPopups.push(popup);
  }

  private tooltipContent(point: MapPoint): string {
    return [point.label, ...(point.details ?? [point.detail])]
      .filter(Boolean)
      .map((value) => this.escapeTooltip(value))
      .join('<br>');
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
   * 更新司機路線 GeoJSON 圖層。
   *
   * 直線連點，不走實際道路 —— 線會穿過建物與港灣，長度也不等於里程，
   * 畫面上要標距離請用看板算出來的 totalDistance。
   */
  private drawLines(lines: RouteLine[], visible: boolean): void {
    const map = this.map;
    if (!map) {
      return;
    }

    const features = visible
      ? lines.filter((line) => line.points.length >= 2).map((line, index) => ({
        type: 'Feature' as const,
        properties: {label: line.label, color: ROUTE_LINE_COLORS[index % ROUTE_LINE_COLORS.length]},
        geometry: {
          type: 'LineString' as const,
          coordinates: line.points.map(([lat, lng]) => [lng, lat]),
        },
      }))
      : [];
    const data = {type: 'FeatureCollection' as const, features};
    const source = map.getSource(FLEET_ROUTE_SOURCE_ID) as maplibregl.GeoJSONSource | undefined;

    if (source) {
      source.setData(data);
      return;
    }

    map.addSource(FLEET_ROUTE_SOURCE_ID, {type: 'geojson', data});
    map.addLayer({
      id: FLEET_ROUTE_LAYER_ID,
      type: 'line',
      source: FLEET_ROUTE_SOURCE_ID,
      paint: {
        'line-color': ['get', 'color'],
        'line-width': 3,
        'line-opacity': 0.85,
      },
      layout: {'line-cap': 'round', 'line-join': 'round'},
    });
    this.bindRouteHover(map);
  }

  private bindRouteHover(map: maplibregl.Map): void {
    map.on('mousemove', FLEET_ROUTE_LAYER_ID, (event) => {
      const feature = event.features?.[0];
      if (!feature) return;
      const label = String(feature.properties?.['label'] ?? '');
      this.routeHoverPopup ??= new this.maplibre!.Popup({closeButton: false, closeOnClick: false, offset: 8});
      this.routeHoverPopup.setLngLat(event.lngLat).setText(label).addTo(map);
      map.getCanvas().style.cursor = 'pointer';
    });
    map.on('mouseleave', FLEET_ROUTE_LAYER_ID, () => {
      this.routeHoverPopup?.remove();
      map.getCanvas().style.cursor = '';
    });
  }

  /**
   * 自動框出所有點，但同一個倉庫只框一次。
   *
   * 每次重畫都 fitBounds 的話，拖一張卡片地圖就跳回全景，使用者放大在看的區域會被蓋掉；
   * 圖層關掉再打開也一樣。換倉庫才需要重新框。
   */
  private fitOnce(map: maplibregl.Map, warehouse: MapPoint | null, stores: MapPoint[]): void {
    const points = warehouse ? [warehouse, ...stores] : stores;
    const warehouseId = warehouse?.id ?? 0;
    if (points.length === 0 || warehouseId === this.fittedWarehouseId) {
      return;
    }

    this.fittedWarehouseId = warehouseId;
    const coordinates = points.map((point) => [point.lng, point.lat] as [number, number]);
    const bounds = new this.maplibre!.LngLatBounds(coordinates[0], coordinates[0]);
    coordinates.slice(1).forEach((coordinate) => bounds.extend(coordinate));
    map.fitBounds(bounds, {padding: 56, maxZoom: 14});
  }

}

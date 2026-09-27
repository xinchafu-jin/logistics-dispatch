import { ANIMATION_MODULE_TYPE, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { Observable, of, Subject, throwError } from 'rxjs';
import { DispatchDashboard } from './dispatch-dashboard';
import { LiveFleetMap } from '../../components/live-fleet-map/live-fleet-map';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverChatSocketService } from '../../../../core/services/driver-chat-socket.service';
import { DispatchResultDto, DispatchBoardPushDto, RouteMetricsDto, VehicleMaintenanceSummary } from '../../../../core/services/dispatch-api.models';

@Component({
  selector: 'app-live-fleet-map', template: '',
  inputs: ['warehousePoint', 'storePoints', 'showPoints', 'driverPoints', 'showDriverPoints', 'routeLines', 'showRouteLines', 'resizable'],
})
class TestFleetMap {}

const now = new Date();
const date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
const warehouse = {id: 1, warehouseCode: 'WH', name: '測試倉', address: '', lat: 22.6, lng: 120.3, isActive: true};
function board(status: 'DRAFT' | 'PUBLISHED'): DispatchResultDto {
  return {
    date, warehouse, unassignedOrders: [], pendingConfirmOrders: [],
    routes: [{routeId: 1, vehicleId: 1, plateNumber: 'CAR-TEST', vehicleType: '3.5噸', capacity: 100,
      driverId: 1, driverName: '測試司機', status, stopCount: 1, loadedBoxes: 10, totalDistance: 1000,
      estimatedFuelCost: null, estimatedWorkMinutes: null, loadRate: .1,
      stops: [{sequence: 1, orderId: 1, orderNumber: 'ORDER-TEST', boxCount: 10, itemDescription: null,
        storeId: 1, storeCode: 'STORE', storeName: '測試店', address: '', lat: 22.6, lng: 120.3,
        contactName: null, phone: null, receivingStart: '09:00', receivingEnd: '18:00'}]}],
  };
}

describe('DispatchDashboard 發布與撤回', () => {
  let fixture: ComponentFixture<DispatchDashboard>;
  let page: DispatchDashboard;
  let dialogClosed: Subject<boolean>;
  let pushes: Subject<DispatchBoardPushDto>;
  let api: ReturnType<typeof createApi>;
  let openDialog: ReturnType<typeof vi.fn>;

  function createApi() {
    return {
      getOrders: vi.fn(() => of([{id: 1, orderNumber: 'ORDER-TEST', storeId: 1, warehouseId: 1, boxCount: 10, deliveryDate: date, status: 'CONFIRMED'}])),
      getStores: vi.fn(() => of([])), getVehicles: vi.fn(() => of([{id: 1, warehouseId: 1, plateNumber: 'CAR-TEST', capacity: 100, status: 'AVAILABLE'}])),
      getDrivers: vi.fn(() => of([{id: 1, warehouseId: 1, account: 'TEST', name: '測試司機', isActive: true}])),
      getWarehouses: vi.fn(() => of([warehouse])), getTemplates: vi.fn(() => of([])),
      getDispatchBoard: vi.fn(() => of(board('PUBLISHED'))), getRouteMetrics: vi.fn<() => Observable<RouteMetricsDto | null>>(() => of(null)), getLiveFleet: vi.fn(() => of([])),
      getDispatchDays: vi.fn(() => of([{date, status: 'PUBLISHED', orderCount: 1, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 0}])),
      getScheduleMonth: vi.fn(() => of({id: 1, status: 'PUBLISHED'})),
      getScheduleMonthShifts: vi.fn(() => of([{id: 1, driverId: 1, workDate: date, shiftType: 'WORK'}])),
      publishDispatch: vi.fn(() => of([board('PUBLISHED')])), withdrawDispatch: vi.fn(() => of([board('DRAFT')])),
      reassignDispatch: vi.fn(() => of(board('DRAFT'))),
    };
  }

  beforeEach(() => {
    api = createApi(); dialogClosed = new Subject<boolean>(); pushes = new Subject<DispatchBoardPushDto>();
    openDialog = vi.fn(() => ({afterClosed: () => dialogClosed.asObservable()}));
    TestBed.configureTestingModule({
      imports: [DispatchDashboard],
      providers: [
        {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations'},
        {provide: DispatchApiService, useValue: api},
        {provide: MatDialog, useValue: {open: openDialog}},
        {provide: DriverChatSocketService, useValue: {boardPushes$: pushes.asObservable(), connected$: new Subject<void>().asObservable()}},
      ],
    }).overrideComponent(DispatchDashboard, {
      remove: {imports: [LiveFleetMap]},
      add: {imports: [TestFleetMap], providers: [{provide: MatDialog, useValue: {open: openDialog}}]},
    });
    fixture = TestBed.createComponent(DispatchDashboard); page = fixture.componentInstance; fixture.detectChanges();
  });

  afterEach(() => { fixture.destroy(); vi.useRealTimers(); });

  function publishButton(): HTMLButtonElement { return fixture.nativeElement.querySelector('.publication-publish'); }
  function withdrawButton(): HTMLButtonElement { return fixture.nativeElement.querySelector('.publication-actions button:last-child'); }
  function confirm(): void { dialogClosed.next(true); dialogClosed.complete(); fixture.detectChanges(); }

  it('keeps both actions visible and enables only withdrawal on the published board', () => {
    expect(publishButton().textContent).toContain('發布');
    expect(withdrawButton().textContent).toContain('撤回發布');
    expect(publishButton().disabled).toBe(true); expect(withdrawButton().disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.published-route-card')).not.toBeNull();
  });

  it('starts with mileage details folded and supports individual and all-card toggles', () => {
    const route = page.routes()[0];
    page.routes.set([route, {...route, slotKey: 'second', routeId: 2, plateNumber: 'CAR-SECOND'}]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.published-route-card').length).toBe(2);
    expect(fixture.nativeElement.querySelectorAll('.published-route-card app-vehicle-maintenance-panel').length).toBe(0);
    const cards = fixture.nativeElement.querySelectorAll('.published-route-card') as NodeListOf<HTMLElement>;
    const firstToggle = cards[0].querySelector('.mileage-card-toggle') as HTMLButtonElement;
    expect(firstToggle.getAttribute('aria-expanded')).toBe('false');
    firstToggle.click(); fixture.detectChanges();
    expect(cards[0].querySelector('app-vehicle-maintenance-panel')).not.toBeNull();
    expect(cards[1].querySelector('app-vehicle-maintenance-panel')).toBeNull();
    const all = fixture.nativeElement.querySelector('.mileage-global-toggle') as HTMLButtonElement;
    expect(all.textContent).toContain('全部展開');
    all.click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.published-route-card app-vehicle-maintenance-panel').length).toBe(2);
    expect(all.textContent).toContain('全部收合');
    all.click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.published-route-card app-vehicle-maintenance-panel').length).toBe(0);
  });

  it('keeps blocked maintenance notices visible even when mileage detail is folded', () => {
    const maintenance: VehicleMaintenanceSummary = {
      currentOdometerKm: 3000, minorRemainingKm: 0, majorRemainingKm: 17000,
      retirementRemainingKm: 497000, warningKm: 500, plannedKm: 20,
      projectedMinorKm: -20, projectedMajorKm: 16980, projectedRetirementKm: 496980,
      decision: 'BLOCKED', reasons: ['小保里程已達上限'], minorCount: 0, majorCount: 0, repairCount: 0,
      lastMinorAt: null, lastMajorAt: null, lastRepairAt: null,
    };
    page.vehicles.update(items => items.map(vehicle => ({...vehicle, maintenance})));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.published-route-card app-vehicle-maintenance-panel')).toBeNull();
    expect(fixture.nativeElement.querySelector('.published-route-card app-vehicle-maintenance-notice').textContent).toContain('禁止出車');
    (fixture.nativeElement.querySelector('.mileage-card-toggle') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.published-route-card app-vehicle-maintenance-notice').length).toBe(1);
  });

  it('uses the same collapse controls on an editable draft board', () => {
    withdrawButton().click(); confirm(); fixture.detectChanges();
    const lane = fixture.nativeElement.querySelector('.slot-grid .board-lane') as HTMLElement;
    expect(lane.querySelector('app-vehicle-maintenance-panel')).toBeNull();
    (lane.querySelector('.mileage-card-toggle') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(lane.querySelector('app-vehicle-maintenance-panel')).not.toBeNull();
    (fixture.nativeElement.querySelector('.mileage-global-toggle') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(lane.querySelector('app-vehicle-maintenance-panel')).toBeNull();
  });

  const forecast: VehicleMaintenanceSummary = {
    currentOdometerKm: 0, minorRemainingKm: 3000, majorRemainingKm: 20000, retirementRemainingKm: 500000,
    warningKm: 500, plannedKm: 36.4, projectedMinorKm: 2963.6, projectedMajorKm: 19963.6,
    projectedRetirementKm: 499963.6, decision: 'NORMAL', reasons: [], minorCount: 0, majorCount: 0, repairCount: 0,
    lastMinorAt: null, lastMajorAt: null, lastRepairAt: null,
  };
  const metrics = (maintenance = forecast, vehicleId = 1) => ({routeId: 1, vehicleId, plannedKm: maintenance.plannedKm,
    mileageStatus: 'COMPLETE', maintenance} as RouteMetricsDto);

  it('草稿和發布卡片都顯示相同的實際與含回程保養預估，不因舊出車紀錄省略', () => {
    page.vehicles.update(items => items.map(vehicle => ({...vehicle, maintenance: {...forecast, plannedKm: null}})));
    api.getRouteMetrics.mockReturnValue(of(metrics()));
    withdrawButton().click(); confirm();
    const route = page.routes()[0]; page.toggleMileageDetails(route); fixture.detectChanges();
    let panel = fixture.nativeElement.querySelector('.slot-grid app-vehicle-maintenance-panel');
    expect(panel.querySelector('.actual').textContent).toContain('實際總里程0 km');
    expect(panel.querySelector('.projection').textContent).toContain('預估行駛（含回程）36.4 km');
    expect(panel.querySelector('.projection').textContent).toContain('距離小保2,963.6 km');
    expect(page.maintenanceEstimatePending(route)).toBe(false);
    api.getDispatchBoard.mockReturnValue(of(board('DRAFT')));
    publishButton().click(); fixture.detectChanges();
    expect(page.published()).toBe(true);
    panel = fixture.nativeElement.querySelector('.published-route-card app-vehicle-maintenance-panel');
    expect(panel.querySelector('.projection').textContent).toContain('預估行駛（含回程）36.4 km');
    expect(panel.querySelector('.projection').textContent).toContain('距離小保2,963.6 km');
  });

  it('路線預估缺少保養欄位時明確提示，不能默默只顯示實際紀錄', () => {
    api.getRouteMetrics.mockReturnValue(of({routeId: 1, vehicleId: 1, plannedKm: 36.4} as RouteMetricsDto));
    withdrawButton().click(); confirm();
    expect(page.maintenanceEstimatePending(page.routes()[0])).toBe(true);
    expect(fixture.nativeElement.querySelector('.slot-grid').textContent).toContain('完整 OSRM 含回程里程尚未取得');
  });

  it('不把另一台車的舊路線預估套到新選的車', () => {
    api.getRouteMetrics.mockReturnValue(of(metrics(forecast, 999)));
    withdrawButton().click(); confirm();
    expect(page.maintenanceSummary(page.routes()[0])).toBeUndefined();
    expect(page.maintenanceEstimatePending(page.routes()[0])).toBe(true);
  });

  it('never offers another warehouse driver even when previously assigned, and changes options when switching warehouses', () => {
    page.drivers.set([
      {id: 1, warehouseId: 2, account: 'TRANSFERRED', name: '已轉倉司機', isActive: true, workStart: '08:00', workEnd: '17:00', restDuration: 60},
      {id: 2, warehouseId: 1, account: 'LOCAL', name: '本倉司機', isActive: true, workStart: '08:00', workEnd: '17:00', restDuration: 60},
      {id: 3, warehouseId: 2, account: 'OTHER', name: '其他倉', isActive: true, workStart: '08:00', workEnd: '17:00', restDuration: 60},
      {id: 4, account: 'LEGACY', name: '未設定', isActive: true, workStart: '08:00', workEnd: '17:00', restDuration: 60},
      {id: 5, warehouseId: 1, account: 'INACTIVE', name: '停用', isActive: false, workStart: '08:00', workEnd: '17:00', restDuration: 60},
    ]);
    const options = page.driverOptions(page.routes()[0]);
    expect(options.map(option => option.id)).toEqual([2]);
    expect(page.assignedDriverOutsideWarehouse(page.routes()[0])).toBe(true);
    page.warehouseId.set(2);
    expect(page.driverOptions(page.routes()[0]).map(option => option.id)).toEqual([1, 3]);
    expect(page.assignedDriverOutsideWarehouse(page.routes()[0])).toBe(false);
  });

  it('refreshes driver affiliation when resources change in another page', () => {
    api.getDrivers.mockReturnValue(of([{id: 1, warehouseId: 2, account: 'TEST', name: '測試司機', isActive: true}]));
    vi.useFakeTimers(); pushes.next({resourcesChanged: true}); vi.advanceTimersByTime(600); fixture.detectChanges();
    expect(page.drivers()[0].warehouseId).toBe(2);
    expect(page.driverOptions(page.routes()[0])).toEqual([]);
    expect(page.routeScheduleProblem(page.routes()[0])).toContain('已轉至其他倉庫');
  });

  it('shows a reassign placeholder without another warehouse name in the draft driver dropdown', () => {
    withdrawButton().click(); confirm();
    page.drivers.update(drivers => drivers.map(driver => ({...driver, warehouseId: 2}))); fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('.slot-grid .board-lane select') as HTMLSelectElement;
    expect(select.textContent).toContain('司機歸屬已變更，請重新指派');
    expect(select.textContent).not.toContain('測試司機');
    expect(select.options[select.selectedIndex].textContent).toContain('請重新指派');
    // 保留未發布草稿的原指派，讓主管明確重新選人；不偷偷修改既有資料。
    expect(page.routes()[0].driverId).toBe(1);
  });

  it('disables same-warehouse drivers on day off, leave, no shift or an unpublished schedule', () => {
    withdrawButton().click(); confirm();
    const shift = page.shiftsByDriverId().get(1)!;
    for (const shiftType of ['DAY_OFF', 'LEAVE', 'UNASSIGNED'] as const) {
      page.shiftsByDriverId.set(new Map([[1, {...shift, shiftType}]])); fixture.detectChanges();
      const option = fixture.nativeElement.querySelector('.slot-grid .board-lane select option[value="1"]') as HTMLOptionElement;
      expect(option.disabled).toBe(true);
    }
    page.shiftsByDriverId.set(new Map()); fixture.detectChanges();
    expect(page.driverOptions(page.routes()[0])[0].scheduleNote).toBe('當天未排班');
    page.scheduleLoadState.set('unavailable'); page.scheduleMessage.set('當月班表尚未發布'); fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.slot-grid .board-lane select option[value="1"]') as HTMLOptionElement).disabled).toBe(true);
    page.scheduleLoadState.set('ready'); page.shiftsByDriverId.set(new Map([[1, shift]])); fixture.detectChanges();
    expect((fixture.nativeElement.querySelector('.slot-grid .board-lane select option[value="1"]') as HTMLOptionElement).disabled).toBe(false);
  });

  it('rejects stale or forged selection before modifying a route or calling reassign', () => {
    withdrawButton().click(); confirm();
    const route = page.routes()[0];
    page.onDriverChange(route, {target: {value: '999'}} as unknown as Event);
    expect(page.boardError()).toContain('不屬於目前倉庫'); expect(page.routes()[0].driverId).toBe(1);
    expect(api.reassignDispatch).not.toHaveBeenCalled();
    page.shiftsByDriverId.set(new Map());
    page.onDriverChange(route, {target: {value: '1'}} as unknown as Event);
    expect(page.boardError()).toBe('當天未排班'); expect(api.reassignDispatch).not.toHaveBeenCalled();
  });

  it('validates a global publication against each route warehouse, not just the selected warehouse', () => {
    withdrawButton().click(); confirm();
    page.warehouses.set([warehouse, {...warehouse, id: 2, warehouseCode: 'OTHER', name: '其他倉'}]);
    page.drivers.update(drivers => [...drivers, {...drivers[0], id: 2, warehouseId: 2, name: '其他倉司機'}]);
    page.shiftsByDriverId.update(shifts => new Map([...shifts, [2, {...shifts.get(1)!, id: 2, driverId: 2}]]));
    const other = board('DRAFT'); other.warehouse = {...warehouse, id: 2};
    other.routes = other.routes.map(route => ({...route, routeId: 2, driverId: 2}));
    api.getDispatchBoard.mockReturnValueOnce(of(board('DRAFT'))).mockReturnValueOnce(of(other));
    publishButton().click(); fixture.detectChanges();
    expect(api.publishDispatch).toHaveBeenCalledWith(date);
    expect(page.publishError()).toBe('');
  });

  it('does not call the withdrawal endpoint when the confirmation is cancelled', () => {
    withdrawButton().click(); fixture.detectChanges();
    expect(openDialog).toHaveBeenCalledOnce(); expect(page.busy()).toBe(true);
    expect(api.withdrawDispatch).not.toHaveBeenCalled();
    dialogClosed.next(false); fixture.detectChanges();
    expect(api.withdrawDispatch).not.toHaveBeenCalled(); expect(page.published()).toBe(true);
    expect(page.busy()).toBe(false); expect(withdrawButton().disabled).toBe(false);
  });

  it('unlocks editing after withdrawal even while the date summary still reports published', () => {
    withdrawButton().click(); confirm();
    expect(api.withdrawDispatch).toHaveBeenCalledWith(date);
    expect(page.days()[0].status).toBe('PUBLISHED');
    expect(page.published()).toBe(false); expect(page.busy()).toBe(false);
    expect(publishButton().disabled).toBe(false); expect(withdrawButton().disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.board-lane select').disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.pending-pool')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.published-route-card')).toBeNull();
  });

  it('can publish again after withdrawing and restores the read-only view', () => {
    withdrawButton().click(); confirm();
    api.getDispatchBoard.mockReturnValue(of(board('DRAFT')));
    publishButton().click(); fixture.detectChanges();
    expect(api.publishDispatch).toHaveBeenCalledWith(date);
    expect(page.published()).toBe(true); expect(page.routes()[0].routeStatus).toBe('PUBLISHED');
    expect(publishButton().disabled).toBe(true); expect(withdrawButton().disabled).toBe(false);
  });

  it('shows the backend protection reason and keeps started routes locked when withdrawal is rejected', () => {
    api.withdrawDispatch.mockReturnValue(throwError(() => new HttpErrorResponse({status: 400, error: {message: '已有訂單開始配送，不能撤回'}})));
    withdrawButton().click(); confirm();
    expect(page.publishError()).toContain('已有訂單開始配送，不能撤回');
    expect(page.published()).toBe(true); expect(page.routes()[0].routeStatus).toBe('PUBLISHED');
    expect(page.busy()).toBe(false); expect(api.reassignDispatch).not.toHaveBeenCalled();
    expect(withdrawButton().disabled).toBe(false);
  });

  it('prevents duplicate withdrawal and changing warehouses while the request is pending', () => {
    const pending = new Subject<DispatchResultDto[]>(); api.withdrawDispatch.mockReturnValue(pending);
    withdrawButton().click(); confirm();
    page.withdraw();
    page.onWarehouseChange({target: {value: '2'}} as unknown as Event);
    expect(api.withdrawDispatch).toHaveBeenCalledOnce(); expect(page.warehouseId()).toBe(1);
    expect(publishButton().disabled).toBe(true); expect(withdrawButton().disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.board-toolbar select').disabled).toBe(true);
    pending.next([board('DRAFT')]); fixture.detectChanges(); expect(page.busy()).toBe(false);
  });

  it('refreshes local withdrawal state when another user republishes the date', () => {
    withdrawButton().click(); confirm(); expect(page.published()).toBe(false);
    vi.useFakeTimers(); pushes.next({date}); vi.advanceTimersByTime(600); fixture.detectChanges();
    expect(page.published()).toBe(true); expect(page.routes()[0].routeStatus).toBe('PUBLISHED');
    expect(publishButton().disabled).toBe(true);
  });
});

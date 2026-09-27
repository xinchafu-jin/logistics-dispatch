import {Component} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {HttpErrorResponse} from '@angular/common/http';
import {of, Subject, throwError} from 'rxjs';
import {DispatchDashboard} from './dispatch-dashboard';
import {LiveFleetMap} from '../../components/live-fleet-map/live-fleet-map';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DriverChatSocketService} from '../../../../core/services/driver-chat-socket.service';
import {DispatchBoardPushDto, DispatchDayDto, DispatchDayStatus, DispatchResultDto, OrderDto} from '../../../../core/services/dispatch-api.models';

@Component({
  selector: 'app-live-fleet-map', template: '',
  inputs: ['warehousePoint', 'storePoints', 'showPoints', 'driverPoints', 'showDriverPoints', 'routeLines', 'showRouteLines', 'resizable'],
})
class WithdrawalTestMap {}

const today = new Date();
const workDate = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
const warehouse = {id: 1, warehouseCode: 'WH', name: '測試倉', address: '', lat: 22.6, lng: 120.3, isActive: true};
function board(status: 'DRAFT' | 'PUBLISHED'): DispatchResultDto {
  return {
    date: workDate, warehouse, unassignedOrders: [], pendingConfirmOrders: [],
    routes: [{routeId: 1, vehicleId: 1, plateNumber: 'CAR-TEST', vehicleType: '3.5噸', capacity: 100,
      driverId: 1, driverName: '測試司機', status, stopCount: 1, loadedBoxes: 10, totalDistance: 1000,
      estimatedFuelCost: null, estimatedWorkMinutes: null, loadRate: .1,
      stops: [{sequence: 1, orderId: 1, orderNumber: 'ORDER-TEST', boxCount: 10, itemDescription: null,
        storeId: 1, storeCode: 'STORE', storeName: '測試店', address: '', lat: 22.6, lng: 120.3,
        contactName: null, phone: null, receivingStart: '09:00', receivingEnd: '18:00'}]}],
  };
}

describe('原版看板撤回發布入口', () => {
  let fixture: ComponentFixture<DispatchDashboard>;
  let page: DispatchDashboard;
  let api: ReturnType<typeof createApi>;
  let boardPushes: Subject<DispatchBoardPushDto>;
  let connections: Subject<void>;

  function createApi() {
    return {
      getOrders: vi.fn(() => of<OrderDto[]>([{id: 1, orderNumber: 'ORDER-TEST', storeId: 1, warehouseId: 1, boxCount: 10, deliveryDate: workDate, status: 'CONFIRMED', notes: ''}])),
      getStores: vi.fn(() => of([])), getVehicles: vi.fn(() => of([{id: 1, warehouseId: 1, plateNumber: 'CAR-TEST', capacity: 100, status: 'AVAILABLE'}])),
      getDrivers: vi.fn(() => of([{id: 1, warehouseId: 1, account: 'TEST', name: '測試司機', isActive: true}])),
      getWarehouses: vi.fn(() => of([warehouse])), getTemplates: vi.fn(() => of([])),
      getDispatchBoard: vi.fn(() => of(board('PUBLISHED'))), getRouteMetrics: vi.fn(() => of(null)), getLiveFleet: vi.fn(() => of([])),
      getDispatchDays: vi.fn(() => of<DispatchDayDto[]>([{date: workDate, status: 'PUBLISHED', published: true, orderCount: 1, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 0}])),
      getScheduleMonth: vi.fn(() => of({id: 1, status: 'PUBLISHED'})),
      getScheduleMonthShifts: vi.fn(() => of([{id: 1, driverId: 1, workDate: workDate, shiftType: 'WORK'}])),
      publishDispatch: vi.fn(() => of([board('PUBLISHED')])), withdrawDispatch: vi.fn(() => of([board('DRAFT')])),
      reassignDispatch: vi.fn(() => of(board('DRAFT'))),
    };
  }

  beforeEach(() => {
    vi.useFakeTimers();
    api = createApi();
    boardPushes = new Subject<DispatchBoardPushDto>();
    connections = new Subject<void>();
    TestBed.configureTestingModule({
      imports: [DispatchDashboard],
      providers: [
        {provide: DispatchApiService, useValue: api},
        {provide: DriverChatSocketService, useValue: {boardPushes$: boardPushes.asObservable(), connected$: connections.asObservable()}},
      ],
    }).overrideComponent(DispatchDashboard, {
      remove: {imports: [LiveFleetMap]}, add: {imports: [WithdrawalTestMap]},
    });
    fixture = TestBed.createComponent(DispatchDashboard);
    page = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    vi.useRealTimers();
  });

  function recalledDay(status: DispatchDayStatus = 'IN_PROGRESS'): void {
    api.getDispatchDays.mockReturnValue(of([{date: workDate, status, published: false,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]));
    api.getDispatchBoard.mockReturnValue(of(board('DRAFT')));
  }

  function push(): void {
    boardPushes.next({date: workDate});
    vi.advanceTimersByTime(500);
    fixture.detectChanges();
  }

  function drop(previousIndex = 0, currentIndex = 0): void {
    const container = {data: page.routes()[0].cards};
    page.onDrop({previousContainer: container, container, previousIndex, currentIndex} as
      Parameters<DispatchDashboard['onDrop']>[0]);
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement | undefined {
    return Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find((item) => Array.from(item.childNodes)
        .filter((node) => node.nodeType === Node.TEXT_NODE)
        .map((node) => node.textContent ?? '').join('').trim() === label);
  }

  it('shows both publication actions and disables the unavailable action', () => {
    expect(button('撤回發布')).toBeDefined();
    expect(button('撤回發布')!.classList.contains('template-secondary')).toBe(true);
    expect(button('發布')!.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.publication-actions')).not.toBeNull();
  });

  it('unlocks editing after a confirmed withdrawal even while the date summary is stale', () => {
    page.withdraw();
    fixture.detectChanges();
    expect(api.withdrawDispatch).toHaveBeenCalledWith(workDate);
    expect(page.published()).toBe(false);
    expect(page.busy()).toBe(false);
    expect(button('發布')).toBeDefined();
    expect(button('撤回發布')!.disabled).toBe(true);
    expect(page.orders()[0].status).toBe('CONFIRMED');
  });

  it('allows publishing again after withdrawing', () => {
    page.withdraw();
    fixture.detectChanges();
    button('發布')!.click();
    fixture.detectChanges();
    expect(api.publishDispatch).toHaveBeenCalledWith(workDate);
    expect(page.published()).toBe(true);
    expect(button('撤回發布')).toBeDefined();
  });

  it('stays editable after withdrawal, a drag save and its real debounced board push', () => {
    page.withdraw();
    recalledDay();
    drop();
    push();

    expect(api.reassignDispatch).toHaveBeenCalledOnce();
    expect(api.publishDispatch).not.toHaveBeenCalled();
    expect(page.days()[0].status).toBe('IN_PROGRESS');
    expect(page.routes()[0].routeStatus).toBe('DRAFT');
    expect(page.published()).toBe(false);
    expect(button('發布')).toBeDefined();
    expect(button('撤回發布')!.disabled).toBe(true);

    drop();
    expect(api.reassignDispatch).toHaveBeenCalledTimes(2);
  });

  it('keeps the confirmed withdrawal while a drag push is still fetching fresh data', () => {
    page.withdraw();
    // 模擬日期列仍快取已發布，新查詢還沒回來；不可先刪掉撤回 API 的結果。
    const freshDays = new Subject<DispatchDayDto[]>();
    const freshBoard = new Subject<DispatchResultDto>();
    api.getDispatchDays.mockReturnValue(freshDays);
    api.getDispatchBoard.mockReturnValue(freshBoard);
    drop();
    push();

    expect(page.days()[0].published).toBe(true);
    expect(page.published()).toBe(false);
    expect(button('發布')).toBeDefined();
    expect(api.publishDispatch).not.toHaveBeenCalled();

    freshDays.next([{date: workDate, status: 'IN_PROGRESS', published: false,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]);
    freshBoard.next(board('DRAFT'));
    fixture.detectChanges();
    expect(page.published()).toBe(false);
    drop();
    expect(api.reassignDispatch).toHaveBeenCalledTimes(2);
  });

  it('keeps the confirmed withdrawal while reconnect queries are pending or fail', () => {
    page.withdraw();
    const days = new Subject<DispatchDayDto[]>();
    const result = new Subject<DispatchResultDto>();
    api.getDispatchDays.mockReturnValue(days);
    api.getDispatchBoard.mockReturnValue(result);
    connections.next();
    fixture.detectChanges();
    expect(page.published()).toBe(false);

    days.error(new Error('offline'));
    result.error(new Error('offline'));
    fixture.detectChanges();
    expect(page.published()).toBe(false);
    expect(button('發布')).toBeDefined();
  });

  it('ignores older published day responses arriving after a fresh draft response', () => {
    page.withdraw();
    recalledDay();
    const oldDays = new Subject<DispatchDayDto[]>();
    api.getDispatchDays.mockReturnValue(oldDays);
    push();

    recalledDay();
    push();
    oldDays.next([{date: workDate, status: 'PUBLISHED', published: true,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]);
    fixture.detectChanges();

    expect(page.days()[0].published).toBe(false);
    expect(page.published()).toBe(false);
    expect(button('發布')).toBeDefined();
  });

  it('ignores an older day query failure instead of clearing the fresh date list', () => {
    page.withdraw();
    recalledDay();
    const oldDays = new Subject<DispatchDayDto[]>();
    api.getDispatchDays.mockReturnValue(oldDays);
    push();
    recalledDay();
    push();
    oldDays.error(new Error('late failure'));

    expect(page.days()).toHaveLength(1);
    expect(page.days()[0].published).toBe(false);
    expect(page.published()).toBe(false);
  });

  it('ignores older published board responses arriving after a fresh draft board', () => {
    page.withdraw();
    recalledDay();
    const oldBoard = new Subject<DispatchResultDto>();
    api.getDispatchBoard.mockReturnValue(oldBoard);
    push();
    recalledDay();
    push();
    oldBoard.next(board('PUBLISHED'));
    fixture.detectChanges();

    expect(page.routes()[0].routeStatus).toBe('DRAFT');
    expect(page.dispatchResult()!.routes[0].status).toBe('DRAFT');
    expect(page.published()).toBe(false);
    expect(api.publishDispatch).not.toHaveBeenCalled();
  });

  it('ignores an older board query failure after a newer query succeeds', () => {
    page.withdraw();
    recalledDay();
    const oldBoard = new Subject<DispatchResultDto>();
    api.getDispatchBoard.mockReturnValue(oldBoard);
    push();
    recalledDay();
    push();
    oldBoard.error(new Error('late failure'));

    expect(page.boardError()).toBe('');
    expect(page.routes()[0].routeStatus).toBe('DRAFT');
    expect(page.busy()).toBe(false);
  });

  it('invalidates pre-withdrawal queries even when they return after withdrawal succeeds', () => {
    const oldDays = new Subject<DispatchDayDto[]>();
    const oldBoard = new Subject<DispatchResultDto>();
    api.getDispatchDays.mockReturnValue(oldDays);
    api.getDispatchBoard.mockReturnValue(oldBoard);
    push();
    recalledDay();
    page.withdraw();
    oldBoard.next(board('PUBLISHED'));
    oldDays.next([{date: workDate, status: 'PUBLISHED', published: true,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]);

    expect(page.dispatchResult()!.routes[0].status).toBe('DRAFT');
    expect(page.days()[0].published).toBe(false);
    expect(page.published()).toBe(false);
  });

  it('does not let a late board query overwrite a drag or unlock an in-flight save', () => {
    page.withdraw();
    recalledDay();
    const oldBoard = new Subject<DispatchResultDto>();
    api.getDispatchBoard.mockReturnValue(oldBoard);
    push();
    const save = new Subject<DispatchResultDto>();
    api.reassignDispatch.mockReturnValue(save);
    drop();
    oldBoard.next(board('PUBLISHED'));

    expect(page.saving()).toBe(true);
    expect(page.busy()).toBe(true);
    expect(page.routes()[0].routeStatus).toBe('DRAFT');
    expect(page.published()).toBe(false);

    save.next(board('DRAFT'));
    expect(page.saving()).toBe(false);
    expect(page.published()).toBe(false);
  });

  it('does not use pre-withdrawal orders to start a late board reload', () => {
    const oldOrders = new Subject<ReturnType<typeof page.orders>>();
    api.getOrders.mockReturnValue(oldOrders);
    push();
    recalledDay();
    page.withdraw();
    const boardReads = api.getDispatchBoard.mock.calls.length;
    oldOrders.next(page.orders().map((order) => ({...order, status: 'COMPLETED'})));

    expect(api.getDispatchBoard).toHaveBeenCalledTimes(boardReads);
    expect(page.orders()[0].status).toBe('CONFIRMED');
    expect(page.published()).toBe(false);
  });

  it('ignores a board for the previous date after switching dates', () => {
    page.withdraw();
    recalledDay();
    const oldBoard = new Subject<DispatchResultDto>();
    api.getDispatchBoard.mockReturnValue(oldBoard);
    push();
    const nextDate = '2099-01-01';
    api.getDispatchBoard.mockReturnValue(of({...board('DRAFT'), date: nextDate}));
    page.selectDay(nextDate);
    oldBoard.next(board('PUBLISHED'));

    expect(page.dispatchDate()).toBe(nextDate);
    expect(page.dispatchResult()!.date).toBe(nextDate);
    expect(page.routes()[0].routeStatus).toBe('DRAFT');
  });

  it('resynchronizes a push received during a drag save after that save completes', () => {
    page.withdraw();
    recalledDay();
    const save = new Subject<DispatchResultDto>();
    api.reassignDispatch.mockReturnValue(save);
    drop();
    const orderReads = api.getOrders.mock.calls.length;
    push();
    expect(api.getOrders).toHaveBeenCalledTimes(orderReads);

    save.next(board('DRAFT'));
    expect(api.getOrders).toHaveBeenCalledTimes(orderReads + 1);
    expect(page.published()).toBe(false);
    expect(page.busy()).toBe(false);
    expect(api.publishDispatch).not.toHaveBeenCalled();
  });

  it('invalidates the old board as soon as a new push starts, before fresh orders return', () => {
    page.withdraw();
    recalledDay();
    const oldBoard = new Subject<DispatchResultDto>();
    api.getDispatchBoard.mockReturnValue(oldBoard);
    push();
    const freshOrders = new Subject<OrderDto[]>();
    api.getOrders.mockReturnValue(freshOrders);
    push();
    oldBoard.next(board('PUBLISHED'));

    expect(page.dispatchResult()!.routes[0].status).toBe('DRAFT');
    expect(page.published()).toBe(false);

    api.getDispatchBoard.mockReturnValue(of(board('DRAFT')));
    freshOrders.next(page.orders());
    expect(page.published()).toBe(false);
  });

  it('ignores a previous warehouse board after switching warehouses', () => {
    page.withdraw();
    recalledDay();
    const oldBoard = new Subject<DispatchResultDto>();
    api.getDispatchBoard.mockReturnValue(oldBoard);
    push();
    const otherBoard = {...board('DRAFT'), warehouse: {...warehouse, id: 2}, routes: []};
    api.getDispatchBoard.mockReturnValue(of(otherBoard));
    page.onWarehouseChange({target: {value: '2'}} as unknown as Event);
    oldBoard.next(board('PUBLISHED'));

    expect(page.warehouseId()).toBe(2);
    expect(page.dispatchResult()!.warehouse.id).toBe(2);
    expect(page.routes().every((route) => route.routeId === 0)).toBe(true);
    expect(page.published()).toBe(false);
  });

  it('does not let an older polling response overwrite orders after withdrawal', () => {
    const oldOrders = new Subject<OrderDto[]>();
    api.getOrders.mockReturnValue(oldOrders);
    vi.advanceTimersByTime(15_000);
    recalledDay();
    page.withdraw();
    oldOrders.next(page.orders().map((order) => ({...order, status: 'COMPLETED'})));

    expect(page.orders()[0].status).toBe('CONFIRMED');
    expect(page.published()).toBe(false);
  });

  it('keeps other-dispatcher publications visible even when the board returns before day metadata', () => {
    page.withdraw();
    recalledDay();
    const days = new Subject<DispatchDayDto[]>();
    api.getDispatchDays.mockReturnValue(days);
    push();
    expect(page.published()).toBe(false);
    days.next([{date: workDate, status: 'IN_PROGRESS', published: true,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]);

    expect(page.published()).toBe(true);
    expect(page.routes()[0].routeStatus).toBe('DRAFT');
    drop();
    expect(api.reassignDispatch).not.toHaveBeenCalled();
  });

  it('recovers a failed drag save and restores the actual server publication state', () => {
    page.withdraw();
    recalledDay();
    api.getDispatchBoard.mockReturnValue(of(board('PUBLISHED')));
    api.getDispatchDays.mockReturnValue(of([{date: workDate, status: 'PUBLISHED', published: true,
      orderCount: 1, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 0}]));
    api.reassignDispatch.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: {message: '其他調度員已發布'},
    })));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      drop();
      expect(page.busy()).toBe(false);
      expect(page.published()).toBe(true);
      expect(page.routes()[0].routeStatus).toBe('PUBLISHED');
      expect(page.boardError()).toContain('其他調度員已發布');
    } finally {
      consoleError.mockRestore();
    }
  });

  it('keeps the save unlocked even when restoring after an error also fails', () => {
    page.withdraw();
    recalledDay();
    api.getDispatchBoard.mockReturnValue(throwError(() => new Error('offline')));
    api.reassignDispatch.mockReturnValue(throwError(() => new Error('offline')));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      drop();
      expect(page.busy()).toBe(false);
      expect(page.saving()).toBe(false);
      expect(page.published()).toBe(false);
      expect(page.boardError()).toContain('無法重新讀取');
    } finally {
      consoleError.mockRestore();
    }
  });

  it('does not republish completed orders when a pending stop is dragged after withdrawal', () => {
    const draft = board('DRAFT');
    const firstStop = draft.routes[0].stops[0];
    draft.routes[0].stops = [1, 2, 3, 4].map((orderId, index) => ({
      ...firstStop, orderId, orderNumber: `ORDER-${orderId}`, sequence: index + 1,
    }));
    draft.routes[0].stopCount = 4;
    const orders = draft.routes[0].stops.map((stop): OrderDto => ({
      id: stop.orderId, orderNumber: stop.orderNumber, storeId: 1, warehouseId: 1,
      boxCount: 10, deliveryDate: workDate, notes: '', status: stop.orderId === 2 ? 'CONFIRMED' : 'COMPLETED',
    }));
    page.orders.set(orders);
    api.getOrders.mockReturnValue(of(orders));
    api.withdrawDispatch.mockReturnValue(of([draft]));
    api.reassignDispatch.mockReturnValue(of(draft));
    api.getDispatchBoard.mockReturnValue(of(draft));
    const days = new Subject<DispatchDayDto[]>();
    api.getDispatchDays.mockReturnValue(days);

    page.withdraw();
    drop(1, 2);
    push();
    expect(api.reassignDispatch).toHaveBeenCalledWith({date: workDate, warehouseId: 1,
      routes: [{vehicleId: 1, driverId: 1, orderIds: [2]}]});
    expect(api.publishDispatch).not.toHaveBeenCalled();
    expect(page.orders().filter((order) => order.status === 'COMPLETED')).toHaveLength(3);
    expect(page.published()).toBe(false);
    days.next([{date: workDate, status: 'IN_PROGRESS', published: false,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]);
    expect(page.published()).toBe(false);
  });

  it.each<DispatchDayStatus>(['IN_PROGRESS', 'CLOSED', 'UNRESOLVED'])(
    'loads recalled drafts as editable on a fresh page even when delivery progress is %s', (status) => {
      recalledDay(status);
      fixture.destroy();
      fixture = TestBed.createComponent(DispatchDashboard);
      page = fixture.componentInstance;
      fixture.detectChanges();

      expect(page.published()).toBe(false);
      expect(button('發布')).toBeDefined();
      expect(page.days()[0].finishedCount).toBe(3);
    },
  );

  it('keeps a draft editable after reconnecting and reloading delivery progress', () => {
    page.withdraw();
    recalledDay();
    connections.next();
    fixture.detectChanges();
    expect(page.published()).toBe(false);
    drop();
    expect(api.reassignDispatch).toHaveBeenCalledOnce();
  });

  it('resynchronizes a publication made by another dispatcher while disconnected', () => {
    page.withdraw();
    expect(page.published()).toBe(false);
    api.getDispatchDays.mockReturnValue(of([{date: workDate, status: 'IN_PROGRESS', published: true,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]));
    connections.next();
    fixture.detectChanges();
    expect(page.published()).toBe(true);
    expect(button('撤回發布')).toBeDefined();
  });

  it('still locks the whole day if another warehouse has published routes', () => {
    page.withdraw();
    recalledDay();
    api.getDispatchDays.mockReturnValue(of([{date: workDate, status: 'IN_PROGRESS', published: true,
      orderCount: 4, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 3}]));
    push();

    expect(page.routes()[0].routeStatus).toBe('DRAFT');
    expect(page.published()).toBe(true);
    expect(button('撤回發布')).toBeDefined();
    drop();
    expect(api.reassignDispatch).not.toHaveBeenCalled();
  });

  it('keeps the original route and cargo state when the backend refuses withdrawal', () => {
    api.withdrawDispatch.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: {message: '貨物已點交，請使用司機交接'},
    })));
    page.withdraw();
    fixture.detectChanges();
    expect(page.publishError()).toContain('貨物已點交');
    expect(page.published()).toBe(true);
    expect(page.routes()[0].routeStatus).toBe('PUBLISHED');
    expect(button('撤回發布')!.disabled).toBe(false);
  });

  it('prevents duplicate withdrawal and changing warehouse while the request is pending', () => {
    const pending = new Subject<DispatchResultDto[]>();
    api.withdrawDispatch.mockReturnValue(pending);
    page.withdraw();
    page.withdraw();
    page.onWarehouseChange({target: {value: '2'}} as unknown as Event);
    fixture.detectChanges();
    expect(api.withdrawDispatch).toHaveBeenCalledOnce();
    expect(page.warehouseId()).toBe(1);
    expect(button('撤回中…')!.disabled).toBe(true);
    pending.next([board('DRAFT')]);
    pending.complete();
    expect(page.busy()).toBe(false);
  });
});

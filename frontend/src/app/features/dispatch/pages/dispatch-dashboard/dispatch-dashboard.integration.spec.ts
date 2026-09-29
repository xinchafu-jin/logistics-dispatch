import {TestBed} from '@angular/core/testing';
import {MatDialog} from '@angular/material/dialog';
import {EMPTY, of, Subject} from 'rxjs';
import {afterEach, beforeEach, describe, expect, vi} from 'vitest';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DispatchBoardEventsService} from '../../../../core/services/dispatch-board-events.service';
import {DriverChatSocketService} from '../../../../core/services/driver-chat-socket.service';
import {DispatchDayDto, DriverDto} from '../../../../core/services/dispatch-api.models';
import {DispatchDashboard} from './dispatch-dashboard';

type Lane = Parameters<DispatchDashboard['driverOptions']>[0];
const lane = (driverId: number | null = null): Lane => ({
  slotKey: 'test-lane', routeId: 1, vehicleId: 10, plateNumber: 'TEST', vehicleType: null,
  capacity: 50, driverId, totalDistance: 0, routeStatus: 'DRAFT', hasLockedStops: false,
  isMaintenance: false, cards: [], storeIds: [],
});
const driver = (id: number, warehouseId: number | null, isActive = true): DriverDto => ({
  id, warehouseId, isActive, account: `D${id}`, name: `Driver ${id}`,
  workStart: '08:00', workEnd: '17:00', restDuration: 60,
});
const day = (date: string, status: DispatchDayDto['status'], published: boolean): DispatchDayDto => ({
  date, status, published, orderCount: 1, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 1,
});

describe('MAJOR dispatch integration', () => {
  let page: DispatchDashboard;
  let boardChanges: Subject<void>;
  beforeEach(() => {
    boardChanges = new Subject<void>();
    TestBed.configureTestingModule({providers: [
      {provide: DispatchApiService, useValue: {getLiveFleet: () => of([])}},
      {provide: DispatchBoardEventsService, useValue: {boardChanged$: boardChanges.asObservable()}},
      {provide: DriverChatSocketService, useValue: {
        boardPushes$: EMPTY, routeDeviationPushes$: EMPTY, connected$: EMPTY,
      }},
      {provide: MatDialog, useValue: {}},
    ]});
    page = TestBed.runInInjectionContext(() => new DispatchDashboard());
    page.warehouseId.set(1);
  });
  afterEach(() => TestBed.resetTestingModule());

  it('offers active drivers from every warehouse and preserves an assigned disabled identity', () => {
    page.drivers.set([driver(1, 1), driver(2, 2), driver(3, 1, false), driver(4, null)]);
    expect(page.driverOptions(lane()).map(option => option.id)).toEqual([1, 2, 4]);
    expect(page.driverOptions(lane(3)).map(option => option.id)).toEqual([1, 2, 3, 4]);
  });

  it('keeps a cross-warehouse assigned driver selectable', () => {
    page.drivers.set([driver(2, 2)]);
    const route = lane(2);
    expect(page.driverOptions(route).map(option => option.id)).toEqual([2]);
    expect(page.assignedDriverMissing(route)).toBe(false);
  });

  it('shows a driver already used at another warehouse but prevents a second assignment', () => {
    page.drivers.set([driver(2, 2)]);
    page.driversTakenElsewhere.set([{driverId: 2, driverName: 'Driver 2', plateNumber: 'TN-22', warehouseName: '台南倉'}]);

    expect(page.driverOptions(lane())[0].takenNote).toContain('已排在 台南倉 TN-22');
  });

  it('still flags an assigned driver whose record no longer exists', () => {
    expect(page.assignedDriverMissing(lane(9))).toBe(true);
  });

  it('does not confuse closed delivery progress with an active publication', () => {
    page.days.set([day(page.dispatchDate(), 'CLOSED', false)]);
    expect(page.published()).toBe(false);
    page.days.set([day(page.dispatchDate(), 'CLOSED', true)]);
    expect(page.published()).toBe(true);
  });

  it('does not allow manually confirming a no-signature order awaiting 06:00 auto dispatch', () => {
    const card: Parameters<DispatchDashboard['confirmPendingOrder']>[0] = {
      orderId: 2, orderNumber: 'NS-2', storeId: 1, storeCode: 'S1',
      storeName: '店', boxCount: 3, awaitingAutomaticDispatch: true,
      autoDispatchAt: '2026-09-30T06:00:00',
    };

    page.confirmPendingOrder(card);

    expect(page.confirmingOrderId()).toBeNull();
  });

  it('also locks the board when a loaded route is published', () => {
    page.routes.set([{...lane(), routeStatus: 'PUBLISHED'}]);
    expect(page.published()).toBe(true);
  });

  it('refreshes both old-date cards and the current board after an anomaly is confirmed', () => {
    const internals = page as unknown as {
      loadDashboard(): void; loadTemplates(): void; loadActiveDeviations(): void;
      loadDays(): void; refreshOrdersAndBoard(): void;
    };
    vi.spyOn(internals, 'loadDashboard').mockImplementation(() => {});
    vi.spyOn(internals, 'loadTemplates').mockImplementation(() => {});
    vi.spyOn(internals, 'loadActiveDeviations').mockImplementation(() => {});
    const loadDays = vi.spyOn(internals, 'loadDays').mockImplementation(() => {});
    const refreshBoard = vi.spyOn(internals, 'refreshOrdersAndBoard').mockImplementation(() => {});

    page.ngOnInit();
    boardChanges.next();

    expect(loadDays).toHaveBeenCalledOnce();
    expect(refreshBoard).toHaveBeenCalledOnce();
  });

  it('keeps the old-date push when an order moves from the old day to today', () => {
    const internals = page as unknown as {
      loadDays(): void;
      refreshOrdersAndBoard(): void;
      onBoardPushes(pushes: readonly {date: string | null; resourcesChanged?: boolean}[]): void;
    };
    const loadDays = vi.spyOn(internals, 'loadDays').mockImplementation(() => {});
    const refreshBoard = vi.spyOn(internals, 'refreshOrdersAndBoard').mockImplementation(() => {});
    page.dispatchDate.set('2026-09-25');

    internals.onBoardPushes([{date: '2026-09-25'}, {date: '2026-09-29'}]);

    expect(loadDays).toHaveBeenCalledOnce();
    expect(refreshBoard).toHaveBeenCalledOnce();
  });
});

import {TestBed} from '@angular/core/testing';
import {MatDialog} from '@angular/material/dialog';
import {of} from 'rxjs';
import {afterEach, beforeEach, describe, expect} from 'vitest';
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
const driver = (id: number, warehouseId: number, isActive = true): DriverDto => ({
  id, warehouseId, isActive, account: `D${id}`, name: `Driver ${id}`,
  workStart: '08:00', workEnd: '17:00', restDuration: 60,
});
const day = (date: string, status: DispatchDayDto['status'], published: boolean): DispatchDayDto => ({
  date, status, published, orderCount: 1, pendingConfirmCount: 0, unassignedCount: 0, finishedCount: 1,
});

describe('MAJOR dispatch integration', () => {
  let page: DispatchDashboard;
  beforeEach(() => {
    TestBed.configureTestingModule({providers: [
      {provide: DispatchApiService, useValue: {getLiveFleet: () => of([])}},
      {provide: DispatchBoardEventsService, useValue: {}},
      {provide: DriverChatSocketService, useValue: {}},
      {provide: MatDialog, useValue: {}},
    ]});
    page = TestBed.runInInjectionContext(() => new DispatchDashboard());
    page.warehouseId.set(1);
  });
  afterEach(() => TestBed.resetTestingModule());

  it('offers only current-warehouse drivers and preserves an assigned disabled identity', () => {
    page.drivers.set([driver(1, 1), driver(2, 2), driver(3, 1, false)]);
    expect(page.driverOptions(lane()).map(option => option.id)).toEqual([1]);
    expect(page.driverOptions(lane(3)).map(option => option.id)).toEqual([1, 3]);
  });

  it('flags a transferred assigned driver instead of silently offering them again', () => {
    page.drivers.set([driver(2, 2)]);
    const route = lane(2);
    expect(page.driverOptions(route)).toEqual([]);
    expect(page.routeScheduleProblem(route)).toContain('重新指派');
  });

  it('does not confuse closed delivery progress with an active publication', () => {
    page.days.set([day(page.dispatchDate(), 'CLOSED', false)]);
    expect(page.published()).toBe(false);
    page.days.set([day(page.dispatchDate(), 'CLOSED', true)]);
    expect(page.published()).toBe(true);
  });

  it('also locks the board when a loaded route is published', () => {
    page.routes.set([{...lane(), routeStatus: 'PUBLISHED'}]);
    expect(page.published()).toBe(true);
  });
});

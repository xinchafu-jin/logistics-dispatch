import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {MatDialog} from '@angular/material/dialog';
import {ActivatedRoute} from '@angular/router';
import {Subject} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {DispatchApiService} from '../../../../core/services/dispatch-api.service';
import {DispatchBoardEventsService} from '../../../../core/services/dispatch-board-events.service';
import {ExceptionCaseDto} from '../../../../core/services/dispatch-api.models';
import {DriverCasesService} from '../../../../core/services/driver-cases.service';
import {DriverChatSocketService} from '../../../../core/services/driver-chat-socket.service';
import {AdminThemeService} from '../../../../core/theme/admin-theme.service';
import {AnomalyCenter} from './anomaly-center';

describe('異常中心確認後同步排車看板', () => {
  let page: AnomalyCenter;
  let confirmResponse: Subject<ExceptionCaseDto>;
  const notifyBoardChanged = vi.fn();
  const confirmExceptionCase = vi.fn();
  const incident = {
    id: 7,
    type: 'LOADING_MISMATCH',
    status: 'OPEN',
    followUpDeliveryDate: '2026-09-30',
  } as ExceptionCaseDto;

  beforeEach(() => {
    notifyBoardChanged.mockClear();
    confirmExceptionCase.mockReset();
    confirmResponse = new Subject<ExceptionCaseDto>();
    confirmExceptionCase.mockReturnValue(confirmResponse.asObservable());
    TestBed.configureTestingModule({providers: [
      {provide: DispatchApiService, useValue: {confirmExceptionCase}},
      {provide: DispatchBoardEventsService, useValue: {notifyBoardChanged}},
      {provide: ActivatedRoute, useValue: {}},
      {provide: MatDialog, useValue: {}},
      {provide: AdminThemeService, useValue: {}},
      {provide: DriverCasesService, useValue: {
        openCases: signal([]), closedCases: signal([]), loadState: signal('ready'), closedLoadState: signal('ready'),
      }},
      {provide: DriverChatSocketService, useValue: {isConnected: signal(false)}},
    ]});
    page = TestBed.runInInjectionContext(() => new AnomalyCenter());
    page.incidents.set([incident]);
    page.selectedIncidentId.set(incident.id);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('API 成功後才通知看板重讀日期與訂單', () => {
    (page as unknown as {confirmSelected(): void}).confirmSelected();
    expect(notifyBoardChanged).not.toHaveBeenCalled();

    confirmResponse.next({...incident, status: 'CLOSED', followUpDeliveryDate: '2026-09-29'});
    confirmResponse.complete();

    expect(notifyBoardChanged).toHaveBeenCalledOnce();
    expect(page.incidents()).toEqual([]);
    expect(page.actionMessage()).toContain('2026-09-29');
  });

  it('API 失敗時不通知看板，也不移除案件', () => {
    (page as unknown as {confirmSelected(): void}).confirmSelected();
    confirmResponse.error(new Error('失敗'));

    expect(notifyBoardChanged).not.toHaveBeenCalled();
    expect(page.incidents()).toEqual([incident]);
  });

  it('無人簽收不顯示主管確認視窗，也不呼叫確認 API', () => {
    page.incidents.set([{...incident, type: 'NO_SIGNATURE'}]);
    const actions = page as unknown as {openConfirmDialog(): void; confirmSelected(): void};

    actions.openConfirmDialog();
    actions.confirmSelected();

    expect(page.confirmDialogOpen()).toBe(false);
    expect(confirmExceptionCase).not.toHaveBeenCalled();
    expect(notifyBoardChanged).not.toHaveBeenCalled();
  });
});

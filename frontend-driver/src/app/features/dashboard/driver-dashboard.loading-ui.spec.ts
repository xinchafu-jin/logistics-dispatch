import {Component, input, output, signal} from '@angular/core';
import {HttpErrorResponse} from '@angular/common/http';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {provideNativeDateAdapter} from '@angular/material/core';
import {EMPTY, of, Subject, throwError} from 'rxjs';
import {DriverDashboard} from './driver-dashboard';
import {PreTripCheck} from './pre-trip-check/pre-trip-check';
import {DriverAuthService} from '../../core/auth/driver-auth.service';
import {DriverChatSocketService} from '../../core/services/driver-chat-socket.service';
import {DriverGpsTrackingService} from '../../core/services/driver-gps-tracking.service';
import {DriverOperationsService} from '../../core/services/driver-operations.service';
import {DriverWeatherService} from '../../core/services/driver-weather.service';
import {DriverRouteTask, DriverTaskOrderItem, DriverTaskStop, LoadingResponse} from '../../core/services/driver-operations.models';

@Component({selector: 'app-pre-trip-check', template: ''})
class TestInspection {
  readonly route = input.required<DriverRouteTask>();
  readonly passedChange = output<boolean>();
}

const product = (id: number, name: string, boxes: number): DriverTaskOrderItem => ({
  id, itemName: name, expectedQuantity: boxes, unit: '箱', productCode: `TEST-${id}`, sequence: id,
  notes: null, loadedQuantity: null, checked: null, checkedAt: null, loadingNotes: null,
});
const stop = (id: number, status: DriverTaskStop['orderStatus'] = 'CONFIRMED'): DriverTaskStop => ({
  orderId: id, orderNumber: `TEST-${id}`, sequence: id, orderStatus: status, expectedBoxCount: 10,
  itemDescription: '飲用水、麵包', orderNotes: null, loadedAt: null, loadingRequired: true,
  itemChecklistCompleted: false, items: [product(id * 10, '飲用水', 4), product(id * 10 + 1, '麵包', 6)],
  storeId: id, storeCode: `S${id}`, storeName: `測試門市 ${id}`, address: '高雄市', lat: 22.6, lng: 120.3,
  contactName: null, phone: null, receivingStart: '09:00', receivingEnd: '17:00',
});
const loaded = (orderId: number) => ({orderId, orderStatus: 'LOADED', loadedAt: '2026-09-29T09:00:00'}) as LoadingResponse;
const mismatch = (orderId: number) => ({orderId, orderStatus: 'FAILED', exceptionCaseId: 501,
  followUpOrderId: 99, followUpOrderNumber: 'LD-TEST-99', followUpDeliveryDate: '2026-09-30'}) as LoadingResponse;

describe('Driver warehouse loading checklist UI', () => {
  let fixture: ComponentFixture<DriverDashboard>;
  let page: any;
  let api: {loading: ReturnType<typeof vi.fn>; reportLoadingMismatch: ReturnType<typeof vi.fn>};

  beforeEach(async () => {
    vi.spyOn(globalThis, 'setInterval').mockImplementation(() => 0 as any);
    for (const method of ['loadAttendance', 'loadPublishedShifts', 'loadTodayTasks', 'loadProfile', 'loadLeaveRequests', 'connectChatSocket'])
      vi.spyOn(DriverDashboard.prototype as any, method).mockImplementation(() => undefined);
    vi.spyOn(DriverDashboard.prototype as any, 'initializeMap').mockResolvedValue(undefined);
    api = {loading: vi.fn(request => of(loaded(request.orderId))),
      reportLoadingMismatch: vi.fn(request => of(mismatch(request.orderId)))};
    TestBed.configureTestingModule({imports: [DriverDashboard], providers: [
      provideNativeDateAdapter(),
      {provide: DriverAuthService, useValue: {user: signal({id: 1, account: 'TEST', name: '測試司機'})}},
      {provide: DriverOperationsService, useValue: api},
      {provide: DriverWeatherService, useValue: {getCurrentWeather: () => EMPTY}},
      {provide: DriverGpsTrackingService, useValue: {stop: vi.fn(), lastUploadedAt: signal(null)}},
      {provide: DriverChatSocketService, useValue: {disconnect: vi.fn(), pushes$: EMPTY, connected$: EMPTY}},
      {provide: Router, useValue: {navigate: vi.fn()}},
    ]});
    TestBed.overrideComponent(DriverDashboard, {remove: {imports: [PreTripCheck]}, add: {imports: [TestInspection]}});
    fixture = TestBed.createComponent(DriverDashboard);
    page = fixture.componentInstance;
    page.activeTab.set('tasks');
    page.taskViewState.set('ready');
    page.todayTasks.set({date: '2026-09-29', driverId: 1, driverName: '測試司機', routes: [{
      routeId: 30, status: 'PUBLISHED', warehouse: {id: 1, name: '測試倉庫'},
      vehicle: {id: 6, plateNumber: 'TEST-6'}, totalBoxes: 20, stopCount: 2,
      stops: [stop(1), stop(2)],
    }]});
    page.inspectionReady.set({30: true});
    await render();
  });
  afterEach(() => {fixture?.destroy(); vi.restoreAllMocks();});
  async function render() {fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();}
  const firstStop = () => page.todayTasks().routes[0].stops[0];
  async function open() {page.openLoadingAction(firstStop()); await render();}
  async function checkAll() {
    for (const checkbox of fixture.nativeElement.querySelectorAll('.loading-item-check input')) checkbox.click();
    await render();
  }
  async function checkProduct(productId: number) {
    const index = firstStop().items.findIndex((item: DriverTaskOrderItem) => item.id === productId);
    const checkbox = fixture.nativeElement.querySelectorAll('.loading-item-check input')[index] as HTMLInputElement;
    if (!checkbox.checked) checkbox.click();
    await render();
  }

  it.each(['tasks', 'map'])('keeps only delivery, no-signature and cancel actions in the %s delivery panel', async (tab) => {
    page.todayTasks.update((tasks: any) => ({...tasks, routes: [{...tasks.routes[0], stops: [stop(1, 'IN_DELIVERY')]}]}));
    const route = page.todayTasks().routes[0];
    page.selectedTask.set({route, stop: firstStop()});
    page.attendanceViewState.set('ready');
    page.activeTab.set(tab);
    page.openDeliveryAction(firstStop());
    await render();
    const panel = fixture.nativeElement.querySelector('[aria-label="交貨處理"]') as HTMLElement;
    expect(panel).not.toBeNull();
    expect([...panel.querySelectorAll('.delivery-action-buttons button')]
      .map(button => button.textContent?.replace(/check_circle|warning_amber/g, '').trim()))
      .toEqual(['確認交貨', '無人簽收', '取消']);
    expect(panel.textContent).not.toMatch(/貨況異常|貨物損毀|損毀箱數/);
  });

  it('confirms normal delivery without sending any merchant damage count', async () => {
    page.todayTasks.update((tasks: any) => ({...tasks, routes: [{...tasks.routes[0], stops: [stop(1, 'IN_DELIVERY')]}]}));
    const deliver = vi.fn((request: {orderId: number}) => of({orderId: request.orderId, orderStatus: 'COMPLETED'}));
    Object.assign(api, {deliver});
    page.openDeliveryAction(firstStop());
    page.deliveryNotes.set('門市已收貨');
    await render();
    (fixture.nativeElement.querySelector('.delivery-complete-button') as HTMLButtonElement).click();
    await render();
    expect(deliver).toHaveBeenCalledWith({orderId: 1, boxCount: 10, photoUrl: undefined, notes: '門市已收貨'});
    expect(deliver.mock.calls[0][0]).not.toHaveProperty('damagedBoxCount');
    expect(firstStop().orderStatus).toBe('COMPLETED');
  });

  it('shows product names, right-hand box counts and checkboxes without actual-quantity or per-product note inputs', async () => {
    await open();
    const panel = fixture.nativeElement.querySelector('.loading-action-panel');
    expect([...panel.querySelectorAll('.loading-item-name strong')].map((n: any) => n.textContent.trim())).toEqual(['飲用水', '麵包']);
    expect([...panel.querySelectorAll('.loading-item-expected')].map((n: any) => n.textContent.trim())).toEqual(['4 箱', '6 箱']);
    expect(panel.querySelectorAll('input').length).toBe(2);
    expect(panel.querySelectorAll('input[type=checkbox]').length).toBe(2);
    expect(panel.querySelector('input[type=number]')).toBeNull();
    expect(panel.querySelector('select')).toBeNull();
    expect(panel.textContent).not.toContain('實點數量');
    expect(panel.textContent).not.toContain('商品備註');
    expect(panel.querySelectorAll('.loading-mismatch-button').length).toBe(1);
    expect(panel.querySelector('.loading-item-list .loading-mismatch-button')).toBeNull();
    expect(panel.querySelector('.delivery-cancel-button').nextElementSibling.matches('.loading-mismatch-button')).toBe(true);
    expect([...panel.querySelectorAll('.delivery-action-buttons button')].map((button: any) => button.textContent.replace(/fact_check|report_problem/g, '').trim()))
      .toEqual(['確認點交', '取消', '點交不符']);
    expect(panel.querySelector('details').open).toBe(false);
  });

  it('requires every product to be checked, submits the displayed quantities, and waits for all other orders before navigation', async () => {
    await open();
    const confirm = fixture.nativeElement.querySelector('.loading-action-panel .delivery-complete-button');
    expect(confirm.disabled).toBe(true);
    fixture.nativeElement.querySelector('.loading-item-check input').click(); await render();
    expect(confirm.disabled).toBe(true);
    fixture.nativeElement.querySelectorAll('.loading-item-check input')[1].click(); await render();
    expect(confirm.disabled).toBe(false);
    confirm.click(); await render();
    expect(api.loading).toHaveBeenCalledWith({orderId: 1, loadedBoxCount: 10, notes: undefined,
      items: [{orderItemId: 10, checked: true, loadedQuantity: 4}, {orderItemId: 11, checked: true, loadedQuantity: 6}]});
    expect(page.canNavigate(firstStop())).toBe(false);
    page.openLoadingAction(page.todayTasks().routes[0].stops[1]); await render(); await checkAll();
    fixture.nativeElement.querySelector('.loading-action-panel .delivery-complete-button').click(); await render();
    expect(page.canNavigate(firstStop())).toBe(true);
    expect([...fixture.nativeElement.querySelectorAll('.task-navigation-button')].every((b: any) => !b.disabled)).toBe(true);
  });

  it('checking just one mismatched product allows one direct click on the footer mismatch button to send the existing exception', async () => {
    await open();
    expect(fixture.nativeElement.querySelector('.loading-mismatch-button').disabled).toBe(true);
    await checkProduct(11);
    expect(fixture.nativeElement.querySelector('.loading-mismatch-button').disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.delivery-complete-button').disabled).toBe(true);
    expect(api.reportLoadingMismatch).not.toHaveBeenCalled();
    fixture.nativeElement.querySelector('.loading-mismatch-button').click(); await render();
    expect(api.reportLoadingMismatch).toHaveBeenCalledWith({orderId: 1, orderItemIds: [11], notes: undefined});
    expect(api.reportLoadingMismatch).toHaveBeenCalledTimes(1);
    expect(api.loading).not.toHaveBeenCalled();
    expect(firstStop().orderStatus).toBe('FAILED');
    expect(page.canNavigate(firstStop())).toBe(false);
    expect(fixture.nativeElement.querySelector('.loading-action-panel')).toBeNull();
    expect(fixture.nativeElement.querySelector('.task-feedback').textContent).toContain('麵包');
    expect(fixture.nativeElement.querySelector('.task-feedback').textContent).toContain('LD-TEST-99');
  });

  it('does not assume an unchecked product is mismatched and blocks reporting until at least one product is checked', async () => {
    await open();
    expect(fixture.nativeElement.querySelector('.loading-mismatch-button').disabled).toBe(true);
    page.handleLoadingMismatch(firstStop());
    expect(page.taskActionError()).toContain('請先勾選');
    expect(api.reportLoadingMismatch).not.toHaveBeenCalled();
    await checkAll();
    expect(fixture.nativeElement.querySelector('.delivery-complete-button').disabled).toBe(false);
    expect(fixture.nativeElement.querySelector('.loading-mismatch-button').disabled).toBe(false);
    expect(api.loading).not.toHaveBeenCalled();
  });

  it('submits all checked mismatched products together in one request, not a separate case per product', async () => {
    await open(); await checkAll();
    fixture.nativeElement.querySelector('.loading-mismatch-button').click(); await render();
    expect(api.reportLoadingMismatch).toHaveBeenCalledWith({orderId: 1, orderItemIds: [10, 11], notes: undefined});
    expect(api.reportLoadingMismatch).toHaveBeenCalledTimes(1);
    expect(api.loading).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.task-feedback').textContent).toContain('飲用水');
    expect(fixture.nativeElement.querySelector('.task-feedback').textContent).toContain('麵包');
  });

  it('cancels the whole loading panel and clears product selection when it is reopened', async () => {
    await open(); await checkProduct(11);
    fixture.nativeElement.querySelector('.delivery-cancel-button').click(); await render();
    expect(fixture.nativeElement.querySelector('.loading-action-panel')).toBeNull();
    expect(api.reportLoadingMismatch).not.toHaveBeenCalled();
    await open();
    expect([...fixture.nativeElement.querySelectorAll('.loading-item-check input')].every((input: any) => !input.checked)).toBe(true);
    expect(fixture.nativeElement.querySelector('.loading-mismatch-button').disabled).toBe(true);
  });

  it('locks the checklist during submission and does not submit a second mismatch on repeated clicks', async () => {
    const result = new Subject<LoadingResponse>();
    api.reportLoadingMismatch.mockReturnValue(result);
    await open();
    await checkProduct(10);
    page.handleLoadingMismatch(firstStop());
    page.handleLoadingMismatch(firstStop());
    await render();
    expect(api.reportLoadingMismatch).toHaveBeenCalledTimes(1);
    expect([...fixture.nativeElement.querySelectorAll('.loading-action-panel button')].every((b: any) => b.disabled)).toBe(true);
    expect(fixture.nativeElement.querySelector('.loading-item-row').classList.contains('is-mismatch')).toBe(true);
    expect([...fixture.nativeElement.querySelectorAll('.loading-item-check input')].every((input: any) => input.disabled)).toBe(true);
    result.next(mismatch(1)); result.complete(); await render();
  });

  it('does not pretend an exception was sent when the request fails', async () => {
    api.reportLoadingMismatch.mockReturnValue(throwError(() => new HttpErrorResponse({status: 400, error: {message: '伺服器拒絕'}})));
    await open(); await checkProduct(10);
    fixture.nativeElement.querySelector('.loading-mismatch-button').click(); await render();
    expect(firstStop().orderStatus).toBe('CONFIRMED');
    expect(fixture.nativeElement.querySelector('.loading-action-panel')).not.toBeNull();
    expect(page.isTaskSubmitting()).toBe(false);
    expect(page.taskActionMessage()).toBeNull();
    expect(page.taskActionError()).toBe('伺服器拒絕');
    expect(fixture.nativeElement.querySelector('.loading-item-check input').checked).toBe(true);
    expect(fixture.nativeElement.querySelector('.loading-mismatch-button').disabled).toBe(false);
  });

  it('does not permit a mismatch report before the existing safety inspection passes', async () => {
    await open();
    await checkProduct(10);
    page.inspectionReady.set({30: false});
    page.handleLoadingMismatch(firstStop());
    expect(api.reportLoadingMismatch).not.toHaveBeenCalled();
    expect(page.taskActionError()).toContain('安全檢查');
  });

  it('keeps legacy orders as an explicit summary check without fabricating product rows', async () => {
    page.todayTasks.update((tasks: any) => ({...tasks, routes: [{...tasks.routes[0], stops: [{...firstStop(), items: []}]}]}));
    await open();
    expect(fixture.nativeElement.querySelectorAll('.loading-item-check input').length).toBe(1);
    expect(fixture.nativeElement.querySelector('.loading-item-name strong').textContent).toBe('飲用水、麵包');
    expect(fixture.nativeElement.querySelector('.delivery-complete-button').disabled).toBe(true);
    fixture.nativeElement.querySelector('.loading-item-check input').click(); await render();
    fixture.nativeElement.querySelector('.delivery-complete-button').click(); await render();
    expect(api.loading).toHaveBeenCalledWith({orderId: 1, loadedBoxCount: 10, notes: undefined, items: undefined});
  });
});

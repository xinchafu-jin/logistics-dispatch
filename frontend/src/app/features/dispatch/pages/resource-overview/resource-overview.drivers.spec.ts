import { TestBed } from '@angular/core/testing';
import { ANIMATION_MODULE_TYPE } from '@angular/core';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatSelectHarness } from '@angular/material/select/testing';
import { of, Subject, throwError } from 'rxjs';
import { ResourceOverview } from './resource-overview';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverChatSocketService } from '../../../../core/services/driver-chat-socket.service';
import { DriverDto } from '../../../../core/services/dispatch-api.models';

describe('ResourceOverview 司機與倉庫', () => {
  const driver: DriverDto = {id: 1, account: 'DRIVER-001', name: '王司機', phone: '0912345678', warehouseId: 1,
    workStart: '08:00:00', workEnd: '17:00:00', restDuration: 60, maxOvertimeMinutes: 30, isActive: true};
  let api: any;
  let fixture: any;
  let page: ResourceOverview;
  const warehouses = [
    {id: 1, warehouseCode: 'KH-01', name: '左營倉', address: '左營路', isActive: true},
    {id: 2, warehouseCode: 'KH-02', name: '鳳山倉', address: '鳳山路', isActive: true},
    {id: 3, warehouseCode: 'KH-03', name: '停用倉', address: '', isActive: false},
  ];
  beforeEach(() => {
    api = {getDrivers: vi.fn(() => of([driver, {...driver, id: 2, name: '李司機', account: 'DRIVER-002', warehouseId: 2, isActive: false},
      {...driver, id: 3, name: '待設定司機', account: 'DRIVER-003', warehouseId: null}])),
      getVehicles: () => of([]), getStores: () => of([]), getWarehouses: () => of(warehouses),
      createDriver: vi.fn((dto: DriverDto) => of({...dto, password: undefined, id: 4})),
      updateDriver: vi.fn((id: number, dto: DriverDto) => of({...dto, id}))};
    TestBed.configureTestingModule({imports: [ResourceOverview], providers: [
      {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations'}, {provide: DispatchApiService, useValue: api},
      {provide: DriverChatSocketService, useValue: {boardPushes$: new Subject().asObservable(), connected$: new Subject().asObservable()}},
    ]});
    fixture = TestBed.createComponent(ResourceOverview); page = fixture.componentInstance; fixture.detectChanges();
    [...fixture.nativeElement.querySelectorAll('.view-switch button')].find((button: any) => button.textContent.trim() === '司機').click();
    fixture.detectChanges();
  });
  afterEach(() => fixture.destroy());

  it('lists database drivers with warehouse, status, contact and work time', () => {
    expect(fixture.nativeElement.querySelectorAll('.driver-resource-card').length).toBe(3);
    const blocks = fixture.nativeElement.querySelector('.driver-resource-card').querySelectorAll('.driver-detail-block');
    expect([...blocks].map((block: Element) => block.querySelector('h4')?.lastChild?.textContent?.trim())).toEqual(['司機資訊', '所屬倉庫', '工作時間']);
    expect(blocks[0].querySelector('.driver-identity strong')?.textContent).toContain('王司機');
    const text = fixture.nativeElement.querySelector('.driver-resource-list').textContent;
    expect(text).toContain('王司機'); expect(text).toContain('左營倉'); expect(text).toContain('0912345678');
    expect(text).toContain('08:00 – 17:00'); expect(text).toContain('待設定所屬倉庫');
    expect(page.activeDriverCount()).toBe(2);
  });
  it('shows actual overtime rather than the configurable maximum, keeping unknown distinct from zero', () => {
    page.drivers.set([{...driver,monthlyOvertimeMinutes:60,maxOvertimeMinutes:120}]);fixture.detectChanges();
    const text=fixture.nativeElement.querySelector('.driver-resource-card .driver-detail-block:last-child .driver-details').textContent;
    expect(text).toContain('本月已加班');expect(text).toContain('60 分鐘');expect(text).not.toContain('120');expect(text).not.toContain('加班上限');
    page.drivers.set([{...driver,monthlyOvertimeMinutes:0}]);fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.driver-resource-card .driver-detail-block:last-child .driver-details').textContent).toContain('0 分鐘');
    page.drivers.set([{...driver,monthlyOvertimeMinutes:undefined}]);fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.driver-resource-card .driver-detail-block:last-child .driver-details').textContent).toContain('— 分鐘');
  });
  it('combines live search, status and warehouse filters', async () => {
    const input = fixture.nativeElement.querySelector('.search-field input');
    input.value = '左營'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(page.visibleDrivers().map(driver => driver.id)).toEqual([1]);
    page.clearSearch(input);
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness.with({selector: '.driver-warehouse-filter'}));
    await select.open(); await select.clickOptions({text: 'KH-02 · 鳳山倉'});
    page.setFilter('停用'); fixture.detectChanges();
    expect(page.visibleDrivers().map(driver => driver.id)).toEqual([2]);
    page.setView('drivers'); page.setFilter('待設定倉庫');
    expect(page.visibleDrivers().map(driver => driver.id)).toEqual([3]);
  });
  it('keeps the overtime amount without showing the unsettled-shifts prompt', () => {
    page.drivers.set([{...driver, monthlyOvertimeMinutes:65, monthlyUnsettledShifts:2}]);
    fixture.detectChanges();
    const text = fixture.nativeElement.querySelector('.driver-resource-card').textContent;
    expect(text).toContain('65 分鐘');
    expect(text).not.toContain('尚有 2 班未完成有效打卡');
    expect(text).not.toContain('不列入加班');
  });
  it('edits warehouse and state without sending a password or losing work settings', async () => {
    fixture.nativeElement.querySelector('[aria-label="編輯司機 王司機"]').click(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[type=password]')).toBeNull();
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness.with({selector: '.driver-warehouse-select'}));
    await select.open(); await select.clickOptions({text: 'KH-02 · 鳳山倉'});
    page.driverForm.update(form => ({...form, isActive: false})); page.submitDriver(); fixture.detectChanges();
    expect(api.updateDriver).toHaveBeenCalledWith(1, expect.objectContaining({warehouseId: 2, isActive: false, restDuration: 60, maxOvertimeMinutes: 30}));
    expect(api.updateDriver.mock.calls[0][1]).not.toHaveProperty('password');
    expect(page.drivers()[0].warehouseId).toBe(2); expect(page.activeForm()).toBeNull();
  });
  it('creates a driver in an active warehouse with the existing initial-password rule', () => {
    page.openCreateDriver();
    page.driverForm.update(form => ({...form, name: '新司機', account: 'NEW-DRIVER', phone: '0912345678', password: 'A123456789'}));
    page.submitDriver();
    expect(api.createDriver).toHaveBeenCalledWith(expect.objectContaining({warehouseId: 1, password: 'A123456789'}));
    expect(page.drivers().at(-1)?.id).toBe(4);
  });
  it('requires warehouse assignment and keeps the edit open on backend error', () => {
    page.openEditDriver(page.drivers()[2]); page.submitDriver();
    expect(page.formError()).toContain('所屬倉庫'); expect(api.updateDriver).not.toHaveBeenCalled();
    page.updateDriverWarehouse(2);
    api.updateDriver.mockReturnValue(throwError(() => ({error: {message: '倉庫已停用'}})));
    page.submitDriver(); expect(page.formError()).toBe('倉庫已停用'); expect(page.activeForm()).toBe('edit-driver');
  });
});

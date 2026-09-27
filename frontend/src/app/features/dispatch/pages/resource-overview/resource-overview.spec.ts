import { TestBed } from '@angular/core/testing';
import { ANIMATION_MODULE_TYPE } from '@angular/core';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatSelectHarness } from '@angular/material/select/testing';
import { of, Subject } from 'rxjs';
import { ResourceOverview } from './resource-overview';
import { DispatchApiService } from '../../../../core/services/dispatch-api.service';
import { DriverChatSocketService } from '../../../../core/services/driver-chat-socket.service';
import { DispatchBoardPushDto, VehicleDto } from '../../../../core/services/dispatch-api.models';

describe('ResourceOverview authoritative mileage', () => {
  const vehicle: VehicleDto = {id: 1, warehouseId: 1, plateNumber: 'ODO-TEST', vehicleType: '3.5噸', capacity: 50, status: 'AVAILABLE', tonnage: 3.5, currentOdometerKm: 0, lastMinorMaintenanceKm: 0, lastMajorMaintenanceKm: 0};
  let api: {getVehicles: ReturnType<typeof vi.fn>; updateVehicle: ReturnType<typeof vi.fn>; getMaintenanceHistory: ReturnType<typeof vi.fn>};
  let pushes: Subject<DispatchBoardPushDto>;

  beforeEach(() => {
    pushes = new Subject<DispatchBoardPushDto>();
    api = {getVehicles: vi.fn(() => of([{...vehicle}])), updateVehicle: vi.fn((_id: number, dto: VehicleDto) => of({...dto, ...vehicle})), getMaintenanceHistory: vi.fn(() => of([]))};
    TestBed.configureTestingModule({
      imports: [ResourceOverview],
      providers: [
        {provide: ANIMATION_MODULE_TYPE, useValue: 'NoopAnimations'},
        {provide: DispatchApiService, useValue: {...api, getDrivers: () => of([]), getStores: () => of([]), getWarehouses: () => of([{id: 1, warehouseCode: 'WH', name: '測試倉', lat: 22.6, lng: 120.3, isActive: true}])}},
        {provide: DriverChatSocketService, useValue: {boardPushes$: pushes.asObservable(), connected$: new Subject<void>().asObservable()}},
      ],
    });
  });

  afterEach(() => vi.useRealTimers());

  it('allows initial mileage only at creation and makes existing mileage/baselines read-only', () => {
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const page = fixture.componentInstance;
    page.openEditVehicle(page.visibleVehicles()[0]); fixture.detectChanges();
    const readOnly = fixture.nativeElement.querySelectorAll('input[readonly]') as NodeListOf<HTMLInputElement>;
    expect(readOnly.length).toBe(3);
    page.closeForm(); page.openCreateVehicle(); fixture.detectChanges();
    expect(page.vehicleForm().currentOdometerKm).toBe(0);
    expect(fixture.nativeElement.querySelectorAll('input[readonly]').length).toBe(2);
    fixture.destroy();
  });

  it('never submits database-owned mileage fields when editing metadata', () => {
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const page = fixture.componentInstance; page.openEditVehicle(page.visibleVehicles()[0]); page.submitVehicle();
    const payload = api.updateVehicle.mock.calls[0][1];
    expect(payload).not.toHaveProperty('currentOdometerKm');
    expect(payload).not.toHaveProperty('lastMinorMaintenanceKm');
    expect(payload).not.toHaveProperty('lastMajorMaintenanceKm');
    expect(payload).not.toHaveProperty('maintenance');
    fixture.destroy();
  });

  it('updates open edit mileage after a driver push without discarding supervisor draft fields', () => {
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const page = fixture.componentInstance; page.openEditVehicle(page.visibleVehicles()[0]);
    page.vehicleForm.update(v => ({...v, vehicleType: '尚未儲存車型'}));
    api.getVehicles.mockReturnValue(of([{...vehicle, currentOdometerKm: 50}]));
    pushes.next({resourcesChanged: true}); vi.advanceTimersByTime(400);
    expect(page.vehicleForm().currentOdometerKm).toBe(50);
    expect(page.vehicleForm().vehicleType).toBe('尚未儲存車型');
    expect(page.vehicles()[0].currentOdometerKm).toBe(50);
    fixture.destroy();
  });

  it('separates repairs from routine service without rewriting database vehicle statuses', () => {
    api.getVehicles.mockReturnValue(of([{...vehicle, status: 'MAINTENANCE'}]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.filter-control').textContent).not.toContain('保養排程');
    expect(fixture.nativeElement.querySelector('.filter-control').textContent).toContain('維修中');
    expect(fixture.nativeElement.querySelector('.filter-control').textContent).toContain('小保中');
    expect(fixture.nativeElement.querySelector('.filter-control').textContent).toContain('大保中');
    expect(fixture.componentInstance.visibleVehicles()[0].status).toBe('維修中');
    expect(fixture.componentInstance.vehicles()[0].status).toBe('MAINTENANCE');
    fixture.destroy();
  });

  it('filters repair vehicles independently of minor and major service and combines with tonnage', () => {
    api.getVehicles.mockReturnValue(of([
      {...vehicle, id: 1, status: 'MAINTENANCE'},
      {...vehicle, id: 2, status: 'MINOR_MAINTENANCE'},
      {...vehicle, id: 3, status: 'MAJOR_MAINTENANCE'},
      {...vehicle, id: 4, status: 'MAINTENANCE', tonnage: 8},
    ]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const repairButton = [...fixture.nativeElement.querySelectorAll('.filter-control button')]
      .find((button: any) => button.textContent.trim() === '維修中') as HTMLButtonElement;
    repairButton.click(); fixture.detectChanges();
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([1, 4]);
    fixture.componentInstance.vehicleTonnageFilter.set(3.5);
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([1]);
    fixture.destroy();
  });

  it('submits accident or breakdown repairs as MAINTENANCE rather than routine service', () => {
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const page = fixture.componentInstance; page.openEditVehicle(page.visibleVehicles()[0]); fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('select option[value="MAINTENANCE"]').parentElement as HTMLSelectElement;
    expect(select.querySelector('option[value="MAINTENANCE"]')?.textContent).toContain('車禍／故障');
    select.value = 'MAINTENANCE'; select.dispatchEvent(new Event('change'));
    page.submitVehicle();
    expect(api.updateVehicle.mock.calls[0][1].status).toBe('MAINTENANCE');
    fixture.destroy();
  });

  it('builds sorted tonnage choices from database fields and selects exact numeric tonnage', async () => {
    api.getVehicles.mockReturnValue(of([
      {...vehicle, id: 1, tonnage: 8}, {...vehicle, id: 2, tonnage: 3.5},
      {...vehicle, id: 3, tonnage: 1.75}, {...vehicle, id: 4, tonnage: 3.5},
    ]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    expect(fixture.componentInstance.vehicleTonnages()).toEqual([1.75, 3.5, 8]);
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness.with({selector: '.tonnage-filter'}));
    await select.open();
    expect(await Promise.all((await select.getOptions()).map(option => option.getText()))).toEqual(['全部噸位', '1.75 噸', '3.5 噸', '8 噸']);
    await select.clickOptions({text: '3.5 噸'}); fixture.detectChanges();
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([2, 4]);
    expect(fixture.nativeElement.querySelectorAll('.vehicle-table .resource-row').length).toBe(2);
    await select.open(); await select.clickOptions({text: '全部噸位'}); fixture.detectChanges();
    expect(fixture.componentInstance.visibleVehicles().length).toBe(4);
    fixture.destroy();
  });

  it('combines tonnage with vehicle status and search filters', () => {
    api.getVehicles.mockReturnValue(of([
      {...vehicle, id: 1, plateNumber: 'MATCH', tonnage: 5, status: 'MINOR_MAINTENANCE'},
      {...vehicle, id: 2, plateNumber: 'MATCH-OTHER', tonnage: 3.5, status: 'MINOR_MAINTENANCE'},
      {...vehicle, id: 3, plateNumber: 'MATCH-READY', tonnage: 5, status: 'AVAILABLE'},
      {...vehicle, id: 4, plateNumber: 'DIFFERENT', tonnage: 5, status: 'MINOR_MAINTENANCE'},
    ]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const page = fixture.componentInstance;
    page.vehicleTonnageFilter.set(5); page.setFilter('小保中'); page.searchTerm.set('match');
    expect(page.visibleVehicles().map(v => v.backendId)).toEqual([1]);
    page.searchTerm.set('missing'); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.vehicle-table .empty-state').textContent).toContain('沒有符合目前篩選條件');
    fixture.destroy();
  });

  it('filters immediately from input events and matches a complete plate instead of its prefixes', () => {
    api.getVehicles.mockReturnValue(of([
      {...vehicle, id: 1, plateNumber: 'CAR-0001'},
      {...vehicle, id: 2, plateNumber: 'CAR-00010'},
      {...vehicle, id: 3, plateNumber: 'CAR-00013'},
      {...vehicle, id: 4, plateNumber: 'TRUCK-0001'},
    ]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.search-field input') as HTMLInputElement;
    const type = (value: string) => {
      input.value = value; input.dispatchEvent(new Event('input', {bubbles: true})); fixture.detectChanges();
    };
    type(' car-0001 ');
    expect(fixture.componentInstance.searchTerm()).toBe(' car-0001 ');
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([1]);
    expect(fixture.nativeElement.querySelectorAll('.vehicle-table .resource-row').length).toBe(1);
    expect(fixture.nativeElement.querySelector('.search-result').textContent).toContain('1 台');
    type('CAR-');
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([1, 2, 3]);
    type('3.5噸');
    expect(fixture.nativeElement.querySelectorAll('.vehicle-table .resource-row').length).toBe(4);
    fixture.destroy();
  });

  it('shows no matches and restores the list with the actual clear-search button or native search event', () => {
    api.getVehicles.mockReturnValue(of([
      {...vehicle, id: 1, plateNumber: 'CAR-0001'}, {...vehicle, id: 2, plateNumber: 'CAR-00010'},
    ]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.search-field input') as HTMLInputElement;
    input.value = 'NOT-FOUND'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.search-result').textContent).toContain('0 台');
    expect(fixture.nativeElement.querySelector('.vehicle-table .empty-state')).not.toBeNull();
    (fixture.nativeElement.querySelector('button[aria-label="清除搜尋"]') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(input.value).toBe('');
    expect(fixture.componentInstance.searchTerm()).toBe('');
    expect(fixture.nativeElement.querySelectorAll('.vehicle-table .resource-row').length).toBe(2);
    expect(fixture.nativeElement.querySelector('.search-result')).toBeNull();
    input.value = 'CAR-0001'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    input.value = ''; input.dispatchEvent(new Event('search')); fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.vehicle-table .resource-row').length).toBe(2);
    fixture.destroy();
  });

  it('does not substitute similar plates when an exact vehicle is excluded by status or tonnage', () => {
    api.getVehicles.mockReturnValue(of([
      {...vehicle, id: 1, plateNumber: 'CAR-0001', tonnage: 3.5, status: 'AVAILABLE'},
      {...vehicle, id: 2, plateNumber: 'CAR-00010', tonnage: 5, status: 'MAINTENANCE'},
    ]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const page = fixture.componentInstance;
    const input = fixture.nativeElement.querySelector('.search-field input') as HTMLInputElement;
    page.setFilter('維修中'); input.value = 'CAR-0001'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(page.visibleVehicles()).toEqual([]);
    expect(fixture.nativeElement.querySelector('.search-result').textContent).toContain('依目前篩選條件');
    page.setFilter('all'); page.vehicleTonnageFilter.set(5); fixture.detectChanges();
    expect(page.visibleVehicles()).toEqual([]);
    (fixture.nativeElement.querySelector('button[aria-label="清除搜尋"]') as HTMLButtonElement).click(); fixture.detectChanges();
    expect(page.vehicleTonnageFilter()).toBe(5);
    expect(page.visibleVehicles().map(v => v.backendId)).toEqual([2]);
    page.vehicleTonnageFilter.set('all'); input.value = 'CAR-0001'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(page.visibleVehicles().map(v => v.backendId)).toEqual([1]);
    fixture.destroy();
  });

  it('keeps the typed search after database resource updates', () => {
    vi.useFakeTimers();
    api.getVehicles.mockReturnValue(of([{...vehicle, plateNumber: 'CAR-0001'}]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const input = fixture.nativeElement.querySelector('.search-field input') as HTMLInputElement;
    input.value = 'CAR-0001'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    api.getVehicles.mockReturnValue(of([
      {...vehicle, plateNumber: 'CAR-0001', currentOdometerKm: 50},
      {...vehicle, id: 2, plateNumber: 'CAR-00010'},
    ]));
    pushes.next({resourcesChanged: true}); vi.advanceTimersByTime(400); fixture.detectChanges();
    expect(input.value).toBe('CAR-0001');
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([1]);
    expect(fixture.componentInstance.vehicles()[0].currentOdometerKm).toBe(50);
    fixture.destroy();
  });

  it('only offers actual tonnages and keeps unconfigured vehicles visible under all', async () => {
    api.getVehicles.mockReturnValue(of([{...vehicle, id: 1, tonnage: undefined}, {...vehicle, id: 2}]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness.with({selector: '.tonnage-filter'}));
    await select.open();
    expect(await Promise.all((await select.getOptions()).map(option => option.getText()))).toEqual(['全部噸位', '3.5 噸']);
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([1, 2]);
    await select.clickOptions({text: '3.5 噸'});
    expect(fixture.componentInstance.visibleVehicles().map(v => v.backendId)).toEqual([2]);
    fixture.destroy();
  });

  it('resets and hides the tonnage filter when switching to other resource types', () => {
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    fixture.componentInstance.vehicleTonnageFilter.set(3.5);
    fixture.componentInstance.setView('stores'); fixture.detectChanges();
    expect(fixture.componentInstance.vehicleTonnageFilter()).toBe('all');
    expect(fixture.nativeElement.querySelector('.tonnage-filter')).toBeNull();
    fixture.destroy();
  });

  it('shows prominent minor and major service counts only once in each resource row', () => {
    api.getVehicles.mockReturnValue(of([{...vehicle, maintenance: {
      currentOdometerKm: 50, minorRemainingKm: 2950, majorRemainingKm: 19950, retirementRemainingKm: 499950,
      warningKm: 500, plannedKm: null, projectedMinorKm: null, projectedMajorKm: null, projectedRetirementKm: null,
      decision: 'NORMAL', reasons: [], minorCount: 2, majorCount: 1, repairCount: 3, lastMinorAt: '2026-09-26T08:00:00', lastMajorAt: null, lastRepairAt: '2026-09-27T09:00:00',
    }}]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const row = fixture.nativeElement.querySelector('.vehicle-table .resource-row');
    const cards = row.querySelectorAll('.service-count');
    expect(cards.length).toBe(3);
    expect(cards[0].querySelector('dt').textContent).toBe('小保');
    expect(cards[0].querySelector('.count-value').textContent).toBe('2');
    expect(cards[1].querySelector('dt').textContent).toBe('大保');
    expect(cards[1].querySelector('.count-value').textContent).toBe('1');
    expect(cards[2].querySelector('dt').textContent).toBe('維修');
    expect(cards[2].querySelector('.count-value').textContent).toBe('3');
    expect(cards[2].querySelector('.last-service-date').textContent).toContain('2026/09/27 09:00');
    expect(row.querySelector('app-vehicle-maintenance-panel .history')).toBeNull();
    expect(row.querySelector('.last-service-date').textContent).toContain('2026/09/26 08:00');
    expect(row.querySelectorAll('.vehicle-maintenance-card').length).toBe(1);
    expect(row.querySelector('.vehicle-row-header .vehicle-identity').textContent).toContain('ODO-TEST');
    expect([...row.querySelectorAll('.vehicle-specs dt')].map((element: Element) => element.textContent)).toEqual(['車型', '載運容量']);
    expect([...row.querySelectorAll('.vehicle-specs dd')].map((element: Element) => element.textContent)).toEqual(['3.5噸', '50 箱']);
    expect(row.querySelector('.vehicle-status-line').textContent).toContain('車輛狀態');
    expect(row.querySelector('.vehicle-row-header .vehicle-status-pill').textContent.trim()).toBe('待派車');
    expect(row.querySelectorAll('.vehicle-row-actions .row-action').length).toBe(2);
    expect(row.querySelectorAll('.vehicle-row-header .vehicle-row-actions .row-action').length).toBe(2);
    expect(row.querySelectorAll('.vehicle-detail-block').length).toBe(3);
    expect(row.querySelector('.vehicle-resource-card > .vehicle-row-actions')).toBeNull();
    expect(row.querySelector('.table-head')).toBeNull();
    expect(cards[0].querySelector('.last-service-date').textContent).toContain('2026/09/26 08:00');
    expect(cards[1].querySelector('.last-service-date').textContent).toContain('尚無完成紀錄');
    expect(row.querySelector('.vehicle-mileage-header').textContent).toContain('實際總里程');
    expect(row.querySelector('.vehicle-mileage-header .odometer').textContent).toBe('50 km');
    expect(row.querySelector('.vehicle-maintenance-summary').textContent).not.toContain('實際總里程');
    expect(row.querySelector('.vehicle-maintenance-summary').textContent).toContain('距離小保2,950 km');
    fixture.destroy();
  });

  it('keeps the last-completion line for each service even before the first completion', () => {
    api.getVehicles.mockReturnValue(of([{...vehicle, maintenance: {
      currentOdometerKm: 0, minorRemainingKm: 3000, majorRemainingKm: 20000, retirementRemainingKm: 500000,
      warningKm: 500, plannedKm: null, projectedMinorKm: null, projectedMajorKm: null, projectedRetirementKm: null,
      decision: 'NORMAL', reasons: [], minorCount: 0, majorCount: 0, repairCount: 0,
      lastMinorAt: null, lastMajorAt: null, lastRepairAt: null,
    }}]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const row = fixture.nativeElement.querySelector('.vehicle-table .resource-row');
    expect([...row.querySelectorAll('.count-value')].map((element: Element) => element.textContent)).toEqual(['0', '0', '0']);
    expect(row.querySelectorAll('.last-service-date').length).toBe(3);
    for (const line of row.querySelectorAll('.last-service-date')) {
      expect(line.textContent).toContain('上次完成');
      expect(line.textContent).toContain('尚無完成紀錄');
    }
    fixture.destroy();
  });

  it('does not present missing maintenance count data as a confirmed zero', () => {
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const counts = fixture.nativeElement.querySelectorAll('.count-value');
    expect([...counts].map((element: Element) => element.textContent)).toEqual(['—', '—', '—']);
    fixture.destroy();
  });

  it.each(['BLOCKED', 'WARNING', 'UNKNOWN'] as const)('places the %s dispatch notice below vehicle status rather than mileage', decision => {
    api.getVehicles.mockReturnValue(of([{...vehicle, status: 'MAINTENANCE', maintenance: {
      currentOdometerKm: 0, minorRemainingKm: 3000, majorRemainingKm: 20000, retirementRemainingKm: 500000,
      warningKm: 500, plannedKm: null, projectedMinorKm: null, projectedMajorKm: null, projectedRetirementKm: null,
      decision, reasons: ['車輛正在保養／維修'], minorCount: 0, majorCount: 0, repairCount: 0, lastMinorAt: null, lastMajorAt: null, lastRepairAt: null,
    }}]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    const row = fixture.nativeElement.querySelector('.resource-row');
    const controls = row.querySelector('.vehicle-header-controls');
    expect(controls.querySelector('.vehicle-status-pill').textContent.trim()).toBe('維修中');
    expect(controls.querySelector('app-vehicle-maintenance-notice')).not.toBeNull();
    expect(row.querySelectorAll('[role="status"]').length).toBe(1);
    expect(row.querySelector('.vehicle-maintenance-summary [role="status"]')).toBeNull();
    expect(controls.querySelector('[role="status"]').textContent).toContain(decision === 'BLOCKED' ? '禁止出車' : decision === 'WARNING' ? '提前保養提醒' : '資料不足，暫不可派車');
    fixture.destroy();
  });

  it('identifies legacy repair history separately and never fabricates its missing start readings', () => {
    api.getMaintenanceHistory.mockReturnValue(of([{id: 1, vehicleId: 1, type: 'REPAIR', status: 'ACTIVE', sentAt: null, sentOdometerKm: null, completedAt: null, completedOdometerKm: null, cancelledAt: null}]));
    const fixture = TestBed.createComponent(ResourceOverview); fixture.detectChanges();
    fixture.componentInstance.openEditVehicle(fixture.componentInstance.visibleVehicles()[0]); fixture.detectChanges();
    const history = fixture.nativeElement.querySelector('.maintenance-history');
    expect(history.textContent).toContain('維修 · 進行中');
    expect(history.textContent).toContain('時間未留存（既有維修）');
    expect(history.textContent).toContain('里程未留存');
    expect(history.textContent).not.toContain('大保 · 進行中');
    fixture.destroy();
  });
});

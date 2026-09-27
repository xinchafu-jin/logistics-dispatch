import { TestBed } from '@angular/core/testing';
import { VehicleMaintenanceSummary } from '../../../../core/services/dispatch-api.models';
import { VehicleMaintenancePanel } from './vehicle-maintenance-panel';

describe('VehicleMaintenancePanel mileage clarity', () => {
  const summary: VehicleMaintenanceSummary = {
    currentOdometerKm: 0, minorRemainingKm: 3000, majorRemainingKm: 20000, retirementRemainingKm: 500000,
    warningKm: 500, plannedKm: 36.4, projectedMinorKm: 2963.6, projectedMajorKm: 19963.6,
    projectedRetirementKm: 499963.6, decision: 'NORMAL', reasons: [], minorCount: 0, majorCount: 0, repairCount: 0,
    lastMinorAt: null, lastMajorAt: null, lastRepairAt: null,
  };

  beforeEach(() => TestBed.configureTestingModule({ imports: [VehicleMaintenancePanel] }));

  function render(data: VehicleMaintenanceSummary | null = summary) {
    const fixture = TestBed.createComponent(VehicleMaintenancePanel);
    fixture.componentRef.setInput('summary', data);
    fixture.detectChanges();
    return fixture;
  }

  it('separates actual records from the full return-trip estimate', () => {
    const fixture = render();
    const actual = fixture.nativeElement.querySelector('[aria-label="目前實際紀錄"]');
    const projection = fixture.nativeElement.querySelector('[aria-label="本趟完成後預估"]');
    expect(actual.textContent).toContain('實際總里程0 km');
    expect(actual.textContent).toContain('距離小保3,000 km');
    expect(actual.textContent).not.toContain('2,963.6');
    expect(projection.textContent).toContain('OSRM');
    expect(projection.textContent).toContain('預估行駛（含回程）36.4 km');
    expect(projection.textContent).toContain('距離小保2,963.6 km');
    expect(projection.textContent).toContain('距離大保19,963.6 km');
    expect(projection.textContent).toContain('距離汰換上限499,963.6 km');
    expect(projection.textContent).toContain('收車後以實際里程更新');
  });

  it('does not show a trip forecast when no OSRM estimate exists', () => {
    const fixture = render({ ...summary, plannedKm: null, projectedMinorKm: null, projectedMajorKm: null, projectedRetirementKm: null });
    expect(fixture.nativeElement.querySelector('.actual')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.projection')).toBeNull();
  });

  it('distinguishes an existing overdue reading from a predicted overrun', () => {
    const fixture = render({ ...summary, minorRemainingKm: -10, projectedMinorKm: -46.4, decision: 'BLOCKED', reasons: ['小保里程將超過 46.4 km'] });
    expect(fixture.nativeElement.querySelector('.actual').textContent).toContain('已超過 10 km');
    expect(fixture.nativeElement.querySelector('.projection').textContent).toContain('預估超過 46.4 km');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('禁止出車');
  });

  it('keeps missing real mileage visibly unknown rather than presenting zero', () => {
    const fixture = render({ ...summary, currentOdometerKm: null, decision: 'UNKNOWN', reasons: ['尚無實際里程紀錄'] });
    expect(fixture.nativeElement.querySelector('.actual').textContent).toContain('實際總里程待補資料');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('資料不足，暫不可派車');
  });

  it('shows warnings and completed service counts without treating them as forecasts', () => {
    const fixture = render({ ...summary, decision: 'WARNING', reasons: ['距離小保剩 450 km'], minorCount: 2, majorCount: 1, repairCount: 3 });
    expect(fixture.nativeElement.querySelector('.history').textContent).toContain('小保 2 次 · 大保 1 次');
    expect(fixture.nativeElement.querySelector('.history').textContent).toContain('維修 3 次');
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('提前保養提醒');
  });

  it('updates the actual section after a new database snapshot arrives', () => {
    const fixture = render();
    fixture.componentRef.setInput('summary', { ...summary, currentOdometerKm: 50, minorRemainingKm: 2950 });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.actual').textContent).toContain('實際總里程50 km');
    expect(fixture.nativeElement.querySelector('.actual').textContent).toContain('距離小保2,950 km');
  });

  it('hides service counts on the dispatch board while retaining mileage', () => {
    const fixture = render();
    fixture.componentRef.setInput('showCounts', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.history')).toBeNull();
    expect(fixture.nativeElement.querySelector('.actual').textContent).toContain('距離小保3,000 km');
    expect(fixture.nativeElement.querySelector('.projection')).not.toBeNull();
  });

  it('can hide only the estimate explanation without removing the actual and forecast values', () => {
    const fixture = render();
    fixture.componentRef.setInput('showEstimateNote', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.estimate-note')).toBeNull();
    expect(fixture.nativeElement.querySelector('.actual').textContent).toContain('實際總里程0 km');
    expect(fixture.nativeElement.querySelector('.projection').textContent).toContain('預估行駛（含回程）36.4 km');
    expect(fixture.nativeElement.querySelector('.projection').textContent).toContain('距離小保2,963.6 km');
    expect(fixture.nativeElement.querySelector('.history')).not.toBeNull();
  });

  it('uses an integrated resource layout without repeating the total odometer or source badge', () => {
    const fixture = render({ ...summary, plannedKm: null });
    fixture.componentRef.setInput('layout', 'resource');
    fixture.componentRef.setInput('showCounts', false);
    fixture.detectChanges();
    const actual = fixture.nativeElement.querySelector('.actual');
    expect(actual.textContent).toContain('保養與汰換剩餘里程');
    expect(actual.textContent).not.toContain('實際總里程');
    expect(actual.querySelector('.source')).toBeNull();
    expect(actual.querySelectorAll('.values > div').length).toBe(3);
    expect(fixture.nativeElement.classList.contains('resource-panel')).toBe(true);
    expect(actual.textContent).toContain('距離小保3,000 km');
    expect(actual.textContent).toContain('距離大保20,000 km');
    expect(actual.textContent).toContain('距離汰換上限500,000 km');
  });

  it('uses shared resource grid tracks only in the resource layout', () => {
    const fixture = render();
    expect(fixture.nativeElement.classList.contains('resource-panel')).toBe(false);
    fixture.componentRef.setInput('layout', 'resource'); fixture.detectChanges();
    expect(fixture.nativeElement.classList.contains('resource-panel')).toBe(true);
    fixture.componentRef.setInput('layout', 'card'); fixture.detectChanges();
    expect(fixture.nativeElement.classList.contains('resource-panel')).toBe(false);
  });

  it('can move the dispatch notice outside the mileage panel without hiding readings', () => {
    const fixture = render({ ...summary, decision: 'BLOCKED', reasons: ['車輛正在保養／維修'] });
    fixture.componentRef.setInput('showNotice', false); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.actual').textContent).toContain('距離小保3,000 km');
    expect(fixture.nativeElement.querySelector('.projection')).not.toBeNull();
  });
});

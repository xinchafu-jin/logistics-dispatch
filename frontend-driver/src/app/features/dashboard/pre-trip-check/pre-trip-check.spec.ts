import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {PreTripCheck} from './pre-trip-check';
import {DriverOperationsService} from '../../../core/services/driver-operations.service';

describe('點交前安全檢查', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<PreTripCheck>>;
  let api: {getPreTripInspection: ReturnType<typeof vi.fn>; submitPreTripInspection: ReturnType<typeof vi.fn>};
  const route = {routeId: 3, vehicle: {id: 2, plateNumber: 'CAR-TEST'}};
  const result = {id: null, routeId: 3, vehicleId: 2, completed: false, passed: false, alcoholMgL: null, submittedAt: null, message: ''};
  beforeEach(async () => {
    api = {getPreTripInspection: vi.fn(() => of(result)), submitPreTripInspection: vi.fn(() => of({...result,id: 5,completed: true,passed: true,alcoholMgL: 0,message: '檢查已通過'}))};
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test-photo'); vi.spyOn(URL,'revokeObjectURL').mockImplementation(() => {});
    TestBed.configureTestingModule({imports: [PreTripCheck], providers: [{provide: DriverOperationsService, useValue: api}]});
    fixture = TestBed.createComponent(PreTripCheck); fixture.componentRef.setInput('route',route); fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
  });
  afterEach(() => {fixture.destroy();vi.restoreAllMocks();});
  async function complete(photoCount=3) {
    fixture.nativeElement.querySelectorAll('input[type=checkbox]').forEach((input: HTMLInputElement) => input.click());
    const reading = fixture.nativeElement.querySelector('input[type=number]') as HTMLInputElement;
    reading.value='0.00'; reading.dispatchEvent(new Event('input'));
    [...fixture.nativeElement.querySelectorAll('input[type=file]')].slice(0,photoCount).forEach((input: HTMLInputElement) => {
      Object.defineProperty(input,'files',{value:[new File(['image'],'photo.png',{type:'image/png'})],configurable:true}); input.dispatchEvent(new Event('change'));
    });
    await fixture.whenStable();fixture.detectChanges();
  }
  it('初始濃度空白，全部十項與三張照片都必填', () => {
    expect(fixture.nativeElement.querySelector('input[type=number]').value).toBe('');
    expect(fixture.nativeElement.querySelectorAll('input[type=checkbox]').length).toBe(10);
    expect(fixture.nativeElement.querySelector('.submit').disabled).toBe(true);
  });
  it('少一張照片不可提交', async () => {await complete(2);expect(fixture.nativeElement.querySelector('.submit').disabled).toBe(true);expect(api.submitPreTripInspection).not.toHaveBeenCalled();});
  it('完整檢查傳實測濃度与照片且以後端通過結果開放點交', async () => {
    const states: boolean[]=[];fixture.componentInstance.passedChange.subscribe((passed) => states.push(passed));
    await complete(); expect(fixture.nativeElement.querySelector('.submit').disabled).toBe(false);
    fixture.nativeElement.querySelector('.submit').click();await fixture.whenStable();fixture.detectChanges();
    expect(api.submitPreTripInspection).toHaveBeenCalledWith(expect.objectContaining({routeId:3,alcoholMgL:0,frontLeftTire:true,dashcam:true}),expect.objectContaining({alcohol:expect.any(File),vehicle:expect.any(File),dashcam:expect.any(File)}));
    expect(states.at(-1)).toBe(true);expect(fixture.nativeElement.textContent).toContain('檢查已通過');
  });
  it('後端判定酒測不通過就不會開放點交', async () => {
    api.submitPreTripInspection.mockReturnValue(of({...result,id:5,completed:true,passed:false,alcoholMgL:.01,message:'禁止出車'}));
    const states: boolean[]=[];fixture.componentInstance.passedChange.subscribe((passed) => states.push(passed));await complete();
    fixture.nativeElement.querySelector('.submit').click();await fixture.whenStable();fixture.detectChanges();
    expect(states.at(-1)).toBe(false);expect(fixture.nativeElement.textContent).toContain('禁止出車');
  });
  it('改派到別台車會清除勾選與照片並重新查詢', async () => {
    await complete();fixture.componentRef.setInput('route',{...route,vehicle:{id:9,plateNumber:'NEW'}});fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(api.getPreTripInspection).toHaveBeenCalledTimes(2);expect(fixture.nativeElement.querySelector('.submit').disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('input[type=number]').value).toBe('');expect(URL.revokeObjectURL).toHaveBeenCalled();
  });
  it('任務查詢失敗不以預設通過放行', async () => {
    api.getPreTripInspection.mockReturnValue(throwError(() => new Error('withdrawn')));
    fixture.componentRef.setInput('route',{...route,routeId:4});fixture.detectChanges();await fixture.whenStable();fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.submit').disabled).toBe(true);expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();
  });
});

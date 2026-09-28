import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {PreTripCheck} from './pre-trip-check';
import {DriverOperationsService} from '../../../core/services/driver-operations.service';
import {PreTripInspectionResult} from '../../../core/services/driver-operations.models';

describe('出車前安全檢查', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<PreTripCheck>>;
  let element: HTMLElement;
  let api: {
    getPreTripInspection: ReturnType<typeof vi.fn>;
    submitPreTripInspection: ReturnType<typeof vi.fn>;
    getPreTripPhoto: ReturnType<typeof vi.fn>;
  };
  const route = {routeId: 3, vehicle: {id: 2, plateNumber: 'CAR-TEST'}};
  const notSubmitted: PreTripInspectionResult = {
    id: null, routeId: 3, vehicleId: 2, completed: false, passed: false, alcoholMgL: null,
    abnormalItems: [], note: null, hasFaultPhoto: false, departed: false, startOdometer: null,
    submittedAt: null, message: '請完成出車前安全檢查',
  };
  const passed: PreTripInspectionResult = {
    ...notSubmitted, id: 5, completed: true, passed: true, alcoholMgL: 0, departed: true, startOdometer: 18400,
    submittedAt: '2026-09-28T08:00:00', message: '檢查通過，已記下出車時的行車紀錄器里程 18400 km，可以點交',
  };

  beforeEach(async () => {
    api = {
      getPreTripInspection: vi.fn(() => of(notSubmitted)),
      submitPreTripInspection: vi.fn(() => of(passed)),
      getPreTripPhoto: vi.fn(() => of(new Blob(['image']))),
    };
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test-photo');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    TestBed.configureTestingModule({
      imports: [PreTripCheck],
      providers: [{provide: DriverOperationsService, useValue: api}],
    });
    fixture = TestBed.createComponent(PreTripCheck);
    fixture.componentRef.setInput('route', route);
    element = fixture.nativeElement;
    await render();
  });

  afterEach(() => {
    fixture.destroy();
    vi.restoreAllMocks();
  });

  async function render(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function submitButton(): HTMLButtonElement {
    return element.querySelector('.submit') as HTMLButtonElement;
  }

  /** 按每一項的「正常」或「異常」：abnormalIndexes 裡的那幾項按異常，其他按正常 */
  function answerAll(abnormalIndexes: number[] = []): void {
    element.querySelectorAll('.check-item').forEach((item, index) => {
      const buttons = item.querySelectorAll('button');
      (abnormalIndexes.includes(index) ? buttons[1] : buttons[0]).click();
    });
  }

  function typeAlcohol(value: string): void {
    typeNumber('mg/L', value);
  }

  function typeMileage(value: string): void {
    typeNumber('行車紀錄器里程', value);
  }

  /** 依欄位標題找數字輸入框：酒測（mg/L）或行車紀錄器里程 */
  function typeNumber(labelText: string, value: string): void {
    const label = [...element.querySelectorAll('label.field')]
      .find((field) => field.textContent?.includes(labelText)) as HTMLElement;
    const input = label.querySelector('input[type=number]') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function choosePhoto(label: string): void {
    const input = element.querySelector(`input[aria-label="${label}"]`) as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      value: [new File(['image'], 'photo.png', {type: 'image/png'})],
      configurable: true,
    });
    input.dispatchEvent(new Event('change'));
  }

  it('還沒送過：15 項都要選、酒測空白、送出鍵不能按', () => {
    expect(element.querySelectorAll('.check-item').length).toBe(15);
    expect((element.querySelector('input[type=number]') as HTMLInputElement).value).toBe('');
    expect(submitButton().disabled).toBe(true);
    expect(element.textContent).toContain('還有 15 項沒選');
  });

  it('全部正常、酒測 0、附酒測照片、填行車紀錄器里程並拍照才能送；通過就記下里程、開放點交', async () => {
    const states: boolean[] = [];
    fixture.componentInstance.passedChange.subscribe((state) => states.push(state));

    answerAll();
    typeAlcohol('0.00');
    choosePhoto('酒測器讀數照片');
    await render();
    // 還沒填行車紀錄器里程、沒拍行車紀錄器
    expect(submitButton().disabled).toBe(true);

    typeMileage('18400');
    await render();
    expect(submitButton().disabled).toBe(true);

    choosePhoto('行車紀錄器照片');
    await render();
    expect(submitButton().disabled).toBe(false);

    submitButton().click();
    await render();

    const [request, alcoholPhoto, dashcamPhoto, faultPhoto] = api.submitPreTripInspection.mock.calls[0];
    expect(request).toEqual(expect.objectContaining({
      routeId: 3, alcoholMgL: 0, odometer: 18400, note: null, dashcam: true, brakeLights: true, dashboardLights: true,
    }));
    expect(alcoholPhoto).toBeInstanceOf(File);
    expect(dashcamPhoto).toBeInstanceOf(File);
    expect(faultPhoto).toBeNull();
    expect(states.at(-1)).toBe(true);
    expect(element.textContent).toContain('出車里程（行車紀錄器）：18400 km');
    // 已經出車就不再顯示表單
    expect(element.querySelector('form')).toBeNull();
  });

  it('行車紀錄器選異常：讀不到里程，里程和行車紀錄器照片都不用填', async () => {
    answerAll([0]);
    typeAlcohol('0.00');
    choosePhoto('酒測器讀數照片');
    await render();
    expect(element.textContent).not.toContain('出車時行車紀錄器里程');

    const textarea = element.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = '行車紀錄器開不了機';
    textarea.dispatchEvent(new Event('input'));
    await render();
    expect(submitButton().disabled).toBe(false);

    submitButton().click();
    await render();
    const [request, , dashcamPhoto] = api.submitPreTripInspection.mock.calls[0];
    expect(request).toEqual(expect.objectContaining({dashcam: false, odometer: null}));
    expect(dashcamPhoto).toBeNull();
  });

  it('改版前就通過、還沒記出車里程的：照樣顯示表單，再送一次會一起記下里程', async () => {
    api.getPreTripInspection.mockReturnValue(of({...passed, departed: false, startOdometer: null}));

    fixture.componentRef.setInput('route', {...route, routeId: 5});
    await render();

    expect(element.querySelector('form')).not.toBeNull();
  });

  it('選了異常就要寫說明，說明會送出去', async () => {
    answerAll([13]);
    typeAlcohol('0');
    typeMileage('18400');
    choosePhoto('酒測器讀數照片');
    choosePhoto('行車紀錄器照片');
    await render();
    expect(element.querySelector('textarea')).not.toBeNull();
    expect(submitButton().disabled).toBe(true);

    const textarea = element.querySelector('textarea') as HTMLTextAreaElement;
    textarea.value = '左後煞車燈不亮';
    textarea.dispatchEvent(new Event('input'));
    await render();
    expect(submitButton().disabled).toBe(false);

    submitButton().click();
    await render();
    const [request] = api.submitPreTripInspection.mock.calls[0];
    expect(request).toEqual(expect.objectContaining({brakeLights: false, note: '左後煞車燈不亮'}));
  });

  it('後端判定不通過：不開放點交，可以按重新檢查再送', async () => {
    api.submitPreTripInspection.mockReturnValue(of({
      ...passed, passed: false, departed: false, startOdometer: null, alcoholMgL: 0.01,
      message: '酒測不是 0.00，不能出車，請聯絡主管',
    }));
    const states: boolean[] = [];
    fixture.componentInstance.passedChange.subscribe((state) => states.push(state));

    answerAll();
    typeAlcohol('0.01');
    typeMileage('18400');
    choosePhoto('酒測器讀數照片');
    choosePhoto('行車紀錄器照片');
    await render();
    submitButton().click();
    await render();

    expect(states.at(-1)).toBe(false);
    expect(element.textContent).toContain('不能出車');
    expect(element.querySelector('form')).toBeNull();

    (element.querySelector('.result-actions .primary') as HTMLButtonElement).click();
    await render();
    expect(element.querySelector('form')).not.toBeNull();
  });

  it('換了車就清空重新查；同一台車重新整理任務不會清掉填到一半的內容', async () => {
    answerAll();
    typeAlcohol('0.00');
    await render();

    // 任務清單重新整理：新物件、同路線同車
    fixture.componentRef.setInput('route', {...route, vehicle: {...route.vehicle}});
    await render();
    expect(api.getPreTripInspection).toHaveBeenCalledTimes(1);
    expect((element.querySelector('input[type=number]') as HTMLInputElement).value).toBe('0.00');

    // 主管換了車
    fixture.componentRef.setInput('route', {...route, vehicle: {id: 9, plateNumber: 'NEW-CAR'}});
    await render();
    expect(api.getPreTripInspection).toHaveBeenCalledTimes(2);
    expect((element.querySelector('input[type=number]') as HTMLInputElement).value).toBe('');
    expect(element.textContent).toContain('還有 15 項沒選');
  });

  it('查詢失敗不能當作通過', async () => {
    api.getPreTripInspection.mockReturnValue(throwError(() => new Error('network')));
    const states: boolean[] = [];
    fixture.componentInstance.passedChange.subscribe((state) => states.push(state));

    fixture.componentRef.setInput('route', {...route, routeId: 4});
    await render();

    expect(states.at(-1)).toBe(false);
    expect(element.querySelector('[role=alert]')).not.toBeNull();
    expect(element.querySelector('form')).toBeNull();
  });
});

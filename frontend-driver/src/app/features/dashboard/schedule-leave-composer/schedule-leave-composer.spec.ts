import {ComponentFixture, TestBed} from '@angular/core/testing';
import {HttpErrorResponse} from '@angular/common/http';
import {of, Subject} from 'rxjs';
import {DriverLeaveRequestResponse, DriverShiftDto} from '../../../core/services/driver-operations.models';
import {DriverOperationsService} from '../../../core/services/driver-operations.service';
import {ScheduleLeaveComposer} from './schedule-leave-composer';

function request(date: string, overrides: Partial<DriverLeaveRequestResponse> = {}): DriverLeaveRequestResponse {
  return {id: Number(date.slice(-2)), workDate: date, status: 'PENDING', requestMode: 'PREPLANNED',
    submissionSource: 'DRIVER', fullDay: true, leaveType: 'ANNUAL', driverReadAt: null,
    requestedAt: '2026-09-27T12:00:00', ...overrides} as DriverLeaveRequestResponse;
}

function shift(date: string, type: DriverShiftDto['shiftType'] = 'WORK'): DriverShiftDto {
  return {id: Number(date.slice(-2)), workDate: date, shiftType: type} as DriverShiftDto;
}

describe('日曆下方的新請假流程', () => {
  let fixture: ComponentFixture<ScheduleLeaveComposer>;
  let composer: ScheduleLeaveComposer;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    vi.useFakeTimers({toFake: ['Date']});
    vi.setSystemTime(new Date('2026-09-27T04:00:00Z'));
    api = {
      getMakeupLeaveCandidates: vi.fn(() => of(['2026-09-24', '2026-09-25'])),
      submitLeaveRequest: vi.fn(() => of(request('2026-09-27', {requestMode: 'TEMPORARY'}))),
      submitPlannedLeaveBatches: vi.fn(() => of([{items: [request('2026-09-28'), request('2026-09-29')]}])),
      submitMakeupLeaveBatch: vi.fn(() => of([request('2026-09-24', {requestMode: 'MAKEUP'}), request('2026-09-25', {requestMode: 'MAKEUP'})])),
      uploadLeaveEvidencePhoto: vi.fn(() => of({url: '/uploads/leave-evidence/test.jpg'})),
      markLeaveRequestRead: vi.fn(() => of(request('2026-09-24', {status: 'APPROVED', driverReadAt: '2026-09-27T12:01:00'}))),
    };
    TestBed.configureTestingModule({imports: [ScheduleLeaveComposer], providers: [{provide: DriverOperationsService, useValue: api}]});
    fixture = TestBed.createComponent(ScheduleLeaveComposer);
    composer = fixture.componentInstance;
    fixture.componentRef.setInput('month', new Date(2026, 8, 1));
    fixture.componentRef.setInput('scheduleReady', true);
    fixture.componentRef.setInput('requestsAvailable', true);
    fixture.componentRef.setInput('shifts', [shift('2026-09-01'), shift('2026-09-18'), shift('2026-09-24'), shift('2026-09-25'), shift('2026-09-27'), shift('2026-09-28'), shift('2026-09-29'), shift('2026-09-30', 'DAY_OFF')]);
    await render();
  });

  afterEach(() => {fixture.destroy(); vi.useRealTimers();});
  async function render(): Promise<void> {fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();}
  const date = (day: number) => new Date(2026, 8, day);

  it('初始只有三個新入口，按下才展開；不出現舊主題切換、舊表單或申請進度', async () => {
    expect([...fixture.nativeElement.querySelectorAll('.leave-calendar-modes strong')].map((node: any) => node.textContent)).toEqual(['排假', '當日臨請', '事後補請']);
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    fixture.nativeElement.querySelector('.leave-calendar-modes button').click();
    await render();
    expect(fixture.nativeElement.querySelectorAll('input[type=radio]')).toHaveLength(6);
    expect(fixture.nativeElement.textContent).not.toContain('申請進度');
  });

  it('先選假別，排假只開放未來上班日；今天、過去與休假日不可選', () => {
    composer.open('planned');
    expect(composer.canSelectDate(date(28))).toBe(false);
    composer.chooseType('ANNUAL');
    expect(composer.canSelectDate(date(28))).toBe(true);
    expect(composer.canSelectDate(date(25))).toBe(false);
    expect(composer.canSelectDate(date(27))).toBe(false);
    expect(composer.canSelectDate(date(30))).toBe(false);
  });

  it('可複選、再次點選取消，取消表單會清空且不送出申請', () => {
    composer.open('planned'); composer.chooseType('SICK');
    composer.toggleDate(date(28)); composer.toggleDate(date(29));
    expect(composer.selectedDates()).toEqual(['2026-09-28', '2026-09-29']);
    expect(composer.dateClass(date(29))).toBe('leave-date-selected');
    composer.toggleDate(date(28));
    expect(composer.selectedDates()).toEqual(['2026-09-29']);
    composer.cancel();
    expect(composer.activeMode()).toBeNull();
    expect(composer.selectedDates()).toEqual([]);
    expect(api['submitPlannedLeaveBatches']).not.toHaveBeenCalled();
  });

  it('跨月仍保留已選日期，送出同一組多日申請與同一原因', async () => {
    composer.open('planned'); composer.chooseType('ANNUAL'); composer.toggleDate(date(28));
    fixture.componentRef.setInput('month', new Date(2026, 9, 1));
    fixture.componentRef.setInput('shifts', [shift('2026-10-02')]); await render();
    composer.toggleDate(new Date(2026, 9, 2)); composer.reason.set('家庭安排'); composer.submit();
    expect(api['submitPlannedLeaveBatches']).toHaveBeenCalledWith({groups: [{workDates: ['2026-09-28', '2026-10-02'], leaveType: 'ANNUAL', reason: '家庭安排'}]});
    expect(composer.activeMode()).toBeNull();
  });

  it('當日臨請日期固定今天，不可改選其他天', () => {
    composer.open('temporary'); composer.chooseType('BEREAVEMENT');
    composer.toggleDate(date(28));
    expect(composer.selectedDates()).toEqual(['2026-09-27']);
    composer.reason.set('家人過世'); composer.submit();
    expect(api['submitLeaveRequest']).toHaveBeenCalledWith(expect.objectContaining({workDate: '2026-09-27', leaveType: 'BEREAVEMENT', reason: '家人過世'}));
  });

  it('補請可複選過去未到班日期；今天及未來不可選', async () => {
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    expect(composer.canSelectDate(date(27))).toBe(false);
    expect(composer.canSelectDate(date(28))).toBe(false);
    expect(composer.canSelectDate(date(23))).toBe(false);
    composer.toggleDate(date(25)); composer.toggleDate(date(24)); composer.reason.set('連續發燒'); composer.submit();
    expect(api['submitMakeupLeaveBatch']).toHaveBeenCalledWith({workDates: ['2026-09-24', '2026-09-25'], leaveType: 'SICK', reason: '連續發燒'});
  });

  it('系統未到紀錄可補充，但待審及已核准的一般申請不能重複送出', async () => {
    fixture.componentRef.setInput('requests', [request('2026-09-24', {submissionSource: 'SYSTEM', requestMode: 'SYSTEM_NO_SHOW'}),
      request('2026-09-25', {status: 'APPROVED', requestMode: 'MAKEUP'}), request('2026-09-28')]);
    composer.open('makeup'); composer.chooseType('PERSONAL'); await render();
    expect(composer.canSelectDate(date(24))).toBe(true);
    expect(composer.canSelectDate(date(25))).toBe(false);
    composer.open('planned'); composer.chooseType('PERSONAL');
    expect(composer.canSelectDate(date(28))).toBe(false);
  });

  it('假別、日期、原因必填，不能以空白原因送出', () => {
    composer.open('planned'); composer.chooseType('ANNUAL'); composer.toggleDate(date(28)); composer.reason.set('   '); composer.submit();
    expect(api['submitPlannedLeaveBatches']).not.toHaveBeenCalled();
    expect(composer.error()).toContain('原因');
  });

  it('日期選取後收到同日待審申請，會取消原選取並鎖住日期；拒絕後才重新開放', async () => {
    composer.open('planned'); composer.chooseType('ANNUAL'); composer.toggleDate(date(28));
    fixture.componentRef.setInput('requests', [request('2026-09-28')]); await render();
    expect(composer.selectedDates()).toEqual([]);
    expect(composer.canSelectDate(date(28))).toBe(false);
    fixture.componentRef.setInput('requests', [request('2026-09-28', {status: 'REJECTED'})]); await render();
    expect(composer.canSelectDate(date(28))).toBe(true);
  });

  it('今日已有待審部分時段申請，即使改另一個時段也不能重複申請', async () => {
    fixture.componentRef.setInput('requests', [request('2026-09-27', {requestMode: 'TEMPORARY', fullDay: false, leaveStart: '08:00:00', leaveEnd: '09:00:00'})]);
    await render();
    composer.open('temporary'); composer.chooseType('SICK'); composer.reason.set('下午回診');
    composer.leaveStart.set('13:00'); composer.leaveEnd.set('17:00'); composer.submit();
    expect(composer.selectionBlocked()).toBe(true);
    expect(api['submitLeaveRequest']).not.toHaveBeenCalled();
  });

  it('部分時段臨請檢查起訖時間，完整時段才會送出', () => {
    composer.open('temporary'); composer.chooseType('SICK'); composer.reason.set('下午回診'); composer.leaveStart.set('13:00'); composer.submit();
    expect(api['submitLeaveRequest']).not.toHaveBeenCalled();
    composer.leaveEnd.set('17:00'); composer.submit();
    expect(api['submitLeaveRequest']).toHaveBeenCalledWith(expect.objectContaining({leaveStart: '13:00', leaveEnd: '17:00'}));
  });

  it('補請有起訖時間欄位，可先選上班日再補時間，送出多日共用時段', async () => {
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    expect(fixture.nativeElement.querySelectorAll('input[type=time]')).toHaveLength(2);
    expect(fixture.nativeElement.textContent).toContain('有打卡的上班日須填起訖時間');
    composer.toggleDate(date(24)); composer.toggleDate(date(25));
    composer.leaveStart.set('08:00'); composer.leaveEnd.set('09:00'); await render();
    expect(api['getMakeupLeaveCandidates']).not.toHaveBeenCalled();
    composer.reason.set('兩天早上就醫'); composer.submit();
    expect(api['submitMakeupLeaveBatch']).toHaveBeenCalledWith({workDates: ['2026-09-24', '2026-09-25'],
      leaveType: 'SICK', reason: '兩天早上就醫', leaveStart: '08:00', leaveEnd: '09:00'});
  });

  it('補請時間只填一欄或倒置不能送出，也不把單邊時間送往候選 API', async () => {
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    composer.toggleDate(date(24)); composer.reason.set('早上就醫'); composer.leaveStart.set('09:00'); await render();
    expect(api['getMakeupLeaveCandidates']).not.toHaveBeenCalled();
    composer.submit(); expect(api['submitMakeupLeaveBatch']).not.toHaveBeenCalled();
    composer.leaveEnd.set('08:00'); composer.submit(); expect(composer.error()).toContain('結束時間');
    expect(api['submitMakeupLeaveBatch']).not.toHaveBeenCalled();
  });

  it('補請核准部分時段不重疊可再選，但申請中即使不同時段仍鎖住', async () => {
    fixture.componentRef.setInput('requests', [request('2026-09-24', {status: 'APPROVED', fullDay: false,
      leaveStart: '08:00:00', leaveEnd: '09:00:00'}), request('2026-09-25', {fullDay: false,
      leaveStart: '08:00:00', leaveEnd: '09:00:00'})]);
    composer.open('makeup'); composer.chooseType('SICK'); composer.leaveStart.set('13:00'); composer.leaveEnd.set('17:00'); await render();
    expect(composer.canSelectDate(date(24))).toBe(true);
    expect(composer.canSelectDate(date(25))).toBe(false);
    composer.leaveStart.set('08:30'); composer.leaveEnd.set('10:00'); await render();
    expect(composer.canSelectDate(date(24))).toBe(false);
  });

  it('舊整天補請候選清單不可鎖住上班日，1日18日24日均可選', async () => {
    api['getMakeupLeaveCandidates'].mockReturnValue(of(['2026-09-25']));
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    for (const day of [1, 18, 24, 25]) {
      expect(composer.canSelectDate(date(day))).toBe(true);
      composer.toggleDate(date(day));
    }
    expect(composer.selectedDates()).toEqual(['2026-09-01', '2026-09-18', '2026-09-24', '2026-09-25']);
    expect(api['getMakeupLeaveCandidates']).not.toHaveBeenCalled();
  });

  it('送出期間不允許重送或切換；失敗保留資料及日期', async () => {
    const result = new Subject<DriverLeaveRequestResponse[]>();
    api['submitMakeupLeaveBatch'].mockReturnValue(result);
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    composer.toggleDate(date(24)); composer.reason.set('發燒'); composer.submit(); composer.submit(); composer.open('planned'); composer.cancel();
    expect(api['submitMakeupLeaveBatch']).toHaveBeenCalledTimes(1);
    expect(composer.activeMode()).toBe('makeup');
    result.error(new HttpErrorResponse({status: 400, error: {message: '該日期已送審'}}));
    expect(composer.selectedDates()).toEqual(['2026-09-24']);
    expect(composer.reason()).toBe('發燒');
    expect(composer.error()).toBe('該日期已送審');
  });

  it('補請以已發布上班日為準，班表未載入及沒有上班班次的日期仍鎖住', async () => {
    composer.open('makeup'); composer.chooseType('SICK'); await render();
    expect(composer.canSelectDate(date(24))).toBe(true);
    expect(composer.canSelectDate(date(23))).toBe(false);
    fixture.componentRef.setInput('scheduleReady', false); await render();
    expect(composer.canSelectDate(date(24))).toBe(false);
  });

  it('請假紀錄未成功載入時不允許申請，避免忽略既有申請', () => {
    fixture.componentRef.setInput('requestsAvailable', false);
    composer.open('temporary'); composer.chooseType('SICK'); composer.reason.set('發燒'); composer.submit();
    expect(api['submitLeaveRequest']).not.toHaveBeenCalled();
    expect(composer.error()).toContain('尚未載入');
  });

  it('只有新的主管回覆會顯示簡單通知，並能標為已讀，不回復舊進度卡片', async () => {
    fixture.componentRef.setInput('requests', [request('2026-09-24', {status: 'APPROVED', reviewedAt: '2026-09-27T12:00:00', decisionReason: '已安排代班，准假'}), request('2026-09-25')]);
    await render();
    expect(fixture.nativeElement.querySelectorAll('.reply-notice')).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('已安排代班，准假');
    const updates: DriverLeaveRequestResponse[][] = []; composer.submitted.subscribe(saved => updates.push(saved));
    fixture.nativeElement.querySelector('.reply-notice button').click();
    expect(api['markLeaveRequestRead']).toHaveBeenCalledWith(24);
    expect(updates[0][0].driverReadAt).not.toBeNull();
  });
});

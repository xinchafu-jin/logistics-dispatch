import {DriverLeaveRequestResponse} from '../../../core/services/driver-operations.models';
import {pendingLeaveDatesInMonth} from './schedule-leave-status';

describe('日曆外部申請中提示', () => {
  const request = (workDate: string, overrides: Partial<DriverLeaveRequestResponse> = {}) => ({
    workDate, status: 'PENDING', submissionSource: 'DRIVER', requestMode: 'PREPLANNED', ...overrides,
  } as DriverLeaveRequestResponse);

  it('只標當月待審日期，同日多筆只計一天，系統未到紀錄不冒充已申請', () => {
    const dates = pendingLeaveDatesInMonth([
      request('2026-09-01'), request('2026-09-01'), request('2026-09-02', {status: 'APPROVED'}),
      request('2026-09-03', {status: 'REJECTED'}), request('2026-10-01'),
      request('2026-09-04', {submissionSource: 'SYSTEM', requestMode: 'SYSTEM_NO_SHOW'}),
      request('2026-09-05', {submissionSource: 'SYSTEM', requestMode: 'MAKEUP'}),
    ], new Date(2026, 8, 1));
    expect([...dates]).toEqual(['2026-09-01', '2026-09-05']);
  });

  it('主管同意或拒絕後申請中提示解除，核准不自行把上班班別改成請假', () => {
    const applications = [request('2026-09-01'), request('2026-09-02')];
    expect(pendingLeaveDatesInMonth(applications, new Date(2026, 8, 1)).size).toBe(2);
    applications[0].status = 'APPROVED'; applications[1].status = 'REJECTED';
    expect(pendingLeaveDatesInMonth(applications, new Date(2026, 8, 1)).size).toBe(0);
  });
});

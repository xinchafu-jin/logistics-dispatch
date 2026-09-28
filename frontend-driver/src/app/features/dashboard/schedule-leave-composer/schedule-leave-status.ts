import {DriverLeaveRequestResponse} from '../../../core/services/driver-operations.models';

/** 真正已送審的請假；系統尚待司機說明的未到紀錄不算重複申請。 */
export function pendingLeaveDatesInMonth(requests: DriverLeaveRequestResponse[], month: Date): Set<string> {
  const prefix = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}-`;
  return new Set(requests.filter(request => request.workDate.startsWith(prefix) && request.status === 'PENDING'
    && (request.submissionSource === 'DRIVER' || request.requestMode === 'MAKEUP')).map(request => request.workDate));
}

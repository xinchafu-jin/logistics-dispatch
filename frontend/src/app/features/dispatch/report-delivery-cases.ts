/** 圖表只看目前仍待處理的案件；歷史頁未篩選時保留已結案原始紀錄。 */
export const CURRENT_ISSUE_CATEGORIES = [
  {id: 'no-signature', label: '無人簽收', icon: 'person_off'},
  {id: 'loading-mismatch', label: '倉庫點交不符', icon: 'rule'},
  {id: 'other-open', label: '其他待處理通報', icon: 'assignment_late'},
] as const;
/** Period totals include resolved cases; resolving a case does not erase its occurrence. */
export const CUMULATIVE_ISSUE_CATEGORIES = [
  {id: 'all-no-signature', label: '無人簽收', icon: 'person_off'},
  {id: 'all-loading-mismatch', label: '倉庫點交不符', icon: 'rule'},
] as const;
export const REPORT_CASE_METRICS = [{id: 'open', label: '待處理'}, ...CURRENT_ISSUE_CATEGORIES,
  ...CUMULATIVE_ISSUE_CATEGORIES.map(item => ({...item, label: item.label + '（累積）'}))] as const;

export function matchesReportCase(row: unknown, metric = ''): boolean {
  if (typeof row !== 'object' || row === null) return false;
  const {type, status} = row as {type?: unknown; status?: unknown};
  switch (metric) {
    case 'open': return status === 'OPEN';
    case 'no-signature': return status === 'OPEN' && type === 'NO_SIGNATURE';
    case 'loading-mismatch': return status === 'OPEN' && type === 'LOADING_MISMATCH';
    case 'other-open': return status === 'OPEN' && type !== 'NO_SIGNATURE' && type !== 'LOADING_MISMATCH';
    case 'all-no-signature': return type === 'NO_SIGNATURE';
    case 'all-loading-mismatch': return type === 'LOADING_MISMATCH';
    default: return true;
  }
}

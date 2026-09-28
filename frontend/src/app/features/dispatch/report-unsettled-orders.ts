/** Matches the existing order lifecycle, not exception-case closure. */
export const UNSETTLED_ORDER_CATEGORIES = [
  {id: 'pending-confirm', label: '待確認', icon: 'fact_check'},
  {id: 'awaiting-delivery', label: '待出貨', icon: 'inventory_2'},
  {id: 'in-delivery', label: '配送中', icon: 'local_shipping'},
] as const;
export const ORDER_PROGRESS_METRICS = [{id: 'unsettled', label: '未結單'}, ...UNSETTLED_ORDER_CATEGORIES] as const;

export function matchesOrderProgress(row: unknown, metric = ''): boolean {
  if (typeof row !== 'object' || row === null) return false;
  const {status} = row as {status?: unknown};
  switch (metric) {
    case 'unsettled': return ['PENDING_CONFIRM', 'CONFIRMED', 'LOADED', 'IN_DELIVERY'].includes(String(status));
    case 'pending-confirm': return status === 'PENDING_CONFIRM';
    case 'awaiting-delivery': return status === 'CONFIRMED' || status === 'LOADED';
    case 'in-delivery': return status === 'IN_DELIVERY';
    default: return true;
  }
}

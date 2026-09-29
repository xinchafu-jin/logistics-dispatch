import {ReportOrderOutcomeDto} from '../../core/services/dispatch-api.models';

/**
 * A missed delivery cutoff remains visible after a late delivery is completed.
 * Without a receiving window the backend uses midnight after the delivery date.
 * Missing completion evidence on a COMPLETED order is unknown, not an incident.
 */
export function isOverdueUnsettledOrder(row: ReportOrderOutcomeDto): boolean {
  if (!row.due || row.noSignature) return false;
  const cutoff = row.windowEnd ?? `${row.date}T24:00:00`;
  if (row.delivered) return !!row.deliveredAt && row.deliveredAt > cutoff;
  return row.status !== 'COMPLETED';
}

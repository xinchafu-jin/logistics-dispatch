import {matchesOrderProgress} from './report-unsettled-orders';

describe('unfinished order lifecycle', () => {
  it.each(['PENDING_CONFIRM', 'CONFIRMED', 'LOADED', 'IN_DELIVERY'])('counts %s as an unfinished order', status => {
    expect(matchesOrderProgress({status}, 'unsettled')).toBe(true);
  });
  it.each(['COMPLETED', 'CANCELLED', 'NO_SIGNATURE', 'FAILED', 'UNKNOWN'])('does not count %s as an active unfinished order', status => {
    expect(matchesOrderProgress({status}, 'unsettled')).toBe(false);
  });
  it('classifies each unfinished order exactly once', () => {
    for (const status of ['PENDING_CONFIRM', 'CONFIRMED', 'LOADED', 'IN_DELIVERY']) {
      expect(['pending-confirm', 'awaiting-delivery', 'in-delivery']
        .filter(metric => matchesOrderProgress({status}, metric))).toHaveLength(1);
    }
    expect(matchesOrderProgress({status: 'LOADED'}, 'awaiting-delivery')).toBe(true);
  });
  it('keeps the unfiltered history complete and rejects absent order data', () => {
    expect(matchesOrderProgress({status: 'COMPLETED'})).toBe(true);
    expect(matchesOrderProgress(null, 'unsettled')).toBe(false);
  });
});

import {CUMULATIVE_ISSUE_CATEGORIES, matchesReportCase} from './report-delivery-cases';

describe('Cumulative and current report case categories', () => {
  it.each(['OPEN', 'CLOSED'])('includes %s cases in their cumulative category', status => {
    const rows = [
      {type: 'NO_SIGNATURE', status}, {type: 'LOADING_MISMATCH', status},
      {type: 'DRIVER_REPORT', status}, {type: 'SHORTAGE', status}, {type: 'PHONE_HANDLED', status},
    ];
    const counts = CUMULATIVE_ISSUE_CATEGORIES.map(category => rows.filter(row => matchesReportCase(row, category.id)).length);
    expect(counts).toEqual([1, 1]);
    expect(counts.reduce((sum, value) => sum + value, 0)).toBe(2);
  });

  it.each(['no-signature', 'loading-mismatch', 'other-open'])('preserves the old pending-only metric %s', metric => {
    const type = metric === 'no-signature' ? 'NO_SIGNATURE' : metric === 'loading-mismatch' ? 'LOADING_MISMATCH' : 'DRIVER_REPORT';
    expect(matchesReportCase({type, status: 'OPEN'}, metric)).toBe(true);
    expect(matchesReportCase({type, status: 'CLOSED'}, metric)).toBe(false);
  });

  it('does not interpret invalid values as case records', () => {
    for (const value of [null, undefined, 3, 'NO_SIGNATURE']) {
      for (const category of CUMULATIVE_ISSUE_CATEGORIES) expect(matchesReportCase(value, category.id)).toBe(false);
    }
  });
});

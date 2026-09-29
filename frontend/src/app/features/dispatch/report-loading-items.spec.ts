import {OrderItemDto} from '../../core/services/dispatch-api.models';
import {loadingItemStatusLabel, loadingMismatchSummary, reportLoadingItems} from './report-loading-items';

describe('Original-order loading item reports', () => {
  const product = (changes: Partial<OrderItemDto> = {}): OrderItemDto => ({
    id: 1, productCode: 'MILK', itemName: '鮮乳', expectedQuantity: 15, loadedQuantity: 12,
    unit: '瓶', sequence: 1, checkedAt: '2026-09-25T08:15:00', loadingNotes: '少一籃', ...changes,
  });

  it('identifies the exact missing product, quantity and unit', () => {
    const items = reportLoadingItems([product()]);
    expect(items[0]).toMatchObject({productCode: 'MILK', itemName: '鮮乳', expectedQuantity: 15,
      loadedQuantity: 12, missingQuantity: 3, excessQuantity: 0, unit: '瓶', status: 'MISSING', notes: '少一籃'});
    expect(loadingMismatchSummary(items)).toBe('鮮乳：缺少 3 瓶');
    expect(loadingItemStatusLabel(items[0].status)).toBe('數量不足');
  });

  it('treats an explicit zero as a full shortage, not as missing evidence', () => {
    const items = reportLoadingItems([product({loadedQuantity: 0})]);
    expect(items[0].missingQuantity).toBe(15);
    expect(loadingMismatchSummary(items)).toBe('鮮乳：缺少 15 瓶');
  });

  it('identifies the product reported by the driver without inventing a zero or marking other products as checked', () => {
    const rows = reportLoadingItems([product({loadedQuantity: null, loadingMismatchReported: true}),
      product({id: 2, itemName: '雞蛋', loadedQuantity: null, loadingMismatchReported: false})]);
    expect(rows[0]).toMatchObject({loadedQuantity: null, missingQuantity: null, excessQuantity: null, status: 'MISMATCH_REPORTED'});
    expect(rows[1].status).toBe('NOT_RECORDED');
    expect(loadingItemStatusLabel(rows[0].status)).toBe('點交不符');
    expect(loadingMismatchSummary(rows)).toBe('鮮乳：已回報點交不符，實點數量未記錄\n雞蛋：點交數量未記錄');
  });

  it('uses the recorded actual quantity for a reported shortage or damage', () => {
    const shortage = reportLoadingItems([product({loadedQuantity: 12, loadingMismatchReported: true})]);
    expect(shortage[0]).toMatchObject({loadedQuantity: 12, missingQuantity: 3, status: 'MISSING'});
    expect(loadingMismatchSummary(shortage)).toBe('鮮乳：缺少 3 瓶');

    const damage = reportLoadingItems([product({loadedQuantity: 15, loadingMismatchReported: true})]);
    expect(damage[0]).toMatchObject({loadedQuantity: 15, missingQuantity: 0, status: 'MISMATCH_REPORTED'});
    expect(loadingMismatchSummary(damage)).toBe('鮮乳：已回報點交不符，實點 15 瓶');
  });

  it.each([undefined, null, -1, Number.NaN, Number.POSITIVE_INFINITY, 2.5, '12'])('does not invent a shortage for invalid or missing actual quantity %s', actual => {
    const items = reportLoadingItems([product({loadedQuantity: actual as any})]);
    expect(items[0]).toMatchObject({loadedQuantity: null, missingQuantity: null, excessQuantity: null, status: 'NOT_RECORDED'});
    expect(loadingMismatchSummary(items)).toBe('鮮乳：點交數量未記錄');
  });

  it('does not calculate a shortage without a valid expected quantity', () => {
    expect(reportLoadingItems([product({expectedQuantity: null as any})])[0])
      .toMatchObject({expectedQuantity: null, loadedQuantity: 12, missingQuantity: null, status: 'NOT_RECORDED'});
  });

  it('keeps different product units separate and excludes matched products from the summary', () => {
    const items = reportLoadingItems([product(), product({id: 2, itemName: '冷凍雞肉', unit: '包', expectedQuantity: 4, loadedQuantity: 2}),
      product({id: 3, itemName: '雞蛋', unit: '盒', expectedQuantity: 6, loadedQuantity: 6})]);
    expect(loadingMismatchSummary(items)).toBe('鮮乳：缺少 3 瓶\n冷凍雞肉：缺少 2 包');
    expect(items[2].status).toBe('MATCHED');
  });

  it('does not say all products match while some actual quantities are missing', () => {
    const items = reportLoadingItems([product(), product({id: 2, itemName: '雞蛋', loadedQuantity: null})]);
    expect(loadingMismatchSummary(items)).toBe('鮮乳：缺少 3 瓶\n雞蛋：點交數量未記錄');
  });

  it('distinguishes item quantities that match from a case about the overall box count', () => {
    expect(loadingMismatchSummary(reportLoadingItems([product({loadedQuantity: 15})])))
      .toBe('逐項數量相符，請核對整單箱數或備註');
  });

  it('shows an excess in legacy data as a mismatch, not a shortage or a match', () => {
    const items = reportLoadingItems([product({loadedQuantity: 17})]);
    expect(items[0]).toMatchObject({missingQuantity: 0, excessQuantity: 2, status: 'EXCESS'});
    expect(loadingMismatchSummary(items)).toBe('鮮乳：多出 2 瓶');
  });

  it.each([undefined, null, []])('marks absent product checks instead of guessing from a case description', items => {
    expect(loadingMismatchSummary(reportLoadingItems(items))).toBe('商品點交明細未記錄');
  });

  it('sorts a copy, preserves the source and does not reuse order instructions as loading evidence', () => {
    const items = Object.freeze([Object.freeze(product({sequence: 2, loadingNotes: null, notes: '保持冷藏'})),
      Object.freeze(product({id: 2, sequence: 1, itemName: '雞蛋', productCode: '', unit: ''}))]);
    const rows = reportLoadingItems(items);
    expect(rows.map(row => row.id)).toEqual([2, 1]);
    expect(items.map(item => item.id)).toEqual([1, 2]);
    expect(rows[1].notes).toBeNull();
    expect(rows[0]).toMatchObject({productCode: null, unit: '（單位未記錄）'});
  });
});

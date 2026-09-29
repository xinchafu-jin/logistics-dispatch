/** Both live order items and historical outcome snapshots can be incomplete. */
interface LoadingItemEvidence {
  id?: number;
  productCode?: string | null;
  itemName?: string | null;
  expectedQuantity?: number | null;
  loadedQuantity?: number | null;
  unit?: string | null;
  sequence?: number | null;
  checkedAt?: string | null;
  loadingNotes?: string | null;
  loadingMismatchReported?: boolean;
}

export type LoadingItemStatus = 'MISSING' | 'EXCESS' | 'MATCHED' | 'NOT_RECORDED' | 'MISMATCH_REPORTED';

export interface ReportLoadingItem {
  id: number | undefined;
  productCode: string | null;
  itemName: string;
  expectedQuantity: number | null;
  loadedQuantity: number | null;
  missingQuantity: number | null;
  excessQuantity: number | null;
  unit: string;
  checkedAt: string | null;
  notes: string | null;
  status: LoadingItemStatus;
}

function recordedQuantity(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

/** Read the original order's item checks. Missing actual quantities are never assumed to be zero. */
export function reportLoadingItems(items: readonly LoadingItemEvidence[] | null | undefined): ReportLoadingItem[] {
  return [...(items ?? [])].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0)).map(item => {
    const expected = recordedQuantity(item.expectedQuantity);
    const actual = recordedQuantity(item.loadedQuantity);
    const difference = expected !== null && actual !== null ? expected - actual : null;
    return {
      id: item.id,
      productCode: item.productCode?.trim() || null,
      itemName: item.itemName?.trim() || '商品名稱未記錄',
      expectedQuantity: expected,
      loadedQuantity: actual,
      missingQuantity: difference === null ? null : Math.max(0, difference),
      excessQuantity: difference === null ? null : Math.max(0, -difference),
      unit: item.unit?.trim() || '（單位未記錄）',
      checkedAt: item.checkedAt ?? null,
      notes: item.loadingNotes?.trim() || null,
      status: difference !== null && difference > 0 ? 'MISSING'
        : difference !== null && difference < 0 ? 'EXCESS'
          : item.loadingMismatchReported ? 'MISMATCH_REPORTED'
            : difference === null ? 'NOT_RECORDED' : 'MATCHED',
    };
  });
}

export function loadingItemStatusLabel(status: LoadingItemStatus): string {
  return {MISSING: '數量不足', EXCESS: '數量超出', MATCHED: '相符', NOT_RECORDED: '數量未記錄', MISMATCH_REPORTED: '點交不符'}[status];
}

/** Describe each product separately; quantities with different units must not be added together. */
export function loadingMismatchSummary(items: readonly ReportLoadingItem[]): string {
  if (!items.length) return '商品點交明細未記錄';
  const differences = items.filter(item => item.status !== 'MATCHED');
  if (!differences.length) return '逐項數量相符，請核對整單箱數或備註';
  return differences.map(item => item.itemName + '：' + (item.status === 'MISSING'
    ? '缺少 ' + item.missingQuantity + ' ' + item.unit
    : item.status === 'EXCESS' ? '多出 ' + item.excessQuantity + ' ' + item.unit
      : item.status === 'MISMATCH_REPORTED' ? item.loadedQuantity === null
        ? '已回報點交不符，實點數量未記錄'
        : '已回報點交不符，實點 ' + item.loadedQuantity + ' ' + item.unit
        : '點交數量未記錄')).join('\n');
}

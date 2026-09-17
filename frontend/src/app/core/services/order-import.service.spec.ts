import { describe, expect, it } from 'vitest';
import * as XLSX from 'xlsx';

import { OrderImportContext, OrderImportService } from './order-import.service';
import { StoreDto, WarehouseDto } from './dispatch-api.models';

const HEADER = ['訂單編號', '門市', '出貨倉', '品項', '箱數', '配送日期', '備註'];

function store(id: number, name: string, storeCode: string): StoreDto {
  return {
    id,
    storeCode,
    name,
    lat: 23,
    lng: 120,
    receivingStart: '09:00',
    receivingEnd: '18:00',
    status: 'ACTIVE',
  };
}

function warehouse(id: number, name: string, warehouseCode: string): WarehouseDto {
  return { id, warehouseCode, name, lat: 23, lng: 120, isActive: true };
}

const context: OrderImportContext = {
  stores: [store(11, '高雄左營店', 'ST-001'), store(12, '高雄苓雅店', 'ST-002')],
  warehouses: [warehouse(21, '高雄倉', 'WH-001')],
};

/** 把 aoa 寫成真的 xlsx File，走跟使用者上傳一樣的路徑。 */
function toFile(rows: unknown[][]): File {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(
    workbook,
    XLSX.utils.aoa_to_sheet(rows, { cellDates: true }),
    '訂單',
  );
  const buffer = XLSX.write(workbook, { bookType: 'xlsx', type: 'array' }) as ArrayBuffer;
  return new File([buffer], 'orders.xlsx');
}

describe('OrderImportService', () => {
  const service = new OrderImportService();

  it('把一列 Excel 轉成可以直接送後端的 OrderDto', async () => {
    const result = await service.parse(
      toFile([HEADER, ['SO-001', '高雄左營店', '高雄倉', '常溫飲料', 12, '2026-01-05', '下午到']]),
      context,
    );

    expect(result.errorRows).toEqual([]);
    expect(result.validRows[0].row).toBe(2);
    expect(result.validRows[0].data).toEqual({
      orderNumber: 'SO-001',
      storeId: 11,
      warehouseId: 21,
      sourceVendor: '',
      itemDescription: '常溫飲料',
      boxCount: 12,
      notes: '下午到',
      deliveryDate: '2026-01-05',
      status: 'PENDING_CONFIRM',
    });
  });

  it('Excel 的日期格不會因為時區差一天', async () => {
    const result = await service.parse(
      toFile([HEADER, ['SO-002', 'ST-001', 'WH-001', '冷藏', 1, new Date(2026, 0, 5), '']]),
      context,
    );

    expect(result.errorRows).toEqual([]);
    expect(result.validRows[0].data.deliveryDate).toBe('2026-01-05');
  });

  it('接受門市代號與民國年', async () => {
    const result = await service.parse(
      toFile([HEADER, ['SO-003', 'ST-002', '高雄倉', '常溫', 3, '115/01/05', '']]),
      context,
    );

    expect(result.errorRows).toEqual([]);
    expect(result.validRows[0].data.storeId).toBe(12);
    expect(result.validRows[0].data.deliveryDate).toBe('2026-01-05');
  });

  it('表頭前面有標題列時仍找得到欄位，且列號對得上 Excel', async () => {
    const result = await service.parse(
      toFile([
        ['2026 年 1 月配送需求表'],
        HEADER,
        ['SO-004', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
      ]),
      context,
    );

    expect(result.validRows[0].row).toBe(3);
  });

  it('缺必要欄位時整份檔案判失敗', async () => {
    await expect(
      service.parse(
        toFile([
          ['訂單編號', '品項'],
          ['SO-005', '常溫'],
        ]),
        context,
      ),
    ).rejects.toThrow('缺少必要欄位');
  });

  it('逐列記錄錯誤，不影響其他列', async () => {
    const result = await service.parse(
      toFile([
        HEADER,
        ['SO-006', '不存在的店', '高雄倉', '常溫', 1, '2026-01-05', ''],
        ['', '高雄左營店', '高雄倉', '常溫', 0, 'abc', ''],
        ['SO-007', '高雄左營店', '高雄倉', '常溫', 2, '2026-01-06', ''],
      ]),
      context,
    );

    expect(result.rows).toHaveLength(3);
    expect(result.errorRows[0].errors).toEqual(['找不到門市：不存在的店']);
    expect(result.errorRows[1].errors).toEqual([
      '訂單編號未填',
      '箱數必須大於 0',
      '配送日期格式錯誤（請用 2026-01-01）：abc',
    ]);
    expect(result.validRows.map((row) => row.data.orderNumber)).toEqual(['SO-007']);
  });

  it('擋掉檔案內重複與資料庫既有的訂單編號', async () => {
    const result = await service.parse(
      toFile([
        HEADER,
        ['SO-008', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
        ['SO-008', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
        ['SO-009', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
      ]),
      { ...context, existingOrderNumbers: ['SO-009'] },
    );

    expect(result.errorRows.map((row) => row.errors)).toEqual([
      ['訂單編號重複：SO-008'],
      ['訂單編號重複：SO-009'],
    ]);
  });

  it('沒有出貨倉欄位時套用預設倉庫', async () => {
    const result = await service.parse(
      toFile([
        ['訂單編號', '門市', '箱數', '配送日期'],
        ['SO-010', '高雄左營店', 1, '2026-01-05'],
      ]),
      { ...context, defaultWarehouseId: 21 },
    );

    expect(result.errorRows).toEqual([]);
    expect(result.validRows[0].data.warehouseId).toBe(21);
  });

  it('略過空白列，並吃掉中文欄位常見的前後空白', async () => {
    const result = await service.parse(
      toFile([
        HEADER,
        [null, null, null, null, null, null, null],
        [' SO-011 ', ' 高雄左營店', '高雄倉 ', '常溫', '1,200', '2026-01-05', ''],
      ]),
      context,
    );

    expect(result.rows).toHaveLength(1);
    expect(result.validRows[0].data.orderNumber).toBe('SO-011');
    expect(result.validRows[0].data.boxCount).toBe(1200);
  });
});

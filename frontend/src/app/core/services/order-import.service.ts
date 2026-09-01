import { Injectable } from '@angular/core';

import { OrderDto, OrderStatus, StoreDto, WarehouseDto } from './dispatch-api.models';

type Xlsx = typeof import('xlsx');

let xlsxModule: Xlsx | null = null;

/**
 * xlsx 壓縮後有 140 kB 左右，靜態 import 會被打進 order-review 的 chunk，
 * 讓沒要匯入的人也一起下載。改成第一次用到才載，之後快取在模組變數裡。
 */
async function loadXlsx(): Promise<Xlsx> {
  xlsxModule ??= await import('xlsx');
  return xlsxModule;
}

/** Excel 欄位會對應到 OrderDto 的哪個欄位。 */
type ImportField =
  | 'orderNumber'
  | 'storeId'
  | 'warehouseId'
  | 'sourceVendor'
  | 'itemDescription'
  | 'boxCount'
  | 'deliveryDate'
  | 'notes'
  | 'status';

/**
 * 表頭別名表。比對前表頭會先正規化（去掉所有空白、轉小寫），
 * 所以這裡只要列「長相不同」的寫法，大小寫與空白差異不必重複列。
 * 客戶的 Excel 用了別的欄位名稱時，直接把字串加進對應的陣列即可。
 */
const COLUMN_ALIASES: Record<ImportField, readonly string[]> = {
  orderNumber: ['訂單編號', '訂單號碼', '單號', 'ordernumber', 'orderno'],
  storeId: ['門市', '門市名稱', '門市代號', '店家', '店家名稱', '店號', 'store', 'storecode'],
  warehouseId: ['出貨倉', '出貨倉庫', '倉庫', '倉庫名稱', '倉庫代號', 'warehouse', 'warehousecode'],
  sourceVendor: ['來源廠商', '廠商', '供應商', 'vendor', 'sourcevendor'],
  itemDescription: ['品項', '品名', '商品', '貨品', 'item', 'itemdescription'],
  boxCount: ['箱數', '件數', '數量', 'boxcount', 'qty'],
  deliveryDate: ['配送日期', '配送日', '送貨日期', '出貨日期', 'deliverydate'],
  notes: ['備註', '備注', '註記', 'notes', 'remark'],
  status: ['狀態', '訂單狀態', 'status'],
};

/** 缺這些欄位就組不出 OrderDto，整份檔案直接判失敗。 */
const REQUIRED_COLUMNS: readonly ImportField[] = [
  'orderNumber',
  'storeId',
  'boxCount',
  'deliveryDate',
];

/** Excel 沒填狀態時一律當新單。 */
const DEFAULT_STATUS: OrderStatus = 'PENDING_CONFIRM';

/** 狀態欄允許填的中文，對應到後端 enum。也接受直接填 enum 名稱。 */
const STATUS_ALIASES: Readonly<Record<string, OrderStatus>> = {
  待總部確認: 'PENDING_CONFIRM',
  待確認: 'PENDING_CONFIRM',
  待排車: 'CONFIRMED',
  已確認: 'CONFIRMED',
  配送中: 'IN_DELIVERY',
  已完成: 'COMPLETED',
  已取消: 'CANCELLED',
  配送失敗: 'FAILED',
};

/** 後端 OrdersDTO 的 @Size 限制，先在前端擋掉，省一趟 400。 */
const MAX_LENGTH = {
  orderNumber: 30,
  sourceVendor: 100,
  itemDescription: 255,
} as const;

/** 表頭最多往下找幾列（有些範本第一列是標題或說明）。 */
const HEADER_SCAN_LIMIT = 10;

/** 匯入範本的欄位順序，也是 createTemplateBlob() 產出的表頭。 */
const TEMPLATE_HEADERS: readonly string[] = [
  '訂單編號',
  '門市',
  '出貨倉',
  '來源廠商',
  '品項',
  '箱數',
  '配送日期',
  '備註',
];

/** 解析時要用到的參照資料，由呼叫端先撈好傳進來。 */
export interface OrderImportContext {
  stores: readonly StoreDto[];
  warehouses: readonly WarehouseDto[];
  /** Excel 沒有出貨倉欄位（或該格留空）時要套用的倉庫。 */
  defaultWarehouseId?: number;
  /** 已存在的訂單編號。後端 createAll 撞號會整批 rollback，所以先在這裡擋掉。 */
  existingOrderNumbers?: readonly string[];
}

/** 一列 Excel 的解析結果。errors 是空陣列才代表這列可以送出。 */
export interface OrderImportRow {
  /** Excel 上實際看到的列號（1-based，含表頭那一列），錯誤訊息要拿這個給使用者對。 */
  row: number;
  data: OrderDto;
  errors: string[];
}

export interface OrderImportResult {
  /** 工作表名稱，多分頁檔案只會讀第一個分頁，顯示出來讓使用者確認。 */
  sheetName: string;
  rows: OrderImportRow[];
  validRows: OrderImportRow[];
  errorRows: OrderImportRow[];
}

/** 名稱重複、無法判斷是哪一筆時用 null 佔位。 */
type Lookup = ReadonlyMap<string, number | null>;

/**
 * 把訂單 Excel 讀成可以直接送 POST /api/orders/batch 的 OrderDto 陣列。
 *
 * 只負責「讀檔 → 欄位對照 → 型別轉換 + 驗證」，不碰 HTTP；
 * 呼叫端拿到 OrderImportResult 後自己決定要預覽、要擋、還是直接送。
 */
@Injectable({
  providedIn: 'root',
})
export class OrderImportService {
  /**
   * @throws Error 檔案本身不能用時（不是 Excel、沒有工作表、缺必要欄位）。
   *         單列的資料問題不會 throw，會記在該列的 errors 裡。
   */
  async parse(file: File, context: OrderImportContext): Promise<OrderImportResult> {
    const [xlsx, buffer] = await Promise.all([loadXlsx(), file.arrayBuffer()]);
    const grid = this.readGrid(xlsx, buffer);
    const headerIndex = this.findHeaderIndex(grid.rows);

    if (headerIndex < 0) {
      throw new Error('找不到表頭，請確認第一列是欄位名稱（訂單編號、門市、箱數、配送日期⋯⋯）。');
    }

    const columns = this.resolveColumns(grid.rows[headerIndex]);
    const missing = REQUIRED_COLUMNS.filter((field) => !columns.has(field));

    if (missing.length > 0) {
      const names = missing.map((field) => COLUMN_ALIASES[field][0]).join('、');
      throw new Error(`Excel 缺少必要欄位：${names}。`);
    }

    const storeLookup = this.buildLookup(
      context.stores.map((store) => ({ id: store.id, keys: [store.name, store.storeCode] })),
    );
    const warehouseLookup = this.buildLookup(
      context.warehouses.map((warehouse) => ({
        id: warehouse.id,
        keys: [warehouse.name, warehouse.warehouseCode],
      })),
    );
    // 檔案內重複、以及跟資料庫既有訂單重複，都算重複。
    const seenOrderNumbers = new Set(
      (context.existingOrderNumbers ?? []).map((value) => normalizeKey(value)).filter(Boolean),
    );

    const rows: OrderImportRow[] = [];

    for (let index = headerIndex + 1; index < grid.rows.length; index += 1) {
      const cells = grid.rows[index];

      if (isBlankRow(cells)) {
        continue;
      }

      rows.push(
        this.parseRow(cells, {
          row: index + 1,
          columns,
          context,
          storeLookup,
          warehouseLookup,
          seenOrderNumbers,
        }),
      );
    }

    return {
      sheetName: grid.sheetName,
      rows,
      validRows: rows.filter((row) => row.errors.length === 0),
      errorRows: rows.filter((row) => row.errors.length > 0),
    };
  }

  /** 產一份空白匯入範本，讓使用者照著填，可以省掉大半的欄位對不上。 */
  async createTemplateBlob(): Promise<Blob> {
    const xlsx = await loadXlsx();
    const sheet = xlsx.utils.aoa_to_sheet([
      [...TEMPLATE_HEADERS],
      ['SO-20260101-001', '台南永康店', '台南倉', '好食品', '常溫飲料', 12, '2026-01-01', ''],
    ]);
    sheet['!cols'] = TEMPLATE_HEADERS.map(() => ({ wch: 16 }));

    const workbook = xlsx.utils.book_new();
    xlsx.utils.book_append_sheet(workbook, sheet, '訂單匯入');

    const buffer = xlsx.write(workbook, { bookType: 'xlsx', type: 'array' }) as ArrayBuffer;
    return new Blob([buffer], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
  }

  /** 讀第一個工作表，並保留原始列號（blankrows 不能關，關掉列號就對不上了）。 */
  private readGrid(xlsx: Xlsx, buffer: ArrayBuffer): { sheetName: string; rows: unknown[][] } {
    let workbook: ReturnType<Xlsx['read']>;

    try {
      // cellDates 讓日期格直接變成 Date，不必自己換算 Excel 序號。
      workbook = xlsx.read(buffer, { cellDates: true });
    } catch {
      throw new Error('讀不到這個檔案，請確認是 .xlsx / .xls / .csv 且沒有加密。');
    }

    const sheetName = workbook.SheetNames[0];
    const sheet = sheetName ? workbook.Sheets[sheetName] : undefined;

    if (!sheet) {
      throw new Error('這個檔案沒有任何工作表。');
    }

    return {
      sheetName,
      rows: xlsx.utils.sheet_to_json<unknown[]>(sheet, {
        header: 1,
        defval: null,
        blankrows: true,
      }),
    };
  }

  /** 表頭不一定在第一列，取前幾列中第一個能對到 2 個以上已知欄位的。 */
  private findHeaderIndex(rows: readonly unknown[][]): number {
    const limit = Math.min(rows.length, HEADER_SCAN_LIMIT);

    for (let index = 0; index < limit; index += 1) {
      if (this.resolveColumns(rows[index]).size >= 2) {
        return index;
      }
    }

    return -1;
  }

  /** 表頭 → 欄位索引。認不得的欄位直接忽略，不當錯誤。 */
  private resolveColumns(headerCells: readonly unknown[] | undefined): Map<ImportField, number> {
    const columns = new Map<ImportField, number>();

    (headerCells ?? []).forEach((cell, columnIndex) => {
      const key = normalizeKey(cell);

      if (!key) {
        return;
      }

      for (const [field, aliases] of Object.entries(COLUMN_ALIASES) as [
        ImportField,
        readonly string[],
      ][]) {
        // 同一個欄位出現兩次時以第一個為準。
        if (!columns.has(field) && aliases.some((alias) => normalizeKey(alias) === key)) {
          columns.set(field, columnIndex);
          return;
        }
      }
    });

    return columns;
  }

  private parseRow(
    cells: readonly unknown[],
    options: {
      row: number;
      columns: ReadonlyMap<ImportField, number>;
      context: OrderImportContext;
      storeLookup: Lookup;
      warehouseLookup: Lookup;
      seenOrderNumbers: Set<string>;
    },
  ): OrderImportRow {
    const { columns, context, seenOrderNumbers } = options;
    const errors: string[] = [];
    const cellOf = (field: ImportField): unknown => {
      const columnIndex = columns.get(field);
      return columnIndex === undefined ? null : cells[columnIndex];
    };

    const orderNumber = normalizeText(cellOf('orderNumber'));

    if (!orderNumber) {
      errors.push('訂單編號未填');
    } else if (orderNumber.length > MAX_LENGTH.orderNumber) {
      errors.push(`訂單編號超過 ${MAX_LENGTH.orderNumber} 字`);
    } else if (seenOrderNumbers.has(normalizeKey(orderNumber))) {
      errors.push(`訂單編號重複：${orderNumber}`);
    } else {
      seenOrderNumbers.add(normalizeKey(orderNumber));
    }

    const storeId = this.resolveReference(cellOf('storeId'), options.storeLookup, '門市', errors);

    const warehouseCell = cellOf('warehouseId');
    let warehouseId = 0;

    if (isBlankCell(warehouseCell)) {
      // 沒有出貨倉欄位時就吃預設值；連預設值都沒有才算錯。
      warehouseId = context.defaultWarehouseId ?? 0;

      if (!warehouseId) {
        errors.push('出貨倉未填，且沒有預設倉庫');
      }
    } else {
      warehouseId = this.resolveReference(warehouseCell, options.warehouseLookup, '出貨倉', errors);
    }

    const boxCountCell = cellOf('boxCount');
    const boxCount = toInteger(boxCountCell);

    if (isBlankCell(boxCountCell)) {
      errors.push('箱數未填');
    } else if (boxCount === null) {
      errors.push(`箱數不是整數：${normalizeText(boxCountCell)}`);
    } else if (boxCount < 1) {
      errors.push('箱數必須大於 0');
    }

    const deliveryDateCell = cellOf('deliveryDate');
    const deliveryDate = toDateString(deliveryDateCell);

    if (isBlankCell(deliveryDateCell)) {
      errors.push('配送日期未填');
    } else if (deliveryDate === null) {
      errors.push(`配送日期格式錯誤（請用 2026-01-01）：${normalizeText(deliveryDateCell)}`);
    }

    const sourceVendor = normalizeText(cellOf('sourceVendor'));

    if (sourceVendor.length > MAX_LENGTH.sourceVendor) {
      errors.push(`來源廠商超過 ${MAX_LENGTH.sourceVendor} 字`);
    }

    const itemDescription = normalizeText(cellOf('itemDescription'));

    if (itemDescription.length > MAX_LENGTH.itemDescription) {
      errors.push(`品項超過 ${MAX_LENGTH.itemDescription} 字`);
    }

    const statusCell = cellOf('status');
    let status = DEFAULT_STATUS;

    if (!isBlankCell(statusCell)) {
      const resolved = resolveStatus(statusCell);

      if (resolved === null) {
        errors.push(`看不懂的狀態：${normalizeText(statusCell)}`);
      } else {
        status = resolved;
      }
    }

    return {
      row: options.row,
      data: {
        orderNumber,
        storeId,
        warehouseId,
        sourceVendor,
        itemDescription,
        boxCount: boxCount ?? 0,
        notes: normalizeText(cellOf('notes')),
        deliveryDate: deliveryDate ?? '',
        status,
      },
      errors,
    };
  }

  /** 門市／倉庫這格填的是名稱或代號，換成 id；也接受直接填 id。 */
  private resolveReference(cell: unknown, lookup: Lookup, label: string, errors: string[]): number {
    if (isBlankCell(cell)) {
      errors.push(`${label}未填`);
      return 0;
    }

    const key = normalizeKey(cell);
    const matched = lookup.get(key);

    if (matched === null) {
      errors.push(`${label}名稱重複，請改填代號：${normalizeText(cell)}`);
      return 0;
    }

    if (matched !== undefined) {
      return matched;
    }

    errors.push(`找不到${label}：${normalizeText(cell)}`);
    return 0;
  }

  private buildLookup(
    items: readonly { id?: number; keys: readonly (string | undefined)[] }[],
  ): Lookup {
    const lookup = new Map<string, number | null>();
    const remember = (key: string, id: number): void => {
      const existing = lookup.get(key);
      // 同一個名稱對到兩個不同 id 就標成 null，交給使用者改填代號。
      lookup.set(key, existing === undefined || existing === id ? id : null);
    };

    for (const item of items) {
      if (item.id === undefined) {
        continue;
      }

      remember(String(item.id), item.id);

      for (const key of item.keys) {
        const normalized = normalizeKey(key);

        if (normalized) {
          remember(normalized, item.id);
        }
      }
    }

    return lookup;
  }
}

/** 去掉前後空白與全形空白。Excel 的中文欄位幾乎一定會有。 */
function normalizeText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }

  return String(value)
    .replace(/[\s　]+/g, ' ')
    .trim();
}

/** 比對用的鍵：去掉所有空白再轉小寫，這樣「台南 倉」和「台南倉」算同一個。 */
function normalizeKey(value: unknown): string {
  return normalizeText(value).replace(/\s+/g, '').toLowerCase();
}

function isBlankCell(value: unknown): boolean {
  return normalizeText(value) === '';
}

function isBlankRow(cells: readonly unknown[] | undefined): boolean {
  return (cells ?? []).every(isBlankCell);
}

function resolveStatus(value: unknown): OrderStatus | null {
  const key = normalizeKey(value);
  const byLabel = Object.entries(STATUS_ALIASES).find(
    ([label]) => normalizeKey(label) === key,
  )?.[1];

  if (byLabel) {
    return byLabel;
  }

  const upper = key.toUpperCase();
  return isOrderStatus(upper) ? (upper as OrderStatus) : null;
}

function isOrderStatus(value: string): boolean {
  return Object.values(STATUS_ALIASES).includes(value as OrderStatus);
}

/** 允許 "12"、"12.0"、"1,200" 這幾種寫法，小數點後有值就當錯。 */
function toInteger(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isInteger(value) ? value : null;
  }

  const text = normalizeText(value).replace(/,/g, '');

  if (!/^\d+(\.0+)?$/.test(text)) {
    return null;
  }

  return Number.parseInt(text, 10);
}

/**
 * 轉成後端 LocalDate 吃的 yyyy-MM-dd。
 * 接受 Date（cellDates 的產物）、Excel 日期序號、以及手打的字串。
 */
function toDateString(value: unknown): string | null {
  if (value instanceof Date) {
    return Number.isNaN(value.getTime())
      ? null
      : formatDate(value.getFullYear(), value.getMonth() + 1, value.getDate());
  }

  if (typeof value === 'number') {
    return fromExcelSerial(value);
  }

  const text = normalizeText(value);

  if (!text) {
    return null;
  }

  // 純數字字串當成 Excel 序號（有些檔案的日期格被存成文字）。
  if (/^\d+(\.\d+)?$/.test(text) && text.length <= 6) {
    return fromExcelSerial(Number(text));
  }

  // 只取日期部分，後面的 00:00:00 之類的丟掉。
  const matched = text.split(' ')[0].match(/^(\d{2,4})[-/.](\d{1,2})[-/.](\d{1,2})$/);

  if (!matched) {
    return null;
  }

  const year = Number(matched[1]);
  return formatDate(
    // 3 碼以下當民國年，台灣的表單很常這樣填。
    year < 1000 ? year + 1911 : year,
    Number(matched[2]),
    Number(matched[3]),
  );
}

function fromExcelSerial(serial: number): string | null {
  if (!Number.isFinite(serial) || serial <= 0) {
    return null;
  }

  // Excel 的第 0 天是 1899-12-30（含 1900 閏年 bug 的補償）。
  const date = new Date(Date.UTC(1899, 11, 30) + Math.round(serial) * 86_400_000);
  return formatDate(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate());
}

/** 補零並檢查日期真的存在（擋掉 2026-02-30 這種）。 */
function formatDate(year: number, month: number, day: number): string | null {
  const date = new Date(Date.UTC(year, month - 1, day));

  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return null;
  }

  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

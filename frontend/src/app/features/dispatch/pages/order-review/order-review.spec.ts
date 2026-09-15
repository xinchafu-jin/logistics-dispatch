import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import * as XLSX from 'xlsx';

import { OrderDto, StoreDto, WarehouseDto } from '../../../../core/services/dispatch-api.models';
import { OrderReview } from './order-review';

const HEADER = ['訂單編號', '門市', '出貨倉', '品項', '箱數', '配送日期', '備註'];

const STORE: StoreDto = {
  id: 11,
  storeCode: 'ST-001',
  name: '高雄左營店',
  address: '高雄市左營區博愛三路 1 號',
  lat: 23,
  lng: 120,
  receivingStart: '09:00',
  receivingEnd: '18:00',
  status: 'ACTIVE',
};

const WAREHOUSE: WarehouseDto = {
  id: 21,
  warehouseCode: 'WH-001',
  name: '高雄倉',
  lat: 23,
  lng: 120,
  isActive: true,
};

function savedOrder(id: number, orderNumber: string): OrderDto {
  return {
    id,
    orderNumber,
    storeId: 11,
    warehouseId: 21,
    boxCount: 1,
    notes: '',
    deliveryDate: '2026-01-05',
    status: 'PENDING_CONFIRM',
  };
}

/** 組一個 change 事件，內容跟使用者真的選檔一樣（含 input.value 會被清掉）。 */
function fileEvent(rows: unknown[][]): Event {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(
    workbook,
    XLSX.utils.aoa_to_sheet(rows, { cellDates: true }),
    '訂單',
  );
  const buffer = XLSX.write(workbook, { bookType: 'xlsx', type: 'array' }) as ArrayBuffer;

  return {
    target: { files: [new File([buffer], 'orders.xlsx')], value: 'orders.xlsx' },
  } as unknown as Event;
}

describe('OrderReview Excel 匯入', () => {
  let fixture: ComponentFixture<OrderReview>;
  let component: OrderReview;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });

    httpTesting = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OrderReview);
    component = fixture.componentInstance;
    fixture.detectChanges();

    httpTesting.expectOne('/api/orders').flush([]);
    httpTesting.expectOne('/api/stores').flush([STORE]);
    httpTesting.expectOne('/api/warehouses').flush([WAREHOUSE]);
    fixture.detectChanges();
  });

  it('解析後開預覽，錯誤列標紅且不列入可匯入筆數', async () => {
    await component.onImportFileSelected(
      fileEvent([
        HEADER,
        ['SO-001', '高雄左營店', '高雄倉', '常溫', 2, '2026-01-05', ''],
        ['SO-002', '不存在的店', '高雄倉', '常溫', 1, '2026-01-05', ''],
      ]),
    );
    fixture.detectChanges();

    const state = component.importState();
    expect(state.stage).toBe('preview');

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('.import-table tbody tr')).toHaveLength(2);
    expect(host.querySelectorAll('.import-row-error')).toHaveLength(1);
    expect(host.textContent).toContain('找不到門市：不存在的店');
  });

  it('以單一批次請求建立全部通過驗證的訂單', async () => {
    await component.onImportFileSelected(
      fileEvent([
        HEADER,
        ['SO-001', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
        ['SO-002', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
        ['SO-003', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
      ]),
    );

    component.confirmImport();

    const request = httpTesting.expectOne('/api/orders/batch');
    expect(request.request.method).toBe('POST');
    expect(request.request.body.orders.map((order: OrderDto) => order.orderNumber)).toEqual([
      'SO-001',
      'SO-002',
      'SO-003',
    ]);
    request.flush([savedOrder(1, 'SO-001'), savedOrder(2, 'SO-002'), savedOrder(3, 'SO-003')]);

    const state = component.importState();
    expect(state).toMatchObject({ stage: 'done', succeeded: 3, failures: [] });
    expect(component.orders().map((order) => order.id)).toEqual(['SO-001', 'SO-002', 'SO-003']);
  });

  it('批次建立失敗時不將任何訂單加入清單', async () => {
    await component.onImportFileSelected(
      fileEvent([
        HEADER,
        ['SO-001', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
        ['SO-002', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', ''],
      ]),
    );

    component.confirmImport();

    httpTesting
      .expectOne('/api/orders/batch')
      .flush(
        { success: false, message: '訂單編號已存在：SO-002' },
        { status: 400, statusText: 'Bad Request' },
      );

    const state = component.importState();
    expect(state).toMatchObject({ stage: 'done', succeeded: 0 });
    expect(state.stage === 'done' && state.failures).toEqual([
      { row: 2, orderNumber: 'SO-001', message: '訂單編號已存在：SO-002' },
      { row: 3, orderNumber: 'SO-002', message: '訂單編號已存在：SO-002' },
    ]);
    expect(component.orders()).toEqual([]);
  });

  it('匯入流程不會動到新增/編輯表單的狀態', async () => {
    component.openCreateOrder();
    expect(component.activeForm()).toBe('create');

    await component.onImportFileSelected(
      fileEvent([HEADER, ['SO-004', '高雄左營店', '高雄倉', '常溫', 1, '2026-01-05', '']]),
    );
    fixture.detectChanges();

    // 匯入把表單關掉，兩個 modal 不會疊在一起
    expect(component.activeForm()).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.order-form')).toHaveLength(0);

    component.closeImport();
    fixture.detectChanges();

    expect(component.importState()).toEqual({ stage: 'idle' });
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.import-table')).toHaveLength(
      0,
    );
  });

  it('新增訂單時自動產生唯讀訂單編號', () => {
    component.openCreateOrder();
    fixture.detectChanges();

    expect(component.orderForm().orderNumber).toMatch(/^DO-\d{8}-[A-F0-9]{8}$/);

    const orderNumberInput = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '.order-form input[readonly]',
    );
    expect(orderNumberInput?.value).toBe(component.orderForm().orderNumber);
    expect(orderNumberInput?.readOnly).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('系統自動產生');
  });

  it('檔案格式不對時停在 failed，不會送出任何請求', async () => {
    await component.onImportFileSelected(
      fileEvent([
        ['訂單編號', '品項'],
        ['SO-005', '常溫'],
      ]),
    );
    fixture.detectChanges();

    const state = component.importState();
    expect(state.stage).toBe('failed');
    expect(state.stage === 'failed' && state.message).toContain('缺少必要欄位');

    httpTesting.verify();
  });
});

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { DriverCaseDto, DriverMessagePushDto } from './dispatch-api.models';
import { DriverCasesService, driverCaseCategoryLabel } from './driver-cases.service';
import { DriverChatSocketService } from './driver-chat-socket.service';

function driverCase(overrides: Partial<DriverCaseDto>): DriverCaseDto {
  return {
    id: 1,
    category: 'VEHICLE',
    status: 'OPEN',
    orderId: null,
    orderNumber: null,
    storeName: null,
    description: '爆胎',
    canContinue: true,
    photoUrl: null,
    createdAt: '2026-09-28T10:00:00',
    acceptedAt: null,
    handledAt: null,
    resolution: null,
    unreadCount: 0,
    driverId: 1,
    driverName: '王小明',
    routeId: 30,
    vehiclePlateNumber: 'ABC-1234',
    acceptedAdminId: null,
    acceptedAdminName: null,
    handledBy: null,
    ...overrides,
  };
}

describe('DriverCasesService', () => {
  let service: DriverCasesService;
  let httpTesting: HttpTestingController;
  // 假的 WebSocket：測試自己丟推播、自己觸發「連上了」
  let pushes: Subject<DriverMessagePushDto>;
  let connected: Subject<void>;

  beforeEach(() => {
    pushes = new Subject();
    connected = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: DriverChatSocketService,
          useValue: { pushes$: pushes.asObservable(), connected$: connected.asObservable() },
        },
      ],
    });
    service = TestBed.inject(DriverCasesService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  function loadWith(cases: DriverCaseDto[]): void {
    service.load();
    httpTesting.expectOne('/api/exceptions/driver-cases').flush(cases);
  }

  it('每次連上都重抓進行中的案件：斷線期間的推播不會補發', () => {
    connected.next();
    httpTesting.expectOne('/api/exceptions/driver-cases').flush([driverCase({ id: 3 })]);

    expect(service.openCases().map((item) => item.id)).toEqual([3]);
    expect(service.loadState()).toBe('ready');
  });

  it('CASE_OPENED 加進清單並重排：沒人接收的在前，同組裡不能繼續配送的在前', () => {
    loadWith([driverCase({ id: 1, acceptedAt: '2026-09-28T10:05:00', acceptedAdminId: 1 }), driverCase({ id: 2 })]);

    pushes.next({
      type: 'CASE_OPENED',
      driverId: 1,
      exceptionCaseId: 5,
      exceptionCase: driverCase({ id: 5, canContinue: false }),
    });

    expect(service.openCases().map((item) => item.id)).toEqual([5, 2, 1]);
    expect(service.waitingCases().map((item) => item.id)).toEqual([5, 2]);
  });

  it('CASE_ACCEPTED 換掉整件，但未讀數用本機的：推播裡固定是 0', () => {
    loadWith([driverCase({ id: 2, unreadCount: 3 })]);

    pushes.next({
      type: 'CASE_ACCEPTED',
      driverId: 1,
      exceptionCaseId: 2,
      exceptionCase: driverCase({ id: 2, acceptedAt: '2026-09-28T10:06:00', acceptedAdminName: '王主管' }),
    });

    expect(service.findCase(2)?.acceptedAdminName).toBe('王主管');
    expect(service.findCase(2)?.unreadCount).toBe(3);
    expect(service.waitingCases()).toEqual([]);
    expect(service.acceptedCases().map((item) => item.id)).toEqual([2]);
  });

  it('CASE_CLOSED 從進行中移到已結案：聊天室開著這件的話還找得到', () => {
    loadWith([driverCase({ id: 2 })]);

    pushes.next({
      type: 'CASE_CLOSED',
      driverId: 1,
      exceptionCaseId: 2,
      exceptionCase: driverCase({ id: 2, status: 'CLOSED', resolution: '已派人支援' }),
    });

    expect(service.openCases()).toEqual([]);
    expect(service.closedCases().map((item) => item.id)).toEqual([2]);
    expect(service.findCase(2)?.resolution).toBe('已派人支援');
  });

  it('司機在案件裡留言：那件未讀加一；管理員的訊息、一般對話的訊息都不算', () => {
    loadWith([driverCase({ id: 2 })]);
    const createdAt = '2026-09-28T10:07:00';

    pushes.next({
      type: 'MESSAGE',
      driverId: 1,
      message: { id: 10, driverId: 1, senderType: 'DRIVER', content: '冒煙了', createdAt, exceptionCaseId: 2 },
    });
    pushes.next({
      type: 'MESSAGE',
      driverId: 1,
      message: { id: 11, driverId: 1, senderType: 'ADMIN', content: '收到', createdAt, exceptionCaseId: 2 },
    });
    pushes.next({
      type: 'MESSAGE',
      driverId: 1,
      message: { id: 12, driverId: 1, senderType: 'DRIVER', content: '一般對話', createdAt },
    });

    expect(service.findCase(2)?.unreadCount).toBe(1);
  });

  it('別的管理員讀了司機的訊息：只有那件的未讀歸零', () => {
    loadWith([driverCase({ id: 2, unreadCount: 4 }), driverCase({ id: 3, unreadCount: 1 })]);

    pushes.next({
      type: 'READ',
      driverId: 1,
      readSenderType: 'DRIVER',
      readAt: '2026-09-28T10:08:00',
      exceptionCaseId: 2,
    });

    expect(service.findCase(2)?.unreadCount).toBe(0);
    expect(service.findCase(3)?.unreadCount).toBe(1);
  });

  it('接收成功後清單跟著更新，不用等推播', () => {
    loadWith([driverCase({ id: 2 })]);

    service.accept(2).subscribe();
    httpTesting
      .expectOne('/api/exceptions/driver-cases/2/accept')
      .flush(driverCase({ id: 2, acceptedAt: '2026-09-28T10:06:00', acceptedAdminId: 1 }));

    expect(service.acceptedCases().map((item) => item.id)).toEqual([2]);
  });

  it('分類名稱跟司機端一致；舊版回報沒有分類、或不認識的分類都顯示「其他」', () => {
    expect(driverCaseCategoryLabel('ACCIDENT')).toBe('交通事故');
    expect(driverCaseCategoryLabel(null)).toBe('其他');
    expect(driverCaseCategoryLabel('FLYING' as never)).toBe('其他');
  });
});

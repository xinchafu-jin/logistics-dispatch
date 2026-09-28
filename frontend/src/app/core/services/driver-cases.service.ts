import {Injectable, computed, inject, signal} from '@angular/core';
import {Observable, Subject, tap} from 'rxjs';
import {DispatchApiService} from './dispatch-api.service';
import {DriverChatSocketService} from './driver-chat-socket.service';
import {DriverCaseCategory, DriverCaseDto, DriverMessagePushDto} from './dispatch-api.models';

type LoadState = 'idle' | 'loading' | 'ready' | 'error';

const CATEGORY_LABELS: Record<DriverCaseCategory, string> = {
  VEHICLE: '車輛問題',
  ACCIDENT: '交通事故',
  ROAD: '路況延誤',
  STORE: '門市狀況',
  GOODS: '貨物問題',
  PERSONAL: '身體／安全',
  SYSTEM: 'App／系統',
  OTHER: '其他',
};

/** 分類的中文名稱，跟司機端的 CASE_CATEGORIES 一致；舊版回報沒有分類、或後端多了還不認識的分類，都顯示「其他」 */
export function driverCaseCategoryLabel(category: DriverCaseCategory | null): string {
  return (category ? CATEGORY_LABELS[category] : undefined) ?? '其他';
}

/**
 * 進行中清單的順序，跟後端 DriverCaseService.ADMIN_OPEN_ORDER 一樣：還沒人接收的在前（鈴鐺列的也是這些），
 * 同一組裡不能繼續配送的在前，再來先回報的在前（等最久的先處理）。推播改了狀態之後要照這個重排。
 */
export function compareOpenDriverCases(left: DriverCaseDto, right: DriverCaseDto): number {
  const accepted = Number(left.acceptedAt !== null) - Number(right.acceptedAt !== null);
  if (accepted !== 0) {
    return accepted;
  }
  const canContinue = Number(left.canContinue === true) - Number(right.canContinue === true);
  if (canContinue !== 0) {
    return canContinue;
  }
  return left.id - right.id;
}

/**
 * 司機回報案件的共用資料：鈴鐺（等待接收）、聊天室（處理中的案件）、異常中心（清單）都讀這裡。
 *
 * 為什麼放 root：鈴鐺和聊天室在 dispatch-shell，異常中心是裡面的頁面，兩者沒有父子關係。
 * 各自載、各自聽推播的話，接收或結案後三個地方的數字會對不起來；推播統一在這裡處理，畫面只讀 signal。
 *
 * 案件訊息的內容不放這裡：只有聊天室開著的那一串要看，由 dispatch-shell 自己載。這裡只管每件的未讀數。
 */
@Injectable({providedIn: 'root'})
export class DriverCasesService {
  private readonly api = inject(DispatchApiService);
  private readonly socket = inject(DriverChatSocketService);
  private readonly openChatSubject = new Subject<number>();

  /** 進行中的案件，順序見 compareOpenDriverCases */
  readonly openCases = signal<DriverCaseDto[]>([]);
  /** 最近結案的，新結案的在前；異常中心切到「已結案」才載，推播來的結案也會放進來 */
  readonly closedCases = signal<DriverCaseDto[]>([]);
  readonly loadState = signal<LoadState>('idle');
  readonly closedLoadState = signal<LoadState>('idle');
  /** 鈴鐺：還沒有人接收的 */
  readonly waitingCases = computed(() => this.openCases().filter((item) => item.acceptedAt === null));
  /** 聊天室的聯絡人：已經有人接收、還沒結案的 */
  readonly acceptedCases = computed(() => this.openCases().filter((item) => item.acceptedAt !== null));
  /** 異常中心按「開啟對話」時發出案件 id；dispatch-shell 收到就打開聊天室的那一件 */
  readonly openChatRequests$: Observable<number> = this.openChatSubject.asObservable();

  constructor() {
    // root service 跟整個後台同壽命，不用取消訂閱。連線由 dispatch-shell 管，連上之前這裡什麼都收不到
    this.socket.pushes$.subscribe((push) => this.applyPush(push));
    // 每次連上（含重連）都重抓：斷線期間的推播不會補發，漏掉的案件、狀態、未讀數靠這裡補回來
    this.socket.connected$.subscribe(() => {
      this.load();
      if (this.closedLoadState() !== 'idle') {
        this.loadClosed();
      }
    });
  }

  load(): void {
    this.loadState.set('loading');
    this.api.getDriverCases().subscribe({
      next: (cases) => {
        this.openCases.set([...cases].sort(compareOpenDriverCases));
        this.loadState.set('ready');
      },
      error: () => this.loadState.set('error'),
    });
  }

  loadClosed(): void {
    this.closedLoadState.set('loading');
    this.api.getDriverCases('CLOSED').subscribe({
      next: (cases) => {
        this.closedCases.set(cases);
        this.closedLoadState.set('ready');
      },
      error: () => this.closedLoadState.set('error'),
    });
  }

  /** 接收；成功後清單跟著更新。錯誤（例如已被別人接收）交給呼叫端顯示 */
  accept(caseId: number): Observable<DriverCaseDto> {
    return this.api.acceptDriverCase(caseId).pipe(tap((updated) => this.upsert(updated)));
  }

  close(caseId: number, resolution: string): Observable<DriverCaseDto> {
    return this.api.closeDriverCase(caseId, resolution).pipe(tap((closed) => this.upsert(closed)));
  }

  requestOpenChat(caseId: number): void {
    this.openChatSubject.next(caseId);
  }

  /** 先找進行中、再找已結案；聊天室開著的那件剛被結案時，要靠已結案清單繼續顯示 */
  findCase(caseId: number): DriverCaseDto | null {
    return this.openCases().find((item) => item.id === caseId)
      ?? this.closedCases().find((item) => item.id === caseId)
      ?? null;
  }

  /** 聊天室看著這件、已經標已讀：先在畫面上歸零，不用等 READ 推播 */
  clearUnread(caseId: number): void {
    this.updateCase(caseId, (item) => ({...item, unreadCount: 0}));
  }

  private applyPush(push: DriverMessagePushDto): void {
    if (push.type === 'CASE_OPENED' || push.type === 'CASE_ACCEPTED' || push.type === 'CASE_CLOSED') {
      if (push.exceptionCase) {
        this.upsert(push.exceptionCase);
      }
      return;
    }
    // 司機在案件裡留言：那件的未讀加一。聊天室正看著的話，dispatch-shell 會馬上標已讀再歸零
    if (push.type === 'MESSAGE' && push.message?.senderType === 'DRIVER') {
      const caseId = push.message.exceptionCaseId;
      if (caseId != null) {
        this.updateCase(caseId, (item) => ({...item, unreadCount: item.unreadCount + 1}));
      }
      return;
    }
    // 別的管理員讀了司機的訊息：已讀是共用的，這邊的紅點也要消
    if (push.type === 'READ' && push.readSenderType === 'DRIVER' && push.exceptionCaseId != null) {
      this.clearUnread(push.exceptionCaseId);
    }
  }

  /**
   * 整件換掉，並依狀態放進對的清單。本機已經有這件的話，未讀數用本機的：
   * 推播裡的 unreadCount 固定 0，未讀數是 MESSAGE、READ 推播在算。
   */
  private upsert(incoming: DriverCaseDto): void {
    const existing = this.findCase(incoming.id);
    const merged = existing ? {...incoming, unreadCount: existing.unreadCount} : incoming;
    const withoutIt = (cases: DriverCaseDto[]) => cases.filter((item) => item.id !== merged.id);
    if (merged.status === 'OPEN') {
      this.openCases.update((cases) => [...withoutIt(cases), merged].sort(compareOpenDriverCases));
      this.closedCases.update(withoutIt);
    } else {
      this.openCases.update(withoutIt);
      this.closedCases.update((cases) => [merged, ...withoutIt(cases)]);
    }
  }

  private updateCase(caseId: number, change: (item: DriverCaseDto) => DriverCaseDto): void {
    const apply = (cases: DriverCaseDto[]) => cases.map((item) => (item.id === caseId ? change(item) : item));
    this.openCases.update(apply);
    this.closedCases.update(apply);
  }
}

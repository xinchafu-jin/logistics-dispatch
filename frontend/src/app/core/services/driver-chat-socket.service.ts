import {Injectable, inject, signal} from '@angular/core';
import {Client, IMessage} from '@stomp/stompjs';
import {Observable, Subject} from 'rxjs';
import {AuthService} from '../auth/auth.service';
import {DriverMessagePushDto} from './dispatch-api.models';

/** 管理員共用的廣播頻道；要跟後端 DriverMessagesPushService.ADMIN_TOPIC、攔截器白名單一致 */
const ADMIN_TOPIC = '/topic/admin/driver-messages';

/**
 * 後台的 WebSocket 連線（STOMP）。只管「電話線」：撥號、報身分、訂閱、斷線重撥；
 * 收到推播要怎麼更新畫面，由 dispatch-shell 決定。
 *
 * 整個後台只能有一條連線，所以放 root。連線與斷線由 dispatch-shell 的生命週期控制：
 * 登入後的頁面都包在 dispatch-shell 裡，它存在的期間剛好就是登入的期間。
 *
 * 注意：WebSocket 只負責「通知」，斷線期間的推播不會補發。
 * 每次連上（包含重連）都會發出 connected$，dispatch-shell 要在那時用 afterId 把漏掉的補抓回來。
 */
@Injectable({providedIn: 'root'})
export class DriverChatSocketService {
  private readonly authService = inject(AuthService);
  private readonly pushSubject = new Subject<DriverMessagePushDto>();
  private readonly connectedSubject = new Subject<void>();
  private client: Client | null = null;

  /** 每收到一則推播發一次 */
  readonly pushes$: Observable<DriverMessagePushDto> = this.pushSubject.asObservable();
  /** 每次連上都發一次（第一次連上、斷線後重連都算），用來觸發補抓 */
  readonly connected$: Observable<void> = this.connectedSubject.asObservable();
  /** 目前是否連著；畫面想顯示「連線中斷」時用 */
  readonly isConnected = signal(false);

  connect(): void {
    // 已經在連或連著了就不再撥第二條
    if (this.client?.active) {
      return;
    }

    const client = new Client({
      // 相對於目前網站：本機是 ws://localhost:4200/api/ws（由 proxy.conf.json 轉給後端），
      // 正式站是 wss://dispatch.xinchafujin.com/api/ws（由 Nginx 轉給後端）。https 網站只能連 wss
      brokerURL: `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/api/ws`,
      // 斷線 5 秒後自動重撥（後端重啟、網路中斷都會觸發）
      reconnectDelay: 5_000,
      // 每 10 秒互傳一次心跳，跟後端 WebSocketConfig 一致；免得閒置 60 秒被 Nginx 切斷
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      // 每次撥號前才讀 token，不在建立時寫死：重新登入換了 token，重連時才會用新的
      beforeConnect: () => {
        client.connectHeaders = {Authorization: `Bearer ${this.authService.accessToken() ?? ''}`};
      },
      onConnect: () => {
        // 先訂閱，再通知 dispatch-shell 補抓：順序反了，補抓完到訂閱成功之間的訊息會漏掉
        client.subscribe(ADMIN_TOPIC, (frame: IMessage) => {
          this.pushSubject.next(JSON.parse(frame.body) as DriverMessagePushDto);
        });
        this.isConnected.set(true);
        this.connectedSubject.next();
      },
      onWebSocketClose: () => this.isConnected.set(false),
      // 後端攔截器拒絕（token 無效、訂閱不在白名單）會回 ERROR；stompjs 仍會照 reconnectDelay 重撥
      onStompError: (frame) => console.warn('[聊天室] WebSocket 被後端拒絕：', frame.headers['message']),
    });

    this.client = client;
    client.activate();
  }

  disconnect(): void {
    // deactivate 會停止重撥；不呼叫的話，登出後還會每 5 秒用舊 token 嘗試連線
    void this.client?.deactivate();
    this.client = null;
    this.isConnected.set(false);
  }
}

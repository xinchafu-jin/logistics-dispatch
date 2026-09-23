import {Injectable} from '@angular/core';
import {Observable, Subject} from 'rxjs';

/**
 * 排車資料在看板以外被寫入時的通知。
 *
 * 聊天面板在 dispatch-shell（外層 layout），看板是裡面的頁面，兩者沒有父子關係，
 * 只能靠 root 層級的 service 傳話：AI 清單確認寫入後發出通知，看板收到就重讀資料庫。
 * 不重讀的話，調度員接著在舊畫面上拖曳，reassign 會把整個舊盤面送出去，蓋掉 AI 剛寫入的結果。
 */
@Injectable({providedIn: 'root'})
export class DispatchBoardEventsService {
  private readonly changed = new Subject<void>();

  /** 看板訂閱這個，收到就重讀 */
  readonly boardChanged$: Observable<void> = this.changed.asObservable();

  notifyBoardChanged(): void {
    this.changed.next();
  }
}

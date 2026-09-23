import {Injectable, signal} from '@angular/core';

/**
 * 頂部欄右側的頁面資訊（例如「莊敬物流中心 · 資料更新於 02:33」）。
 *
 * 頂部欄在 dispatch-shell（外層 layout），資料在裡面的頁面，兩者沒有父子關係，
 * 跟 DispatchBoardEventsService 一樣靠 root 層級的 service 傳話：頁面寫入，shell 顯示。
 * 頁面離開時要設回 null，否則切到別頁還會留著上一頁的資訊。
 */
@Injectable({providedIn: 'root'})
export class DispatchHeaderService {
  /** null 代表這一頁沒有要顯示的資訊 */
  readonly meta = signal<string | null>(null);
}

// 單元測試的環境補丁（angular.json 的 test.options.setupFiles 載入，每個測試檔執行前跑一次）。
//
// Node 25 起內建 Web Storage：globalThis 上本來就有 localStorage，但沒給 --localstorage-file 時取值是 undefined。
// vitest 的 jsdom 環境把 jsdom 的屬性搬到 globalThis 時，遇到「globalThis 已經有的名字」會跳過
// （node_modules/vitest 的 getWindowKeys），於是測試裡拿到的是 Node 那個 undefined，
// auth.service.spec 的 localStorage.removeItem 直接丟錯，TestBed 沒被重置，後面的測試檔跟著連環失敗。
// 這裡把 localStorage 改指回 jsdom 的 Storage。舊版 Node 沒有內建的 localStorage，本來拿到的就是 jsdom 的，換了也一樣。
// sessionStorage 不用處理：Node 內建的 sessionStorage 存在記憶體，不需要檔案，本來就能用
const testGlobal = globalThis as typeof globalThis & {jsdom?: {window: Window}};

if (testGlobal.jsdom) {
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get: () => testGlobal.jsdom?.window.localStorage,
  });
}

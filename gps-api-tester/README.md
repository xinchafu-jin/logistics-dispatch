# GPS Signal Lab

獨立的 GPS API 測試頁面，直接串接本專案的 Spring Boot 後端。

## 功能

- 透過瀏覽器取得一次或持續追蹤真實 GPS
- 使用搖桿、方向鍵或 WASD 模擬位置移動
- 司機登入、查詢出勤、上班打卡
- 呼叫 `POST /api/driver/gps`，並顯示完整 HTTP 狀態與回傳內容
- 可設定位置變更後自動送出

## 本機啟動

先啟動後端（預設 `http://localhost:8080`），再於此資料夾執行：

```powershell
pnpm install
pnpm dev
```

開啟 `http://localhost:4204`。此連接埠已列在後端 CORS 白名單中；請勿直接雙擊 HTML 檔開啟。

GPS 寫入需要 DRIVER JWT，而且司機當日出勤狀態必須是 `WORKING`。若上班打卡被拒絕，需先由管理端建立並發布當日班表。

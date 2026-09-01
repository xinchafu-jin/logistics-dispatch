# GPS Signal Lab

GPS Signal Lab 是物流系統的獨立 GPS API 測試工具。它可以取得瀏覽器真實定位、用搖桿模擬司機移動，並直接呼叫後端 `POST /api/driver/gps`，方便確認登入、出勤、座標與 API 回應是否正常。

## 功能

- 取得一次或持續追蹤真實 GPS
- 使用搖桿、方向鍵或 `WASD` 模擬移動
- 手動輸入緯度與經度
- 設定每步移動 1、5、10、25 或 50 公尺
- 司機登入、查詢出勤與上班打卡
- 手動送出 GPS，或在停止移動後自動送出
- 顯示 HTTP 狀態、Request Body 與 Response

## 系統需求

- Java 21 與 Spring Boot 後端
- MySQL 及專案所需資料
- Node.js `22.13.0` 以上
- pnpm
- Chrome、Edge 或其他支援 Geolocation API 的瀏覽器

## 快速啟動

### 1. 啟動後端

在專案根目錄開啟 PowerShell：

```powershell
cd backend
.\gradlew.bat bootRun
```

後端預設網址為 `http://localhost:8080`。可開啟 `http://localhost:8080/actuator/health`，確認狀態為 `UP`。

### 2. 啟動 GPS 測試頁

另開一個 PowerShell：

```powershell
cd gps-api-tester
pnpm install
pnpm dev
```

開啟：

```text
http://localhost:4204
```

請使用 `localhost:4204`，不要直接雙擊 HTML 檔。這個連接埠已加入後端 CORS 白名單。

若依賴已安裝但 PowerShell 找不到 pnpm，也可執行：

```powershell
node .\node_modules\vinext\dist\cli.js dev --host localhost
```

若出現 `ERR_PNPM_IGNORED_BUILDS`，代表 pnpm 正在阻擋套件安裝 scripts。不要直接批准所有 scripts；應先由維護者檢查套件，再決定是否執行 `pnpm approve-builds`。

## 使用方式

### 1. 測試後端連線

1. `API Base URL` 保持 `http://localhost:8080`。
2. 按「測試連線」。
3. 顯示「API 在線」代表後端正常。

### 2. 登入司機

1. 輸入資料庫中的有效司機帳號與密碼。
2. 按「取得 Driver Token」。
3. 登入成功後會顯示司機名稱、帳號與 Driver ID。

如果資料庫已匯入原始 seed，可嘗試 `D001 / driver123`；實際帳密仍以目前資料庫為準。

### 3. 確認出勤

1. 按出勤狀態旁的「查詢」。
2. GPS 上傳要求狀態為 `WORKING`。
3. 尚未上班時按「上班打卡」。

如果打卡失敗，通常是管理端尚未建立並發布今天的 `WORK` 班表。休息中、已下班或沒有今日出勤紀錄時，GPS API 都會拒絕上傳。

### 4. 取得真實位置

1. 按「取得我的真實位置」。
2. 瀏覽器詢問權限時選擇「允許」。
3. 成功後會顯示緯度、經度及定位精度。
4. 「持續追蹤」可在位置變動時持續更新。

瀏覽器定位需要安全環境。`http://localhost` 可以使用；手機若透過 `http://192.168.x.x` 開啟，可能會被瀏覽器阻擋。桌機沒有 GPS 晶片時，也可能發生定位逾時，此時可改用手動座標或搖桿。

### 5. 模擬位置

- 拖動搖桿，或使用方向鍵／`WASD`。
- 用「每步」選擇移動距離。
- 也可以直接修改 `LAT` 與 `LNG`。
- 開啟「位置變更後自動送出」時，停止移動約 0.55 秒會送出一次。

每次 POST 都會新增一筆 `gps_pings` 軌跡資料。不要長時間快速移動並開啟自動送出，以免產生過多測試資料。

### 6. 送出 GPS

按「送出目前 GPS」，頁面會呼叫：

```http
POST /api/driver/gps
Authorization: Bearer <DRIVER_TOKEN>
Content-Type: application/json
```

```json
{
  "lat": 25.047817,
  "lng": 121.517053
}
```

成功時為 HTTP `200`：

```json
{
  "id": 123,
  "driverId": 1,
  "lat": 25.047817,
  "lng": 121.517053,
  "timestamp": "2026-09-01T17:30:00"
}
```

前端只傳 `lat` 與 `lng`；`driverId` 由 JWT 決定，`timestamp` 由後端使用 Asia/Taipei 時間產生。

## 常見錯誤

| 狀態 | 處理方式 |
|---|---|
| `Failed to fetch` | 確認後端在 `8080`、頁面在 `localhost:4204`，且 API Base URL 正確。 |
| HTTP `401` | Token 無效或過期，請登出後重新登入。 |
| HTTP `403` | Token 角色不是 `DRIVER`；管理員 Token 不能上傳司機 GPS。 |
| HTTP `400`：不是可上傳 GPS 的工作狀態 | 先確認今天有已發布的 WORK 班表，再完成上班打卡。 |
| 定位權限被拒絕 | 在瀏覽器網站權限中允許「位置」，再重新整理。 |
| 取得定位逾時 | 開啟作業系統定位服務，或改用手動座標／搖桿。 |
| Hydration failed | 強制重新整理；最新版已將瀏覽器 Session 資料延後到水合後載入。 |

## 座標限制

- 緯度 `lat`：`-90` 到 `90`
- 經度 `lng`：`-180` 到 `180`
- `accuracy`、`heading`、`speed`、`altitude` 目前不會傳到後端

## 開發檢查

```powershell
pnpm lint
pnpm build
```

pnpm scripts 無法執行時，可使用：

```powershell
node .\node_modules\eslint\bin\eslint.js . --ignore-pattern dist --ignore-pattern .next
node .\node_modules\vinext\dist\cli.js build
```

## 目錄結構

```text
gps-api-tester/
├─ app/
│  ├─ page.tsx       定位、登入、搖桿與 API 呼叫
│  ├─ globals.css    畫面樣式與響應式配置
│  └─ layout.tsx     頁面 Metadata
├─ public/og.png     社群預覽圖片
├─ package.json
├─ pnpm-lock.yaml
├─ vite.config.ts    固定使用 localhost:4204
└─ README.md
```

## 安全與隱私

- 密碼只用於登入 API，不會保存到 Local Storage。
- Driver Token 只保存在目前瀏覽器的 Session Storage。
- GPS 是敏感資訊，不要在截圖、Commit 或 Issue 中貼正式環境帳密、Token 或不必要的真實軌跡。
- 本工具只供開發測試，不應直接公開連接正式環境後端。

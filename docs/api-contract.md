# API 契約

所有回應統一格式：成功回傳 `data`，失敗回傳 `{ error: { code, message } }`。

## 主檔

### 門市

```
GET    /api/stores
POST   /api/stores
PUT    /api/stores/{id}
DELETE /api/stores/{id}
```

POST /api/stores — Request:
```json
{
  "storeCode": "TN001",
  "name": "東區門市",
  "address": "...",
  "lat": 22.99,
  "lng": 120.22,
  "contactName": "王小明",
  "phone": "...",
  "receivingStart": "09:00",
  "receivingEnd": "18:00",
  "notes": "",
  "regionId": 1
}
```

POST /api/stores — Response:
```json
{
  "id": 1,
  "storeCode": "TN001",
  "status": "ACTIVE"
}
```

### 地址轉座標（後端代理 Nominatim）

```
GET /api/geocode?q=台南市東區...
```

Response:
```json
{
  "results": [
    { "lat": 22.99, "lng": 120.22 }
  ]
}
```

### 車輛

```
GET/POST/PUT/DELETE /api/vehicles
```

### 司機

```
GET/POST/PUT/DELETE /api/drivers
```

## 訂單匯入

### 驗證預覽（不寫入）

```
POST /api/orders/import/validate
```

Request:
```json
{
  "rows": [
    {
      "storeCode": "TN001",
      "sourceVendor": "商家A",
      "itemDescription": "民生用品",
      "boxCount": 10,
      "volume": 1.2,
      "notes": ""
    }
  ]
}
```

Response:
```json
{
  "valid": [
    { "row": 0, "storeId": 1, "mergedInto": null }
  ],
  "invalid": [
    { "row": 3, "reason": "門市代碼不存在" }
  ]
}
```

### 確認寫入

```
POST /api/orders/import/confirm
```

Request（`rows` 只放上一步驗證通過的部分）：
```json
{
  "rows": [],
  "deliveryDate": "2026-09-21"
}
```

Response:
```json
{
  "createdCount": 12,
  "orderIds": [101, 102]
}
```

### 手動建立單筆

```
POST /api/orders
```

## 訂單審核

```
GET   /api/orders?date=2026-09-21&status=PENDING_CONFIRM
PATCH /api/orders/{id}
```

PATCH Request（`action` 可為 `CONFIRM` / `CANCEL`）：
```json
{
  "action": "CONFIRM",
  "modifiedFields": {},
  "reason": ""
}
```

不做商店端，訂單全由後台建立／匯入，審核只是資料品質關卡，不需要「退回補件（MODIFY）」這種等外部補件的中間狀態；發現問題就直接修欄位或取消（CANCEL）。

## 排車

### 執行排車

```
POST /api/dispatch/optimize
```

一次排一個倉庫。要排多個倉庫就分別呼叫，各倉獨立最佳化。

Request:
```json
{
  "date": "2026-09-21",
  "warehouseId": 1,
  "vehicleIds": [1, 2, 3, 4]
}
```

Response:
```json
{
  "routes": [
    {
      "vehicleId": 1,
      "driverId": null,
      "stops": [
        { "orderId": 101, "storeId": 1, "sequence": 1 }
      ],
      "totalDistance": 12000,
      "estimatedFuelCost": null,
      "estimatedWorkMinutes": null,
      "loadRate": 0.87
    }
  ],
  "unassignedOrderIds": [110]
}
```

行為說明：

- 只處理 `deliveryDate = date`、`status = CONFIRMED`、`warehouseId` 相符的訂單
- 排進路線的訂單狀態改為 `SCHEDULED`，並寫入 `routeId`、`sequence`、`assignedVehicleId`
- 裝不下的訂單放在 `unassignedOrderIds`，狀態維持 `CONFIRMED`，下次排程仍會被撈到
- 路線以 `status = DRAFT` 存入 `routes` 表；`driverId` 為 null，司機由主管另行手動指派
- 沒有被排到訂單的車輛不會產生路線，也不會出現在 `routes` 裡

目前尚未實作的欄位（皆回傳 null）：

| 欄位 | 缺什麼 |
|---|---|
| `driverId` | 待手動指派功能 |
| `estimatedFuelCost` | 系統尚無油價設定，只有車輛的 `fuelConsumption`（公里／公升） |
| `estimatedWorkMinutes` | 需改 `OsrmClient` 一併取回 OSRM 的 `durations` 矩陣 |

### 取得當日調度看板狀態

```
GET /api/dispatch/board?date=2026-09-21
```

Response 結構與「執行排車」的 `routes` 相同，並附上 `unassigned`：
```json
{
  "unassigned": [],
  "routes": []
}
```

### 拖曳改派（整包送、整包回）

```
POST /api/dispatch/reassign
```

Request（`routes` 為完整的當日分派狀態）：
```json
{
  "date": "2026-09-21",
  "routes": []
}
```

Response:
```json
{
  "routes": [],
  "diff": {
    "totalDistance": { "before": 182000, "after": 179500 },
    "loadRate": { "before": 0.87, "after": 0.91 }
  }
}
```

### 發布前檢查

```
GET /api/dispatch/publish-check?date=2026-09-21
```

Response:
```json
{
  "passed": false,
  "issues": [
    { "vehicleId": 2, "reason": "超出容量上限" }
  ]
}
```

### 發布

```
POST /api/dispatch/publish
```

Request:
```json
{ "date": "2026-09-21" }
```

Response:
```json
{
  "version": 1,
  "publishedAt": "..."
}
```

## 司機端

```
GET  /api/driver/tasks/today
POST /api/driver/loading
POST /api/driver/gps
POST /api/driver/arrive
POST /api/driver/deliver
POST /api/driver/no-signature
POST /api/driver/exception
POST /api/driver/mileage/start
POST /api/driver/mileage/end
```

| 端點 | Request body |
|---|---|
| `/api/driver/loading` | `{ orderId, loadedBoxCount, notes }`：倉庫點交，一次一張單。箱數相符轉 `LOADED`；不符則原單 `FAILED`，並建立異常單與明日補送單 |
| `/api/driver/gps` | `{ lat, lng }` |
| `/api/driver/arrive` | `{ orderId }`：訂單須為 `LOADED`（已點交） |
| `/api/driver/deliver` | `{ orderId, boxCount, notes, photoUrl }`：司機確認交貨，不登記貨物損毀；損毀由門市回報。舊版相容欄位 `damagedBoxCount` 僅接受省略或 `0`，大於 `0` 回傳 `400`，不建立配送損毀異常 |
| `/api/driver/no-signature` | `{ orderId, photo }` |
| `/api/driver/exception` | `{ category, description }` |
| `/api/driver/mileage/start` | `{ odometer }` |
| `/api/driver/mileage/end` | `{ odometer }` |

## 即時車隊與異常

```
GET   /api/fleet/live?date=2026-09-21
GET   /api/exceptions?status=OPEN
PATCH /api/exceptions/{id}
```

PATCH /api/exceptions/{id} — Request:
```json
{
  "resolution": "",
  "handledBy": ""
}
```

## 報表

### 發車前檢點表

`GET /api/reports/pre-trip` 僅主管可查詢，使用報表共用的 `date` 或 `period/from/to`，
支援 `warehouseId`、`storeId`（路線所屬門市）、`driverId`、`vehicleId`／`vehicleIds`。
回傳 `{ from, to, inspections }`，每次提交各一筆，包含未通過、重檢與已作廢紀錄，不顯示未來日期或未來提交時間。
各列提供檢查時間、人車與路線識別、酒測值、15 項檢點結果、異常項目、備註、作廢時間及照片存在旗標。
車輛與司機篩選依檢查時保存的 ID；舊路線刪除仍保留檢查，但無法還原倉庫、門市時不推定歷史歸屬。
`checks[].normal` 是 `true`（正常）、`false`（異常）或 `null`（未記錄），不補假值。

`GET /api/reports/pre-trip/{inspectionId}/photos/{alcohol|fault}` 僅主管可讀，
透過登入驗證讀取酒測器或車況異常照片，回應 `Cache-Control: no-store`，不公開檔名或儲存路徑。
歷史報表 Excel 同時包含「發車前檢點表」與「檢點項目明細」兩張工作表。

```
GET /api/reports/summary?date=2026-09-21
```

Response:
```json
{
  "planned": { "distance": 182000, "fuelCost": 900, "workMinutes": 960, "loadRate": 0.87 },
  "actual": { "distance": 175300, "fuelCost": 860, "workMinutes": 1010, "loadRate": 0.85 }
}
```

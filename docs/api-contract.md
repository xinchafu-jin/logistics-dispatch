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
| `/api/driver/loading` | `{ orderId, loadedBoxCount, notes, items }`：倉庫點交，一次一張單。`notes` 是選填整單點交備註，最多 500 字；相符與不符都保存在原單的 `loadingNotes`，不覆寫建單或商品備註。箱數／商品相符轉 `LOADED`；不符則原單 `FAILED`，並建立異常與待主管確認的重建單 |
| `/api/driver/loading/mismatch` | `{ orderId, items: [{ orderItemId, loadedQuantity, mismatchReported }], notes }`：每項商品都填實點數量，至少一項 `mismatchReported: true`；其餘數量需與應點相符，全部商品的清點結果都存入原單供歷史查看。舊版 `orderItemId`／`orderItemIds` 或未帶 `mismatchReported` 的逐項請求仍相容，未提供的實點數量不推測。`notes` 同時保存在原單點交紀錄與異常原因中；新重建單不繼承前次點交備註 |
| `/api/driver/gps` | `{ lat, lng }` |
| `/api/driver/arrive` | `{ orderId }`：訂單須為 `LOADED`（已點交） |
| `/api/driver/deliver` | `{ orderId, boxCount, notes, photo }` |
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

無人簽收會先建立隔日待確認重送單並在異常中心顯示；隔日台北時間 06:00 由系統自動轉為 `CONFIRMED`，進入對應日期的待排車看板，案件記錄系統處理結果。主管不需、也不能對無人簽收呼叫 `PATCH /api/exceptions/{id}/confirm`；此端點仍用於其他需人工確認的配送異常。若 06:00 後端未運行，復機後會補掃並以補掃當天為配送日（已發布日期會順延）。

無人簽收重送單等待自動處理期間，普通訂單的確認、修改與刪除 API 也會拒絕操作；拖曳看板只顯示自動處理時間，不提供人工確認按鈕，審單頁也標示待自動排車並隱藏人工操作。原單及異常歷史保留。

配送日結束後，仍是 `PENDING_CONFIRM` 或 `CONFIRMED`、尚未進入點交的訂單會補掃成一筆 `UNSETTLED_ORDER` 異常，另建整單 `PENDING_CONFIRM` 後續單。來源單與原日期先保留供主管查核；主管在異常中心確認後，來源單改為 `FAILED` 並保留歷史路線，後續單改為確認當天的 `CONFIRMED` 待排單。排程每分鐘補掃，異常中心查詢也補掃停機期間的舊單；已有異常的原單不重複建案。後續單不能由一般審單 API 搶先確認、修改或刪除。

## 報表

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

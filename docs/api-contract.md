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

POST /api/stores
```json
Request:
{ "storeCode": "TN001", "name": "東區門市", "address": "...",
  "lat": 22.99, "lng": 120.22, "contactName": "王小明", "phone": "...",
  "receivingStart": "09:00", "receivingEnd": "18:00", "notes": "", "regionId": 1 }

Response:
{ "id": 1, "storeCode": "TN001", "...": "同上", "status": "ACTIVE" }
```

### 地址轉座標（後端代理 Nominatim）
```
GET /api/geocode?q=台南市東區...
```
```json
Response: { "results": [{ "lat": 22.99, "lng": 120.22 }] }
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
```json
Request:
{ "rows": [
  { "storeCode": "TN001", "sourceVendor": "商家A", "itemDescription": "民生用品",
    "boxCount": 10, "volume": 1.2, "notes": "" }
] }

Response:
{ "valid": [{ "row": 0, "storeId": 1, "mergedInto": null }],
  "invalid": [{ "row": 3, "reason": "門市代碼不存在" }] }
```

### 確認寫入
```
POST /api/orders/import/confirm
```
```json
Request: { "rows": [ "...同上，僅 valid 的部分..." ], "deliveryDate": "2026-09-21" }
Response: { "createdCount": 12, "orderIds": [101, 102] }
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
```json
Request: { "action": "CONFIRM", "modifiedFields": {}, "reason": "" }
```
`action` 可為 `CONFIRM` / `MODIFY` / `REJECT` / `CANCEL`

## 排車

### 執行排車
```
POST /api/dispatch/optimize
```
```json
Request: { "date": "2026-09-21", "vehicleIds": [1, 2, 3, 4] }

Response:
{ "routes": [
  { "vehicleId": 1, "driverId": 1,
    "stops": [{ "orderId": 101, "storeId": 1, "sequence": 1 }],
    "totalDistance": 12000, "estimatedFuelCost": 150,
    "estimatedWorkMinutes": 240, "loadRate": 0.87 }
], "unassignedOrderIds": [110] }
```

### 取得當日調度看板狀態
```
GET /api/dispatch/board?date=2026-09-21
```
```json
Response: { "unassigned": [], "routes": [ "...同上結構..." ] }
```

### 拖曳改派（整包送、整包回）
```
POST /api/dispatch/reassign
```
```json
Request: { "date": "2026-09-21", "routes": [ "...完整當日分派狀態..." ] }

Response:
{ "routes": [ "...重算後的完整狀態..." ],
  "diff": {
    "totalDistance": { "before": 182000, "after": 179500 },
    "loadRate": { "before": 0.87, "after": 0.91 }
  } }
```

### 發布前檢查
```
GET /api/dispatch/publish-check?date=2026-09-21
```
```json
Response: { "passed": false, "issues": [
  { "vehicleId": 2, "reason": "超出容量上限" }
] }
```

### 發布
```
POST /api/dispatch/publish
```
```json
Request: { "date": "2026-09-21" }
Response: { "version": 1, "publishedAt": "..." }
```

## 司機端

```
GET  /api/driver/tasks/today
POST /api/driver/handover        { routeId, items: [{ orderId, expectedBoxCount, actualBoxCount }] }
POST /api/driver/gps             { lat, lng }
POST /api/driver/arrive          { orderId }
POST /api/driver/deliver         { orderId, boxCount, notes, photo }
POST /api/driver/no-signature    { orderId, photo }
POST /api/driver/exception       { category, description }
POST /api/driver/mileage/start   { odometer }
POST /api/driver/mileage/end     { odometer }
```

## 即時車隊與異常

```
GET   /api/fleet/live?date=2026-09-21
GET   /api/exceptions?status=OPEN
PATCH /api/exceptions/{id}        { resolution, handledBy }
```

## 報表

```
GET /api/reports/summary?date=2026-09-21
```
```json
Response: {
  "planned": { "distance": 182000, "fuelCost": 900, "workMinutes": 960, "loadRate": 0.87 },
  "actual":  { "distance": 175300, "fuelCost": 860, "workMinutes": 1010, "loadRate": 0.85 }
}
```

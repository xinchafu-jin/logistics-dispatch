# 資料模型

## 倉庫 Warehouse

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| warehouseCode | String | 倉庫代碼，唯一 |
| name | String | 名稱 |
| address | String | 地址 |
| lat, lng | Double | 座標（排車路線的起訖點 depot）|
| phone | String | 電話 |
| isActive | Boolean | 是否啟用 |

V1 僅一筆資料，但保留為獨立資料表以支援未來多倉。

## 門市 Store

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| storeCode | String | 門市代碼，唯一，Excel 匯入比對用 |
| name | String | 門市名稱 |
| address | String | 地址（顯示用，不參與計算）|
| lat, lng | Double | 座標（排車與地圖顯示用）|
| contactName | String | 聯絡人 |
| phone | String | 電話 |
| receivingStart, receivingEnd | Time | 收貨時段 |
| notes | String | 卸貨限制／備註 |
| status | Enum | ACTIVE / SUSPENDED |

## 車輛 Vehicle

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| warehouseId | Long | 所屬倉庫，車輛隸屬於單一倉庫，排車時只會使用該倉的車 |
| plateNumber | String | 車牌 |
| vehicleType | String | 車型 |
| capacity | Integer | **可裝箱數**（容量單位統一用「箱」，箱子規格一致）|
| fuelConsumption | Double | 平均油耗 |
| status | Enum | AVAILABLE / MAINTENANCE / RETIRED |

「配送中」不存成狀態值，由當日 Route 推導，避免需要手動同步而卡住。

車輛隸屬於單一倉庫。排車若未指定車輛，後端會自動取該倉所有 `AVAILABLE` 的車當候選車池，由 OR-Tools 決定實際出幾台、各跑哪些點；沒被用到的車不會出現在排車結果中。司機有行政上的所屬倉庫，但派車時是全公司共用。

## 司機 Driver

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| warehouseId | Long? | 目前所屬倉庫，用於人事資料與篩選；舊司機可能未設定，不限制派車 |
| account | String | 員工編號或手機號碼（登入帳號）|
| name | String | 姓名 |
| phone | String | 電話 |
| workStart, workEnd | Time | 工作起訖時間 |
| restDuration | Integer | 休息時長（分鐘，後台固定值）|
| maxOvertimeMinutes | Integer | 加班上限 |
| isActive | Boolean | 在職狀態 |

**所屬倉庫不是派車限制**。新建司機須設定所屬倉庫；舊資料可能為空。車輛屬於出貨倉庫（`Vehicle.warehouseId`，排車時會驗「車輛不跨倉」），司機則可由調度員指派到任何出貨倉庫的路線，接該倉的訂單。

所屬倉庫變更不會改寫既有路線；只要司機仍在職、當天班表允許且未被其他路線佔用，就能繼續執行原任務。

代價是「一位司機一天只開一條路線」（`uk_routes_date_driver`）變成**跨倉的約束**，而調度看板是按倉切的。因此凡是要列出可指派司機的地方，都必須額外查當天其他倉的佔用狀況，不能只看當前倉的路線：

- `GET /api/dispatch/board` 回應帶 `driversTakenElsewhere`，讓前端把別倉已用的司機標成不可選
- `POST /api/dispatch/reassign` 驗證時要查當天全部路線，不能只檢查同一個請求內部

## 訂單 Order

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| orderNumber | String | 訂單編號（系統產生，如 DO-20260921-00125）|
| storeId | Long | 門市 |
| warehouseId | Long | 出貨倉庫，排車以「日期 + 倉庫」為單位各自最佳化 |
| sourceVendor | String | 來源商家 |
| itemDescription | String | 品項描述 |
| boxCount | Integer | **箱數，系統的唯一容量單位**（排車、交貨、異常皆以箱計）|
| notes | String | 備註 |
| loadingNotes | String | 此次整單點交備註，最多 500 字；V17 新增，與建單／逐商品備註分開，舊資料維持空值 |
| deliveryDate | Date | 配送日期 |
| status | Enum | PENDING_CONFIRM / CONFIRMED / SCHEDULED / PUBLISHED / IN_DELIVERY / COMPLETED / CANCELLED / FAILED |
| routeId | Long | 所屬配送計畫 |
| assignedVehicleId | Long | 指派車輛 |
| assignedDriverId | Long | 指派司機 |
| sequence | Integer | 建議配送順序（司機可自主調整，此為起點值）|
| createdAt, updatedAt | DateTime | |

**一張訂單 = 某商家給某門市某天的一批貨。** 同一門市同一天可有多張訂單（來自不同商家），排車時合併為同一個配送站。箱內混裝多種商品，`itemDescription` 僅為備註，不參與運算。

容量以「箱」為單位：訂單需求 = `boxCount`，車輛容量 = `Vehicle.capacity`（可裝箱數），兩邊同單位直接比對，不經過體積換算。OR-Tools 的容量維度只吃整數，箱數天生就是整數，不需轉換。

## 配送計畫 Route

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| date | Date | 配送日期 |
| warehouseId | Long | 出發與返回的倉庫（depot）|
| vehicleId | Long | 車輛 |
| driverId | Long | 司機 |
| totalDistance | Double | 總里程（公尺）|
| estimatedFuelCost | Double | 預估油耗成本 |
| estimatedWorkMinutes | Integer | 預估總工時 |
| loadRate | Double | 平均裝載率 |
| status | Enum | DRAFT / PUBLISHED |
| version | Integer | 發布後每次異動 +1 |

本趟包含哪些訂單，由 `Order.routeId` 指向此表，Route 本身不存訂單清單。

## 交貨紀錄 DeliveryRecord

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| orderId | Long | 對應訂單 |
| arrivedAt | DateTime | 抵達時間 |
| deliveredAt | DateTime | 交貨時間 |
| lat, lng | Double | 交貨地點 GPS |
| deliveredBoxCount | Integer | 交貨箱數 |
| photoUrl | String | 交貨照片 |
| notes | String | 備註 |
| noSignature | Boolean | 是否為無人簽收 |

## 異常案件 ExceptionCase

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| orderId | Long | 關聯訂單（可為空）|
| type | Enum | NO_SIGNATURE / DRIVER_REPORT / PHONE_HANDLED |
| description | String | 說明 |
| createdAt | DateTime | |
| handledBy | String | 處理人 |
| handledAt | DateTime | 處理時間 |
| resolution | String | 處理方式 |
| status | Enum | OPEN / CLOSED |

## GPS 軌跡 GpsPing

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| driverId | Long | |
| lat, lng | Double | |
| timestamp | DateTime | |

保存期限：3 個月，期滿刪除或去識別化。

## 里程紀錄 MileageLog

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| driverId | Long | |
| date | Date | |
| startOdometer, endOdometer | Integer | 出車／收工里程表讀數 |
| startTime, endTime | DateTime | 出車／收工時間（實際工時來源）|

## 距離矩陣快取 DistanceMatrixCache

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| fromType, toType | Enum | WAREHOUSE / STORE |
| fromId, toId | Long | 對應類型的 id |
| distance | Integer | 公尺 |
| duration | Integer | 秒 |
| updatedAt | DateTime | 新增／修改座標時觸發重算 |

起訖點以「類型 + id」識別，倉庫與門市的編號空間各自獨立。`(fromType, fromId, toType, toId)` 為唯一鍵。

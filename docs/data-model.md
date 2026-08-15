# 資料模型

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
| regionId | Long | 常態負責區域（對應 Region）|
| status | Enum | ACTIVE / SUSPENDED |

## 車輛 Vehicle

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| plateNumber | String | 車牌 |
| vehicleType | String | 車型 |
| capacity | Double | 可用容量（立方公尺）|
| fuelConsumption | Double | 平均油耗 |
| maxTripsPerDay | Integer | 趟次上限 |
| status | Enum | AVAILABLE / IN_DELIVERY / MAINTENANCE / RETIRED |
| assignedDriverId | Long | 常態配對司機（模式1）|

## 司機 Driver

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| account | String | 員工編號或手機號碼（登入帳號）|
| name | String | 姓名 |
| phone | String | 電話 |
| workStart, workEnd | Time | 工作起訖時間 |
| restDuration | Integer | 休息時長（分鐘，後台固定值）|
| maxOvertimeMinutes | Integer | 加班上限 |
| regionId | Long | 常態負責區域 |
| isActive | Boolean | 在職狀態 |

## 訂單 Order

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| orderNumber | String | 訂單編號（系統產生，如 DO-20260921-00125）|
| storeId | Long | 門市 |
| sourceVendor | String | 來源商家 |
| itemDescription | String | 品項描述 |
| boxCount | Integer | 箱數 |
| volume | Double | 體積小計（立方公尺，由匯入資料直接提供）|
| notes | String | 備註 |
| deliveryDate | Date | 配送日期 |
| status | Enum | PENDING_CONFIRM / CONFIRMED / SCHEDULED / PUBLISHED / LOADED / IN_DELIVERY / COMPLETED / CANCELLED / FAILED |
| assignedVehicleId | Long | 指派車輛 |
| assignedDriverId | Long | 指派司機 |
| sequence | Integer | 建議配送順序（司機可自主調整，此為起點值）|
| createdAt, updatedAt | DateTime | |

## 配送計畫 Route

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| date | Date | 配送日期 |
| vehicleId | Long | 車輛 |
| driverId | Long | 司機 |
| orderIds | Long[] | 本趟訂單清單 |
| totalDistance | Double | 總里程（公尺）|
| estimatedFuelCost | Double | 預估油耗成本 |
| estimatedWorkMinutes | Integer | 預估總工時 |
| loadRate | Double | 平均裝載率 |
| status | Enum | DRAFT / PUBLISHED |
| version | Integer | 發布後每次異動 +1 |

## 點交紀錄 HandoverRecord

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| routeId | Long | 對應配送計畫 |
| expectedBoxCount | Integer | 應裝箱數 |
| actualBoxCount | Integer | 實裝箱數 |
| hasDiscrepancy | Boolean | 是否有落差 |
| createdAt | DateTime | |

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
| type | Enum | NO_SIGNATURE / HANDOVER_MISMATCH / DRIVER_REPORT / PHONE_HANDLED |
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
| fromId, toId | Long | 門市或倉庫 id |
| distance | Integer | 公尺 |
| duration | Integer | 秒 |
| updatedAt | DateTime | 新增/修改門市座標時觸發重算 |

## Region（區域，2-6 模式1 用）

| 欄位 | 型別 | 說明 |
|---|---|---|
| id | Long | 主鍵 |
| name | String | 區域名稱 |

# 點交前安全檢查與實際加班

## 司機流程

主管發布任務 → 司機在「今日任務」填寫安全檢查 → 後端確認通過 → 開放倉庫點交與出車里程起登。

- 酒測：勾選本人已測、填呼氣酒精濃度（mg/L），不預填 0；目前採零酒精出車的系統規則，不宣稱為法定門檻。
- 車輛：頭燈、尾燈、方向燈、煞車燈逐項確認；左前、右前、左後、右後四輪逐項確認有氣且無破胎。
- 行車紀錄器：確認正常錄影。
- 酒測結果、車輛檢點、行車紀錄器各附一张照片，支援 JPG／PNG／WebP，每張最多 5 MB。
- 若有車况異常，不能勾選正常，應通知主管撤回或改派；非零酒測提交會保存不通過紀錄，不能點交或出車。

不能只靠前端解鎖。`DeliveryService.load`、`MileageLogsService.start` 都驗證當次有效的通過紀錄，並與撤回共用路線鎖。
已點交的既有任務不會因此停止抵達／交貨；未點交與新出車必須通過檢查。

## 紀錄與撤回

每次提交新增一筆稽核紀錄，保存司機、車輛、路線、版本、日期、勾選、實測濃度、提交時間與三張照片。最新有效紀錄決定是否放行，不以舊通過結果覆蓋後來的非零測值。

只做安全檢查、未點交且未登記出車里程的任務可撤回。撤回使檢查作廢但不刪稽核紀錄；重新發布、改派人車或版本變動後必須重新檢查。已點交或已有里程紀錄不能一般撤回，仍走既有交接流程。

照片位於 `${app.storage.pre-trip-dir:uploads/pre-trip}`，不設公開網址。本人登入後透過 `/api/driver/pre-trip/{id}/photos/{kind}` 讀取，回應禁止快取。交易失敗與測試 rollback 會清理尚未提交的照片。

新增 migration：`db/migration/drivers/V19__pre_trip_inspections.sql`，沒有修改既有 migration。
本機舊版本链重複且停用 Flyway，已使用 `scripts/ApplyPreTripSchema.java` 只新增檢查表，不變更既有資料或 Flyway 歷史。其他環境先確認版本链再套用；不要未經確認執行重複遷移。

## 加班欄位

人車資源的司機卡顯示「本月已加班」，累加本月至今實際打卡的加班分鐘，不顯示可加班上限。沿用既有每滿 30 分鐘計入的規則，扣除重疊休息時間；本月無打卡為 0。排班的上限設定保留作調度使用。資料讀取失敗／舊後端未提供欄位時顯示「—」，不把未知當作 0。跨年等歷史區間應由主管到歷史查詢查看。

## API

- `GET /api/driver/pre-trip?routeId=...`：今天本人已發布任務的最新有效狀態。
- `POST /api/driver/pre-trip`：multipart 的 `request` JSON 與 `alcoholPhoto`、`vehiclePhoto`、`dashcamPhoto` 必填。
- `GET /api/driver/pre-trip/{id}/photos/alcohol|vehicle|dashcam`：本人照片。

這些入口沿用 DRIVER 角色權限與 JWT userId，不接受前端自選司機 ID。

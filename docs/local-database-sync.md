# 本機分支與資料庫同步

## 2026-09-29 同步內容

`feature/major-jin` 的 `ba9d7fb` 已合入 `feature/major` 的營運報表、歷史查詢、累積異常及商品點交不符功能，並保留 JIN 共用 UI。`6adc6c3` 補上正式追蹤的 V16 migration；這次不重做合併、不改原本 MAJOR 的提交。

已驗證的版本也同步至本機 `feature/major-new`；它原先是此版本的祖先，因此使用快轉、不重寫提交歷史。目前工作目錄保持 `feature/major-jin`，本次補齊不推送遠端。

本機 MySQL 原先已執行舊檔名 `V16__loading_item_mismatch.sql`，新增的欄位與分支上的 `V16__order_items_loading_mismatch_reported.sql` 相同：`order_items.loading_mismatch_reported BIT(1) NOT NULL DEFAULT b'0'`。

這次先完整備份，確認只有已知 V16 的描述／checksum 差異、沒有失敗或其他待修版本，再使用 Flyway repair 對齊這一筆歷程。保留原先 script 檔名作為實際執行來源，不重新執行 DDL、不冒充其他分支的遷移。修復期間以 READ locks 保護 41 張原資料表；修復前後逐欄資料指紋完全相同。Flyway 驗證通過，目前 32 個 entity 的 Hibernate schema validation 也通過。

備份與稽核收據放在專案外：

- `../logistics-dispatch-db-backups/branch-sync-20260929-110300/`：V16 修復前完整 SQL、SHA-256 與原表指紋收據。
- `../logistics-dispatch-db-backups/loading-demo-20260929-110500/`：新增示範資料前完整 SQL、SHA-256 與新增 ID 清單。

這些檔案含本機私有資料，不得提交 Git。分支只包含 migration、工具、測試與說明；Git 同步不會自動同步或分享任何人的實際資料庫。

## 示範商品點交

經使用者確認，另新增 **2026-09-27** 的獨立示範批次，涵蓋 6 個倉庫：12 張訂單、36 筆商品明細、6 筆正常交貨紀錄、6 筆已結案點交不符案件。

- 訂單號以 `DEMO-LOAD-RPT-20260927-` 開頭，商品、來源及備註均明確標示「示範」。
- 商品為飲用水、麵包、鮮乳，單位皆為箱，商品應點箱數合計等於訂單箱數。
- 正常案例皆已點交並完成；不符案例的鮮乳應點 3 箱、實點 2 箱，並記錄點交不符旗標。
- 所有示範訂單皆在過去日期且不指派真實司機、車輛或路線。不增加今日待排單或尚待處理案件。
- 既有訂單、點交、交貨與案件資料逐欄指紋核對不變；原先其他日期的示範批次也不覆寫。同批次重跑只驗證並略過。

在歷史查詢選 **2026-09-27**、全部倉庫，可查看這批商品點交與異常明細。原始歷史單若未曾記錄實點數量，仍保持缺漏，不偽造成相符。

## 可重用的本機檢查工具

從 `backend` 目錄使用 Java 21。先選定唯一時間戳及專案外備份目錄，再執行：

```powershell
.\gradlew.bat -I ../scripts/local-major-database.gradle localMajorV16 -PdbMode=v16-inspect -PdbTag=YYYYMMDD_HHMMSS -PdbBackup=外部專用備份目錄
.\gradlew.bat -I ../scripts/local-major-database.gradle localMajorV16 -PdbMode=schema-validate -PdbTag=YYYYMMDD_HHMMSS -PdbBackup=外部專用備份目錄
```

`v16-inspect` 只檢查。`schema-validate` 只驗證目前 entity／資料庫結構，不啟動 Spring、排程或 DDL 更新。

只有已知本機 V15 baseline + 舊 V16 的等價差異才允許手動指定 `-PdbMode=v16-align`；工具核對原 checksum、成功狀態、實際欄位、正式 V16 內容指紋及所有驗證錯誤，拒絕其他分支或未檢查的歷程修復。有進行中的 transaction 時不套用。結構與歷程已一致時直接略過，不再備份或修改。

示範資料工具另行、明確執行，不放進 migration 或應用啟動流程：

```powershell
.\gradlew.bat -I ../scripts/local-major-database.gradle localLoadingDemo -PdemoMode=inspect -PdemoDate=2026-09-27 -PdbBackup=外部專用備份目錄
.\gradlew.bat -I ../scripts/local-major-database.gradle localLoadingDemo -PdemoMode=seed -PdemoDate=2026-09-27 -PdbBackup=外部專用備份目錄
```

示範日期必須在過去，備份目錄必須是 `../logistics-dispatch-db-backups/` 下的專用子目錄。新增前備份、交易內核對原資料指紋；不完整的既有批次會報錯，不補寫或刪除原資料。

仍保留 2026-08-26 車輛 ID 2 的路線 ID 2 與 3，以及已知歷史唯一限制例外，等待使用者決定，不自行整理或刪單。

## 驗證

- 主管前端 200 個、司機前端 105 個測試通過；兩個前端正式建置通過。
- 後端點交、異常重建日期、跨倉司機及報表等 81 個核心測試通過；bootJar 建置通過。
- Flyway 驗證、32 個 entity 結構驗證、示範批次完整性與重跑不重複驗證通過。
- 修正合併後司機請假歷史引用不存在的 `reason`，改用 API 的 `requestReason`，並補實際模板回歸測試。

-- 司機例外回報案件（司機端的「支援中心」）。
--
-- 流程：司機在 App 選分類回報 → 後台在異常中心按「接收」→ 在聊天室溝通 → 回異常中心填處理結果結案。
-- 案件放在 exception_cases（type = DRIVER_REPORT，狀態一樣只有 OPEN／CLOSED），不另外開表：
-- 異常中心本來就查這張表，處理人、處理時間、處理結果的欄位也現成。
-- 案件不改訂單狀態：無人簽收、交貨短少破損、點交不符，仍然走各自的按鈕。
--
-- 一、exception_cases 加司機回報要用的欄位；其他類型的異常，這幾欄都是 NULL
--   driver_id          誰回報的。舊的 DRIVER_REPORT 只存 order_id，訂單改派之後就查不到是誰回報
--   route_id           回報當下，這位司機今天已發布的路線；今天沒排路線是 NULL
--   category           分類，對照 DriverCaseCategory。用 VARCHAR 不用 ENUM：之後加分類不用再寫 migration
--   can_continue       司機說還能不能繼續配送；後台清單把不能繼續的排前面
--   photo_url          司機附的照片，只收交貨照片上傳 API 回傳的網址；長度跟 delivery_records.photo_url 一樣
--   accepted_admin_id  在異常中心按「接收」的管理員；NULL＝還沒有人接收（鈴鐺只列這種）
--   accepted_at        接收時間；之後報表可以算「建立到接收」花了多久
--
-- 二、driver_messages 加 exception_case_id：案件對話沿用聊天室的表，一件案件一串，NULL＝一般對話。
--   一般對話的查詢都要加 exception_case_id IS NULL，不然案件訊息會混進一般對話，紅點也會把案件未讀算進去。
--
-- 都不設外鍵，跟 route_deviations 的 driver_id、pre_trip_inspections 的 route_id 一樣：
-- 案件是紀錄，撤回發布後重排可能刪掉草稿路線，案件仍要留著。

ALTER TABLE exception_cases
    ADD COLUMN driver_id         BIGINT       DEFAULT NULL,
    ADD COLUMN route_id          BIGINT       DEFAULT NULL,
    ADD COLUMN category          VARCHAR(30)  DEFAULT NULL,
    ADD COLUMN can_continue      BIT(1)       DEFAULT NULL,
    ADD COLUMN photo_url         VARCHAR(500) DEFAULT NULL,
    ADD COLUMN accepted_admin_id BIGINT       DEFAULT NULL,
    ADD COLUMN accepted_at       DATETIME(6)  DEFAULT NULL,
    -- 司機端「我的案件」：某位司機、某個狀態
    ADD KEY idx_exception_cases_driver_status (driver_id, status),
    -- 異常中心「司機回報」：某個類型、某個狀態
    ADD KEY idx_exception_cases_type_status (type, status);

ALTER TABLE driver_messages
    ADD COLUMN exception_case_id BIGINT DEFAULT NULL,
    -- 一般對話和案件對話都是「某位司機、某一串、照 id 往後讀」
    ADD KEY idx_driver_messages_driver_case (driver_id, exception_case_id, id);

-- 偏離預定路線的紀錄：司機確定開出路線（連續 3 筆離目前這一段超過 200 公尺）時新增一筆，
-- 偏離 10 分鐘還沒結束記下升級時間，回到路線或停止比對（交貨、休息、收車、下班）時記下結束時間與原因。
--
-- 用途：
--   1. 後台警報：ended_at 是 NULL 的就是進行中；escalated_at 有值是「警報」，沒有是「提示」
--   2. 之後的報表：誰常偏離、偏離多久、偏離完去了哪（結束原因）
--
-- 「連續第幾筆超過 200 公尺」這種 30 秒內的暫時計數放在記憶體（RouteDeviationTracker），這張表只存確定的事件。
-- 後端重啟時記憶體歸零，還沒結束的紀錄就是「誰正在偏離」的依據：不會重複開一筆，也不會永遠結束不了。
--
-- route_id 設 ON DELETE CASCADE，理由同 V8：刪草稿路線時不會因為還有紀錄參照而刪不掉。
-- driver_id 不設外鍵，跟 gps_pings、route_leg_mileages 一樣。

CREATE TABLE IF NOT EXISTS route_deviations (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    route_id              BIGINT      NOT NULL,
    driver_id             BIGINT      NOT NULL,
    -- 偏離的是哪一段：對照 route_planned_legs.sequence
    leg_sequence          INT         NOT NULL,
    -- 確定偏離的那一筆 GPS（連續第 3 筆）的時間、位置、離那一段多遠
    started_at            DATETIME(6) NOT NULL,
    start_lat             DOUBLE      NOT NULL,
    start_lng             DOUBLE      NOT NULL,
    start_distance_meters DOUBLE      NOT NULL,
    -- 偏離 10 分鐘還沒結束、從「提示」升級成「警報」的時間；沒升級是 NULL
    escalated_at          DATETIME(6) DEFAULT NULL,
    -- 結束時間；NULL＝進行中
    ended_at              DATETIME(6) DEFAULT NULL,
    -- 結束原因，對照 RouteDeviationEndReason：BACK_ON_ROUTE、DELIVERING、ON_BREAK、TRIP_ENDED、OFF_DUTY。
    -- 用 VARCHAR 不用 ENUM：原因之後可能會加，ENUM 每加一個值都要一支 migration 改欄位定義
    end_reason            VARCHAR(20) DEFAULT NULL,
    -- 樂觀鎖：GPS 上傳（回到路線→結束）跟每分鐘的排程（升級、休息→結束）可能同時改到同一筆，
    -- Hibernate 預設整列更新，沒有版本號的話後存的會把先存的 ended_at 蓋回 NULL
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 進行中的偏離：後台警報清單、每分鐘的升級排程、重啟後還原，都是查 ended_at IS NULL
    KEY idx_route_deviations_ended_at (ended_at),
    -- 某位司機的偏離歷史（報表）
    KEY idx_route_deviations_driver_started (driver_id, started_at),
    CONSTRAINT fk_route_deviations_route FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

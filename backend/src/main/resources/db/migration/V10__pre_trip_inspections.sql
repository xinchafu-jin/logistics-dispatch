-- 出車前安全檢查：司機每送一次就新增一筆，不覆蓋舊的（稽核用）。
--
-- 放行規則：同一條路線、同一位司機、同一台車、同一個路線版本（route_version）底下，
-- 還沒作廢的最新一筆通過，才能記出車里程和點交。
--   * 換車、換人、交接時路線版本會 +1，舊車、舊人的檢查自然對不上，要重做
--   * 撤回發布時把當天的檢查作廢（invalidated_at），重新發布後要重做
--
-- 酒測不是 0.00，或 15 項任一項異常，就是不通過；不通過也照樣存，主管才看得到哪裡有問題。
--
-- route_id 不設外鍵：這是稽核紀錄，撤回後重排可能刪掉草稿路線，紀錄仍要留著。
-- driver_id、vehicle_id 也不設外鍵，跟 gps_pings、route_deviations 的 driver_id 一樣。

CREATE TABLE IF NOT EXISTS pre_trip_inspections (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    route_id             BIGINT        NOT NULL,
    driver_id            BIGINT        NOT NULL,
    vehicle_id           BIGINT        NOT NULL,
    -- 送出時的 routes.version
    route_version        INT           NOT NULL,
    work_date            DATE          NOT NULL,
    -- 呼氣酒精濃度 mg/L，0.00～9.99；系統規定 0.00 才能出車
    alcohol_mg_l         DECIMAL(3, 2) NOT NULL,
    -- 以下 15 項：1＝正常，0＝異常
    -- 行車紀錄器
    dashcam              BIT(1)        NOT NULL,
    -- 五油
    engine_oil           BIT(1)        NOT NULL,
    brake_fluid          BIT(1)        NOT NULL,
    power_steering_fluid BIT(1)        NOT NULL,
    transmission_oil     BIT(1)        NOT NULL,
    fuel                 BIT(1)        NOT NULL,
    -- 三水
    coolant              BIT(1)        NOT NULL,
    battery_water        BIT(1)        NOT NULL,
    washer_fluid         BIT(1)        NOT NULL,
    -- 二胎
    tire_pressure        BIT(1)        NOT NULL,
    tire_tread           BIT(1)        NOT NULL,
    -- 四燈（依交通部行車前檢查：頭燈、方向燈、煞車燈、儀表板燈）
    headlights           BIT(1)        NOT NULL,
    turn_signals         BIT(1)        NOT NULL,
    brake_lights         BIT(1)        NOT NULL,
    dashboard_lights     BIT(1)        NOT NULL,
    -- 有異常時的說明；全部正常可留空
    note                 VARCHAR(500)  DEFAULT NULL,
    -- 照片只存檔名，不給公開網址：透過本人才能呼叫的 API 讀取
    alcohol_photo        VARCHAR(100)  NOT NULL,
    fault_photo          VARCHAR(100)  DEFAULT NULL,
    passed               BIT(1)        NOT NULL,
    submitted_at         DATETIME(6)   NOT NULL,
    -- 撤回發布時作廢；NULL＝有效
    invalidated_at       DATETIME(6)   DEFAULT NULL,
    PRIMARY KEY (id),
    -- 點交、出車時查「這條路線最新一筆」，撤回時查「這條路線還有效的」
    KEY idx_pre_trip_inspections_route (route_id, id),
    -- 某台車的檢查歷史（之後後台、報表用）
    KEY idx_pre_trip_inspections_vehicle (vehicle_id, work_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

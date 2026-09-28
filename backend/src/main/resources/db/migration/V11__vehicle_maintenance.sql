-- 車輛保養與退役（參考 murse-V2-all 的設計改寫）：用行車紀錄器里程預估保養，預估超過就擋發布、擋出車。
--
-- 1. vehicles 加三欄：
--    tonnage                    噸位，同噸位共用一組保養規則
--    last_minor_maintenance_km  上次小保完成時的行車紀錄器里程（小保基準）
--    last_major_maintenance_km  上次大保完成時的行車紀錄器里程（大保基準）
--    基準只在三種時候寫入：新增車輛時填、舊車第一次補（原本是 NULL）、保養完成時由系統記下，
--    補過之後就不能在畫面上改，避免為了讓車「過關」去改數字。
--
-- 2. vehicles.status 在尾端追加 MINOR_MAINTENANCE（送小保）、MAJOR_MAINTENANCE（送大保）；
--    原本的 MAINTENANCE 當「送維修」（車禍、故障）。MODIFY 要列出全部現有值，目前是 V1 的三個。
--
-- 3. 三張新表：同噸位的保養規則、全車共用的提前提醒公里數、每一次送修的紀錄。

ALTER TABLE vehicles
    ADD COLUMN tonnage                   DECIMAL(6, 2) DEFAULT NULL,
    ADD COLUMN last_minor_maintenance_km INT           DEFAULT NULL,
    ADD COLUMN last_major_maintenance_km INT           DEFAULT NULL;

ALTER TABLE vehicles
    MODIFY COLUMN status ENUM ('AVAILABLE', 'MAINTENANCE', 'RETIRED', 'MINOR_MAINTENANCE', 'MAJOR_MAINTENANCE') NOT NULL;

-- 同噸位共用的規則；車輛不存間隔的副本，規則一改，同噸位的車立刻照新規則算
CREATE TABLE IF NOT EXISTS vehicle_maintenance_policies (
    tonnage           DECIMAL(6, 2) NOT NULL,
    minor_interval_km INT           NOT NULL,
    major_interval_km INT           NOT NULL,
    -- 退役總里程：行車紀錄器里程到這個數字就該退役，不會因為保養而重設
    retirement_km     INT           NOT NULL,
    PRIMARY KEY (tonnage)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 全車共用的設定，只有 id = 1 這一列：預估剩下多少公里以內要提醒
CREATE TABLE IF NOT EXISTS vehicle_maintenance_settings (
    id         BIGINT NOT NULL,
    warning_km INT    NOT NULL DEFAULT 500,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO vehicle_maintenance_settings (id, warning_km) VALUES (1, 500);

-- 每一次送修一筆：送小保、送大保、送維修時新增，完成或取消時補上時間
CREATE TABLE IF NOT EXISTS vehicle_maintenance_records (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    vehicle_id            BIGINT       NOT NULL,
    -- 對照 MaintenanceRecordType：MINOR、MAJOR、REPAIR；用 VARCHAR 不用 ENUM，理由同 route_deviations.end_reason
    type                  VARCHAR(10)  NOT NULL,
    -- 對照 MaintenanceRecordStatus：ACTIVE（進行中）、COMPLETED、CANCELLED
    status                VARCHAR(12)  NOT NULL,
    sent_at               DATETIME(6)  DEFAULT NULL,
    -- 送修當下的行車紀錄器里程；車輛還沒有里程時是 NULL，不猜
    sent_odometer_km      INT          DEFAULT NULL,
    completed_at          DATETIME(6)  DEFAULT NULL,
    completed_odometer_km INT          DEFAULT NULL,
    cancelled_at          DATETIME(6)  DEFAULT NULL,
    recorded_by           VARCHAR(100) DEFAULT NULL,
    completed_by          VARCHAR(100) DEFAULT NULL,
    -- 進行中時等於 vehicle_id，結束（完成或取消）就清成 NULL。
    -- 配上唯一鍵：一台車同一時間只能有一筆進行中；MySQL 的唯一鍵允許多個 NULL，結束的紀錄不會互相衝突
    active_vehicle_id     BIGINT       DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vehicle_maintenance_records_active (active_vehicle_id),
    -- 某台車的送修歷史，新的在前
    KEY idx_vehicle_maintenance_records_vehicle (vehicle_id, id),
    -- 有送修紀錄的車不能刪，只能改成退役，保留歷史
    CONSTRAINT fk_vehicle_maintenance_records_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

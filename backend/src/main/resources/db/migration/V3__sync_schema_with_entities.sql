-- 依 entity 補齊資料表結構。
--
-- 背景：下面這些表與欄位在 entity 裡早就在用，卻從來沒有進 migration（V1 只涵蓋建立當時的
-- seed/schema.sql）。各環境的資料庫是各自手動補的，狀態不一致：有的表已經存在、有的還沒有，
-- 同一張表也可能缺後來才加的欄位。所以這支 migration 每一步都先檢查再做，重複執行也不會出錯：
--   表：CREATE TABLE IF NOT EXISTS
--   欄位：information_schema 查不到才 ADD COLUMN（MySQL 8.4 沒有 ADD COLUMN IF NOT EXISTS）
--   enum：只在尾端追加新值，既有值的順序不動
--
-- 欄位定義取自 Hibernate 依 entity 產生的 DDL。entity 以 Long 存關聯 id、沒有宣告外鍵，
-- 這裡也不加外鍵。
-- entity 有初始值的 NOT NULL 欄位補上相同的 DEFAULT，舊資料列加欄位時才有值可填。

DROP PROCEDURE IF EXISTS v3_add_column_if_missing;

DELIMITER //
CREATE PROCEDURE v3_add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = p_table
                     AND column_name = p_column) THEN
        SET @v3_ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE v3_stmt FROM @v3_ddl;
        EXECUTE v3_stmt;
        DEALLOCATE PREPARE v3_stmt;
    END IF;
END //
DELIMITER ;


-- ── 一、entity 有、V1 沒有的表 ─────────────────────────────

-- 班表主檔（DriverScheduleService）
CREATE TABLE IF NOT EXISTS schedule_months (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    generated_at   DATETIME(6) NOT NULL,
    published_at   DATETIME(6) DEFAULT NULL,
    schedule_month DATE        NOT NULL,
    status         ENUM ('DRAFT', 'PUBLISHED') NOT NULL,
    version        BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_months_month (schedule_month)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 司機每日班次
CREATE TABLE IF NOT EXISTS driver_shifts (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    change_reason     VARCHAR(255) DEFAULT NULL,
    driver_id         BIGINT       NOT NULL,
    last_modified_at  DATETIME(6)  NOT NULL,
    overtime_minutes  INT          NOT NULL DEFAULT 0,
    schedule_month_id BIGINT       NOT NULL,
    shift_type        ENUM ('DAY_OFF', 'LEAVE', 'UNASSIGNED', 'WORK') NOT NULL,
    version           BIGINT       NOT NULL,
    work_date         DATE         NOT NULL,
    work_end          TIME         DEFAULT NULL,
    work_start        TIME         DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_shifts_driver_date (driver_id, work_date),
    KEY idx_driver_shifts_month_date (schedule_month_id, work_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 司機打卡紀錄
CREATE TABLE IF NOT EXISTS attendance_records (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    break_ends_at        DATETIME(6) DEFAULT NULL,
    break_started_at     DATETIME(6) DEFAULT NULL,
    break_used           BIT(1)      NOT NULL,
    clock_in_at          DATETIME(6) NOT NULL,
    clock_out_at         DATETIME(6) DEFAULT NULL,
    driver_id            BIGINT      NOT NULL,
    driver_shift_id      BIGINT      NOT NULL,
    overtime_minutes     INT         NOT NULL DEFAULT 0,
    overtime_started_at  DATETIME(6) DEFAULT NULL,
    regular_work_minutes INT         NOT NULL DEFAULT 0,
    status               ENUM ('CLOCKED_OUT', 'ON_BREAK', 'OVERTIME', 'WORKING') NOT NULL,
    total_work_minutes   INT         NOT NULL DEFAULT 0,
    version              BIGINT      NOT NULL,
    work_date            DATE        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_shift (driver_shift_id),
    KEY idx_attendance_driver_date (driver_id, work_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 司機與調度中心的聊天訊息
CREATE TABLE IF NOT EXISTS driver_messages (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    content         VARCHAR(1000) NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    driver_id       BIGINT        NOT NULL,
    read_at         DATETIME(6)   DEFAULT NULL,
    sender_admin_id BIGINT        DEFAULT NULL,
    sender_type     ENUM ('ADMIN', 'DRIVER') NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 司機臨時請假申請（EmergencyLeaveService）
CREATE TABLE IF NOT EXISTS emergency_leave_requests (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    attendance_record_id    BIGINT       NOT NULL,
    clocked_out_at          DATETIME(6)  DEFAULT NULL,
    driver_id               BIGINT       NOT NULL,
    reason                  VARCHAR(500) NOT NULL,
    rejection_reason        VARCHAR(500) DEFAULT NULL,
    replacement_driver_id   BIGINT       DEFAULT NULL,
    requested_at            DATETIME(6)  NOT NULL,
    reviewed_at             DATETIME(6)  DEFAULT NULL,
    reviewed_by             VARCHAR(60)  DEFAULT NULL,
    route_id                BIGINT       NOT NULL,
    route_reassigned_at     DATETIME(6)  DEFAULT NULL,
    status                  ENUM ('APPROVED', 'PENDING', 'REJECTED') NOT NULL,
    transferred_order_count INT          NOT NULL DEFAULT 0,
    vehicle_id              BIGINT       NOT NULL,
    version                 BIGINT       NOT NULL,
    work_date               DATE         NOT NULL,
    PRIMARY KEY (id),
    KEY idx_emergency_leave_status_requested (status, requested_at),
    KEY idx_emergency_leave_driver_date (driver_id, work_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 中油牌價歷史（CpcFuelPriceSyncService）
CREATE TABLE IF NOT EXISTS fuel_price_history (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    effective_from  DATETIME(6)   NOT NULL,
    fetched_at      DATETIME(6)   NOT NULL,
    fuel_type       ENUM ('DIESEL', 'GASOLINE_92', 'GASOLINE_95', 'GASOLINE_98', 'OTHER') NOT NULL,
    price_per_liter DECIMAL(8, 3) NOT NULL,
    source          VARCHAR(100)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ── 二、既有表缺的欄位 ─────────────────────────────────────

-- 打卡紀錄：9/23 才加的工時與加班欄位。表若是更早手動建的，會缺這四欄
CALL v3_add_column_if_missing('attendance_records', 'overtime_started_at', 'DATETIME(6) NULL');
CALL v3_add_column_if_missing('attendance_records', 'regular_work_minutes', 'INT NOT NULL DEFAULT 0');
CALL v3_add_column_if_missing('attendance_records', 'overtime_minutes', 'INT NOT NULL DEFAULT 0');
CALL v3_add_column_if_missing('attendance_records', 'total_work_minutes', 'INT NOT NULL DEFAULT 0');

-- 簽收紀錄：短缺、破損的箱數
CALL v3_add_column_if_missing('delivery_records', 'expected_box_count', 'INT NULL');
CALL v3_add_column_if_missing('delivery_records', 'shortage_box_count', 'INT NULL');
CALL v3_add_column_if_missing('delivery_records', 'damaged_box_count', 'INT NULL');
CALL v3_add_column_if_missing('delivery_records', 'replacement_required_box_count', 'INT NULL');

-- 司機大頭照
CALL v3_add_column_if_missing('drivers', 'profile_photo_url', 'VARCHAR(500) NULL');

-- 異常案件：關聯的簽收紀錄、後續補送單、排程時間
CALL v3_add_column_if_missing('exception_cases', 'delivery_record_id', 'BIGINT NULL');
CALL v3_add_column_if_missing('exception_cases', 'follow_up_order_id', 'BIGINT NULL');
CALL v3_add_column_if_missing('exception_cases', 'review_available_at', 'DATETIME(6) NULL');
CALL v3_add_column_if_missing('exception_cases', 'queued_at', 'DATETIME(6) NULL');

-- 里程紀錄：綁路線與車輛，以及 GPS 里程結算
CALL v3_add_column_if_missing('mileage_logs', 'route_id', 'BIGINT NULL');
CALL v3_add_column_if_missing('mileage_logs', 'vehicle_id', 'BIGINT NULL');
CALL v3_add_column_if_missing('mileage_logs', 'gps_distance_km', 'DOUBLE NULL');
CALL v3_add_column_if_missing('mileage_logs', 'gps_distance_status', 'VARCHAR(50) NULL');
CALL v3_add_column_if_missing('mileage_logs', 'mileage_settled_at', 'DATETIME(6) NULL');

-- 訂單：補送、再配送的訂單類型與來源單
CALL v3_add_column_if_missing('orders', 'order_type', 'ENUM (''NORMAL'', ''REDELIVERY'', ''REPLENISHMENT'') NOT NULL DEFAULT ''NORMAL''');
CALL v3_add_column_if_missing('orders', 'parent_order_id', 'BIGINT NULL');
CALL v3_add_column_if_missing('orders', 'retry_count', 'INT NOT NULL DEFAULT 0');

-- 車輛累積里程
CALL v3_add_column_if_missing('vehicles', 'cumulative_mileage_km', 'DOUBLE NOT NULL DEFAULT 0');

DROP PROCEDURE v3_add_column_if_missing;


-- ── 三、enum 追加新值 ──────────────────────────────────────

-- 訂單新增「未簽收」狀態
ALTER TABLE orders
    MODIFY COLUMN status ENUM ('PENDING_CONFIRM', 'CONFIRMED', 'IN_DELIVERY', 'COMPLETED', 'CANCELLED', 'FAILED',
        'NO_SIGNATURE') NOT NULL;

-- 異常案件新增短缺、破損類型
ALTER TABLE exception_cases
    MODIFY COLUMN type ENUM ('DRIVER_REPORT', 'NO_SIGNATURE', 'PHONE_HANDLED', 'DAMAGE', 'SHORTAGE',
        'SHORTAGE_AND_DAMAGE') NOT NULL;

-- 打卡狀態新增「加班中」。手動建的舊表值的順序可能不同，MODIFY 會照值轉換，不會弄亂資料
ALTER TABLE attendance_records
    MODIFY COLUMN status ENUM ('CLOCKED_OUT', 'ON_BREAK', 'OVERTIME', 'WORKING') NOT NULL;


-- ── 四、里程紀錄的唯一鍵：一位司機一天一筆 ────────────────────
-- 已經有同一位司機同一天的重複資料時，加唯一鍵會失敗、後端就起不來，所以有重複就先跳過。
-- 跳過時要另外清掉重複資料，再用新的 migration 補上這個唯一鍵。
SET @v3_has_uk = (SELECT COUNT(*)
                  FROM information_schema.statistics
                  WHERE table_schema = DATABASE()
                    AND table_name = 'mileage_logs'
                    AND index_name = 'uk_mileage_logs_driver_date');
SET @v3_duplicates = (SELECT COUNT(*)
                      FROM (SELECT driver_id FROM mileage_logs GROUP BY driver_id, `date` HAVING COUNT(*) > 1) d);
SET @v3_ddl = IF(@v3_has_uk = 0 AND @v3_duplicates = 0,
                 'ALTER TABLE mileage_logs ADD CONSTRAINT uk_mileage_logs_driver_date UNIQUE (driver_id, `date`)',
                 'DO 0');
PREPARE v3_stmt FROM @v3_ddl;
EXECUTE v3_stmt;
DEALLOCATE PREPARE v3_stmt;

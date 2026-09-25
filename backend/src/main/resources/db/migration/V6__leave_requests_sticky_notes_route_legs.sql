-- 合併 murse-main 帶進來的功能補上資料表：司機請假申請、出勤準時判定、後台便利貼、路段里程與里程表照片。
--
-- 背景：這些 entity 在 murse-main 上沒有 migration（路段里程原本有一支 V5，後來被刪掉，而且會跟 V5__order_loading 撞號）。
-- 欄位定義取自 Hibernate 依 entity 產生的 DDL；路段里程的唯一鍵與索引沿用 murse 原本那支 V5 的設計。
-- 跟 V3 一樣每一步都先檢查再做：哪台資料庫曾經跑過被刪掉的那支 V5、或手動建過這些表，重跑也不會出錯。
-- entity 有初始值的 NOT NULL 欄位補上相同的 DEFAULT，舊資料列加欄位時才有值可填。

DROP PROCEDURE IF EXISTS v6_add_column_if_missing;

DELIMITER //
CREATE PROCEDURE v6_add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = p_table
                     AND column_name = p_column) THEN
        SET @v6_ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE v6_stmt FROM @v6_ddl;
        EXECUTE v6_stmt;
        DEALLOCATE PREPARE v6_stmt;
    END IF;
END //
DELIMITER ;


-- ── 一、司機請假申請（DriverLeaveRequestService） ─────────────

CREATE TABLE IF NOT EXISTS driver_leave_requests (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    decision_reason          VARCHAR(500) DEFAULT NULL,
    driver_id                BIGINT       NOT NULL,
    driver_read_at           DATETIME(6)  DEFAULT NULL,
    driver_shift_id          BIGINT       NOT NULL,
    full_day                 BIT(1)       NOT NULL,
    last_updated_at          DATETIME(6)  NOT NULL,
    leave_end                TIME         DEFAULT NULL,
    leave_start              TIME         DEFAULT NULL,
    leave_type               ENUM ('ABSENT', 'ANNUAL', 'MENSTRUAL', 'SICK', 'SPECIAL') NOT NULL,
    request_reason           VARCHAR(500) NOT NULL,
    requested_at             DATETIME(6)  NOT NULL,
    requested_leave_type     ENUM ('ABSENT', 'ANNUAL', 'MENSTRUAL', 'SICK', 'SPECIAL') NOT NULL,
    reviewed_at              DATETIME(6)  DEFAULT NULL,
    reviewed_by              VARCHAR(60)  DEFAULT NULL,
    reviewed_by_admin_id     BIGINT       DEFAULT NULL,
    status                   ENUM ('APPROVED', 'PENDING', 'REJECTED') NOT NULL,
    submission_source        ENUM ('ADMIN', 'DRIVER', 'SYSTEM') NOT NULL,
    type_change_reason       VARCHAR(500) DEFAULT NULL,
    type_changed_at          DATETIME(6)  DEFAULT NULL,
    type_changed_by          VARCHAR(60)  DEFAULT NULL,
    type_changed_by_admin_id BIGINT       DEFAULT NULL,
    version                  BIGINT       NOT NULL,
    work_date                DATE         NOT NULL,
    PRIMARY KEY (id),
    KEY idx_driver_leave_driver_date (driver_id, work_date),
    KEY idx_driver_leave_shift (driver_shift_id),
    KEY idx_driver_leave_status_requested (status, requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 請假單每一次異動（送出、核准、駁回、改假別）一筆，給主管與司機查歷程
CREATE TABLE IF NOT EXISTS driver_leave_request_events (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    actor_account    VARCHAR(60)  NOT NULL,
    actor_id         BIGINT       NOT NULL,
    actor_type       ENUM ('ADMIN', 'DRIVER', 'SYSTEM') NOT NULL,
    driver_id        BIGINT       NOT NULL,
    event_type       ENUM ('APPROVED', 'AUTO_NO_SHOW_CREATED', 'PLANNED_CREATED', 'REJECTED', 'SUBMITTED', 'TYPE_CHANGED') NOT NULL,
    leave_request_id BIGINT       NOT NULL,
    new_leave_type   ENUM ('ABSENT', 'ANNUAL', 'MENSTRUAL', 'SICK', 'SPECIAL') DEFAULT NULL,
    new_status       ENUM ('APPROVED', 'PENDING', 'REJECTED') DEFAULT NULL,
    occurred_at      DATETIME(6)  NOT NULL,
    old_leave_type   ENUM ('ABSENT', 'ANNUAL', 'MENSTRUAL', 'SICK', 'SPECIAL') DEFAULT NULL,
    old_status       ENUM ('APPROVED', 'PENDING', 'REJECTED') DEFAULT NULL,
    reason           VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_leave_event_request_time (leave_request_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ── 二、出勤準時判定（AttendanceService） ─────────────────────

CALL v6_add_column_if_missing('attendance_records', 'punctuality_status',
    'ENUM (''LATE'', ''LATE_EXCUSED'', ''LEAVE_COVERED'', ''LEAVE_REQUIRED'', ''ON_TIME'') NOT NULL DEFAULT ''ON_TIME''');
CALL v6_add_column_if_missing('attendance_records', 'late_minutes', 'INT NOT NULL DEFAULT 0');
CALL v6_add_column_if_missing('attendance_records', 'late_excused', 'BIT(1) NOT NULL DEFAULT b''0''');
CALL v6_add_column_if_missing('attendance_records', 'leave_required', 'BIT(1) NOT NULL DEFAULT b''0''');
CALL v6_add_column_if_missing('attendance_records', 'leave_required_minutes', 'INT NOT NULL DEFAULT 0');
CALL v6_add_column_if_missing('attendance_records', 'covered_leave_request_id', 'BIGINT NULL');


-- ── 三、後台便利貼（AdminStickyNoteService） ──────────────────

CREATE TABLE IF NOT EXISTS admin_sticky_notes (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    admin_id   BIGINT        NOT NULL,
    color      VARCHAR(20)   NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    sort_order INT           NOT NULL,
    title      VARCHAR(120)  DEFAULT NULL,
    updated_at DATETIME(6)   NOT NULL,
    version    BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY idx_admin_sticky_notes_owner_sort (admin_id, sort_order, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ── 四、路段里程與里程表照片（RouteLegMileageService、MileageLogsService） ──

-- 一段路（倉庫→門市、門市→門市、門市→倉庫）一筆，抵達門市與回倉時記錄
CREATE TABLE IF NOT EXISTS route_leg_mileages (
    id                     BIGINT      NOT NULL AUTO_INCREMENT,
    accepted_segment_count INT         NOT NULL DEFAULT 0,
    calculation_status     VARCHAR(50) NOT NULL,
    created_at             DATETIME(6) NOT NULL,
    delivery_record_id     BIGINT      DEFAULT NULL,
    driver_id              BIGINT      NOT NULL,
    ended_at               DATETIME(6) NOT NULL,
    from_store_id          BIGINT      DEFAULT NULL,
    from_type              ENUM ('STORE', 'WAREHOUSE') NOT NULL,
    gps_point_count        INT         NOT NULL DEFAULT 0,
    mileage_log_id         BIGINT      NOT NULL,
    order_id               BIGINT      DEFAULT NULL,
    route_id               BIGINT      NOT NULL,
    sequence               INT         NOT NULL,
    started_at             DATETIME(6) NOT NULL,
    system_distance_km     DOUBLE      DEFAULT NULL,
    to_store_id            BIGINT      DEFAULT NULL,
    to_type                ENUM ('STORE', 'WAREHOUSE') NOT NULL,
    vehicle_id             BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_leg_mileage_log_sequence (mileage_log_id, sequence),
    KEY idx_route_leg_route_sequence (route_id, sequence),
    KEY idx_route_leg_driver_time (driver_id, started_at, ended_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 里程表人工讀數與照片，留存作為系統 GPS 里程的佐證
CALL v6_add_column_if_missing('mileage_logs', 'actual_distance_km', 'INT NULL');
CALL v6_add_column_if_missing('mileage_logs', 'mileage_photo_url', 'VARCHAR(512) NULL');
CALL v6_add_column_if_missing('mileage_logs', 'mileage_photo_recorded_at', 'DATETIME(6) NULL');
CALL v6_add_column_if_missing('mileage_logs', 'end_mileage_photo_url', 'VARCHAR(512) NULL');
CALL v6_add_column_if_missing('mileage_logs', 'end_mileage_photo_recorded_at', 'DATETIME(6) NULL');
CALL v6_add_column_if_missing('vehicles', 'current_odometer_km', 'INT NULL');
CALL v6_add_column_if_missing('delivery_records', 'handled_at', 'DATETIME(6) NULL');

DROP PROCEDURE v6_add_column_if_missing;

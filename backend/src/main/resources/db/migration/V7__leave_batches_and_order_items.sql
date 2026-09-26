-- 多日預排／補請假與訂單逐項點交。
-- V6 已建立請假主檔；本 migration 只補新增欄位、enum 值與 order_items。

DROP PROCEDURE IF EXISTS v7_add_column_if_missing;

DELIMITER //
CREATE PROCEDURE v7_add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @v7_ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE v7_stmt FROM @v7_ddl;
        EXECUTE v7_stmt;
        DEALLOCATE PREPARE v7_stmt;
    END IF;
END //
DELIMITER ;

-- 請假群組、申請來源模式與補請假佐證照片。
CALL v7_add_column_if_missing('driver_leave_requests', 'batch_id', 'VARCHAR(36) NULL');
CALL v7_add_column_if_missing('driver_leave_requests', 'request_mode',
    'ENUM (''PREPLANNED'', ''TEMPORARY'', ''MAKEUP'', ''SYSTEM_NO_SHOW'', ''ADMIN_PLANNED_PARTIAL'') NOT NULL DEFAULT ''TEMPORARY''');
CALL v7_add_column_if_missing('driver_leave_requests', 'evidence_photo_url', 'VARCHAR(500) NULL');

-- 舊資料沒有模式欄位，依既有來源保留其原本的語意。
UPDATE driver_leave_requests
SET request_mode = CASE
    WHEN submission_source = 'SYSTEM' THEN 'SYSTEM_NO_SHOW'
    WHEN submission_source = 'ADMIN' AND full_day = b'0' THEN 'ADMIN_PLANNED_PARTIAL'
    ELSE 'TEMPORARY'
END
WHERE request_mode = 'TEMPORARY';

-- 新增的假別與補請假歷程事件。MODIFY 會保留原有資料列的 enum 值。
ALTER TABLE driver_leave_requests
    MODIFY COLUMN leave_type ENUM ('ABSENT', 'ANNUAL', 'BEREAVEMENT', 'MENSTRUAL', 'PERSONAL', 'SICK', 'SPECIAL') NOT NULL,
    MODIFY COLUMN requested_leave_type ENUM ('ABSENT', 'ANNUAL', 'BEREAVEMENT', 'MENSTRUAL', 'PERSONAL', 'SICK', 'SPECIAL') NOT NULL;

ALTER TABLE driver_leave_request_events
    MODIFY COLUMN event_type ENUM ('APPROVED', 'AUTO_NO_SHOW_CREATED', 'DRIVER_EXPLANATION_SUBMITTED', 'PLANNED_CREATED', 'REJECTED', 'SUBMITTED', 'TYPE_CHANGED') NOT NULL,
    MODIFY COLUMN old_leave_type ENUM ('ABSENT', 'ANNUAL', 'BEREAVEMENT', 'MENSTRUAL', 'PERSONAL', 'SICK', 'SPECIAL') NULL,
    MODIFY COLUMN new_leave_type ENUM ('ABSENT', 'ANNUAL', 'BEREAVEMENT', 'MENSTRUAL', 'PERSONAL', 'SICK', 'SPECIAL') NULL;

SET @v7_has_batch_index = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'driver_leave_requests'
      AND index_name = 'idx_driver_leave_batch'
);
SET @v7_batch_index_ddl = IF(
    @v7_has_batch_index = 0,
    'ALTER TABLE driver_leave_requests ADD KEY idx_driver_leave_batch (batch_id, work_date)',
    'DO 0'
);
PREPARE v7_batch_index_stmt FROM @v7_batch_index_ddl;
EXECUTE v7_batch_index_stmt;
DEALLOCATE PREPARE v7_batch_index_stmt;

-- 每張訂單可有多筆實際商品；點交資料寫回同一筆明細。
CREATE TABLE IF NOT EXISTS order_items (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    order_id             BIGINT       NOT NULL,
    product_code         VARCHAR(50)  DEFAULT NULL,
    item_name            VARCHAR(100) NOT NULL,
    expected_quantity    INT          NOT NULL,
    unit                 VARCHAR(20)  NOT NULL DEFAULT '件',
    sequence             INT          NOT NULL,
    notes                VARCHAR(255) DEFAULT NULL,
    loaded_quantity      INT          DEFAULT NULL,
    checked_at           DATETIME(6)  DEFAULT NULL,
    checked_by_driver_id BIGINT       DEFAULT NULL,
    loading_notes        VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_order_items_order_sequence (order_id, sequence)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

DROP PROCEDURE v7_add_column_if_missing;

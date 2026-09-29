-- 配送日結束後仍未確認或尚未點交的訂單，進入未結訂單異常中心。
-- MySQL ENUM 的 MODIFY 必須保留所有既有值與順序，新的類型只加在尾端。
ALTER TABLE exception_cases
    MODIFY COLUMN type ENUM ('DRIVER_REPORT', 'NO_SIGNATURE', 'PHONE_HANDLED', 'DAMAGE', 'SHORTAGE',
        'SHORTAGE_AND_DAMAGE', 'LOADING_MISMATCH', 'UNSETTLED_ORDER') NOT NULL;

ALTER TABLE exception_cases
    ADD KEY idx_exception_cases_order_type (order_id, type);

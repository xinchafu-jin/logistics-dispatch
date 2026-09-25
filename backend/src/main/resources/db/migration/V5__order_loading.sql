-- 司機倉庫點交：司機在倉庫清點箱數，相符才能出發配送。
-- 1. 訂單新增 LOADED（已點交），介於 CONFIRMED 與 IN_DELIVERY 之間，沒點交不能登記抵達門市。
-- 2. orders.loaded_at 記點交成功的時間。訂單轉成配送中以後就看不到 LOADED 了，這欄是點交過的紀錄。
-- 3. 異常新增 LOADING_MISMATCH，點交箱數不符時建立。
-- MODIFY 會整個換掉 enum 定義，所以要列出全部現有值（以 V3 為準，V1 少了 NO_SIGNATURE 等值），
-- 漏掉已在使用的值會讓 migration 失敗、後端起不來。新值只加在尾端，前面的順序不動。
ALTER TABLE orders
    MODIFY COLUMN status ENUM ('PENDING_CONFIRM', 'CONFIRMED', 'IN_DELIVERY', 'COMPLETED', 'CANCELLED', 'FAILED',
        'NO_SIGNATURE', 'LOADED') NOT NULL;

ALTER TABLE orders
    ADD COLUMN loaded_at DATETIME(6) NULL;

ALTER TABLE exception_cases
    MODIFY COLUMN type ENUM ('DRIVER_REPORT', 'NO_SIGNATURE', 'PHONE_HANDLED', 'DAMAGE', 'SHORTAGE',
        'SHORTAGE_AND_DAMAGE', 'LOADING_MISMATCH') NOT NULL;

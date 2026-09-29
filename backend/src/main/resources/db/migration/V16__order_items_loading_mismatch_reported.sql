-- 點交時司機勾選「不符商品」直接回報的旗標（同學的 MAJOR 功能）。
--
-- 司機沒有數量讀數就回報不符時，loaded_quantity 保留 NULL，不猜成 0，也不把其他沒勾的商品當成相符；
-- 這個旗標讓後台歷史報表與匯出分得出「司機回報不符」和「還沒核對」。舊資料都不是司機回報的，所以預設 0。
--
-- 補這一版的原因：OrderItemsEntity 已經有 loadingMismatchReported（nullable = false），
-- docs/major-integration.md 也寫由 V16 新增，可是同學的分支裡沒有這個檔案；ddl-auto=none，
-- 資料庫沒有欄位時讀 order_items 會直接出錯。
--
-- 同學的資料庫可能已經手動加過這個欄位，所以用「欄位不存在才新增」，寫法同 V7。
-- 一定要有預設值：直接用 SQL 新增 order_items 的地方（例如測試）不會帶這個欄位，NOT NULL 沒預設值會被擋。

DROP PROCEDURE IF EXISTS v16_add_column_if_missing;

DELIMITER //
CREATE PROCEDURE v16_add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @v16_ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE v16_stmt FROM @v16_ddl;
        EXECUTE v16_stmt;
        DEALLOCATE PREPARE v16_stmt;
    END IF;
END //
DELIMITER ;

-- BIT(1) 對應 entity 的 boolean，專案其他布林欄位也這樣宣告（例如 V10）
CALL v16_add_column_if_missing('order_items', 'loading_mismatch_reported', 'BIT(1) NOT NULL DEFAULT b''0''');

DROP PROCEDURE v16_add_column_if_missing;

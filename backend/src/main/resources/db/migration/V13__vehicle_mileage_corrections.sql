-- 主管更正行車紀錄器里程與保養基準。
--
-- 這三個數字平常只能補一次，之後只由出車、收車、保養完成更新，免得有人為了讓車「過關」去改數字。
-- 可是打錯了也要能改回來（不然只能直接改資料庫），所以另外開一條路：一定要填原因，每一筆都留下來，
-- 誰、什麼時候、從多少改成多少，事後查得到。
--
-- 一次更正改到幾個數字就寫幾列。車在外面跑（出車還沒收車）時更正目前里程，
-- 那一趟的出車讀數也會一起改，不然司機收車時會被「收車里程不能小於出車里程」擋住；mileage_log_id 記下是哪一趟。

CREATE TABLE IF NOT EXISTS vehicle_mileage_corrections (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    vehicle_id     BIGINT       NOT NULL,
    -- 對照 MileageCorrectionField：CURRENT_ODOMETER、MINOR_BASELINE、MAJOR_BASELINE；用 VARCHAR 不用 ENUM，理由同 route_deviations.end_reason
    field          VARCHAR(20)  NOT NULL,
    -- 改之前是空的就是 NULL
    old_km         INT          DEFAULT NULL,
    new_km         INT          NOT NULL,
    reason         VARCHAR(200) NOT NULL,
    mileage_log_id BIGINT       DEFAULT NULL,
    corrected_by   VARCHAR(100) DEFAULT NULL,
    corrected_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- 某台車的更正紀錄，新的在前
    KEY idx_vehicle_mileage_corrections_vehicle (vehicle_id, id),
    -- 有更正紀錄的車不能刪，只能改成退役，保留紀錄
    CONSTRAINT fk_vehicle_mileage_corrections_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

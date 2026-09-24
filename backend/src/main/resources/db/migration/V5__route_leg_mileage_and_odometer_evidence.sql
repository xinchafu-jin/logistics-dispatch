-- 0824 里程功能以 main 的既有 schema 為基礎補入，不覆蓋既有班表與調度資料。

CREATE TABLE IF NOT EXISTS route_leg_mileages (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    route_id                BIGINT       NOT NULL,
    mileage_log_id          BIGINT       NOT NULL,
    driver_id               BIGINT       NOT NULL,
    vehicle_id              BIGINT       NOT NULL,
    sequence                INT          NOT NULL,
    from_type               VARCHAR(20)  NOT NULL,
    from_store_id           BIGINT       DEFAULT NULL,
    to_type                 VARCHAR(20)  NOT NULL,
    to_store_id             BIGINT       DEFAULT NULL,
    order_id                BIGINT       DEFAULT NULL,
    delivery_record_id      BIGINT       DEFAULT NULL,
    started_at              DATETIME(6)  NOT NULL,
    ended_at                DATETIME(6)  NOT NULL,
    system_distance_km      DOUBLE       DEFAULT NULL,
    gps_point_count         INT          NOT NULL DEFAULT 0,
    accepted_segment_count  INT          NOT NULL DEFAULT 0,
    calculation_status      VARCHAR(50)  NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_leg_mileage_log_sequence (mileage_log_id, sequence),
    KEY idx_route_leg_route_sequence (route_id, sequence),
    KEY idx_route_leg_driver_time (driver_id, started_at, ended_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 里程表人工讀數與照片，留存作為系統 GPS 里程的佐證。
ALTER TABLE mileage_logs
    ADD COLUMN actual_distance_km INT DEFAULT NULL;

ALTER TABLE mileage_logs
    ADD COLUMN mileage_photo_url VARCHAR(512) DEFAULT NULL;

ALTER TABLE mileage_logs
    ADD COLUMN mileage_photo_recorded_at DATETIME(6) DEFAULT NULL;

ALTER TABLE mileage_logs
    ADD COLUMN end_mileage_photo_url VARCHAR(512) DEFAULT NULL;

ALTER TABLE mileage_logs
    ADD COLUMN end_mileage_photo_recorded_at DATETIME(6) DEFAULT NULL;

ALTER TABLE vehicles
    ADD COLUMN current_odometer_km INT DEFAULT NULL;

ALTER TABLE delivery_records
    ADD COLUMN handled_at DATETIME(6) DEFAULT NULL;

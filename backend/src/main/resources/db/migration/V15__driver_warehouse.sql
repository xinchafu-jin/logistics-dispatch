-- V2 的司機所屬倉庫功能，接續 JIN 的 V1～V14。
-- 不以歷史路線推測員工歸屬；既有司機由主管確認後再設定。
ALTER TABLE drivers
    ADD COLUMN warehouse_id BIGINT DEFAULT NULL,
    ADD KEY idx_drivers_warehouse (warehouse_id),
    ADD CONSTRAINT fk_drivers_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouses (id);

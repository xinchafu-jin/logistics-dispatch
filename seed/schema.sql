-- 物流排車系統 schema
--
-- 使用方式：
--   mysql -u root -proot --default-character-set=utf8mb4 logistics < seed/schema.sql
--   接著灌測試資料：
--   mysql -u root -proot --default-character-set=utf8mb4 logistics < seed/seed-data.sql
--
-- 這個檔案會「先刪後建」，執行等於整個資料庫重置。
--
-- 由 Hibernate 原本自動產生的 schema 匯出後整理而成，補上：
--   1. 外鍵約束（原本完全沒有）
--   2. 排車熱路徑的索引
--   3. routes 的唯一約束（擋掉重複排車）
--   4. 可讀的約束名稱
-- application.properties 的 ddl-auto 已設為 none，Hibernate 不再碰 schema，
-- 改動 entity 欄位時務必同步修改這個檔案。

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS delivery_records;
DROP TABLE IF EXISTS exception_cases;
DROP TABLE IF EXISTS gps_pings;
DROP TABLE IF EXISTS mileage_logs;
DROP TABLE IF EXISTS distance_matrix_cache;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS routes;
DROP TABLE IF EXISTS route_template_stores;
DROP TABLE IF EXISTS route_templates;
DROP TABLE IF EXISTS vehicles;
DROP TABLE IF EXISTS drivers;
DROP TABLE IF EXISTS stores;
DROP TABLE IF EXISTS warehouses;
DROP TABLE IF EXISTS admin_users;
-- Hibernate ddl-auto=update 遺留的孤兒表（AdminUsersEntity 加上 @Table 之前的預設命名）
DROP TABLE IF EXISTS admin_users_entity;

SET FOREIGN_KEY_CHECKS = 1;


-- ══════════════════════════════════════════════════════════════
-- 基礎資料
-- ══════════════════════════════════════════════════════════════

CREATE TABLE warehouses (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  warehouse_code VARCHAR(20)  NOT NULL,
  name           VARCHAR(100) NOT NULL,
  address        VARCHAR(255) DEFAULT NULL,
  lat            DOUBLE       NOT NULL,
  lng            DOUBLE       NOT NULL,
  phone          VARCHAR(30)  DEFAULT NULL,
  is_active      BIT(1)       NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_warehouses_code (warehouse_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE stores (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  store_code      VARCHAR(20)  NOT NULL,
  name            VARCHAR(100) NOT NULL,
  address         VARCHAR(255) DEFAULT NULL,
  lat             DOUBLE       NOT NULL,
  lng             DOUBLE       NOT NULL,
  contact_name    VARCHAR(50)  DEFAULT NULL,
  phone           VARCHAR(30)  DEFAULT NULL,
  receiving_start TIME         NOT NULL,
  receiving_end   TIME         NOT NULL,
  notes           VARCHAR(500) DEFAULT NULL,
  status          ENUM('ACTIVE','SUSPENDED') NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stores_store_code (store_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE vehicles (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  warehouse_id     BIGINT      NOT NULL,
  plate_number     VARCHAR(20) NOT NULL,
  vehicle_type     VARCHAR(50) DEFAULT NULL,
  capacity         INT         NOT NULL,
  fuel_consumption DOUBLE      DEFAULT NULL,
  status           ENUM('AVAILABLE','MAINTENANCE','RETIRED') NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_vehicles_plate_number (plate_number),
  CONSTRAINT fk_vehicles_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE drivers (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  account              VARCHAR(60) NOT NULL,
  -- BCrypt 雜湊為 60 字元，放寬到 100 以容納帶前綴的編碼器（{bcrypt}...）或 Argon2
  password             VARCHAR(100) DEFAULT NULL,
  name                 VARCHAR(50) NOT NULL,
  phone                VARCHAR(30) DEFAULT NULL,
  work_start           TIME        NOT NULL,
  work_end             TIME        NOT NULL,
  rest_duration        INT         NOT NULL,
  max_overtime_minutes INT         DEFAULT NULL,
  is_active            BIT(1)      NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_drivers_account (account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE admin_users (
  id       BIGINT       NOT NULL AUTO_INCREMENT,
  account  VARCHAR(60)  NOT NULL,
  password VARCHAR(100) NOT NULL,
  name     VARCHAR(60)  NOT NULL,
  phone    VARCHAR(30)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admin_users_account (account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ══════════════════════════════════════════════════════════════
-- 常配編組（可重複套用的「哪幾間門市歸哪台車」定義）
-- ══════════════════════════════════════════════════════════════

CREATE TABLE route_templates (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  name               VARCHAR(100) NOT NULL,
  warehouse_id       BIGINT       NOT NULL,
  -- 預設車輛，套用時可臨時覆寫成別台
  default_vehicle_id BIGINT       DEFAULT NULL,
  notes              VARCHAR(500) DEFAULT NULL,
  is_active          BIT(1)       NOT NULL,
  created_at         DATETIME(6)  NOT NULL,
  updated_at         DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_route_templates_wh_name (warehouse_id, name),
  CONSTRAINT fk_route_templates_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
  CONSTRAINT fk_route_templates_vehicle   FOREIGN KEY (default_vehicle_id) REFERENCES vehicles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE route_template_stores (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  store_id    BIGINT NOT NULL,
  -- 預設停靠順序；套用時可選擇沿用或用 OSRM 重算
  `sequence`  INT    NOT NULL,
  PRIMARY KEY (id),
  -- 同一間門市在同一個編組裡只能出現一次
  UNIQUE KEY uk_tpl_stores_tpl_store (template_id, store_id),
  KEY idx_tpl_stores_tpl_seq (template_id, `sequence`),
  -- 編組刪除時成員清單一併刪除（成員沒有獨立存在的意義）
  CONSTRAINT fk_tpl_stores_template FOREIGN KEY (template_id) REFERENCES route_templates (id) ON DELETE CASCADE,
  CONSTRAINT fk_tpl_stores_store    FOREIGN KEY (store_id)    REFERENCES stores (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ══════════════════════════════════════════════════════════════
-- 排車結果
-- ══════════════════════════════════════════════════════════════

CREATE TABLE routes (
  id                     BIGINT NOT NULL AUTO_INCREMENT,
  `date`                 DATE   NOT NULL,
  warehouse_id           BIGINT NOT NULL,
  vehicle_id             BIGINT NOT NULL,
  driver_id              BIGINT DEFAULT NULL,
  -- 這條路線是從哪個編組套出來的；臨時手動排的為 NULL
  template_id            BIGINT DEFAULT NULL,
  total_distance         DOUBLE DEFAULT NULL,
  estimated_fuel_cost    DOUBLE DEFAULT NULL,
  estimated_work_minutes INT    DEFAULT NULL,
  load_rate              DOUBLE DEFAULT NULL,
  status                 ENUM('DRAFT','PUBLISHED') NOT NULL,
  version                INT    NOT NULL,
  PRIMARY KEY (id),
  -- 一台車一天只跑一條路線。擋掉 optimize 重跑產生的第二組路線
  -- （若日後實作多趟次，這裡要改成 (date, vehicle_id, trip_no)）
  UNIQUE KEY uk_routes_date_vehicle (`date`, vehicle_id),
  KEY idx_routes_date_warehouse (`date`, warehouse_id),
  CONSTRAINT fk_routes_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
  CONSTRAINT fk_routes_vehicle   FOREIGN KEY (vehicle_id)   REFERENCES vehicles (id),
  CONSTRAINT fk_routes_driver    FOREIGN KEY (driver_id)    REFERENCES drivers (id),
  -- 刪編組不該擋住，路線只是失去來源標記
  CONSTRAINT fk_routes_template  FOREIGN KEY (template_id)  REFERENCES route_templates (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE orders (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  order_number        VARCHAR(30)  NOT NULL,
  store_id            BIGINT       NOT NULL,
  warehouse_id        BIGINT       NOT NULL,
  source_vendor       VARCHAR(100) DEFAULT NULL,
  item_description    VARCHAR(255) DEFAULT NULL,
  box_count           INT          NOT NULL,
  notes               VARCHAR(500) DEFAULT NULL,
  delivery_date       DATE         NOT NULL,
  status              ENUM('PENDING_CONFIRM','CONFIRMED','SCHEDULED','MODIFY','PUBLISHED',
                           'IN_DELIVERY','COMPLETED','CANCELLED','FAILED') NOT NULL,
  route_id            BIGINT       DEFAULT NULL,
  assigned_vehicle_id BIGINT       DEFAULT NULL,
  assigned_driver_id  BIGINT       DEFAULT NULL,
  `sequence`          INT          DEFAULT NULL,
  created_at          DATETIME(6)  NOT NULL,
  updated_at          DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_orders_order_number (order_number),
  -- 排車撈當日已確認訂單：findByDeliveryDateAndStatusAndWarehouseId
  KEY idx_orders_date_status_wh (delivery_date, status, warehouse_id),
  -- 看板撈未排入路線的訂單：findByDeliveryDateAndWarehouseIdAndRouteIdIsNull
  KEY idx_orders_date_wh_route (delivery_date, warehouse_id, route_id),
  -- 撈某條路線的停靠點：findByRouteIdOrderBySequence
  KEY idx_orders_route_seq (route_id, `sequence`),
  -- 全部用 RESTRICT（預設）：刪除路線前必須先解除訂單綁定，
  -- 避免訂單被留在 SCHEDULED 卻沒有路線的不一致狀態
  CONSTRAINT fk_orders_store     FOREIGN KEY (store_id)            REFERENCES stores (id),
  CONSTRAINT fk_orders_warehouse FOREIGN KEY (warehouse_id)        REFERENCES warehouses (id),
  CONSTRAINT fk_orders_route     FOREIGN KEY (route_id)            REFERENCES routes (id),
  CONSTRAINT fk_orders_vehicle   FOREIGN KEY (assigned_vehicle_id) REFERENCES vehicles (id),
  CONSTRAINT fk_orders_driver    FOREIGN KEY (assigned_driver_id)  REFERENCES drivers (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ══════════════════════════════════════════════════════════════
-- 以下五張表目前尚無程式使用，維持 Hibernate 原本產生的結構，
-- 外鍵與索引等實作該功能時再一併設計。
-- ══════════════════════════════════════════════════════════════

CREATE TABLE delivery_records (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  order_id            BIGINT      NOT NULL,
  arrived_at          DATETIME(6) DEFAULT NULL,
  delivered_at        DATETIME(6) DEFAULT NULL,
  delivered_box_count INT         DEFAULT NULL,
  lat                 DOUBLE      DEFAULT NULL,
  lng                 DOUBLE      DEFAULT NULL,
  photo_url           VARCHAR(500) DEFAULT NULL,
  notes               VARCHAR(500) DEFAULT NULL,
  no_signature        BIT(1)      NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE exception_cases (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  order_id    BIGINT       DEFAULT NULL,
  type        ENUM('DRIVER_REPORT','NO_SIGNATURE','PHONE_HANDLED') NOT NULL,
  description VARCHAR(1000) DEFAULT NULL,
  created_at  DATETIME(6)  NOT NULL,
  handled_by  VARCHAR(50)  DEFAULT NULL,
  handled_at  DATETIME(6)  DEFAULT NULL,
  resolution  VARCHAR(1000) DEFAULT NULL,
  status      ENUM('CLOSED','OPEN') NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE gps_pings (
  id        BIGINT      NOT NULL AUTO_INCREMENT,
  driver_id BIGINT      NOT NULL,
  lat       DOUBLE      NOT NULL,
  lng       DOUBLE      NOT NULL,
  timestamp DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_gps_driver_time (driver_id, timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE mileage_logs (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  driver_id      BIGINT      NOT NULL,
  `date`         DATE        NOT NULL,
  start_odometer INT         DEFAULT NULL,
  end_odometer   INT         DEFAULT NULL,
  start_time     DATETIME(6) DEFAULT NULL,
  end_time       DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE distance_matrix_cache (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  from_type  ENUM('STORE','WAREHOUSE') NOT NULL,
  from_id    BIGINT      NOT NULL,
  to_type    ENUM('STORE','WAREHOUSE') NOT NULL,
  to_id      BIGINT      NOT NULL,
  distance   INT         NOT NULL,
  duration   INT         NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_matrix_from_to (from_type, from_id, to_type, to_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

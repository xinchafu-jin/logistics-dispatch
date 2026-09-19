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
DROP TABLE IF EXISTS template_stops;
DROP TABLE IF EXISTS template_routes;
DROP TABLE IF EXISTS dispatch_templates;
-- 舊版常配編組（單倉單車），已被上面三張表取代
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
  -- AI 助理 API Key：密文（AES-GCM，16 進位）、末 4 碼（畫面辨識用）、最後設定時間
  ai_api_key_encrypted  VARCHAR(512) DEFAULT NULL,
  ai_api_key_last4      VARCHAR(4)   DEFAULT NULL,
  ai_api_key_updated_at DATETIME(6)  DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admin_users_account (account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ══════════════════════════════════════════════════════════════
-- 常配編組（可重複套用的整天排班樣板）
--
-- 三層：編組 → 路線（一倉一車）→ 站點（門市 + 順序）。
-- 一個編組可以涵蓋多個倉庫，因為第二層每一列各自帶 warehouse_id；
-- 第一層完全不碰倉庫。
--
-- 樣板存的是「門市」不是「訂單」——訂單綁日期、會完成會取消，
-- 不能當樣板內容。套用時才去找當天這些門市各有哪些已確認訂單。
-- ══════════════════════════════════════════════════════════════

CREATE TABLE dispatch_templates (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  name       VARCHAR(100) NOT NULL,
  notes      VARCHAR(500) DEFAULT NULL,
  created_at DATETIME(6)  NOT NULL,
  updated_at DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dispatch_templates_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE template_routes (
  id           BIGINT NOT NULL AUTO_INCREMENT,
  template_id  BIGINT NOT NULL,
  -- 設定當下認定的倉庫。刻意跟 vehicles.warehouse_id 存兩份：
  -- 車輛調倉後兩者會不一致，套用時據此擋下並提示重新設定，
  -- 不存的話那條線只會靜默消失（門市的訂單屬於舊倉，撈不到）
  warehouse_id BIGINT NOT NULL,
  vehicle_id   BIGINT NOT NULL,
  PRIMARY KEY (id),
  -- 同一個編組裡一台車只出現一次
  UNIQUE KEY uk_tpl_routes_tpl_vehicle (template_id, vehicle_id),
  -- 編組刪除時路線一併刪除（沒有獨立存在的意義）
  CONSTRAINT fk_tpl_routes_template  FOREIGN KEY (template_id)  REFERENCES dispatch_templates (id) ON DELETE CASCADE,
  CONSTRAINT fk_tpl_routes_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
  CONSTRAINT fk_tpl_routes_vehicle   FOREIGN KEY (vehicle_id)   REFERENCES vehicles (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE template_stops (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  template_route_id BIGINT NOT NULL,
  store_id          BIGINT NOT NULL,
  -- 配送順序。套用時照這個順序組 orderIds 交給 reassign，
  -- reassign 不會重排（陣列順序即配送順序），所以會原封變成 orders.sequence
  `sequence`        INT    NOT NULL,
  PRIMARY KEY (id),
  -- 同一條路線裡一間門市只跑一次
  UNIQUE KEY uk_template_stops (template_route_id, store_id),
  KEY idx_template_stops_seq (template_route_id, `sequence`),
  CONSTRAINT fk_template_stops_route FOREIGN KEY (template_route_id) REFERENCES template_routes (id) ON DELETE CASCADE,
  CONSTRAINT fk_template_stops_store FOREIGN KEY (store_id)          REFERENCES stores (id)
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
  -- 一個司機一天只開一條路線。MySQL 的 UNIQUE 不把多個 NULL 視為重複，
  -- 所以草稿階段一堆尚未指派（driver_id IS NULL）的路線不會互相衝突。
  -- Service 層要先擋一次並給看得懂的訊息，這裡是繞過 API 時的最後一道。
  UNIQUE KEY uk_routes_date_driver (`date`, driver_id),
  KEY idx_routes_date_warehouse (`date`, warehouse_id),
  CONSTRAINT fk_routes_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id),
  CONSTRAINT fk_routes_vehicle   FOREIGN KEY (vehicle_id)   REFERENCES vehicles (id),
  CONSTRAINT fk_routes_driver    FOREIGN KEY (driver_id)    REFERENCES drivers (id),
  -- 刪編組不該擋住，路線只是失去來源標記
  CONSTRAINT fk_routes_template  FOREIGN KEY (template_id)  REFERENCES dispatch_templates (id) ON DELETE SET NULL
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
  status              ENUM('PENDING_CONFIRM','CONFIRMED',
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

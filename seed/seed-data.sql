-- 排車測試資料
-- 高雄地區：2 個倉庫 + 10 個門市 + 5 台車 + 8 位司機 + 21 張訂單
--
-- 兩個倉庫各自有車、各自有訂單，用來驗證「排車以倉庫為單位、車輛不跨倉」：
--   WH001 楠梓物流中心（楠梓區，北高雄）3 台車 / 15 張訂單
--   WH002 小港轉運站（小港區，南高雄）  2 台車 / 6 張訂單
-- 門市是共用的，同一間門市可以同時收到兩個倉庫出的貨。
-- 座標都用 OSRM 的 nearest 確認過離道路不到 150 公尺，地址的路名取自 OSRM 對到的道路。
--
-- 使用方式：
--   mysql -u root -proot logistics < seed/seed-data.sql
--   或直接貼進 IDEA 的 SQL console 執行
--
-- 訂單的 delivery_date 用 CURDATE()（今天），排車時傳今天的日期即可。
-- 若要測其他日期，把 CURDATE() 換成 '2026-09-21' 這類固定日期。

USE logistics;

SET FOREIGN_KEY_CHECKS = 0;

-- 清空舊資料
DELETE FROM orders;
DELETE FROM routes;
DELETE FROM vehicles;
DELETE FROM drivers;
DELETE FROM stores;
DELETE FROM warehouses;
DELETE FROM driver_messages;
DELETE FROM attendance_records;
DELETE FROM mileage_logs;
DELETE FROM gps_pings;

ALTER TABLE orders     AUTO_INCREMENT = 1;
ALTER TABLE routes     AUTO_INCREMENT = 1;
ALTER TABLE vehicles   AUTO_INCREMENT = 1;
ALTER TABLE drivers    AUTO_INCREMENT = 1;
ALTER TABLE stores     AUTO_INCREMENT = 1;
ALTER TABLE warehouses AUTO_INCREMENT = 1;

SET FOREIGN_KEY_CHECKS = 1;

-- ══════════════════════════════════════════
-- 倉庫（id = 1~2）
-- ══════════════════════════════════════════
INSERT INTO warehouses (warehouse_code, name, address, lat, lng, phone, is_active) VALUES
('WH001', '楠梓物流中心', '高雄市楠梓區東六街', 22.7240, 120.3070, '07-3610000', b'1'),
('WH002', '小港轉運站',   '高雄市小港區紹興街', 22.5650, 120.3550, '07-8010000', b'1');

-- ══════════════════════════════════════════
-- 門市（id = 1~10，高雄各區）
-- 收貨時段大多 09:00-18:00，刻意讓兩家較短以便測試時間窗
-- 北高雄（左營、鼓山、楠梓、岡山、路竹、鹽埕）多半由 WH001 出貨，南高雄（前鎮、鳳山、林園）由 WH002，
-- 三民在中間，兩倉都會送（對應 gen-demo-data.py 的 WAREHOUSE_STORES）
-- ══════════════════════════════════════════
INSERT INTO stores (store_code, name, address, lat, lng, contact_name, phone, receiving_start, receiving_end, notes, status) VALUES
('KH001', '三民門市', '高雄市三民區建工路', 22.6505, 120.3285, '王小明', '07-3800001', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('KH002', '左營門市', '高雄市左營區博愛二路', 22.6690, 120.3030, '李大華', '07-5560002', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('KH003', '鼓山門市', '高雄市鼓山區美術東二路', 22.6570, 120.2870, '陳美玲', '07-5550003', '10:00:00', '17:00:00', '後門卸貨', 'ACTIVE'),
('KH004', '前鎮門市', '高雄市前鎮區時代大道', 22.5960, 120.3070, '張志豪', '07-8230004', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('KH005', '鳳山門市', '高雄市鳳山區曹公路',   22.6280, 120.3570, '林淑芬', '07-7400005', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('KH006', '林園門市', '高雄市林園區福興街',   22.5030, 120.3950, '黃建國', '07-6430006', '09:00:00', '17:00:00', NULL,       'ACTIVE'),
('KH007', '岡山門市', '高雄市岡山區民有路',   22.7920, 120.2980, '吳雅婷', '07-6210007', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('KH008', '路竹門市', '高雄市路竹區中正路',   22.8560, 120.2650, '劉俊傑', '07-6970008', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('KH009', '鹽埕門市', '高雄市鹽埕區新樂街',   22.6240, 120.2855, '蔡佩君', '07-5310009', '10:00:00', '16:00:00', '巷弄狹窄', 'ACTIVE'),
('KH010', '楠梓門市', '高雄市楠梓區楠梓新路', 22.7280, 120.3250, '鄭文彬', '07-3510010', '09:00:00', '18:00:00', NULL,       'ACTIVE');

-- ══════════════════════════════════════════
-- 車輛（id = 1~5，1~3 屬倉庫 1、4~5 屬倉庫 2）
-- 容量單位是「箱」，刻意設不同容量以驗證混合車隊
-- 倉庫 1 總容量 = 40+60+30 = 130 箱
-- ══════════════════════════════════════════
INSERT INTO vehicles (warehouse_id, plate_number, vehicle_type, capacity, fuel_consumption, status) VALUES
-- WH001 楠梓物流中心
(1, 'KH-1001', '3.5噸貨車', 40, 8.5,  'AVAILABLE'),
(1, 'KH-1002', '5噸貨車',   60, 6.2,  'AVAILABLE'),
(1, 'KH-1003', '小貨車',    30, 12.0, 'AVAILABLE'),
-- WH002 小港轉運站
(2, 'KH-2001', '3.5噸貨車', 40, 8.0,  'AVAILABLE'),
(2, 'KH-2002', '小貨車',    25, 11.5, 'AVAILABLE');

-- ══════════════════════════════════════════
-- 司機（id = 1~3）
-- password 為 BCrypt 雜湊，明文皆為 driver123（已實測驗證，改動時請重新產生並驗證）
-- ══════════════════════════════════════════
INSERT INTO drivers (account, name, phone, work_start, work_end, rest_duration, max_overtime_minutes, is_active, password) VALUES
('D001', '陳大明', '0912345001', '08:30:00', '17:30:00', 90, 120, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK'),
('D002', '林志偉', '0912345002', '08:30:00', '17:30:00', 90, 120, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK'),
('D003', '黃俊宏', '0912345003', '08:30:00', '17:30:00', 90, 120, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK'),
-- D006 早退、D007 早班且加班上限較低，用來驗證班表與工時限制不是寫死的
('D004', '張家豪', '0912345004', '08:30:00', '17:30:00', 90, 120, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK'),
('D005', '李冠廷', '0912345005', '08:30:00', '17:30:00', 90, 120, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK'),
('D006', '王詩涵', '0912345006', '09:00:00', '18:00:00', 60, 120, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK'),
('D007', '吳建良', '0912345007', '07:30:00', '16:30:00', 90,  60, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK'),
('D008', '蔡佩君', '0912345008', '08:30:00', '17:30:00', 90, 120, b'1', '$2y$10$jxVZZFbObH8dfuO3/7J5vec3UufLq5ShSDPNQ50dU/ahFHuiziyyK');

-- ══════════════════════════════════════════
-- 訂單（15 張，狀態 CONFIRMED、倉庫 1、今天配送）
-- 總箱數 = 118 箱，車隊總容量 130 箱 → 應該全部排得下
-- 三民(KH001)與左營(KH002)各有兩張不同商家的訂單，可驗證同門市多單
-- ══════════════════════════════════════════
INSERT INTO orders
(order_number, store_id, warehouse_id, source_vendor, item_description, box_count, notes, delivery_date, status, created_at, updated_at) VALUES
('DO-TEST-001', 1,  1, '商家甲', '民生用品', 8,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-002', 1,  1, '商家乙', '飲料',     6,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-003', 2,  1, '商家甲', '民生用品', 12, NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-004', 2,  1, '商家丙', '清潔用品', 5,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-005', 3,  1, '商家甲', '民生用品', 10, NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-006', 4,  1, '商家乙', '飲料',     7,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-007', 5,  1, '商家甲', '民生用品', 9,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-008', 6,  1, '商家丙', '清潔用品', 6,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-009', 7,  1, '商家甲', '民生用品', 11, NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-010', 8,  1, '商家乙', '飲料',     8,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-011', 9,  1, '商家甲', '民生用品', 7,  '巷弄狹窄，小車配送', CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-012', 10, 1, '商家丙', '清潔用品', 9,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-013', 4,  1, '商家甲', '民生用品', 6,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-014', 7,  1, '商家丙', '清潔用品', 8,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-015', 10, 1, '商家乙', '飲料',     6,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
-- WH002 小港轉運站，服務南高雄門市（三民、前鎮、鳳山、林園）
-- 總計 52 箱，車隊容量 65 箱，正常情況下裝得完
('DO-TEST-201', 4,  2, '商家丁', '生鮮',     12, NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-202', 4,  2, '商家戊', '冷凍食品', 9,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-203', 5,  2, '商家丁', '生鮮',     10, NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-204', 6,  2, '商家戊', '冷凍食品', 8,  '臨海工業區，注意大型車進出', CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-205', 1,  2, '商家丁', '生鮮',     7,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-206', 5,  2, '商家戊', '冷凍食品', 6,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW());

-- ══════════════════════════════════════════
-- 確認結果
-- ══════════════════════════════════════════
SELECT 'warehouses' AS 資料表, COUNT(*) AS 筆數 FROM warehouses
UNION ALL SELECT 'stores',   COUNT(*) FROM stores
UNION ALL SELECT 'vehicles', COUNT(*) FROM vehicles
UNION ALL SELECT 'drivers',  COUNT(*) FROM drivers
UNION ALL SELECT 'orders',   COUNT(*) FROM orders;

-- 各倉庫的訂單量與車隊容量，確認裝得下
SELECT
    w.warehouse_code AS 倉庫,
    w.name           AS 名稱,
    (SELECT COUNT(*)       FROM orders   o WHERE o.warehouse_id = w.id AND o.status = 'CONFIRMED') AS 訂單數,
    (SELECT SUM(box_count) FROM orders   o WHERE o.warehouse_id = w.id AND o.status = 'CONFIRMED') AS 總箱數,
    (SELECT COUNT(*)       FROM vehicles v WHERE v.warehouse_id = w.id AND v.status = 'AVAILABLE') AS 車輛數,
    (SELECT SUM(capacity)  FROM vehicles v WHERE v.warehouse_id = w.id AND v.status = 'AVAILABLE') AS 車隊容量
FROM warehouses w ORDER BY w.id;

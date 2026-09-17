-- 排車測試資料
-- 台南地區：2 個倉庫 + 10 個門市 + 5 台車 + 3 位司機 + 21 張訂單
--
-- 兩個倉庫各自有車、各自有訂單，用來驗證「排車以倉庫為單位、車輛不跨倉」：
--   WH001 莊敬物流中心（安南區，西北）3 台車 / 15 張訂單
--   WH002 仁德轉運站（仁德區，東南）  2 台車 / 6 張訂單
-- 門市是共用的，同一間門市可以同時收到兩個倉庫出的貨。
--
-- 使用方式：
--   mysql -u root -proot logistics < seed/seed-data.sql
--   或直接貼進 IDEA 的 SQL console 執行
--
-- 訂單的 delivery_date 用 CURDATE()（今天），排車時傳今天的日期即可。
-- 若要測其他日期，把 CURDATE() 換成 '2026-09-21' 這類固定日期。

USE logistics;

-- 清空舊資料（依外鍵相依順序反向刪除）
DELETE FROM orders;
DELETE FROM routes;
DELETE FROM fuel_price_history;
DELETE FROM vehicles;
DELETE FROM drivers;
DELETE FROM stores;
DELETE FROM warehouses;

ALTER TABLE orders     AUTO_INCREMENT = 1;
ALTER TABLE routes     AUTO_INCREMENT = 1;
ALTER TABLE fuel_price_history AUTO_INCREMENT = 1;
ALTER TABLE vehicles   AUTO_INCREMENT = 1;
ALTER TABLE drivers    AUTO_INCREMENT = 1;
ALTER TABLE stores     AUTO_INCREMENT = 1;
ALTER TABLE warehouses AUTO_INCREMENT = 1;

-- ══════════════════════════════════════════
-- 倉庫（id = 1）
-- ══════════════════════════════════════════
INSERT INTO warehouses (warehouse_code, name, address, lat, lng, phone, is_active) VALUES
('WH001', '莊敬物流中心', '台南市安南區工業二路100號', 23.0355, 120.1875, '06-2846000', b'1'),
('WH002', '仁德轉運站',   '台南市仁德區中山路500號',   22.9700, 120.2530, '06-2793000', b'1');

-- ══════════════════════════════════════════
-- 門市（id = 1~10，台南各區真實位置）
-- 收貨時段大多 09:00-18:00，刻意讓兩家較短以便測試時間窗
-- ══════════════════════════════════════════
INSERT INTO stores (store_code, name, address, lat, lng, contact_name, phone, receiving_start, receiving_end, notes, status) VALUES
('TN001', '東區門市',   '台南市東區中華東路三段',   22.9908, 120.2255, '王小明', '06-2350001', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('TN002', '永康門市',   '台南市永康區中正南路',     23.0264, 120.2578, '李大華', '06-2320002', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('TN003', '安平門市',   '台南市安平區安平路',       22.9977, 120.1601, '陳美玲', '06-2260003', '10:00:00', '17:00:00', '後門卸貨', 'ACTIVE'),
('TN004', '仁德門市',   '台南市仁德區中山路',       22.9722, 120.2515, '張志豪', '06-2790004', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('TN005', '歸仁門市',   '台南市歸仁區中山路二段',   22.9672, 120.2938, '林淑芬', '06-2300005', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('TN006', '關廟門市',   '台南市關廟區中正路',       22.9622, 120.3277, '黃建國', '06-5950006', '09:00:00', '17:00:00', NULL,       'ACTIVE'),
('TN007', '新市門市',   '台南市新市區中山路',       23.0781, 120.2954, '吳雅婷', '06-5890007', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('TN008', '善化門市',   '台南市善化區中山路',       23.1197, 120.2966, '劉俊傑', '06-5810008', '09:00:00', '18:00:00', NULL,       'ACTIVE'),
('TN009', '中西區門市', '台南市中西區民權路二段',   22.9950, 120.2010, '蔡佩君', '06-2210009', '10:00:00', '16:00:00', '巷弄狹窄', 'ACTIVE'),
('TN010', '北區門市',   '台南市北區公園路',         23.0035, 120.2103, '鄭文彬', '06-2250010', '09:00:00', '18:00:00', NULL,       'ACTIVE');

-- ══════════════════════════════════════════
-- 車輛（id = 1~3，全部隸屬倉庫 1）
-- 容量單位是「箱」，刻意設不同容量以驗證混合車隊
-- 總容量 = 40+60+30 = 130 箱
-- ══════════════════════════════════════════
INSERT INTO vehicles (warehouse_id, plate_number, vehicle_type, capacity, fuel_consumption, status) VALUES
-- WH001 莊敬物流中心
(1, 'TN-1001', '3.5噸貨車', 40, 8.5,  'AVAILABLE'),
(1, 'TN-1002', '5噸貨車',   60, 6.2,  'AVAILABLE'),
(1, 'TN-1003', '小貨車',    30, 12.0, 'AVAILABLE'),
-- WH002 仁德轉運站
(2, 'TN-2001', '3.5噸貨車', 40, 8.0,  'AVAILABLE'),
(2, 'TN-2002', '小貨車',    25, 11.5, 'AVAILABLE');

-- 貨車使用的超級柴油歷史牌價（元／公升）。
INSERT INTO fuel_price_history
(fuel_type, price_per_liter, effective_from, source, fetched_at) VALUES
('DIESEL', 29.300, '2026-08-24 00:00:00', 'CPC_OFFICIAL_HISTORY', '2026-09-14 15:37:08.253538'),
('DIESEL', 29.300, '2026-08-31 00:00:00', 'CPC_OFFICIAL_HISTORY', '2026-09-14 15:37:08.253538'),
('DIESEL', 29.300, '2026-09-07 00:00:00', 'CPC_OFFICIAL_HISTORY', '2026-09-14 15:37:08.253538'),
('DIESEL', 29.900, '2026-09-14 00:00:00', 'CPC_OFFICIAL_HISTORY', '2026-09-14 15:37:08.253538');

-- ══════════════════════════════════════════
-- 司機（id = 1~3）
-- password 為 BCrypt 雜湊，明文皆為 driver123
-- ══════════════════════════════════════════
INSERT INTO drivers (account, name, phone, work_start, work_end, rest_duration, max_overtime_minutes, is_active, password) VALUES
('D001', '陳大明', '0912345001', '08:30:00', '17:30:00', 90, 120, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'),
('D002', '林志偉', '0912345002', '08:30:00', '17:30:00', 90, 120, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'),
('D003', '黃俊宏', '0912345003', '08:30:00', '17:30:00', 90, 120, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'),
-- D006 早退、D007 早班且加班上限較低，用來驗證班表與工時限制不是寫死的
('D004', '張家豪', '0912345004', '08:30:00', '17:30:00', 90, 120, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'),
('D005', '李冠廷', '0912345005', '08:30:00', '17:30:00', 90, 120, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'),
('D006', '王詩涵', '0912345006', '09:00:00', '18:00:00', 60, 120, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'),
('D007', '吳建良', '0912345007', '07:30:00', '16:30:00', 90,  60, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'),
('D008', '蔡佩君', '0912345008', '08:30:00', '17:30:00', 90, 120, b'1', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy');

-- ══════════════════════════════════════════
-- 訂單（15 張，狀態 CONFIRMED、倉庫 1、今天配送）
-- 總箱數 = 118 箱，車隊總容量 130 箱 → 應該全部排得下
-- 東區(TN001)與永康(TN002)各有兩張不同商家的訂單，可驗證同門市多單
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
-- WH002 仁德轉運站，服務東南側門市（東區、仁德、歸仁、關廟）
-- 總計 52 箱，車隊容量 65 箱，正常情況下裝得完
('DO-TEST-201', 4,  2, '商家丁', '生鮮',     12, NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-202', 4,  2, '商家戊', '冷凍食品', 9,  NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-203', 5,  2, '商家丁', '生鮮',     10, NULL, CURDATE(), 'CONFIRMED', NOW(), NOW()),
('DO-TEST-204', 6,  2, '商家戊', '冷凍食品', 8,  '山區路段，注意配送時間', CURDATE(), 'CONFIRMED', NOW(), NOW()),
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

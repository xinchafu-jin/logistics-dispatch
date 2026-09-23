-- 把測試資料搬到「今天」
--
-- 用途：seed-data.sql 是用 CURDATE() 灌的，灌完隔天資料就過期，
-- 司機端的今日任務會變空。這支把最新一批資料整批搬到今天，不用重灌整個資料庫。
--
-- 執行：mysql -u root -proot --default-character-set=utf8mb4 logistics < seed/refresh-today.sql

USE logistics;

-- ── 訂單 ────────────────────────────────────────────────
-- 把最新那一天的整批搬到今天
SET @lastOrderDate = (SELECT MAX(delivery_date) FROM orders);
UPDATE orders SET delivery_date = CURDATE() WHERE delivery_date = @lastOrderDate;

-- ── 路線 ────────────────────────────────────────────────
-- 一台車一天只能有一條路線（uk_routes_date_vehicle），
-- 先清掉今天既有的，避免搬過來時撞唯一鍵
SET @lastRouteDate = (SELECT MAX(date) FROM routes);
DELETE FROM routes WHERE date = CURDATE() AND date <> @lastRouteDate;
UPDATE routes SET date = CURDATE() WHERE date = @lastRouteDate;

-- ── 解除孤兒連結 ────────────────────────────────────────
-- 上面只搬「最新那一天」的路線，比它更舊的路線留在原本的日期。
-- 但訂單全部被搬到今天了，於是出現「訂單是今天、掛的路線是舊日期」的矛盾，
-- 看板與司機端會讀到不一致的資料。這裡把那些連結解掉，讓它們回到未排入狀態。
UPDATE orders o
LEFT JOIN routes r ON r.id = o.route_id
SET o.route_id = NULL,
    o.assigned_vehicle_id = NULL,
    o.assigned_driver_id = NULL,
    o.sequence = NULL
WHERE o.delivery_date = CURDATE()
  AND o.route_id IS NOT NULL
  AND (r.id IS NULL OR r.date <> CURDATE());

-- ── 班表 ────────────────────────────────────────────────
-- 今天沒有班次的司機補上，掛在當月已發布的班表底下。
-- 沒有當月班表就整段跳過（EXISTS 條件），不會硬塞出孤兒班次。
INSERT INTO driver_shifts
  (schedule_month_id, driver_id, work_date, shift_type, work_start, work_end, overtime_minutes, last_modified_at, version)
SELECT
  (SELECT id FROM schedule_months WHERE schedule_month = DATE_FORMAT(CURDATE(), '%Y-%m-01')),
  d.id, CURDATE(), 'WORK', d.work_start, d.work_end, 0, NOW(), 0
FROM drivers d
WHERE EXISTS (SELECT 1 FROM schedule_months WHERE schedule_month = DATE_FORMAT(CURDATE(), '%Y-%m-01'))
  AND NOT EXISTS (SELECT 1 FROM driver_shifts ds WHERE ds.driver_id = d.id AND ds.work_date = CURDATE());

-- ── 出勤紀錄 ────────────────────────────────────────────
-- 出勤是綁日期的，過期的清掉讓司機今天可以重新打卡
DELETE FROM attendance_records WHERE work_date <> CURDATE();

-- ── 結果確認 ────────────────────────────────────────────
SELECT
  CURDATE()                                                                    AS today,
  (SELECT COUNT(*) FROM orders        WHERE delivery_date = CURDATE())          AS orders_today,
  (SELECT COUNT(*) FROM routes        WHERE date = CURDATE())                   AS routes_today,
  (SELECT COUNT(*) FROM driver_shifts WHERE work_date = CURDATE())              AS shifts_today,
  (SELECT COUNT(*) FROM orders o LEFT JOIN routes r ON r.id = o.route_id
     WHERE o.delivery_date = CURDATE() AND o.route_id IS NOT NULL
       AND (r.id IS NULL OR r.date <> CURDATE()))                               AS orphan_links;

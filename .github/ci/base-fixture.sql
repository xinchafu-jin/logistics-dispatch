-- CI 專用的最小基礎資料（全部是合成的，只存在於 GitHub Actions 的臨時 MySQL，不會進正式環境）。
--
-- 為什麼需要：部分整合測試假設資料庫已經有資料——直接查最小 id 的倉庫與門市、要求司機 id 1、2 存在、
-- 至少有一位主管；空資料庫上會在 setUp 就 NullPointerException。
-- 測試自己建立的資料一律帶標記（例如 LOADTEST）並在結束時清掉，所以這裡不放訂單、路線。
--
-- 這裡沒有任何可登入的帳密：
--   * 司機的 password 欄位留 NULL
--   * 主管的 password 是佔位字串，不是 BCrypt 雜湊，任何登入都會失敗
--   測試用 JwtEncoder 直接簽 token，不走登入。
--
-- 載入時機：Flyway 建完表之後、跑測試之前（見 .github/workflows/ci.yml）。

-- 前兩個是啟用的：測試用「最小 id」與「啟用中的前兩個」當倉庫；
-- 第三個是停用的：DriverWarehouseApiTest 要拿停用倉庫驗證「司機不能轉到停用倉庫」，沒有就會判成功而失敗
INSERT INTO warehouses (warehouse_code, name, address, lat, lng, is_active) VALUES
('CI-WH1', 'CI 倉庫一', 'CI 測試地址一', 23.0355, 120.1875, b'1'),
('CI-WH2', 'CI 倉庫二', 'CI 測試地址二', 22.9700, 120.2530, b'1'),
('CI-WH3', 'CI 停用倉庫', 'CI 測試地址三', 23.0000, 120.2000, b'0');

-- 座標都給：有的測試會挑「有座標的門市」，收貨時段都涵蓋白天
INSERT INTO stores (store_code, name, address, lat, lng, receiving_start, receiving_end, status) VALUES
('CI-S1', 'CI 門市 1', 'CI 測試地址', 22.9908, 120.2255, '09:00:00', '18:00:00', 'ACTIVE'),
('CI-S2', 'CI 門市 2', 'CI 測試地址', 23.0264, 120.2578, '09:00:00', '18:00:00', 'ACTIVE'),
('CI-S3', 'CI 門市 3', 'CI 測試地址', 22.9977, 120.1601, '09:00:00', '18:00:00', 'ACTIVE'),
('CI-S4', 'CI 門市 4', 'CI 測試地址', 22.9722, 120.2515, '09:00:00', '18:00:00', 'ACTIVE'),
('CI-S5', 'CI 門市 5', 'CI 測試地址', 22.9672, 120.2938, '09:00:00', '18:00:00', 'ACTIVE'),
('CI-S6', 'CI 門市 6', 'CI 測試地址', 23.0781, 120.2954, '09:00:00', '18:00:00', 'ACTIVE');

INSERT INTO drivers (id, account, name, work_start, work_end, rest_duration, is_active) VALUES
(1, 'ci-driver-1', 'CI 司機 1', '08:30:00', '17:30:00', 90, b'1'),
(2, 'ci-driver-2', 'CI 司機 2', '08:30:00', '17:30:00', 90, b'1'),
(3, 'ci-driver-3', 'CI 司機 3', '08:30:00', '17:30:00', 90, b'1');

INSERT INTO admin_users (id, account, password, name) VALUES
(1, 'ci-admin', 'not-a-password-hash', 'CI 主管');

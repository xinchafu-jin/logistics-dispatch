-- 保養間隔改成每台車自己設定，拿掉 V11 的「同噸位共用規則」。
--
-- 1. vehicles 加三欄，是這台車自己的保養與退役規則：
--    minor_maintenance_interval_km  每跑多少公里要小保
--    major_maintenance_interval_km  每跑多少公里要大保
--    retirement_km                  行車紀錄器里程到這個數字就該退役，不會因為保養而重設
--    三個一起填、或都不填（都不填＝還沒設定，只提醒、不擋）。
--    跟保養基準（last_*_maintenance_km）不同，這三個是主管訂的規則，隨時可以在畫面上改。
--
-- 2. 原本各噸位規則的數字先帶到那個噸位的每台車，再刪掉 vehicle_maintenance_policies 和 vehicles.tonnage：
--    噸位只拿來對應規則，沒有其他地方用到（容量另有 capacity，單位是箱）。

ALTER TABLE vehicles
    ADD COLUMN minor_maintenance_interval_km INT DEFAULT NULL,
    ADD COLUMN major_maintenance_interval_km INT DEFAULT NULL,
    ADD COLUMN retirement_km                 INT DEFAULT NULL;

UPDATE vehicles v
    JOIN vehicle_maintenance_policies p ON p.tonnage = v.tonnage
SET v.minor_maintenance_interval_km = p.minor_interval_km,
    v.major_maintenance_interval_km = p.major_interval_km,
    v.retirement_km                 = p.retirement_km;

DROP TABLE vehicle_maintenance_policies;

ALTER TABLE vehicles
    DROP COLUMN tonnage;

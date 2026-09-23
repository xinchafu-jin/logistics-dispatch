-- 編組的每條路線可以記一位預設司機，套用編組時帶入。
-- NULL 表示這條線沒有固定司機，套用後由調度員指派。
--
-- uk_tpl_routes_tpl_driver：同一個編組裡，一位司機只能是一條線的預設。
--   編組是套用到同一天的，而一位司機一天只能開一條線（routes 的 uk_routes_date_driver），
--   同一人掛兩條線的編組套不出來。MySQL 的 UNIQUE 不把多個 NULL 視為重複，
--   沒有預設司機的線不會互相衝突。
-- fk_tpl_routes_driver：刪除司機不該被編組擋住。預設司機只是偏好，清成 NULL 即可。
ALTER TABLE template_routes
    ADD COLUMN driver_id BIGINT NULL AFTER vehicle_id,
    ADD CONSTRAINT uk_tpl_routes_tpl_driver UNIQUE (template_id, driver_id),
    ADD CONSTRAINT fk_tpl_routes_driver FOREIGN KEY (driver_id) REFERENCES drivers (id) ON DELETE SET NULL;

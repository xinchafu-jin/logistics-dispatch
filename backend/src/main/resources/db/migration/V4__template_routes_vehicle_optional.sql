-- 編組改成存「格子」：一格是一趟車，司機、車輛都是選填（至少填一個，由 TemplatesService 檢查）。
-- 只排人、還沒決定車的格子要存得下來，所以 vehicle_id 改成可以是 NULL。
-- 外鍵 fk_tpl_routes_vehicle 照舊；唯一鍵 (template_id, vehicle_id) 不把多個 NULL 視為重複，
-- 沒選車的格子不會互相衝突。
ALTER TABLE template_routes
    MODIFY COLUMN vehicle_id BIGINT NULL;

-- 發布時存下每條路線、每一段的預定道路形狀（OSRM overview=full 的完整形狀）。
--
-- 用途：
--   1. 後台地圖畫已發布的路線時沿實際道路畫線（草稿仍畫門市之間的直線）
--   2. 偏離預定路線判斷：司機的 GPS 點跟「目前這一段」的形狀比距離
--
-- 切段方式跟 RoutePlanMetricsService.calculatePlan 相同：倉庫 → 各門市（同一門市只算一站）→ 回倉。
-- 欄位命名比照 route_leg_mileages（實際里程），之後可以用 route_id + sequence 對照預定與實際。
--
-- route_id 設 ON DELETE CASCADE：草稿路線是用 routesDAO.deleteAllById 刪的（DispatchDraftService、DispatchService），
-- 刪路線時這張表的資料由資料庫一起刪掉，不會因為還有資料參照而刪不掉路線。

CREATE TABLE IF NOT EXISTS route_planned_legs (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    route_id         BIGINT      NOT NULL,
    sequence         INT         NOT NULL,
    from_type        ENUM ('STORE', 'WAREHOUSE') NOT NULL,
    from_store_id    BIGINT      DEFAULT NULL,
    to_type          ENUM ('STORE', 'WAREHOUSE') NOT NULL,
    to_store_id      BIGINT      DEFAULT NULL,
    distance_meters  DOUBLE      NOT NULL,
    duration_seconds DOUBLE      NOT NULL,
    -- 道路形狀：JSON 陣列 [[經度, 緯度], ...]，就是 OSRM geometries=geojson 回的 coordinates，
    -- 跟 MapLibre 同一個順序，前端畫圖不用轉。一段 15 公里約 260 點、6 KB；
    -- 用 MEDIUMTEXT（上限 16 MB）而不是 TEXT（64 KB），長途的一段點數多也放得下
    path             MEDIUMTEXT  NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_planned_legs_route_sequence (route_id, sequence),
    CONSTRAINT fk_route_planned_legs_route FOREIGN KEY (route_id) REFERENCES routes (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

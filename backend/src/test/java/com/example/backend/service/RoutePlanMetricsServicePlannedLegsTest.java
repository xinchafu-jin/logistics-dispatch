package com.example.backend.service;

import com.example.backend.constants.RouteLegLocationType;
import com.example.backend.dao.RoutePlannedLegsDAO;
import com.example.backend.dto.respones.PlannedPathResponse;
import com.example.backend.entity.RoutePlannedLegsEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 發布時存預定路線形狀（calculateAndStore）、撤回時刪除（deletePlannedPaths）整條走一遍。
 *
 * <p>連本機 OSRM（5001）與本機 DB：形狀是不是真的存進去、段落與起訖對不對，只有真的算一次才知道。
 * 「沒把形狀收集起來」這種錯不會報錯，只會默默存了空清單，所以要實際查資料表。</p>
 *
 * <p>前提：本機 DB 至少有一個倉庫、兩間有座標的門市；本機 OSRM 有開。CI 沒有 OSRM，自動跳過。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class RoutePlanMetricsServicePlannedLegsTest {

    private static final String MARKER = "PLANPATHTEST";
    // 很遠的日期：calculateAndStore 會處理「這一天所有路線」，不能跟本機真實資料混在一起
    private static final LocalDate DAY = LocalDate.of(2099, 2, 1);

    @Autowired
    private RoutePlanMetricsService routePlanMetricsService;

    @Autowired
    private RoutePlannedLegsDAO routePlannedLegsDAO;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    private long warehouseId;
    private long routeId;
    private long firstStoreId;
    private long secondStoreId;

    @BeforeEach
    void setUp() {
        cleanUp();
        warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        List<Long> storeIds = jdbcTemplate.queryForList(
                "SELECT id FROM stores WHERE lat IS NOT NULL AND lng IS NOT NULL ORDER BY id LIMIT 2", Long.class);
        firstStoreId = storeIds.get(0);
        secondStoreId = storeIds.get(1);

        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, MARKER);
        long vehicleId = jdbcTemplate.queryForObject(
                "SELECT id FROM vehicles WHERE plate_number = ?", Long.class, MARKER);
        jdbcTemplate.update("INSERT INTO routes (date, warehouse_id, vehicle_id, status, version) "
                + "VALUES (?, ?, ?, 'DRAFT', 1)", DAY, warehouseId, vehicleId);
        routeId = jdbcTemplate.queryForObject(
                "SELECT id FROM routes WHERE vehicle_id = ? AND date = ?", Long.class, vehicleId, DAY);

        insertOrder(MARKER + "-1", 1, firstStoreId, vehicleId);
        insertOrder(MARKER + "-2", 2, secondStoreId, vehicleId);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM orders WHERE order_number LIKE ?", MARKER + "-%");
        // 刪路線時形狀會被 ON DELETE CASCADE 一起刪掉
        jdbcTemplate.update("DELETE FROM routes WHERE vehicle_id IN "
                + "(SELECT id FROM vehicles WHERE plate_number = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM vehicles WHERE plate_number = ?", MARKER);
    }

    @Test
    void 發布時存下每一段_倉庫出發_依序到各門市_最後回倉() {
        routePlanMetricsService.calculateAndStore(DAY);

        List<RoutePlannedLegsEntity> legs = routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(routeId);
        assertEquals(3, legs.size(), "兩間門市＝倉庫→門市1、門市1→門市2、門市2→回倉，三段");

        assertLeg(legs.get(0), 1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, firstStoreId);
        assertLeg(legs.get(1), 2, RouteLegLocationType.STORE, firstStoreId, RouteLegLocationType.STORE, secondStoreId);
        assertLeg(legs.get(2), 3, RouteLegLocationType.STORE, secondStoreId, RouteLegLocationType.WAREHOUSE, null);
    }

    @Test
    void 重新發布_先刪舊的再存_不會變成兩份也不會撞唯一鍵() {
        routePlanMetricsService.calculateAndStore(DAY);
        routePlanMetricsService.calculateAndStore(DAY);

        assertEquals(3, routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(routeId).size());
    }

    @Test
    void 撤回時刪掉_看路線明細不會存任何形狀() {
        routePlanMetricsService.calculateAndStore(DAY);
        routePlanMetricsService.deletePlannedPaths(DAY);
        assertEquals(0, routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(routeId).size());

        // getMetrics 走 withPath = false，只算里程，不應該寫進任何形狀
        routePlanMetricsService.getMetrics(routeId);
        assertEquals(0, routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(routeId).size());
    }

    @Test
    void 後台畫線_只回已發布的路線_各段依行駛順序() {
        routePlanMetricsService.calculateAndStore(DAY);
        // calculateAndStore 只存形狀，改成已發布是 dispatchService.publish 做的；這時路線還是草稿
        assertTrue(routePlanMetricsService.getPlannedPaths(DAY, warehouseId).isEmpty(), "草稿不畫道路線");

        publishRoute();
        List<PlannedPathResponse> paths = routePlanMetricsService.getPlannedPaths(DAY, warehouseId);

        assertEquals(1, paths.size());
        assertEquals(routeId, paths.get(0).getRouteId());
        List<PlannedPathResponse.Leg> legs = paths.get(0).getLegs();
        assertEquals(3, legs.size());
        assertEquals(List.of(1, 2, 3), legs.stream().map(PlannedPathResponse.Leg::getSequence).toList());
        assertEquals(firstStoreId, legs.get(0).getToStoreId());
        assertEquals(secondStoreId, legs.get(1).getToStoreId());
        assertNull(legs.get(2).getToStoreId(), "最後一段回倉，沒有門市");
        // 字串要解析回陣列，前端才能直接當 GeoJSON 的 coordinates；順序一樣是 [經度, 緯度]
        double[][] path = legs.get(0).getPath();
        assertTrue(path.length > 2, "要是完整道路形狀，不是只有頭尾兩點");
        assertTrue(path[0][0] > 119 && path[0][0] < 122, "第一個數字應該是經度：" + path[0][0]);
    }

    @Test
    void 已發布但沒存形狀_不在回傳裡_前端退回畫直線() {
        // 假資料腳本、V8 上線前發布的路線都是這樣：狀態是已發布，但從沒跑過 calculateAndStore
        publishRoute();

        assertTrue(routePlanMetricsService.getPlannedPaths(DAY, warehouseId).isEmpty());
    }

    private void publishRoute() {
        jdbcTemplate.update("UPDATE routes SET status = 'PUBLISHED' WHERE id = ?", routeId);
    }

    private void assertLeg(RoutePlannedLegsEntity leg, int sequence,
                           RouteLegLocationType fromType, Long fromStoreId,
                           RouteLegLocationType toType, Long toStoreId) {
        assertEquals(sequence, leg.getSequence());
        assertEquals(fromType, leg.getFromType());
        assertEquals(fromStoreId, leg.getFromStoreId());
        assertEquals(toType, leg.getToType());
        if (toStoreId == null) {
            assertNull(leg.getToStoreId());
        } else {
            assertEquals(toStoreId, leg.getToStoreId());
        }
        assertTrue(leg.getDistanceMeters() > 0, "第 " + sequence + " 段沒有道路距離");

        // 形狀要能解析回 [[經度, 緯度], …]，而且是完整形狀（不是只有頭尾兩點）
        double[][] path = jsonMapper.readValue(leg.getPath(), double[][].class);
        assertTrue(path.length > 2, "第 " + sequence + " 段形狀只有 " + path.length + " 點");
        // 順序是 [經度, 緯度]：高雄經度約 120～121、緯度約 22～23.5，反了就會落在範圍外
        assertTrue(path[0][0] > 119 && path[0][0] < 122, "第一個數字應該是經度：" + path[0][0]);
        assertTrue(path[0][1] > 21 && path[0][1] < 26, "第二個數字應該是緯度：" + path[0][1]);
    }

    private void insertOrder(String orderNumber, int sequence, long storeId, long vehicleId) {
        jdbcTemplate.update("INSERT INTO orders (order_number, store_id, warehouse_id, box_count, delivery_date, "
                        + "status, route_id, assigned_vehicle_id, sequence, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 12, ?, 'CONFIRMED', ?, ?, ?, NOW(6), NOW(6))",
                orderNumber, storeId, warehouseId, DAY, routeId, vehicleId, sequence);
    }
}

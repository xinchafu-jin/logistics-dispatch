package com.example.backend.service;

import com.example.backend.constants.RouteDeviationEndReason;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RouteDeviationsDAO;
import com.example.backend.dao.RoutePlannedLegsDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.entity.RouteDeviationsEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * onGpsPing 整條走一遍：真的 GPS 座標 → 真的 RouteDeviationRules／Tracker → 真的資料庫，
 * 不 mock 任何一層。RulesTest／TrackerTest 測純邏輯、RouteDeviationServiceEscalationTest 測排程，
 * 這裡補的是「一筆一筆餵 GPS，資料庫真的多出一筆、真的結束」這件事本身。
 *
 * <p>路線形狀是自己造的一條正東西向直線（不打 OSRM）：倉庫 (120.3000, 22.6273) → 門市A，
 * 座標換算跟 RouteDeviationRules.distanceToPathMeters 用同一套公式（緯度 1 度 111,195 公尺，
 * 經度要乘 cos(緯度)），所以「北邊 260 公尺」這種話能精確造出來，不用估算誤差。</p>
 *
 * <p>每個測試方法用不同的司機、路線（MARKER 加字母後綴），彼此獨立：
 * RouteDeviationService 是單例，司機的 tracker 狀態（連續幾筆）會跨方法留在記憶體，
 * 用不同 driverId 就不會互相汙染，不必去動它私有的 map。</p>
 *
 * <p>前提：本機 DB 已套用 V9，至少有一個倉庫與兩間有座標的門市。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class RouteDeviationServiceGpsIntegrationTest {

    private static final String MARKER = "DEVGPSTEST";
    // 很遠的日期，不會跟本機真實路線撞到 uk_routes_date_vehicle
    private static final LocalDate DAY = LocalDate.of(2099, 5, 1);
    private static final LocalDateTime T0 = DAY.atTime(10, 0, 0);

    // 一條正東西向的直線，1 公里：[經度, 緯度]（構造方式同 RouteDeviationRulesTest 的 eastRoad）
    private static final double LINE_LAT = 22.6273;
    private static final double LINE_START_LNG = 120.3000;
    private static final double METERS_PER_LAT_DEGREE = 111_195;
    private static final double METERS_PER_LNG_DEGREE = METERS_PER_LAT_DEGREE * Math.cos(Math.toRadians(LINE_LAT));
    private static final double LINE_END_LNG = LINE_START_LNG + 1_000 / METERS_PER_LNG_DEGREE;
    // 線段正中間：兩端點距離都是 500 公尺，往正北量距離最好算（東西向的線，往北量的距離就是垂直距離）
    private static final double MID_LNG = (LINE_START_LNG + LINE_END_LNG) / 2;

    @Autowired
    private RouteDeviationService routeDeviationService;

    @Autowired
    private RouteDeviationsDAO routeDeviationsDAO;

    @Autowired
    private RoutesDAO routesDAO;

    @Autowired
    private OrdersDAO ordersDAO;

    @Autowired
    private RoutePlannedLegsDAO routePlannedLegsDAO;

    @Autowired
    private MileageLogsDAO mileageLogsDAO;

    @Autowired
    private AttendanceRecordsDAO attendanceRecordsDAO;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM orders WHERE order_number LIKE ?", MARKER + "-%");
        // 刪路線時形狀、偏離紀錄都會被 ON DELETE CASCADE 一起刪掉
        jdbcTemplate.update("DELETE FROM routes WHERE vehicle_id IN "
                + "(SELECT id FROM vehicles WHERE plate_number LIKE ?)", MARKER + "-%");
        jdbcTemplate.update("DELETE FROM mileage_logs WHERE driver_id IN "
                + "(SELECT id FROM drivers WHERE account LIKE ?)", MARKER + "-%");
        jdbcTemplate.update("DELETE FROM attendance_records WHERE driver_id IN "
                + "(SELECT id FROM drivers WHERE account LIKE ?)", MARKER + "-%");
        jdbcTemplate.update("DELETE FROM vehicles WHERE plate_number LIKE ?", MARKER + "-%");
        jdbcTemplate.update("DELETE FROM drivers WHERE account LIKE ?", MARKER + "-%");
    }

    @Test
    void 貼著路線走_不會開任何一筆偏離() {
        Scenario scenario = buildScenario("A");

        // 正中間、往北 30 公尺、正中間：都在 200 公尺內，不會累積偏離計數
        sendPing(scenario, latAt(0), 0);
        sendPing(scenario, latAt(30), 10);
        sendPing(scenario, latAt(0), 20);

        assertTrue(openDeviationsOf(scenario).isEmpty());
    }

    @Test
    void 連續3筆超過200公尺_開一筆_連續2筆回到路線_結束() {
        Scenario scenario = buildScenario("B");

        sendPing(scenario, latAt(260), 0);
        sendPing(scenario, latAt(310), 10);
        assertTrue(openDeviationsOf(scenario).isEmpty(), "才 2 筆，還不該開");

        sendPing(scenario, latAt(380), 20);
        RouteDeviationsEntity started = onlyOpen(scenario);
        assertEquals(scenario.routeId, started.getRouteId());
        assertEquals(scenario.driverId, started.getDriverId());
        assertEquals(1, started.getLegSequence(), "偏離的是倉庫到門市A那一段");
        assertEquals(T0.plusSeconds(20), started.getStartedAt(), "開始時間要用第 3 筆的時間，不是第 1 筆");
        assertTrue(started.getStartDistanceMeters() > 375 && started.getStartDistanceMeters() < 385,
                "實際：" + started.getStartDistanceMeters());

        sendPing(scenario, latAt(50), 30);
        assertEquals(1, openDeviationsOf(scenario).size(), "回來 1 筆，還不該解除（要連續 2 筆）");

        sendPing(scenario, latAt(80), 40);
        assertTrue(openDeviationsOf(scenario).isEmpty(), "連續 2 筆回到路線，已經解除");
        RouteDeviationsEntity resolved = routeDeviationsDAO.findById(started.getId()).orElseThrow();
        assertEquals(RouteDeviationEndReason.BACK_ON_ROUTE, resolved.getEndReason());
        assertEquals(T0.plusSeconds(40), resolved.getEndedAt(), "結束時間要用第 2 筆回來的時間");
    }

    @Test
    void 後端重啟後從資料庫還原偏離中_不用重新累積3筆就能解除() {
        Scenario scenario = buildScenario("C");
        // 模擬「重啟前」已經確定偏離、寫進資料庫的那一筆
        jdbcTemplate.update("INSERT INTO route_deviations (route_id, driver_id, leg_sequence, started_at, "
                        + "start_lat, start_lng, start_distance_meters, version, created_at) "
                        + "VALUES (?, ?, 1, ?, ?, ?, 300, 0, NOW(6))",
                scenario.routeId, scenario.driverId, T0.minusMinutes(5), latAt(300), MID_LNG);

        // 重啟＝重新 new 一個 Service：舊的記憶體狀態（trackers）全部歸零，只剩資料庫裡的那一筆
        RouteDeviationService restarted = new RouteDeviationService(
                routesDAO, ordersDAO, routePlannedLegsDAO, mileageLogsDAO, attendanceRecordsDAO,
                routeDeviationsDAO, jsonMapper, eventPublisher);

        restarted.onGpsPing(scenario.driverId, latAt(50), MID_LNG, T0);
        assertEquals(1, openDeviationsOf(scenario).size(), "第 1 筆回來的還不能解除（連續要滿 2 筆）");

        restarted.onGpsPing(scenario.driverId, latAt(50), MID_LNG, T0.plusSeconds(10));

        assertTrue(openDeviationsOf(scenario).isEmpty(), "2 筆回來就該解除");
        assertEquals(1, countDeviationsOf(scenario),
                "重啟後只有原本那一筆：沒有先誤判成『沒在偏離』又重新累積 3 筆才開出第二筆");
    }

    @Test
    void 到店開始交貨_結束偏離_結束時間是到店那筆GPS的時間() {
        Scenario scenario = buildScenario("D");
        sendPing(scenario, latAt(300), 0);
        sendPing(scenario, latAt(300), 10);
        sendPing(scenario, latAt(300), 20);
        RouteDeviationsEntity started = onlyOpen(scenario);

        jdbcTemplate.update("UPDATE orders SET status = 'IN_DELIVERY' WHERE id = ?", scenario.orderAId);
        sendPing(scenario, latAt(300), 30);

        assertTrue(openDeviationsOf(scenario).isEmpty());
        RouteDeviationsEntity ended = routeDeviationsDAO.findById(started.getId()).orElseThrow();
        assertEquals(RouteDeviationEndReason.DELIVERING, ended.getEndReason());
        assertEquals(T0.plusSeconds(30), ended.getEndedAt());
    }

    @Test
    void 收車後不再比對_結束偏離_原因是收車() {
        Scenario scenario = buildScenario("E");
        sendPing(scenario, latAt(300), 0);
        sendPing(scenario, latAt(300), 10);
        sendPing(scenario, latAt(300), 20);
        RouteDeviationsEntity started = onlyOpen(scenario);

        jdbcTemplate.update("UPDATE mileage_logs SET end_time = ? WHERE id = ?",
                T0.plusMinutes(3), scenario.mileageLogId);
        sendPing(scenario, latAt(300), 40);

        assertTrue(openDeviationsOf(scenario).isEmpty());
        RouteDeviationsEntity ended = routeDeviationsDAO.findById(started.getId()).orElseThrow();
        assertEquals(RouteDeviationEndReason.TRIP_ENDED, ended.getEndReason());
    }

    // ── 座標、送 GPS、查結果 ──────────────────────────────

    /** 線段正中間，往北 metersNorth 公尺的緯度；0 就是貼在路線上 */
    private double latAt(double metersNorth) {
        return LINE_LAT + metersNorth / METERS_PER_LAT_DEGREE;
    }

    private void sendPing(Scenario scenario, double lat, int secondsAfterT0) {
        routeDeviationService.onGpsPing(scenario.driverId, lat, MID_LNG, T0.plusSeconds(secondsAfterT0));
    }

    private List<RouteDeviationsEntity> openDeviationsOf(Scenario scenario) {
        return routeDeviationsDAO.findAllByEndedAtIsNullOrderByStartedAtAsc().stream()
                .filter(deviation -> deviation.getDriverId().equals(scenario.driverId))
                .toList();
    }

    private RouteDeviationsEntity onlyOpen(Scenario scenario) {
        List<RouteDeviationsEntity> open = openDeviationsOf(scenario);
        assertEquals(1, open.size());
        return open.getFirst();
    }

    private int countDeviationsOf(Scenario scenario) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_deviations WHERE driver_id = ?", Integer.class, scenario.driverId);
        return count == null ? 0 : count;
    }

    // ── 建置一個情境：司機、車、路線（已發布）、預定形狀、訂單、出勤、出車紀錄 ──────

    private Scenario buildScenario(String suffix) {
        String marker = MARKER + "-" + suffix;
        long warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        List<Long> storeIds = jdbcTemplate.queryForList(
                "SELECT id FROM stores WHERE lat IS NOT NULL AND lng IS NOT NULL ORDER BY id LIMIT 2", Long.class);
        long storeA = storeIds.get(0);
        long storeB = storeIds.get(1);

        jdbcTemplate.update("INSERT INTO drivers (account, name, work_start, work_end, rest_duration, is_active) "
                + "VALUES (?, ?, '08:00', '17:00', 60, 1)", marker, marker);
        long driverId = jdbcTemplate.queryForObject("SELECT id FROM drivers WHERE account = ?", Long.class, marker);

        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, marker);
        long vehicleId = jdbcTemplate.queryForObject(
                "SELECT id FROM vehicles WHERE plate_number = ?", Long.class, marker);

        jdbcTemplate.update("INSERT INTO routes (date, warehouse_id, vehicle_id, driver_id, status, version) "
                + "VALUES (?, ?, ?, ?, 'PUBLISHED', 1)", DAY, warehouseId, vehicleId, driverId);
        long routeId = jdbcTemplate.queryForObject(
                "SELECT id FROM routes WHERE vehicle_id = ? AND date = ?", Long.class, vehicleId, DAY);

        // 只有第 1 段（倉庫→門市A）是這裡實際要測的直線；第 2、3 段隨便給個形狀湊完整，測試不會走到那裡
        insertLeg(routeId, 1, "WAREHOUSE", null, "STORE", storeA,
                "[[" + LINE_START_LNG + "," + LINE_LAT + "],[" + LINE_END_LNG + "," + LINE_LAT + "]]");
        insertLeg(routeId, 2, "STORE", storeA, "STORE", storeB,
                "[[" + LINE_END_LNG + "," + LINE_LAT + "],[" + LINE_END_LNG + "," + (LINE_LAT + 0.01) + "]]");
        insertLeg(routeId, 3, "STORE", storeB, "WAREHOUSE", null,
                "[[" + LINE_END_LNG + "," + (LINE_LAT + 0.01) + "],[" + LINE_START_LNG + "," + LINE_LAT + "]]");

        jdbcTemplate.update("INSERT INTO orders (order_number, store_id, warehouse_id, box_count, delivery_date, "
                        + "status, route_id, assigned_vehicle_id, assigned_driver_id, sequence, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 12, ?, 'CONFIRMED', ?, ?, ?, 1, NOW(6), NOW(6))",
                marker + "-1", storeA, warehouseId, DAY, routeId, vehicleId, driverId);
        long orderAId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", Long.class, marker + "-1");
        jdbcTemplate.update("INSERT INTO orders (order_number, store_id, warehouse_id, box_count, delivery_date, "
                        + "status, route_id, assigned_vehicle_id, assigned_driver_id, sequence, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 12, ?, 'CONFIRMED', ?, ?, ?, 2, NOW(6), NOW(6))",
                marker + "-2", storeB, warehouseId, DAY, routeId, vehicleId, driverId);

        // 出勤：上班中。driver_shift_id 沒有外鍵，塞 0 就好
        jdbcTemplate.update("INSERT INTO attendance_records (driver_id, driver_shift_id, work_date, clock_in_at, "
                        + "status, break_used, version) VALUES (?, 0, ?, ?, 'WORKING', 0, 1)",
                driverId, DAY, DAY.atTime(8, 0));

        // 出車：已出車、還沒收車
        jdbcTemplate.update("INSERT INTO mileage_logs (driver_id, date, start_time, route_id, vehicle_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                driverId, DAY, DAY.atTime(8, 30), routeId, vehicleId);
        long mileageLogId = jdbcTemplate.queryForObject(
                "SELECT id FROM mileage_logs WHERE driver_id = ? AND date = ?", Long.class, driverId, DAY);

        return new Scenario(driverId, routeId, orderAId, mileageLogId);
    }

    private void insertLeg(long routeId, int sequence, String fromType, Long fromStoreId,
                           String toType, Long toStoreId, String path) {
        jdbcTemplate.update("INSERT INTO route_planned_legs (route_id, sequence, from_type, from_store_id, "
                        + "to_type, to_store_id, distance_meters, duration_seconds, path, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1000, 120, ?, NOW(6))",
                routeId, sequence, fromType, fromStoreId, toType, toStoreId, path);
    }

    /** 一個情境建好的幾個 id，方法之間傳來傳去用；不是 API 的 DTO，就是幾個 id 湊一包 */
    private static final class Scenario {
        private final long driverId;
        private final long routeId;
        private final long orderAId;
        private final long mileageLogId;

        private Scenario(long driverId, long routeId, long orderAId, long mileageLogId) {
            this.driverId = driverId;
            this.routeId = routeId;
            this.orderAId = orderAId;
            this.mileageLogId = mileageLogId;
        }
    }
}

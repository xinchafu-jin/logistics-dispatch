package com.example.backend.dao;

import com.example.backend.constants.RouteDeviationEndReason;
import com.example.backend.entity.RouteDeviationsEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 偏離紀錄的 V9 資料表、Entity、DAO 是否對得上（ddl-auto=none，打錯欄位要到真的讀寫才會爆，理由同 RoutePlannedLegsDAOTest）。
 *
 * <p>本機 DB 之後可能有真的進行中偏離，所以查「進行中」的結果只看這次建的路線、司機。</p>
 *
 * <p>前提：本機 DB 至少有一個倉庫。測試資料用 MARKER 標記，前後都會清掉。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class RouteDeviationsDAOTest {

    private static final String MARKER = "DEVIATIONDAOTEST";
    // 很遠的日期，不會跟本機真實路線撞到 uk_routes_date_vehicle
    private static final LocalDate DAY = LocalDate.of(2099, 4, 1);
    // driver_id 沒有外鍵，用一個不會是真實司機的 id，查「這位司機進行中那一筆」才不會撈到真的資料
    private static final long DRIVER_ID = 9_000_000_001L;
    private static final LocalDateTime TEN_O_CLOCK = LocalDateTime.of(2099, 4, 1, 10, 0);

    @Autowired
    private RouteDeviationsDAO routeDeviationsDAO;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long routeId;

    @BeforeEach
    void setUp() {
        cleanUp();
        long warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, MARKER);
        long vehicleId = jdbcTemplate.queryForObject(
                "SELECT id FROM vehicles WHERE plate_number = ?", Long.class, MARKER);
        jdbcTemplate.update("INSERT INTO routes (date, warehouse_id, vehicle_id, status, version) "
                + "VALUES (?, ?, ?, 'PUBLISHED', 1)", DAY, warehouseId, vehicleId);
        routeId = jdbcTemplate.queryForObject(
                "SELECT id FROM routes WHERE vehicle_id = ? AND date = ?", Long.class, vehicleId, DAY);
    }

    @AfterEach
    void cleanUp() {
        // 刪路線時偏離紀錄會被 ON DELETE CASCADE 一起刪掉
        jdbcTemplate.update("DELETE FROM routes WHERE vehicle_id IN "
                + "(SELECT id FROM vehicles WHERE plate_number = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM vehicles WHERE plate_number = ?", MARKER);
    }

    @Test
    void 存進去再讀出來_欄位都對得上_結束原因存成文字() {
        RouteDeviationsEntity deviation = deviation(TEN_O_CLOCK);
        deviation.setEscalatedAt(TEN_O_CLOCK.plusMinutes(10));
        deviation.setEndedAt(TEN_O_CLOCK.plusMinutes(12));
        deviation.setEndReason(RouteDeviationEndReason.DELIVERING);
        long id = routeDeviationsDAO.save(deviation).getId();

        RouteDeviationsEntity saved = routeDeviationsDAO.findById(id).orElseThrow();
        assertEquals(routeId, saved.getRouteId());
        assertEquals(DRIVER_ID, saved.getDriverId());
        assertEquals(2, saved.getLegSequence());
        assertEquals(TEN_O_CLOCK, saved.getStartedAt());
        assertEquals(22.6273, saved.getStartLat());
        assertEquals(120.3014, saved.getStartLng());
        assertEquals(236.5, saved.getStartDistanceMeters());
        assertEquals(TEN_O_CLOCK.plusMinutes(10), saved.getEscalatedAt());
        assertEquals(TEN_O_CLOCK.plusMinutes(12), saved.getEndedAt());
        assertEquals(RouteDeviationEndReason.DELIVERING, saved.getEndReason());
        assertNotNull(saved.getCreatedAt(), "@PrePersist 要補上建立時間");
        assertNotNull(saved.getVersion(), "@Version 要有初始值");
        // 資料表存的是 enum 名稱（VARCHAR），報表直接查資料庫也看得懂
        assertEquals("DELIVERING", jdbcTemplate.queryForObject(
                "SELECT end_reason FROM route_deviations WHERE id = ?", String.class, id));
    }

    @Test
    void 進行中清單_只有還沒結束的_先開始的在前() {
        RouteDeviationsEntity later = routeDeviationsDAO.save(deviation(TEN_O_CLOCK.plusMinutes(30)));
        RouteDeviationsEntity earlier = routeDeviationsDAO.save(deviation(TEN_O_CLOCK));
        RouteDeviationsEntity ended = deviation(TEN_O_CLOCK.minusMinutes(30));
        ended.setEndedAt(TEN_O_CLOCK.minusMinutes(20));
        ended.setEndReason(RouteDeviationEndReason.BACK_ON_ROUTE);
        routeDeviationsDAO.save(ended);

        List<Long> activeIds = routeDeviationsDAO.findAllByEndedAtIsNullOrderByStartedAtAsc().stream()
                .filter(deviation -> deviation.getRouteId().equals(routeId))
                .map(RouteDeviationsEntity::getId)
                .toList();

        assertEquals(List.of(earlier.getId(), later.getId()), activeIds, "已結束的不算、先開始的在前");
    }

    @Test
    void 找司機進行中那一筆_已結束的不算() {
        RouteDeviationsEntity ended = deviation(TEN_O_CLOCK);
        ended.setEndedAt(TEN_O_CLOCK.plusMinutes(5));
        ended.setEndReason(RouteDeviationEndReason.BACK_ON_ROUTE);
        routeDeviationsDAO.save(ended);
        assertTrue(routeDeviationsDAO.findFirstByDriverIdAndEndedAtIsNullOrderByStartedAtDesc(DRIVER_ID).isEmpty(),
                "只有已結束的紀錄時，重啟後不能把他還原成偏離中");

        RouteDeviationsEntity open = routeDeviationsDAO.save(deviation(TEN_O_CLOCK.plusMinutes(20)));
        assertEquals(open.getId(),
                routeDeviationsDAO.findFirstByDriverIdAndEndedAtIsNullOrderByStartedAtDesc(DRIVER_ID)
                        .orElseThrow().getId());
    }

    @Test
    void 兩邊同時改同一筆_後存的失敗_不會把結束時間蓋掉() {
        long id = routeDeviationsDAO.save(deviation(TEN_O_CLOCK)).getId();
        // 模擬兩條執行緒各自讀到同一個版本：GPS 那邊判定回到路線、排程這邊正要升級
        RouteDeviationsEntity gpsCopy = routeDeviationsDAO.findById(id).orElseThrow();
        RouteDeviationsEntity schedulerCopy = routeDeviationsDAO.findById(id).orElseThrow();

        gpsCopy.setEndedAt(TEN_O_CLOCK.plusMinutes(9));
        gpsCopy.setEndReason(RouteDeviationEndReason.BACK_ON_ROUTE);
        routeDeviationsDAO.save(gpsCopy);

        schedulerCopy.setEscalatedAt(TEN_O_CLOCK.plusMinutes(10));
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> routeDeviationsDAO.save(schedulerCopy));

        RouteDeviationsEntity current = routeDeviationsDAO.findById(id).orElseThrow();
        assertEquals(TEN_O_CLOCK.plusMinutes(9), current.getEndedAt(), "結束時間被舊資料蓋回去了");
        assertNull(current.getEscalatedAt(), "已經結束的偏離不該再升級");
    }

    @Test
    void 路線被刪時_紀錄跟著刪() {
        routeDeviationsDAO.save(deviation(TEN_O_CLOCK));

        // 草稿路線是 routesDAO.deleteAllById 刪的；外鍵沒設 ON DELETE CASCADE 的話這句會因為還有參照而失敗
        jdbcTemplate.update("DELETE FROM routes WHERE id = ?", routeId);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_deviations WHERE route_id = ?", Integer.class, routeId);
        assertEquals(0, remaining);
        assertFalse(routeDeviationsDAO.findFirstByDriverIdAndEndedAtIsNullOrderByStartedAtDesc(DRIVER_ID).isPresent());
    }

    private RouteDeviationsEntity deviation(LocalDateTime startedAt) {
        RouteDeviationsEntity deviation = new RouteDeviationsEntity();
        deviation.setRouteId(routeId);
        deviation.setDriverId(DRIVER_ID);
        deviation.setLegSequence(2);
        deviation.setStartedAt(startedAt);
        deviation.setStartLat(22.6273);
        deviation.setStartLng(120.3014);
        deviation.setStartDistanceMeters(236.5);
        return deviation;
    }
}

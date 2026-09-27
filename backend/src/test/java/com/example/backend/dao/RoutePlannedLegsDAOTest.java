package com.example.backend.dao;

import com.example.backend.constants.RouteLegLocationType;
import com.example.backend.entity.RoutePlannedLegsEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 預定路線形狀的 V8 資料表、Entity、DAO 是否對得上。
 *
 * <p>application.properties 設 ddl-auto=none，Hibernate 不會驗證 Entity 跟資料表，
 * 欄位名稱或型別打錯要到真的讀寫時才會爆，所以直接對本機 DB 讀寫一次。</p>
 *
 * <p>前提：本機 DB 至少有一個倉庫。測試資料用 MARKER 標記，前後都會清掉。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class RoutePlannedLegsDAOTest {

    private static final String MARKER = "PLANLEGTEST";
    private static final String PATH = "[[120.3014,22.6273],[120.3015,22.6280],[120.312,22.639]]";
    // 用很遠的日期，不會跟本機的真實路線撞到 uk_routes_date_vehicle
    private static final LocalDate DAY_ONE = LocalDate.of(2099, 1, 1);
    private static final LocalDate DAY_TWO = LocalDate.of(2099, 1, 2);

    @Autowired
    private RoutePlannedLegsDAO routePlannedLegsDAO;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long warehouseId;
    private long storeId;
    private long vehicleId;

    @BeforeEach
    void setUp() {
        cleanUp();
        warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        storeId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM stores", Long.class);
        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, MARKER);
        vehicleId = jdbcTemplate.queryForObject("SELECT id FROM vehicles WHERE plate_number = ?", Long.class, MARKER);
    }

    @AfterEach
    void cleanUp() {
        // 刪路線時形狀會被 ON DELETE CASCADE 一起刪掉，不用另外刪 route_planned_legs
        jdbcTemplate.update("DELETE FROM routes WHERE vehicle_id IN "
                + "(SELECT id FROM vehicles WHERE plate_number = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM vehicles WHERE plate_number = ?", MARKER);
    }

    @Test
    void 存進去再讀出來_依行駛順序排好_形狀原樣保留() {
        long routeId = insertRoute(DAY_ONE);
        // 故意先存第 2 段，確認讀出來是照 sequence 排，不是照寫入順序
        routePlannedLegsDAO.save(leg(routeId, 2, RouteLegLocationType.STORE, storeId, RouteLegLocationType.WAREHOUSE, null));
        routePlannedLegsDAO.save(leg(routeId, 1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, storeId));

        List<RoutePlannedLegsEntity> legs = routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(routeId);

        assertEquals(2, legs.size());
        RoutePlannedLegsEntity first = legs.get(0);
        assertEquals(1, first.getSequence());
        assertEquals(RouteLegLocationType.WAREHOUSE, first.getFromType());
        assertNull(first.getFromStoreId(), "起點是倉庫時門市 id 是 null");
        assertEquals(storeId, first.getToStoreId());
        assertEquals(2454.3, first.getDistanceMeters());
        assertEquals(318.0, first.getDurationSeconds());
        assertEquals(PATH, first.getPath(), "形狀要原樣存回來，前端與偏離判斷才能直接解析");
        assertNotNull(first.getCreatedAt(), "@PrePersist 要補上建立時間");
        assertEquals(RouteLegLocationType.WAREHOUSE, legs.get(1).getToType());
    }

    @Test
    void 整批刪除只刪指定的路線() {
        long withdrawn = insertRoute(DAY_ONE);
        long kept = insertRoute(DAY_TWO);
        routePlannedLegsDAO.save(leg(withdrawn, 1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, storeId));
        routePlannedLegsDAO.save(leg(withdrawn, 2, RouteLegLocationType.STORE, storeId, RouteLegLocationType.WAREHOUSE, null));
        routePlannedLegsDAO.save(leg(kept, 1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, storeId));

        // @Modifying 的刪除要在交易裡跑，實際呼叫端是 publish／withdraw，本來就有 @Transactional
        Integer deleted = transactionTemplate.execute(status -> routePlannedLegsDAO.deleteAllForRoutes(List.of(withdrawn)));

        assertEquals(2, deleted);
        assertEquals(0, routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(withdrawn).size());
        assertEquals(1, routePlannedLegsDAO.findAllByRouteIdOrderBySequenceAsc(kept).size());
    }

    @Test
    void 路線被刪時_形狀跟著刪() {
        long routeId = insertRoute(DAY_ONE);
        routePlannedLegsDAO.save(leg(routeId, 1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, storeId));

        // 草稿路線實際是 routesDAO.deleteAllById 刪的；外鍵沒設 ON DELETE CASCADE 的話這句會因為還有參照而失敗
        jdbcTemplate.update("DELETE FROM routes WHERE id = ?", routeId);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM route_planned_legs WHERE route_id = ?", Integer.class, routeId);
        assertEquals(0, remaining);
    }

    @Test
    void 同一條路線的同一段不能存兩次() {
        long routeId = insertRoute(DAY_ONE);
        routePlannedLegsDAO.save(leg(routeId, 1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, storeId));

        // 重新發布忘了先刪舊的，會在這裡被擋下，而不是默默變成兩份形狀
        assertThrows(DataIntegrityViolationException.class, () -> routePlannedLegsDAO.save(
                leg(routeId, 1, RouteLegLocationType.WAREHOUSE, null, RouteLegLocationType.STORE, storeId)));
    }

    private long insertRoute(LocalDate date) {
        jdbcTemplate.update("INSERT INTO routes (date, warehouse_id, vehicle_id, status, version) "
                + "VALUES (?, ?, ?, 'DRAFT', 1)", date, warehouseId, vehicleId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM routes WHERE vehicle_id = ? AND date = ?", Long.class, vehicleId, date);
    }

    private RoutePlannedLegsEntity leg(long routeId, int sequence,
                                       RouteLegLocationType fromType, Long fromStoreId,
                                       RouteLegLocationType toType, Long toStoreId) {
        RoutePlannedLegsEntity leg = new RoutePlannedLegsEntity();
        leg.setRouteId(routeId);
        leg.setSequence(sequence);
        leg.setFromType(fromType);
        leg.setFromStoreId(fromStoreId);
        leg.setToType(toType);
        leg.setToStoreId(toStoreId);
        leg.setDistanceMeters(2454.3);
        leg.setDurationSeconds(318.0);
        leg.setPath(PATH);
        return leg;
    }
}

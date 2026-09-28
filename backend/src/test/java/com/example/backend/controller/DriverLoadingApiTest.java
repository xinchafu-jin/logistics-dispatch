package com.example.backend.controller;

import com.example.backend.service.AuthService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 倉庫點交的 API 測試：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>DeliveryServiceTest 用 mock 測規則；這裡補 mock 測不到的兩件事：
 * MySQL 的 enum 欄位真的存得下 LOADED、LOADING_MISMATCH，以及點交不符的異常單真的出現在異常中心。</p>
 *
 * <p>routes 有「同一天同一位司機」「同一天同一台車」的唯一鍵，借用既有司機或車可能撞到本機真實的排班，
 * 所以另建一位測試司機、一台測試車和一條今天已發布的路線，測完連同訂單、補送單、異常單全部刪掉。
 * token 用專案的 JwtEncoder 簽（做法同 DriverMessagesApiTest）。</p>
 *
 * <p>點交前要先通過出車前安全檢查（PreTripInspectionService.requirePassed），這裡直接插一筆通過的檢查，
 * 檢查本身的規則由 PreTripInspectionApiTest、PreTripInspectionServiceTest 負責。</p>
 *
 * <p>前提：本機 DB 已套用 V10，至少有一個倉庫與一間門市。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class DriverLoadingApiTest {

    private static final String MARKER = "LOADTEST";
    private static final long ADMIN_ID = 1L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private long driverId;
    private long vehicleId;
    private long routeId;
    private long firstOrderId;
    private long secondOrderId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        cleanUp();

        long warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        long storeId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM stores", Long.class);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));

        jdbcTemplate.update("INSERT INTO drivers (account, name, work_start, work_end, rest_duration, is_active) "
                + "VALUES (?, '點交測試司機', '08:00', '17:00', 60, 1)", MARKER);
        driverId = jdbcTemplate.queryForObject("SELECT id FROM drivers WHERE account = ?", Long.class, MARKER);

        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, MARKER);
        vehicleId = jdbcTemplate.queryForObject("SELECT id FROM vehicles WHERE plate_number = ?", Long.class, MARKER);

        jdbcTemplate.update("INSERT INTO routes (date, warehouse_id, vehicle_id, driver_id, status, version) "
                + "VALUES (?, ?, ?, ?, 'PUBLISHED', 1)", today, warehouseId, vehicleId, driverId);
        routeId = jdbcTemplate.queryForObject(
                "SELECT id FROM routes WHERE vehicle_id = ? AND date = ?", Long.class, vehicleId, today);

        insertPassedInspection(today);
        firstOrderId = insertOrder(MARKER + "-1", 1, storeId, warehouseId, today);
        secondOrderId = insertOrder(MARKER + "-2", 2, storeId, warehouseId, today);
    }

    @AfterEach
    void cleanUp() {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "SELECT id FROM orders WHERE order_number LIKE ?", Long.class, MARKER + "-%");
        for (Long orderId : orderIds) {
            jdbcTemplate.update("DELETE FROM exception_cases WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM delivery_records WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM orders WHERE parent_order_id = ?", orderId);
        }
        jdbcTemplate.update("DELETE FROM orders WHERE order_number LIKE ?", MARKER + "-%");
        // pre_trip_inspections 沒有外鍵（稽核紀錄），刪路線不會連帶刪，要自己刪
        jdbcTemplate.update("DELETE FROM pre_trip_inspections WHERE route_id IN (SELECT id FROM routes WHERE vehicle_id IN "
                + "(SELECT id FROM vehicles WHERE plate_number = ?))", MARKER);
        jdbcTemplate.update("DELETE FROM routes WHERE vehicle_id IN (SELECT id FROM vehicles WHERE plate_number = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM vehicles WHERE plate_number = ?", MARKER);
        jdbcTemplate.update("DELETE FROM drivers WHERE account = ?", MARKER);
    }

    @Test
    void 點交相符_轉為已點交_之後可以抵達() throws Exception {
        mockMvc.perform(post("/api/driver/loading")
                        .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + firstOrderId + ", \"loadedBoxCount\": 12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("LOADED"))
                .andExpect(jsonPath("$.loadedAt").isNotEmpty())
                .andExpect(jsonPath("$.exceptionCaseId").doesNotExist());

        Map<String, Object> saved = jdbcTemplate.queryForMap(
                "SELECT status, loaded_at FROM orders WHERE id = ?", firstOrderId);
        assertEquals("LOADED", saved.get("status"));
        assertNotNull(saved.get("loaded_at"));

        mockMvc.perform(post("/api/driver/arrive")
                        .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + firstOrderId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("IN_DELIVERY"));
    }

    @Test
    void 沒點交直接抵達_回400() throws Exception {
        mockMvc.perform(post("/api/driver/arrive")
                        .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + secondOrderId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("只有已點交的訂單可以登記抵達")));
    }

    @Test
    void 點交不符_原單配送失敗_異常中心看得到() throws Exception {
        String body = mockMvc.perform(post("/api/driver/loading")
                        .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + secondOrderId + ", \"loadedBoxCount\": 10, \"notes\": \"少兩箱\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("FAILED"))
                .andExpect(jsonPath("$.followUpOrderNumber").value(startsWith("LD-")))
                .andReturn().getResponse().getContentAsString();
        int exceptionCaseId = JsonPath.read(body, "$.exceptionCaseId");

        assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, secondOrderId));
        assertEquals("PENDING_CONFIRM", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE parent_order_id = ?", String.class, secondOrderId));
        assertEquals("LOADING_MISMATCH", jdbcTemplate.queryForObject(
                "SELECT type FROM exception_cases WHERE id = ?", String.class, exceptionCaseId));

        mockMvc.perform(get("/api/exceptions/pending-confirmation")
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(exceptionCaseId)))
                .andExpect(jsonPath("$[?(@.id == " + exceptionCaseId + ")].type").value(hasItem("LOADING_MISMATCH")));
    }

    @Test
    void 實點比應到多_回400且訂單不變() throws Exception {
        mockMvc.perform(post("/api/driver/loading")
                        .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + firstOrderId + ", \"loadedBoxCount\": 13}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("12")));

        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, firstOrderId));
    }

    @Test
    void 主管的token不能點交() throws Exception {
        mockMvc.perform(post("/api/driver/loading")
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + firstOrderId + ", \"loadedBoxCount\": 12}"))
                .andExpect(status().isForbidden());
    }

    /** 這條路線、這組人車、版本 1 的一筆通過檢查：酒測 0.00、15 項全部正常 */
    private void insertPassedInspection(LocalDate today) {
        jdbcTemplate.update("INSERT INTO pre_trip_inspections (route_id, driver_id, vehicle_id, route_version, work_date, "
                        + "alcohol_mg_l, dashcam, engine_oil, brake_fluid, power_steering_fluid, transmission_oil, fuel, "
                        + "coolant, battery_water, washer_fluid, tire_pressure, tire_tread, headlights, turn_signals, "
                        + "brake_lights, dashboard_lights, alcohol_photo, passed, submitted_at) "
                        + "VALUES (?, ?, ?, 1, ?, 0.00, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 'test.jpg', 1, NOW(6))",
                routeId, driverId, vehicleId, today);
    }

    private long insertOrder(String orderNumber, int sequence, long storeId, long warehouseId, LocalDate today) {
        jdbcTemplate.update("INSERT INTO orders (order_number, store_id, warehouse_id, box_count, delivery_date, "
                        + "status, route_id, assigned_vehicle_id, assigned_driver_id, sequence, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 12, ?, 'CONFIRMED', ?, ?, ?, ?, NOW(6), NOW(6))",
                orderNumber, storeId, warehouseId, today, routeId, vehicleId, driverId, sequence);
        return jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, orderNumber);
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token。 */
    private String bearer(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("driver-loading-test")
                .claim("userId", userId)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

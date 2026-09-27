package com.example.backend.controller;

import com.example.backend.service.AuthService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/dispatch/planned-paths：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>Service 的規則在 RoutePlanMetricsServicePlannedLegsTest 測過（要連 OSRM）；這裡補它測不到的兩件事：
 * 只有後台帳號拿得到，以及 double[][] 真的序列化成 [[經度, 緯度], …] 巢狀陣列 ——
 * 前端直接拿去當 GeoJSON 座標，格式錯了地圖只會默默畫不出線。
 * 形狀直接寫進資料表，不打 OSRM，本機沒開 OSRM 也能跑。</p>
 *
 * <p>前提：本機 DB 已套用 V8，至少有一個倉庫與一間門市。token 用專案的 JwtEncoder 簽（做法同 DriverLoadingApiTest）。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class PlannedPathsApiTest {

    private static final String MARKER = "PATHAPITEST";
    // 很遠的日期：不會跟本機真實路線撞到 uk_routes_date_vehicle，查詢結果也只會有這次建的路線
    private static final LocalDate DAY = LocalDate.of(2099, 3, 1);

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
    private long warehouseId;
    private long storeId;
    private long routeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        cleanUp();

        warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        storeId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM stores", Long.class);
        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, MARKER);
        long vehicleId = jdbcTemplate.queryForObject(
                "SELECT id FROM vehicles WHERE plate_number = ?", Long.class, MARKER);
        jdbcTemplate.update("INSERT INTO routes (date, warehouse_id, vehicle_id, status, version) "
                + "VALUES (?, ?, ?, 'PUBLISHED', 1)", DAY, warehouseId, vehicleId);
        routeId = jdbcTemplate.queryForObject(
                "SELECT id FROM routes WHERE vehicle_id = ? AND date = ?", Long.class, vehicleId, DAY);

        // 故意先寫第 2 段（回倉），確認回傳是照段號排，不是照寫入順序
        insertLeg(2, "STORE", storeId, "WAREHOUSE", null, "[[120.312,22.639],[120.3015,22.628],[120.3014,22.6273]]");
        insertLeg(1, "WAREHOUSE", null, "STORE", storeId, "[[120.3014,22.6273],[120.3015,22.628],[120.312,22.639]]");
    }

    @AfterEach
    void cleanUp() {
        // 刪路線時形狀會被 ON DELETE CASCADE 一起刪掉
        jdbcTemplate.update("DELETE FROM routes WHERE vehicle_id IN "
                + "(SELECT id FROM vehicles WHERE plate_number = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM vehicles WHERE plate_number = ?", MARKER);
    }

    @Test
    void 後台拿得到_各段依行駛順序_形狀是經緯度巢狀陣列() throws Exception {
        mockMvc.perform(get("/api/dispatch/planned-paths")
                        .param("date", DAY.toString())
                        .param("warehouseId", String.valueOf(warehouseId))
                        .header("Authorization", bearer(AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].routeId").value(routeId))
                .andExpect(jsonPath("$[0].legs", hasSize(2)))
                .andExpect(jsonPath("$[0].legs[0].sequence").value(1))
                .andExpect(jsonPath("$[0].legs[0].toStoreId").value(storeId))
                .andExpect(jsonPath("$[0].legs[0].path", hasSize(3)))
                .andExpect(jsonPath("$[0].legs[0].path[0][0]").value(120.3014))
                .andExpect(jsonPath("$[0].legs[0].path[0][1]").value(22.6273))
                .andExpect(jsonPath("$[0].legs[1].sequence").value(2))
                // 回倉那一段沒有門市：要是 null，前端才認得出是回倉
                .andExpect(jsonPath("$[0].legs[1].toStoreId").value(nullValue()));
    }

    @Test
    void 司機拿不到() throws Exception {
        mockMvc.perform(get("/api/dispatch/planned-paths")
                        .param("date", DAY.toString())
                        .param("warehouseId", String.valueOf(warehouseId))
                        .header("Authorization", bearer(AuthService.ROLE_DRIVER)))
                .andExpect(status().isForbidden());
    }

    private void insertLeg(int sequence, String fromType, Long fromStoreId,
                           String toType, Long toStoreId, String path) {
        jdbcTemplate.update("INSERT INTO route_planned_legs (route_id, sequence, from_type, from_store_id, "
                        + "to_type, to_store_id, distance_meters, duration_seconds, path, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 2454.3, 318, ?, NOW(6))",
                routeId, sequence, fromType, fromStoreId, toType, toStoreId, path);
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token。 */
    private String bearer(String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("planned-paths-test")
                .claim("userId", 1L)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

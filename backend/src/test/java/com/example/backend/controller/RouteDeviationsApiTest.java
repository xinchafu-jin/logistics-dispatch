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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/fleet/route-deviations：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>本機 DB 之後可能有真的進行中偏離，所以用 JSONPath 篩出這次建的路線再比對。
 * token 用專案的 JwtEncoder 簽（做法同 DriverLoadingApiTest）。</p>
 *
 * <p>前提：本機 DB 已套用 V9，至少有一個倉庫。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class RouteDeviationsApiTest {

    private static final String MARKER = "DEVIATIONAPITEST";
    private static final LocalDate DAY = LocalDate.of(2099, 4, 2);

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
    private long routeId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
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

        // 一筆進行中、已升級；一筆已結束
        jdbcTemplate.update("INSERT INTO route_deviations (route_id, driver_id, leg_sequence, started_at, start_lat, "
                        + "start_lng, start_distance_meters, escalated_at, version, created_at) "
                        + "VALUES (?, 9000000002, 3, '2099-04-02 10:00:00', 22.6273, 120.3014, 236.5, "
                        + "'2099-04-02 10:10:00', 0, NOW(6))", routeId);
        jdbcTemplate.update("INSERT INTO route_deviations (route_id, driver_id, leg_sequence, started_at, start_lat, "
                        + "start_lng, start_distance_meters, ended_at, end_reason, version, created_at) "
                        + "VALUES (?, 9000000002, 1, '2099-04-02 09:00:00', 22.6273, 120.3014, 210, "
                        + "'2099-04-02 09:05:00', 'BACK_ON_ROUTE', 0, NOW(6))", routeId);
    }

    @AfterEach
    void cleanUp() {
        // 刪路線時偏離紀錄會被 ON DELETE CASCADE 一起刪掉
        jdbcTemplate.update("DELETE FROM routes WHERE vehicle_id IN "
                + "(SELECT id FROM vehicles WHERE plate_number = ?)", MARKER);
        jdbcTemplate.update("DELETE FROM vehicles WHERE plate_number = ?", MARKER);
    }

    @Test
    void 後台拿得到進行中的偏離_已結束的不在裡面() throws Exception {
        String mine = "$[?(@.routeId == " + routeId + ")]";
        mockMvc.perform(get("/api/fleet/route-deviations")
                        .header("Authorization", bearer(AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(mine, hasSize(1)))
                .andExpect(jsonPath(mine + ".legSequence").value(3))
                .andExpect(jsonPath(mine + ".startedAt").value("2099-04-02T10:00:00"))
                // 有升級時間＝警報；前端靠這個欄位決定顏色
                .andExpect(jsonPath(mine + ".escalatedAt").value("2099-04-02T10:10:00"));
    }

    @Test
    void 司機拿不到() throws Exception {
        mockMvc.perform(get("/api/fleet/route-deviations")
                        .header("Authorization", bearer(AuthService.ROLE_DRIVER)))
                .andExpect(status().isForbidden());
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token。 */
    private String bearer(String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("route-deviations-api-test")
                .claim("userId", 1L)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

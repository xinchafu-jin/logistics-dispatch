package com.example.backend.controller;

import com.example.backend.service.AuthService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.Filter;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 車輛保養的 API 測試：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>補 VehicleMaintenanceServiceTest 用 mock 測不到的：vehicles.status 的 ENUM 存得下送小保、送大保，
 * V12 加在車輛上的保養間隔欄位存得進去、V13 的里程更正紀錄寫得進去、
 * 「一台車只有一筆進行中送修」的唯一鍵、只有主管能改設定和更正里程。</p>
 *
 * <p>整個類別 {@code @Transactional}，測完 rollback，建的車、設定、送修和更正紀錄都不會留下（做法同 PreTripInspectionApiTest）。
 * 前提：本機 DB 已套用 V13，至少有一個倉庫。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
@Transactional
class VehicleMaintenanceApiTest {

    private static final String MARKER = "MAINTTEST";
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

    @Autowired
    private JsonMapper jsonMapper;

    /**
     * 測試跟 API 在同一個交易：JPA 的修改要到 commit 才寫進資料庫，用 JdbcTemplate 直接查之前要先 flush，
     * 不然查到的是舊值（flush 時 ENUM 存不下的話也會在這裡報 Data truncated）
     */
    @PersistenceContext
    private EntityManager entityManager;

    private MockMvc mockMvc;
    private long warehouseId;
    private long vehicleId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);

        // 功能上線前就有的舊車：里程、保養間隔、基準都是空的
        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, MARKER);
        vehicleId = jdbcTemplate.queryForObject("SELECT id FROM vehicles WHERE plate_number = ?", Long.class, MARKER);
    }

    @Test
    void 提醒公里數_存了讀得回來() throws Exception {
        mockMvc.perform(put("/api/vehicle-maintenance/settings")
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("warningKm", 400))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warningKm").value(400));
        entityManager.flush();

        assertEquals(400, jdbcTemplate.queryForObject(
                "SELECT warning_km FROM vehicle_maintenance_settings WHERE id = 1", Integer.class));
    }

    @Test
    void 司機不能看或改設定() throws Exception {
        mockMvc.perform(get("/api/vehicle-maintenance/settings")
                        .header("Authorization", bearer(123L, AuthService.ROLE_DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 保養間隔存在車輛那一列_只填一部分回400() throws Exception {
        updateVehicle(vehicleJson("AVAILABLE")).andExpect(status().isOk());
        entityManager.flush();

        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT minor_maintenance_interval_km, "
                + "major_maintenance_interval_km, retirement_km FROM vehicles WHERE id = ?", vehicleId);
        assertEquals(3000, ((Number) saved.get("minor_maintenance_interval_km")).intValue());
        assertEquals(20000, ((Number) saved.get("major_maintenance_interval_km")).intValue());
        assertEquals(500000, ((Number) saved.get("retirement_km")).intValue());

        Map<String, Object> partial = vehicleJson("AVAILABLE");
        partial.remove("retirementKm");
        updateVehicle(partial)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("小保間隔、大保間隔、退役總里程要一起填"));
    }

    @Test
    void 舊車的里程和基準可以補一次_補過就改不動() throws Exception {
        Map<String, Object> vehicle = vehicleJson("AVAILABLE");
        vehicle.put("currentOdometerKm", 18400);
        vehicle.put("lastMinorMaintenanceKm", 17000);
        vehicle.put("lastMajorMaintenanceKm", 10000);

        updateVehicle(vehicle)
                .andExpect(status().isOk())
                // 17000＋3000－18400
                .andExpect(jsonPath("$.maintenance.minorRemainingKm").value(1600))
                .andExpect(jsonPath("$.maintenance.decision").value("NORMAL"));

        vehicle.put("lastMinorMaintenanceKm", 18000);
        updateVehicle(vehicle)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("小保基準已經有紀錄，平常只能由出車、收車或保養完成更新；打錯了請用「更正里程」"));
    }

    @Test
    void 主管更正里程_要寫原因_寫進更正紀錄_有紀錄的車不能刪() throws Exception {
        // 司機收車多打一位數，車上變成 184500
        jdbcTemplate.update("UPDATE vehicles SET current_odometer_km = 184500 WHERE id = ?", vehicleId);

        Map<String, Object> noReason = new LinkedHashMap<>();
        noReason.put("currentOdometerKm", 18450);
        noReason.put("reason", " ");
        correctMileage(noReason, AuthService.ROLE_ADMIN).andExpect(status().isBadRequest());

        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("currentOdometerKm", 18450);
        correction.put("reason", "司機收車多打一位數");
        correctMileage(correction, AuthService.ROLE_ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentOdometerKm").value(18450));
        entityManager.flush();

        Map<String, Object> saved = jdbcTemplate.queryForMap("SELECT field, old_km, new_km, reason, corrected_by "
                + "FROM vehicle_mileage_corrections WHERE vehicle_id = ?", vehicleId);
        assertEquals("CURRENT_ODOMETER", saved.get("field"));
        assertEquals(184500, ((Number) saved.get("old_km")).intValue());
        assertEquals(18450, ((Number) saved.get("new_km")).intValue());
        assertEquals("司機收車多打一位數", saved.get("reason"));
        // bearer() 簽的 name claim
        assertEquals("測試", saved.get("corrected_by"));

        mockMvc.perform(get("/api/vehicles/" + vehicleId + "/mileage-corrections")
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].field").value("CURRENT_ODOMETER"))
                .andExpect(jsonPath("$[0].oldKm").value(184500));

        mockMvc.perform(delete("/api/vehicles/" + vehicleId)
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 司機不能更正里程() throws Exception {
        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("currentOdometerKm", 18450);
        correction.put("reason", "想讓車過關");

        correctMileage(correction, AuthService.ROLE_DRIVER).andExpect(status().isForbidden());
    }

    @Test
    void 送小保到完成_狀態存得進ENUM_完成時小保基準改成當下里程() throws Exception {
        jdbcTemplate.update("UPDATE vehicles SET current_odometer_km = 18400, "
                + "last_minor_maintenance_km = 15000, last_major_maintenance_km = 10000 WHERE id = ?", vehicleId);

        updateVehicle(vehicleJson("MINOR_MAINTENANCE")).andExpect(status().isOk());
        entityManager.flush();
        assertEquals("MINOR_MAINTENANCE", jdbcTemplate.queryForObject(
                "SELECT status FROM vehicles WHERE id = ?", String.class, vehicleId));
        mockMvc.perform(get("/api/vehicle-maintenance/" + vehicleId + "/history")
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MINOR"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].sentOdometerKm").value(18400));

        // 保養中不能再送另一種
        updateVehicle(vehicleJson("MAINTENANCE")).andExpect(status().isBadRequest());

        updateVehicle(vehicleJson("AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastMinorMaintenanceKm").value(18400))
                .andExpect(jsonPath("$.lastMajorMaintenanceKm").value(10000))
                .andExpect(jsonPath("$.maintenance.minorCount").value(1));
    }

    @Test
    void 取消送修_不計次數也不動基準_有紀錄的車不能刪() throws Exception {
        jdbcTemplate.update("UPDATE vehicles SET current_odometer_km = 18400, "
                + "last_minor_maintenance_km = 15000, last_major_maintenance_km = 10000 WHERE id = ?", vehicleId);
        updateVehicle(vehicleJson("MAJOR_MAINTENANCE")).andExpect(status().isOk());

        mockMvc.perform(post("/api/vehicle-maintenance/" + vehicleId + "/cancel")
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk());
        entityManager.flush();

        Map<String, Object> saved = jdbcTemplate.queryForMap(
                "SELECT status, last_major_maintenance_km FROM vehicles WHERE id = ?", vehicleId);
        assertEquals("AVAILABLE", saved.get("status"));
        assertEquals(10000, ((Number) saved.get("last_major_maintenance_km")).intValue());
        assertEquals("CANCELLED", jdbcTemplate.queryForObject(
                "SELECT status FROM vehicle_maintenance_records WHERE vehicle_id = ?", String.class, vehicleId));

        mockMvc.perform(delete("/api/vehicles/" + vehicleId)
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isBadRequest());
    }

    private ResultActions correctMileage(Map<String, Object> correction, String role) throws Exception {
        long userId = AuthService.ROLE_ADMIN.equals(role) ? ADMIN_ID : 123L;
        return mockMvc.perform(post("/api/vehicles/" + vehicleId + "/mileage-corrections")
                .header("Authorization", bearer(userId, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(correction)));
    }

    private ResultActions updateVehicle(Map<String, Object> vehicle) throws Exception {
        return mockMvc.perform(put("/api/vehicles/" + vehicleId)
                .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(vehicle)));
    }

    /**
     * 修改車輛的請求：必填欄位照舊，里程與基準不帶（null＝不改）。
     * 保養間隔跟畫面一樣每次都帶：它沒有「只能補一次」，沒帶就會被清掉
     */
    private Map<String, Object> vehicleJson(String status) {
        Map<String, Object> vehicle = new LinkedHashMap<>();
        vehicle.put("warehouseId", warehouseId);
        vehicle.put("plateNumber", MARKER);
        vehicle.put("capacity", 50);
        vehicle.put("minorMaintenanceIntervalKm", 3000);
        vehicle.put("majorMaintenanceIntervalKm", 20000);
        vehicle.put("retirementKm", 500000);
        vehicle.put("status", status);
        return vehicle;
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token（做法同 DriverLoadingApiTest） */
    private String bearer(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("vehicle-maintenance-test")
                .claim("userId", userId)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

package com.example.backend.controller;

import com.example.backend.service.AttendanceService;
import com.example.backend.service.AuthService;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 出車前安全檢查的 API 測試：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>補 PreTripInspectionServiceTest 用 mock 測不到的：multipart 解析、JSON 驗證訊息、BIT／DECIMAL 欄位真的存得進去、
 * 點交與出車里程真的被擋、通過時真的記下出車時的行車紀錄器里程、照片只有本人讀得到。</p>
 *
 * <p>出車（MileageLogsService.start）要求司機已打上班卡；打卡有自己的測試，這裡把 AttendanceService 換成
 * 「一律在上班中」的 mock，不必為了測安全檢查再造一整套班表與打卡資料。</p>
 *
 * <p>整個測試類別加 {@code @Transactional}：MockMvc 跟測試在同一條執行緒，service 的交易會併進測試的交易，
 * 每個測試結束整筆 rollback，自己建的司機、車、路線、訂單、檢查紀錄都不會留下；
 * 照片則靠 service 註冊的「沒有 commit 就刪檔」清掉，存在 build 底下的暫存資料夾。</p>
 *
 * <p>前提：本機 DB 已套用 V10，至少有一個倉庫與一間門市。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef",
        "app.storage.pre-trip-photos-dir=build/test-pre-trip-photos",
        // 出車時的行車紀錄器照片走 MileagePhotoStorageService，也放到 build 底下，不寫進 uploads/
        "app.storage.mileage-photos-dir=build/test-mileage-photos"
})
@Transactional
class PreTripInspectionApiTest {

    private static final String MARKER = "PRETRIPTEST";
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

    @MockitoBean
    private AttendanceService attendanceService;

    private MockMvc mockMvc;
    private long driverId;
    private long routeId;
    private long orderId;

    @BeforeEach
    void setUp() {
        when(attendanceService.isGpsUploadAllowed(anyLong())).thenReturn(true);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        long warehouseId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM warehouses", Long.class);
        long storeId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM stores", Long.class);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));

        jdbcTemplate.update("INSERT INTO drivers (account, name, work_start, work_end, rest_duration, is_active) "
                + "VALUES (?, '安全檢查測試司機', '08:00', '17:00', 60, 1)", MARKER);
        driverId = jdbcTemplate.queryForObject("SELECT id FROM drivers WHERE account = ?", Long.class, MARKER);

        jdbcTemplate.update("INSERT INTO vehicles (warehouse_id, plate_number, capacity, status) "
                + "VALUES (?, ?, 50, 'AVAILABLE')", warehouseId, MARKER);
        long vehicleId = jdbcTemplate.queryForObject("SELECT id FROM vehicles WHERE plate_number = ?", Long.class, MARKER);

        jdbcTemplate.update("INSERT INTO routes (date, warehouse_id, vehicle_id, driver_id, status, version) "
                + "VALUES (?, ?, ?, ?, 'PUBLISHED', 1)", today, warehouseId, vehicleId, driverId);
        routeId = jdbcTemplate.queryForObject(
                "SELECT id FROM routes WHERE vehicle_id = ? AND date = ?", Long.class, vehicleId, today);

        jdbcTemplate.update("INSERT INTO orders (order_number, store_id, warehouse_id, box_count, delivery_date, "
                        + "status, route_id, assigned_vehicle_id, assigned_driver_id, sequence, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 2, ?, 'CONFIRMED', ?, ?, ?, 1, NOW(6), NOW(6))",
                MARKER + "-1", storeId, warehouseId, today, routeId, vehicleId, driverId);
        orderId = jdbcTemplate.queryForObject("SELECT id FROM orders WHERE order_number = ?", Long.class, MARKER + "-1");
    }

    @Test
    void 還沒檢查_直接呼叫點交和出車里程API也會被擋() throws Exception {
        load().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請先在「今日任務」完成出車前安全檢查"));

        mockMvc.perform(post("/api/driver/mileage/start")
                        .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"odometer\": 1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請先在「今日任務」完成出車前安全檢查"));
    }

    @Test
    void 全部正常而且酒測0_通過時記下行車紀錄器里程_之後可以點交_照片只有本人看得到() throws Exception {
        String body = submit(requestJson(new LinkedHashMap<>()), true, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.departed").value(true))
                .andExpect(jsonPath("$.startOdometer").value(18400))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 用原本的出車流程記下：出車讀數、行車紀錄器照片都在 mileage_logs
        Map<String, Object> mileage = jdbcTemplate.queryForMap(
                "SELECT start_odometer, mileage_photo_url FROM mileage_logs WHERE route_id = ?", routeId);
        assertEquals(18400, ((Number) mileage.get("start_odometer")).intValue());
        assertEquals(true, String.valueOf(mileage.get("mileage_photo_url")).startsWith("/uploads/mileage-photos/"));
        Number inspectionId = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/driver/pre-trip").param("routeId", Long.toString(routeId))
                        .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        String photoUrl = "/api/driver/pre-trip/" + inspectionId + "/photos/alcohol";
        mockMvc.perform(get(photoUrl).header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mockMvc.perform(get(photoUrl).header("Authorization", bearer(driverId + 100000, AuthService.ROLE_DRIVER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(photoUrl))
                .andExpect(status().isUnauthorized());

        load().andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("LOADED"));
    }

    @Test
    void 有異常項目_存成不通過_點交照樣被擋() throws Exception {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("brakeLights", false);
        changes.put("note", "左後煞車燈不亮");

        submit(requestJson(changes), true, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.abnormalItems[0]").value("煞車燈"))
                .andExpect(jsonPath("$.hasFaultPhoto").value(true));

        // BIT 欄位真的存成 0：這一筆 passed = 0、brake_lights = 0
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pre_trip_inspections WHERE route_id = ? AND passed = 0 AND brake_lights = 0",
                Integer.class, routeId));
        load().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("出車前安全檢查沒有通過，請聯絡主管處理"));
    }

    @Test
    void 酒測值存成兩位小數() throws Exception {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("alcoholMgL", 0.15);

        submit(requestJson(changes), true, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false));

        assertEquals("0.15", jdbcTemplate.queryForObject(
                "SELECT CAST(alcohol_mg_l AS CHAR) FROM pre_trip_inspections WHERE route_id = ?", String.class, routeId));
    }

    @Test
    void 通過卻沒拍行車紀錄器_回400而且沒有出車() throws Exception {
        submit(requestJson(new LinkedHashMap<>()), true, false, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請拍行車紀錄器畫面，要看得到里程"));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mileage_logs WHERE route_id = ?", Integer.class, routeId));
    }

    @Test
    void 跑完這趟會超過保養里程_不能出車() throws Exception {
        // 這台車每 1000 公里小保；上次小保 17400，今天出車讀數 18400 → 剛好到，這趟 30 公里跑完就超過 30 公里
        jdbcTemplate.update("UPDATE vehicles SET minor_maintenance_interval_km = 1000, major_maintenance_interval_km = 20000, "
                + "retirement_km = 500000, last_minor_maintenance_km = 17400, last_major_maintenance_km = 10000 "
                + "WHERE plate_number = ?", MARKER);
        // 發布時存的總里程（公尺）
        jdbcTemplate.update("UPDATE routes SET total_distance = 30000 WHERE id = ?", routeId);

        submit(requestJson(new LinkedHashMap<>()), true, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MARKER + " 不能出車：小保里程將超過 30.0 km"));
    }

    @Test
    void 已經出車_不能再送檢查() throws Exception {
        submit(requestJson(new LinkedHashMap<>()), true, false).andExpect(status().isOk());

        submit(requestJson(new LinkedHashMap<>()), true, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("今天已經出車，不用再做安全檢查"));
    }

    @Test
    void 沒附酒測器照片_回400而且講清楚() throws Exception {
        submit(requestJson(new LinkedHashMap<>()), false, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請拍酒測器讀數照片"));
    }

    @Test
    void 漏填項目_回400而且講是哪一項() throws Exception {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("dashcam", null);

        submit(requestJson(changes), true, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請檢查行車紀錄器"));
    }

    @Test
    void 主管的token不能呼叫司機的安全檢查() throws Exception {
        mockMvc.perform(get("/api/driver/pre-trip").param("routeId", Long.toString(routeId))
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isForbidden());
    }

    private ResultActions load() throws Exception {
        return mockMvc.perform(post("/api/driver/loading")
                .header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": " + orderId + ", \"loadedBoxCount\": 2}"));
    }

    /** 行車紀錄器照片預設有附；要測沒附的情況用四個參數的版本 */
    private ResultActions submit(String requestJson, boolean withAlcoholPhoto, boolean withFaultPhoto) throws Exception {
        return submit(requestJson, withAlcoholPhoto, withFaultPhoto, true);
    }

    private ResultActions submit(String requestJson, boolean withAlcoholPhoto, boolean withFaultPhoto,
                                 boolean withDashcamPhoto) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/driver/pre-trip");
        request.file(new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE,
                requestJson.getBytes(StandardCharsets.UTF_8)));
        if (withAlcoholPhoto) {
            request.file(new MockMultipartFile("alcoholPhoto", "alcohol.png", "image/png", png()));
        }
        if (withDashcamPhoto) {
            request.file(new MockMultipartFile("dashcamPhoto", "dashcam.png", "image/png", png()));
        }
        if (withFaultPhoto) {
            request.file(new MockMultipartFile("faultPhoto", "fault.png", "image/png", png()));
        }
        return mockMvc.perform(request.header("Authorization", bearer(driverId, AuthService.ROLE_DRIVER)));
    }

    /** 15 項全部正常、酒測 0.00 的請求；changes 裡的欄位會覆蓋預設值（放 null 代表漏填） */
    private String requestJson(Map<String, Object> changes) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("routeId", routeId);
        request.put("alcoholMgL", 0.00);
        request.put("odometer", 18400);
        for (String item : new String[]{"dashcam", "engineOil", "brakeFluid", "powerSteeringFluid",
                "transmissionOil", "fuel", "coolant", "batteryWater", "washerFluid", "tirePressure",
                "tireTread", "headlights", "turnSignals", "brakeLights", "dashboardLights"}) {
            request.put(item, true);
        }
        request.putAll(changes);
        return jsonMapper.writeValueAsString(request);
    }

    /** 1×1 的真 PNG：照片存檔會看檔頭判斷格式，隨便幾個位元組存不進去 */
    private byte[] png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token（做法同 DriverLoadingApiTest） */
    private String bearer(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("pre-trip-inspection-test")
                .claim("userId", userId)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

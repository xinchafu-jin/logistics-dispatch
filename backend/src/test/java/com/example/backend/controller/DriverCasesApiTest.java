package com.example.backend.controller;

import com.example.backend.dto.respones.DriverMessageSummaryResponse;
import com.example.backend.service.AuthService;
import com.example.backend.service.DriverMessagesService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 司機例外回報案件的 API 測試：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>案件都用 API 建，說明帶 MARKER；@AfterEach 先刪這些案件的對話，再刪案件，不留痕跡。
 * 規則的細節（訂單要在今天路線上、照片網址、同時接收）在 DriverCaseServiceTest，這裡測的是
 * 權限、JSON 長相、SQL 查詢（一般對話不能混進案件訊息）這些只有接上資料庫才看得到的東西。</p>
 *
 * <p>前提同 DriverMessagesApiTest：drivers 有 id 1、2，admin_users 有 id 1。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class DriverCasesApiTest {

    private static final String MARKER = "[DriverCasesApiTest]";
    private static final long DRIVER_ID = 1L;
    private static final long OTHER_DRIVER_ID = 2L;
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
    private DriverMessagesService driverMessagesService;

    private MockMvc mockMvc;
    private String driver;
    private String otherDriver;
    private String admin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        driver = bearer(DRIVER_ID, AuthService.ROLE_DRIVER);
        otherDriver = bearer(OTHER_DRIVER_ID, AuthService.ROLE_DRIVER);
        admin = bearer(ADMIN_ID, AuthService.ROLE_ADMIN);
    }

    @AfterEach
    void cleanUp() {
        List<Long> caseIds = jdbcTemplate.queryForList(
                "SELECT id FROM exception_cases WHERE description LIKE ?", Long.class, MARKER + "%");
        for (Long caseId : caseIds) {
            jdbcTemplate.update("DELETE FROM driver_messages WHERE exception_case_id = ?", caseId);
        }
        jdbcTemplate.update("DELETE FROM exception_cases WHERE description LIKE ?", MARKER + "%");
    }

    // ── 建案 ───────────────────────────────────────────────

    @Test
    void 司機建案_回司機端的形狀_清單看得到_還沒人接收() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/driver/cases")
                        .header("Authorization", driver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(caseJson("VEHICLE", MARKER + " 爆胎", false, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.category").value("VEHICLE"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.canContinue").value(false))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.acceptedAt").doesNotExist())
                .andExpect(jsonPath("$.unreadCount").value(0))
                // 後台才有的欄位不能出現在司機的回應
                .andExpect(jsonPath("$.driverName").doesNotExist())
                .andReturn();
        long caseId = idOf(result);

        mockMvc.perform(get("/api/driver/cases").header("Authorization", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + caseId + ")].status").value(contains("OPEN")));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT type, driver_id, category, can_continue FROM exception_cases WHERE id = ?", caseId);
        assertEquals("DRIVER_REPORT", row.get("type"));
        assertEquals(DRIVER_ID, ((Number) row.get("driver_id")).longValue(), "司機要從 token 取");
        assertEquals("VEHICLE", row.get("category"));
        assertEquals(Boolean.FALSE, row.get("can_continue"));
    }

    @Test
    void 建案的檢查_錯了回400也不存檔() throws Exception {
        mockMvc.perform(createRequest(caseJson("OTHER", "　　", true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請點選發生的狀況，或寫一段說明"));
        mockMvc.perform(createRequest(caseJson("OTHER", MARKER + " 外部照片", true, "https://example.com/x.jpg")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("照片網址不正確，請重新上傳照片"));
        mockMvc.perform(createRequest("{\"category\":\"OTHER\",\"description\":\"" + MARKER + " 沒選\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請選擇還能不能繼續配送"));
        // 不存在的分類：JSON 轉不成 enum，被當成一般錯誤回 400
        mockMvc.perform(createRequest(caseJson("FLYING", MARKER + " 亂填分類", true, null)))
                .andExpect(status().isBadRequest());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exception_cases WHERE description LIKE ?", Integer.class, MARKER + "%"));
    }

    // ── 權限 ───────────────────────────────────────────────

    @Test
    void 司機看不到別人的案件() throws Exception {
        long caseId = createCase(MARKER + " 塞車", true);

        mockMvc.perform(get("/api/driver/cases/{caseId}/messages", caseId).header("Authorization", otherDriver))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("找不到案件，ID：" + caseId));
        mockMvc.perform(send("/api/driver/cases/" + caseId + "/messages", otherDriver, MARKER + " 偷留言"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/driver/cases").header("Authorization", otherDriver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(not(hasItem((int) caseId))));
    }

    @Test
    void 司機不能打後台的案件API() throws Exception {
        long caseId = createCase(MARKER + " 爆胎", false);

        mockMvc.perform(get("/api/exceptions/driver-cases").header("Authorization", driver))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/exceptions/driver-cases/{caseId}/accept", caseId).header("Authorization", driver))
                .andExpect(status().isForbidden());

        assertNull(jdbcTemplate.queryForObject(
                "SELECT accepted_admin_id FROM exception_cases WHERE id = ?", Long.class, caseId),
                "被擋下的請求卻改到了資料庫");
    }

    // ── 接收與對話 ───────────────────────────────────────────

    @Test
    void 還沒接收就回覆_400_接收後才能回() throws Exception {
        long caseId = createCase(MARKER + " 無法發動", false);
        String url = "/api/exceptions/driver-cases/" + caseId + "/messages";

        mockMvc.perform(send(url, admin, MARKER + " 太早回"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請先在異常中心接收這件案件，再回覆司機"));

        mockMvc.perform(patch("/api/exceptions/driver-cases/{caseId}/accept", caseId).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedAdminId").value(ADMIN_ID))
                .andExpect(jsonPath("$.acceptedAt").isString())
                .andExpect(jsonPath("$.driverName").isString());

        mockMvc.perform(send(url, admin, MARKER + " 收到，派人過去"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderType").value("ADMIN"))
                .andExpect(jsonPath("$.exceptionCaseId").value(caseId));
    }

    @Test
    void 案件訊息不會混進一般對話_也不算進一般紅點() throws Exception {
        long caseId = createCase(MARKER + " 門市沒開", true);

        mockMvc.perform(send("/api/driver/cases/" + caseId + "/messages", driver, MARKER + " 案件補充"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptionCaseId").value(caseId));

        mockMvc.perform(get("/api/driver/messages").header("Authorization", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].content").value(not(hasItem(MARKER + " 案件補充"))));

        // 一般對話的紅點只算 exception_case_id 是 NULL 的
        int generalUnread = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM driver_messages WHERE driver_id = ? AND exception_case_id IS NULL "
                        + "AND sender_type = 'DRIVER' AND read_at IS NULL", Integer.class, DRIVER_ID);
        long summaryUnread = driverMessagesService.findUnreadSummary().stream()
                .filter(item -> item.getDriverId() == DRIVER_ID)
                .mapToLong(DriverMessageSummaryResponse::getUnreadCount)
                .sum();
        assertEquals(generalUnread, summaryUnread, "一般對話的紅點把案件訊息算進去了");

        // 案件清單的紅點要算它
        mockMvc.perform(get("/api/exceptions/driver-cases").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + caseId + ")].unreadCount").value(contains(1)));
    }

    @Test
    void 管理員回覆後_司機清單的未讀加一_標已讀後歸零() throws Exception {
        long caseId = createCase(MARKER + " 裝錯貨", true);
        accept(caseId);
        mockMvc.perform(send("/api/exceptions/driver-cases/" + caseId + "/messages", admin, MARKER + " 先送下一站"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/driver/cases").header("Authorization", driver))
                .andExpect(jsonPath("$[?(@.id == " + caseId + ")].unreadCount").value(contains(1)));
        MvcResult messages = mockMvc.perform(get("/api/driver/cases/{caseId}/messages", caseId)
                        .header("Authorization", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[-1:].content").value(contains(MARKER + " 先送下一站")))
                .andReturn();
        int lastId = JsonPath.read(messages.getResponse().getContentAsString(), "$[-1].id");
        mockMvc.perform(get("/api/driver/cases/{caseId}/messages", caseId)
                        .param("afterId", String.valueOf(lastId))
                        .header("Authorization", driver))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/driver/cases/{caseId}/messages/read", caseId).header("Authorization", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
        mockMvc.perform(get("/api/driver/cases").header("Authorization", driver))
                .andExpect(jsonPath("$[?(@.id == " + caseId + ")].unreadCount").value(contains(0)));
    }

    // ── 結案 ───────────────────────────────────────────────

    @Test
    void 結案後_司機不能再留言_清單移到已結案() throws Exception {
        long caseId = createCase(MARKER + " 擦撞無人受傷", false);

        mockMvc.perform(patch("/api/exceptions/driver-cases/{caseId}/close", caseId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"已報警並改派\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.handledBy").value("測試"))
                .andExpect(jsonPath("$.resolution").value("已報警並改派"));

        mockMvc.perform(send("/api/driver/cases/" + caseId + "/messages", driver, MARKER + " 還在嗎"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("這件案件已結案，不能再留言"));
        mockMvc.perform(get("/api/exceptions/driver-cases").header("Authorization", admin))
                .andExpect(jsonPath("$[*].id").value(not(hasItem((int) caseId))));
        mockMvc.perform(get("/api/exceptions/driver-cases").param("status", "CLOSED").header("Authorization", admin))
                .andExpect(jsonPath("$[*].id").value(hasItem((int) caseId)));
        mockMvc.perform(get("/api/driver/cases").header("Authorization", driver))
                .andExpect(jsonPath("$[?(@.id == " + caseId + ")].status").value(contains("CLOSED")));
    }

    @Test
    void 一般異常的結案API_不能結司機回報() throws Exception {
        long caseId = createCase(MARKER + " 走錯API", true);

        mockMvc.perform(patch("/api/exceptions/{id}/close", caseId)
                        .header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"不該在這裡結\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("司機回報請在異常中心的司機回報清單結案"));
    }

    // ── 工具 ───────────────────────────────────────────────

    private long createCase(String description, boolean canContinue) throws Exception {
        MvcResult result = mockMvc.perform(createRequest(caseJson("VEHICLE", description, canContinue, null)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result);
    }

    private void accept(long caseId) throws Exception {
        mockMvc.perform(patch("/api/exceptions/driver-cases/{caseId}/accept", caseId).header("Authorization", admin))
                .andExpect(status().isOk());
    }

    private RequestBuilder createRequest(String json) {
        return post("/api/driver/cases")
                .header("Authorization", driver)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    /** 用字串組 JSON：測試的文字裡沒有雙引號和反斜線，不需要跳脫 */
    private String caseJson(String category, String description, boolean canContinue, String photoUrl) {
        return "{\"category\":\"" + category + "\",\"orderId\":null,\"description\":\"" + description
                + "\",\"canContinue\":" + canContinue + ",\"photoUrl\":"
                + (photoUrl == null ? "null" : "\"" + photoUrl + "\"") + "}";
    }

    private RequestBuilder send(String url, String bearer, String content) {
        return post(url)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + content + "\"}");
    }

    private long idOf(MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token。 */
    private String bearer(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("driver-cases-test")
                .claim("userId", userId)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

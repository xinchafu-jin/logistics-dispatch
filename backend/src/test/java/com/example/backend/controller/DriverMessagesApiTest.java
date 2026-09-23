package com.example.backend.controller;

import com.example.backend.service.AuthService;
import com.example.backend.service.DriverMessagesService;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 司機聊天室的 API 測試：讀取對話、發訊息、標已讀、紅點：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>測試前直接用 SQL 塞三則訊息給司機 1；發訊息測試送出的內容也帶 MARKER，
 * 測完在 @AfterEach 一起刪掉，不留痕跡。
 * 標已讀會改到整串對話，所以司機 1 原本就未讀的訊息也會先記下 id，測完還原成未讀。
 * token 用專案的 JwtEncoder 簽，不需要帳號密碼（做法同 AiApiKeyApiTest）。</p>
 *
 * <p>前提：本機 DB 已有 driver_messages 表，drivers 表有 id 1、2 兩位司機，admin_users 有 id 1。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class DriverMessagesApiTest {

    private static final String MARKER = "[DriverMessagesApiTest]";
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

    /** 三則測試訊息的 id，由舊到新：DRIVER、ADMIN、DRIVER，全部未讀 */
    private List<Long> ids;

    /** 司機 1 在測試前就未讀的非測試訊息，測完要還原 */
    private List<Long> preExistingUnread;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        preExistingUnread = jdbcTemplate.queryForList(
                "SELECT id FROM driver_messages WHERE driver_id = ? AND read_at IS NULL AND content NOT LIKE ?",
                Long.class, DRIVER_ID, MARKER + "%");

        LocalDateTime now = LocalDateTime.now();
        insert("DRIVER", MARKER + " 1 司機：塞車", now);
        insert("ADMIN", MARKER + " 2 調度中心：收到", now.plusSeconds(1));
        insert("DRIVER", MARKER + " 3 司機：到了", now.plusSeconds(2));
        ids = jdbcTemplate.queryForList(
                "SELECT id FROM driver_messages WHERE content LIKE ? ORDER BY id",
                Long.class, MARKER + "%");
        assertTrue(ids.size() == 3, "測試訊息沒有正確寫入");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM driver_messages WHERE content LIKE ?", MARKER + "%");
        for (Long id : preExistingUnread) {
            jdbcTemplate.update("UPDATE driver_messages SET read_at = NULL WHERE id = ?", id);
        }
    }

    @Test
    void 司機不帶afterId_最後三則是剛寫入的_由舊到新() throws Exception {
        // 司機 1 原本可能就有訊息，所以只檢查最後三則；[-3:] 是 JsonPath 的「最後三個」
        mockMvc.perform(get("/api/driver/messages").header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[-3:].id").value(contains(
                        ids.get(0).intValue(), ids.get(1).intValue(), ids.get(2).intValue())))
                .andExpect(jsonPath("$[-3:].senderType").value(contains("DRIVER", "ADMIN", "DRIVER")));
    }

    @Test
    void 司機帶afterId_只拿到比它新的() throws Exception {
        mockMvc.perform(get("/api/driver/messages")
                        .param("afterId", ids.get(0).toString())
                        .header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id").value(contains(ids.get(1).intValue(), ids.get(2).intValue())));
    }

    @Test
    void 帶最後一則的afterId_回空陣列() throws Exception {
        mockMvc.perform(get("/api/driver/messages")
                        .param("afterId", ids.get(2).toString())
                        .header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 司機看不到別人的對話() throws Exception {
        mockMvc.perform(get("/api/driver/messages").header("Authorization", bearer(OTHER_DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(not(hasItem(ids.get(0).intValue()))));
    }

    @Test
    void 回應不帶senderAdminId() throws Exception {
        mockMvc.perform(get("/api/driver/messages").header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[-1].senderAdminId").doesNotExist());
    }

    @Test
    void 管理員可以讀指定司機的對話() throws Exception {
        mockMvc.perform(get("/api/drivers/{driverId}/messages", DRIVER_ID)
                        .param("afterId", ids.get(0).toString())
                        .header("Authorization", bearer(1L, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(ids.get(1).intValue(), ids.get(2).intValue())));
    }

    /** 最重要的一項：後台 API 掛在 /api/drivers/** 底下，司機的 token 必須被擋。 */
    @Test
    void 司機的Token不能呼叫後台API() throws Exception {
        mockMvc.perform(get("/api/drivers/{driverId}/messages", DRIVER_ID)
                        .header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 管理員的Token不能呼叫司機端API() throws Exception {
        mockMvc.perform(get("/api/driver/messages").header("Authorization", bearer(1L, AuthService.ROLE_ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 沒帶Token會被拒絕() throws Exception {
        mockMvc.perform(get("/api/driver/messages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 查不存在的司機回400() throws Exception {
        mockMvc.perform(get("/api/drivers/{driverId}/messages", 999_999_999L)
                        .header("Authorization", bearer(1L, AuthService.ROLE_ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("找不到司機，ID：999999999"));
    }

    // ── 發訊息 ─────────────────────────────────────────────

    @Test
    void 司機發訊息_前後空白被去掉_回傳帶id() throws Exception {
        mockMvc.perform(send("/api/driver/messages", bearer(DRIVER_ID, AuthService.ROLE_DRIVER),
                        "  " + MARKER + " 司機發的  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.driverId").value(DRIVER_ID))
                .andExpect(jsonPath("$.senderType").value("DRIVER"))
                .andExpect(jsonPath("$.content").value(MARKER + " 司機發的"))
                .andExpect(jsonPath("$.readAt").doesNotExist());

        Map<String, Object> row = latestTestRow();
        assertEquals(DRIVER_ID, ((Number) row.get("driver_id")).longValue());
        assertNull(row.get("sender_admin_id"), "司機發的訊息不該有 sender_admin_id");
    }

    /** 前端多塞 driverId 也沒用：DTO 只有 content，對話屬於誰一律從 token 取。 */
    @Test
    void 司機不能替別人發訊息() throws Exception {
        mockMvc.perform(post("/api/driver/messages")
                        .header("Authorization", bearer(OTHER_DRIVER_ID, AuthService.ROLE_DRIVER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":" + DRIVER_ID + ",\"content\":\"" + MARKER + " 冒充\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(OTHER_DRIVER_ID));
    }

    @Test
    void 全形空白會被擋() throws Exception {
        mockMvc.perform(send("/api/driver/messages", bearer(DRIVER_ID, AuthService.ROLE_DRIVER), "\u3000\u3000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("訊息不能是空白"));
    }

    @Test
    void 超過1000字會被擋() throws Exception {
        mockMvc.perform(send("/api/driver/messages", bearer(DRIVER_ID, AuthService.ROLE_DRIVER), "字".repeat(1001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("訊息不能超過 1000 字"));
    }

    @Test
    void 管理員回覆_記下是哪位管理員() throws Exception {
        mockMvc.perform(send("/api/drivers/" + DRIVER_ID + "/messages", bearer(ADMIN_ID, AuthService.ROLE_ADMIN),
                        MARKER + " 調度中心回覆"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(DRIVER_ID))
                .andExpect(jsonPath("$.senderType").value("ADMIN"))
                .andExpect(jsonPath("$.senderAdminId").doesNotExist());

        Map<String, Object> row = latestTestRow();
        assertEquals(ADMIN_ID, ((Number) row.get("sender_admin_id")).longValue(), "沒有記下回覆的管理員");
    }

    @Test
    void 管理員回覆不存在的司機回400() throws Exception {
        mockMvc.perform(send("/api/drivers/999999999/messages", bearer(ADMIN_ID, AuthService.ROLE_ADMIN),
                        MARKER + " 不存在"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("找不到司機，ID：999999999"));
    }

    @Test
    void 司機不能用後台API冒充調度中心() throws Exception {
        mockMvc.perform(send("/api/drivers/" + DRIVER_ID + "/messages", bearer(DRIVER_ID, AuthService.ROLE_DRIVER),
                        MARKER + " 冒充調度中心"))
                .andExpect(status().isForbidden());
        assertEquals(3, countTestRows(), "被擋下的請求卻寫進了資料庫");
    }

    /**
     * 繞過 DTO 直接呼叫 Service：模擬哪天有 controller 忘了加 @Valid。
     * Service 的錯誤訊息要跟 DTO 一字不差，前端才不會因為擋在哪一層而看到不同的字。
     */
    @Test
    void Service的錯誤訊息跟DTO一致() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> driverMessagesService.sendFromDriver(DRIVER_ID, "\u3000 \u3000"));
        assertEquals("訊息不能是空白", blank.getMessage());

        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> driverMessagesService.sendFromDriver(DRIVER_ID, "字".repeat(1001)));
        assertEquals("訊息不能超過 1000 字", tooLong.getMessage());

        assertEquals(3, countTestRows(), "被擋下的訊息卻寫進了資料庫");
    }

    // ── 標已讀 ─────────────────────────────────────────────

    @Test
    void 管理員標已讀_只標司機發的() throws Exception {
        int expected = countUnread(DRIVER_ID, "DRIVER");
        mockMvc.perform(post("/api/drivers/{driverId}/messages/read", DRIVER_ID)
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(expected));

        assertTrue(isRead(ids.get(0)), "司機發的第 1 則應該變成已讀");
        assertTrue(isRead(ids.get(2)), "司機發的第 3 則應該變成已讀");
        assertFalse(isRead(ids.get(1)), "調度中心發的那則是給司機讀的，管理員標已讀不能動到它");
    }

    @Test
    void 已讀的再標一次回0() throws Exception {
        String admin = bearer(ADMIN_ID, AuthService.ROLE_ADMIN);
        mockMvc.perform(post("/api/drivers/{driverId}/messages/read", DRIVER_ID).header("Authorization", admin))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/drivers/{driverId}/messages/read", DRIVER_ID).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(0));
    }

    @Test
    void 司機標已讀_只標調度中心發的() throws Exception {
        int expected = countUnread(DRIVER_ID, "ADMIN");
        mockMvc.perform(post("/api/driver/messages/read")
                        .header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(expected));

        assertTrue(isRead(ids.get(1)), "調度中心發的那則應該變成已讀");
        assertFalse(isRead(ids.get(0)), "司機自己發的訊息不能被自己標成已讀");
        assertFalse(isRead(ids.get(2)), "司機自己發的訊息不能被自己標成已讀");
    }

    @Test
    void 司機標已讀不會動到別人的對話() throws Exception {
        mockMvc.perform(post("/api/driver/messages/read")
                        .header("Authorization", bearer(OTHER_DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isOk());
        assertFalse(isRead(ids.get(1)), "司機 2 標已讀，卻改到了司機 1 的對話");
    }

    @Test
    void 已讀時間是台北時間() throws Exception {
        LocalDateTime before = LocalDateTime.now(java.time.ZoneId.of("Asia/Taipei")).minusSeconds(5);
        mockMvc.perform(post("/api/drivers/{driverId}/messages/read", DRIVER_ID)
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk());
        LocalDateTime readAt = jdbcTemplate.queryForObject(
                "SELECT read_at FROM driver_messages WHERE id = ?", LocalDateTime.class, ids.get(0));
        assertTrue(readAt.isAfter(before), "read_at 不是台北時間：" + readAt);
    }

    @Test
    void 司機不能用後台API標已讀() throws Exception {
        mockMvc.perform(post("/api/drivers/{driverId}/messages/read", DRIVER_ID)
                        .header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isForbidden());
        assertFalse(isRead(ids.get(0)), "被擋下的請求卻改到了資料庫");
    }

    // ── 紅點 ───────────────────────────────────────────────

    @Test
    void 紅點_算的是司機發的未讀() throws Exception {
        int expected = countUnread(DRIVER_ID, "DRIVER");
        mockMvc.perform(get("/api/drivers/messages/summary")
                        .header("Authorization", bearer(ADMIN_ID, AuthService.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.driverId == " + DRIVER_ID + ")].unreadCount").value(contains(expected)));
    }

    @Test
    void 紅點_管理員標已讀後這位司機就不在清單裡() throws Exception {
        String admin = bearer(ADMIN_ID, AuthService.ROLE_ADMIN);
        mockMvc.perform(post("/api/drivers/{driverId}/messages/read", DRIVER_ID).header("Authorization", admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/drivers/messages/summary").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].driverId").value(not(hasItem((int) DRIVER_ID))));
    }

    @Test
    void 司機不能看紅點() throws Exception {
        mockMvc.perform(get("/api/drivers/messages/summary")
                        .header("Authorization", bearer(DRIVER_ID, AuthService.ROLE_DRIVER)))
                .andExpect(status().isForbidden());
    }

    private boolean isRead(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT read_at IS NOT NULL FROM driver_messages WHERE id = ?", Boolean.class, id);
    }

    private int countUnread(long driverId, String senderType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM driver_messages WHERE driver_id = ? AND sender_type = ? AND read_at IS NULL",
                Integer.class, driverId, senderType);
    }

    private org.springframework.test.web.servlet.RequestBuilder send(String url, String bearer, String content) {
        return post(url)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                // 用字串組 JSON：content 裡沒有雙引號和反斜線，不需要跳脫
                .content("{\"content\":\"" + content + "\"}");
    }

    private Map<String, Object> latestTestRow() {
        return jdbcTemplate.queryForMap(
                "SELECT driver_id, sender_type, sender_admin_id FROM driver_messages WHERE content LIKE ? ORDER BY id DESC LIMIT 1",
                MARKER + "%");
    }

    private int countTestRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM driver_messages WHERE content LIKE ?", Integer.class, MARKER + "%");
    }

    private void insert(String senderType, String content, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO driver_messages (driver_id, sender_type, content, created_at) VALUES (?, ?, ?, ?)",
                DRIVER_ID, senderType, content, createdAt);
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token。 */
    private String bearer(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("driver-messages-test")
                .claim("userId", userId)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

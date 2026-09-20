package com.example.backend.controller;

import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.entity.AdminUsersEntity;
import com.example.backend.service.AuthService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 步驟四驗證清單的自動版：請求經過真的 Security 過濾器 → Controller → Service → 本機 MySQL。
 *
 * <p>不走登入 API，改用專案自己的 JwtEncoder 簽出測試用 token（claim 與 AuthService.issueToken 相同），
 * 所以不需要任何帳號密碼。</p>
 *
 * <p>會改到第一位主管的 ai_api_key 三個欄位，測完在 finally 還原成測試前的值。</p>
 *
 * <p>加密用的 password／salt 用測試專用的假值，不依賴環境變數；測試只還原原本的密文字串、不解密它，
 * 所以跟真正的加密設定不衝突。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class AiApiKeyApiTest {

    private static final String URL = "/api/admin-users/me/ai-api-key";
    private static final String FAKE_KEY = "sk-test-fake-key-Zq7x";

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
    private AdminUsersDAO adminUsersDAO;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void 主管可以設定_查詢_移除自己的Key() throws Exception {
        List<AdminUsersEntity> admins = adminUsersDAO.findAll();
        assertFalse(admins.isEmpty(), "DB 裡沒有任何主管帳號，無法測試");
        Long adminId = admins.getFirst().getId();
        String adminToken = token(adminId, AuthService.ROLE_ADMIN);
        Map<String, Object> original = readKeyColumns(adminId);

        try {
            // 先移除，讓起始狀態固定為「未設定」
            mockMvc.perform(delete(URL).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());

            // 1. GET → 未設定
            mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configured").value(false))
                    .andExpect(jsonPath("$.maskedKey").value(nullValue()));

            // 2. PUT 假 Key → 已設定、只回遮罩、時間有時分秒
            mockMvc.perform(putKey(adminToken, FAKE_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configured").value(true))
                    .andExpect(jsonPath("$.maskedKey").value("****Zq7x"))
                    .andExpect(jsonPath("$.updatedAt").value(containsString("T")))
                    .andExpect(content -> assertFalse(
                            content.getResponse().getContentAsString().contains(FAKE_KEY),
                            "回應裡出現了完整的 Key"));

            // 3. DB 存的是密文
            Map<String, Object> afterFirstSave = readKeyColumns(adminId);
            String firstCipher = (String) afterFirstSave.get("ai_api_key_encrypted");
            assertNotNull(firstCipher, "PUT 成功但 DB 沒有寫入密文");
            assertFalse(firstCipher.contains(FAKE_KEY), "DB 裡看得到原本的 Key");
            assertTrue(firstCipher.matches("^[0-9a-f]+$"), "密文應該是 16 進位字串");
            assertEquals("Zq7x", afterFirstSave.get("ai_api_key_last4"));
            Object updatedAt = afterFirstSave.get("ai_api_key_updated_at");
            System.out.printf("[AiApiKeyApiTest] 密文長度=%d, last4=%s, updated_at=%s (%s)%n",
                    firstCipher.length(), afterFirstSave.get("ai_api_key_last4"), updatedAt,
                    updatedAt == null ? "null" : updatedAt.getClass().getSimpleName());
            assertInstanceOf(LocalDateTime.class, updatedAt,
                    "ai_api_key_updated_at 應該是 DATETIME(6)，讀回來的型別不對，請檢查 DB 欄位型別");

            // 4. 同一把 Key 再存一次 → 密文不同（隨機 IV）
            mockMvc.perform(putKey(adminToken, FAKE_KEY))
                    .andExpect(status().isOk());
            String secondCipher = (String) readKeyColumns(adminId).get("ai_api_key_encrypted");
            assertNotEquals(firstCipher, secondCipher, "同一把 Key 存兩次密文一樣，代表沒有用隨機 IV");

            // 5. 只有空白 → DTO 的 @NotBlank 擋下
            mockMvc.perform(putKey(adminToken, "   "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("API Key 不可空白"));

            // 6. 中間有空白 → Service 擋下
            mockMvc.perform(putKey(adminToken, "sk-test fake-Zq7x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("API Key 中間不可有空白或換行，請確認複製是否完整"));

            // 5、6 失敗的請求不能動到 DB
            assertEquals(secondCipher, readKeyColumns(adminId).get("ai_api_key_encrypted"),
                    "驗證失敗的請求卻改到了 DB");

            // 9. 另一位主管看不到這把 Key（DB 有第二位主管才測）
            if (admins.size() > 1) {
                Long otherId = admins.get(1).getId();
                Map<String, Object> otherBefore = readKeyColumns(otherId);
                mockMvc.perform(get(URL).header("Authorization", "Bearer " + token(otherId, AuthService.ROLE_ADMIN)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.maskedKey").value(not("****Zq7x")));
                assertEquals(otherBefore, readKeyColumns(otherId), "另一位主管的資料被改到了");
            } else {
                System.out.println("[AiApiKeyApiTest] DB 只有一位主管，略過第 9 項");
            }

            // 8. DELETE → 204 → GET 未設定、DB 三欄清空
            mockMvc.perform(delete(URL).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configured").value(false));
            Map<String, Object> afterDelete = readKeyColumns(adminId);
            assertNull(afterDelete.get("ai_api_key_encrypted"));
            assertNull(afterDelete.get("ai_api_key_last4"));
            assertNull(afterDelete.get("ai_api_key_updated_at"));
        } finally {
            jdbcTemplate.update(
                    "UPDATE admin_users SET ai_api_key_encrypted = ?, ai_api_key_last4 = ?, ai_api_key_updated_at = ? WHERE id = ?",
                    original.get("ai_api_key_encrypted"),
                    original.get("ai_api_key_last4"),
                    original.get("ai_api_key_updated_at"),
                    adminId);
        }
    }

    /** 7. 司機 token 的 userId 指的是 drivers 表，不能拿來查主管的 Key。只測 GET，避免擋不住時改到資料。 */
    @Test
    void 司機的Token不能呼叫主管的KeyAPI() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + token(1L, AuthService.ROLE_DRIVER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 沒帶Token會被拒絕() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.RequestBuilder putKey(String token, String apiKey) {
        return put(URL)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + apiKey + "\"}");
    }

    private Map<String, Object> readKeyColumns(Long adminId) {
        return jdbcTemplate.queryForMap(
                "SELECT ai_api_key_encrypted, ai_api_key_last4, ai_api_key_updated_at FROM admin_users WHERE id = ?",
                adminId);
    }

    /** 簽出與 AuthService.issueToken 相同 claim 的測試用 token。 */
    private String token(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("ai-api-key-test")
                .claim("userId", userId)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

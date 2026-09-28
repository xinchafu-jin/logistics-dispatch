package com.example.backend.controller;

import com.example.backend.constants.DriverCaseCategory;
import com.example.backend.dto.request.DriverCaseRequestDTO;
import com.example.backend.service.AuthService;
import com.example.backend.service.DriverCaseService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 案件推播測試：用真的 WebSocket 客戶端訂閱，再呼叫 DriverCaseService，確認誰收到什麼。
 *
 * <p>重點是後台和司機拿到的內容不一樣：後台要有回報人、接收人，司機那份不能有是哪位管理員
 * （跟聊天訊息不帶 senderAdminId 同一個理由）。</p>
 *
 * <p>前提同 DriverMessagesApiTest：drivers 有 id 1、2，admin_users 有 id 1。
 * 案件的說明帶 MARKER，測完先刪對話再刪案件。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class DriverCasesPushTest {

    private static final String MARKER = "[DriverCasesPushTest]";
    private static final String ADMIN_TOPIC = "/topic/admin/driver-messages";
    private static final String DRIVER_QUEUE = "/user/queue/messages";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private DriverCaseService driverCaseService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        List<Long> caseIds = jdbcTemplate.queryForList(
                "SELECT id FROM exception_cases WHERE description LIKE ?", Long.class, MARKER + "%");
        for (Long caseId : caseIds) {
            jdbcTemplate.update("DELETE FROM driver_messages WHERE exception_case_id = ?", caseId);
        }
        jdbcTemplate.update("DELETE FROM exception_cases WHERE description LIKE ?", MARKER + "%");
    }

    @Test
    void 司機建案_後台和司機本人都收到CASE_OPENED_別的司機收不到() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), ADMIN_TOPIC);
        BlockingQueue<String> driver1 = subscribe(bearer(1L, AuthService.ROLE_DRIVER), DRIVER_QUEUE);
        BlockingQueue<String> driver2 = subscribe(bearer(2L, AuthService.ROLE_DRIVER), DRIVER_QUEUE);

        Long caseId = driverCaseService.create(1L, request(MARKER + " 爆胎")).getId();

        String toAdmin = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(toAdmin, "管理員沒收到");
        assertEquals("CASE_OPENED", JsonPath.read(toAdmin, "$.type"));
        assertEquals(caseId.intValue(), (int) JsonPath.read(toAdmin, "$.exceptionCaseId"));
        assertTrue(JsonPath.read(toAdmin, "$.exceptionCase.driverName") instanceof String, "後台要知道是誰回報：" + toAdmin);

        String toDriver = driver1.poll(5, TimeUnit.SECONDS);
        assertNotNull(toDriver, "司機本人沒收到");
        assertEquals("CASE_OPENED", JsonPath.read(toDriver, "$.type"));
        assertFalse(toDriver.contains("driverName"), "司機那份不該有後台的欄位：" + toDriver);

        assertNull(driver2.poll(1, TimeUnit.SECONDS), "別的司機收到了");
    }

    @Test
    void 接收_司機那份沒有是哪位管理員_後台那份有接收人() throws Exception {
        Long caseId = driverCaseService.create(1L, request(MARKER + " 無法發動")).getId();
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), ADMIN_TOPIC);
        BlockingQueue<String> driver1 = subscribe(bearer(1L, AuthService.ROLE_DRIVER), DRIVER_QUEUE);

        driverCaseService.accept(caseId, 1L);

        String toAdmin = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(toAdmin, "管理員沒收到接收推播");
        assertEquals("CASE_ACCEPTED", JsonPath.read(toAdmin, "$.type"));
        assertEquals(1, (int) JsonPath.read(toAdmin, "$.exceptionCase.acceptedAdminId"));

        String toDriver = driver1.poll(5, TimeUnit.SECONDS);
        assertNotNull(toDriver, "司機沒收到接收推播");
        assertEquals("CASE_ACCEPTED", JsonPath.read(toDriver, "$.type"));
        // 司機端靠 acceptedAt 從「等待回覆」變「處理中」；時間要是字串，跟 REST 同一種格式
        assertTrue(JsonPath.read(toDriver, "$.exceptionCase.acceptedAt") instanceof String, toDriver);
        assertFalse(toDriver.contains("acceptedAdmin"), "推播外洩了是哪位管理員接收：" + toDriver);
    }

    @Test
    void 案件的訊息和已讀推播都帶exceptionCaseId() throws Exception {
        Long caseId = driverCaseService.create(1L, request(MARKER + " 門市沒開")).getId();
        driverCaseService.accept(caseId, 1L);
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), ADMIN_TOPIC);
        BlockingQueue<String> driver1 = subscribe(bearer(1L, AuthService.ROLE_DRIVER), DRIVER_QUEUE);

        driverCaseService.sendFromAdmin(caseId, 1L, MARKER + " 先送下一站");
        String reply = driver1.poll(5, TimeUnit.SECONDS);
        assertNotNull(reply, "司機沒收到案件回覆");
        assertEquals("MESSAGE", JsonPath.read(reply, "$.type"));
        assertEquals(caseId.intValue(), (int) JsonPath.read(reply, "$.message.exceptionCaseId"));
        assertFalse(reply.contains("senderAdminId"), "推播外洩了是哪位管理員回的");

        driverCaseService.sendFromDriver(1L, caseId, MARKER + " 好");
        Thread.sleep(300); // 等上面兩則訊息的推播都送到再清掉，免得混進下面的判斷
        admin.clear();

        driverCaseService.markReadByAdmin(caseId);
        String read = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(read, "標已讀沒有推播");
        assertEquals("READ", JsonPath.read(read, "$.type"));
        assertEquals("DRIVER", JsonPath.read(read, "$.readSenderType"));
        assertEquals(caseId.intValue(), (int) JsonPath.read(read, "$.exceptionCaseId"),
                "READ 沒帶案件，前端會把一般對話也標成已讀");
    }

    /** 建案的交易沒成功，就不能推出去：不然後台鈴鐺會多一件資料庫裡不存在的案件 */
    @Test
    void 交易rollback就不推() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), ADMIN_TOPIC);

        transactionTemplate.executeWithoutResult(status -> {
            driverCaseService.create(1L, request(MARKER + " 會被 rollback"));
            status.setRollbackOnly();
        });

        assertNull(admin.poll(1, TimeUnit.SECONDS), "rollback 的案件被推出去了");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exception_cases WHERE description = ?", Integer.class, MARKER + " 會被 rollback"));
    }

    private DriverCaseRequestDTO request(String description) {
        DriverCaseRequestDTO request = new DriverCaseRequestDTO();
        request.setCategory(DriverCaseCategory.VEHICLE);
        request.setDescription(description);
        request.setCanContinue(false);
        return request;
    }

    private BlockingQueue<String> subscribe(String authorization, String destination) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // 推播內容是 JSON，預設的 StringMessageConverter 只收 text/plain，這裡改成任何型別都當字串收
        client.setMessageConverter(new StringMessageConverter() {
            @Override
            protected boolean supportsMimeType(MessageHeaders headers) {
                return true;
            }
        });
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", authorization);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/api/ws",
                        new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        sessions.add(session);

        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { inbox.add((String) payload); }
        });
        Thread.sleep(300); // 等訂閱在 broker 登記完成
        return inbox;
    }

    private String bearer(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER).issuedAt(now).expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("driver-cases-push-test")
                .claim("userId", userId)
                .claim("name", "測試")
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}

package com.example.backend.controller;

import com.example.backend.service.AuthService;
import com.example.backend.service.DriverMessagesService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推播測試：用真的 WebSocket 客戶端訂閱，再呼叫 Service 發訊息／標已讀，確認誰收到什麼。
 *
 * <p>前提同 DriverMessagesApiTest：drivers 有 id 1、2，admin_users 有 id 1。
 * 發出的訊息都帶 MARKER，測完刪掉。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class DriverMessagesPushTest {

    private static final String MARKER = "[DriverMessagesPushTest]";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private DriverMessagesService driverMessagesService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
        jdbcTemplate.update("DELETE FROM driver_messages WHERE content LIKE ?", MARKER + "%");
    }

    @Test
    void 司機發訊息_管理員和司機本人都收到_別的司機收不到() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), "/topic/admin/driver-messages");
        BlockingQueue<String> driver1 = subscribe(bearer(1L, AuthService.ROLE_DRIVER), "/user/queue/messages");
        BlockingQueue<String> driver2 = subscribe(bearer(2L, AuthService.ROLE_DRIVER), "/user/queue/messages");

        Long id = driverMessagesService.sendFromDriver(1L, MARKER + " 塞車").getId();

        String toAdmin = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(toAdmin, "管理員沒收到");
        assertEquals("MESSAGE", JsonPath.read(toAdmin, "$.type"));
        assertEquals(id.intValue(), (int) JsonPath.read(toAdmin, "$.message.id"));
        assertEquals(MARKER + " 塞車", JsonPath.read(toAdmin, "$.message.content"));
        // 時間要是字串格式（跟 REST 一樣），前端才能用同一套方式解析
        assertTrue(JsonPath.read(toAdmin, "$.message.createdAt") instanceof String, "createdAt 不是字串：" + toAdmin);

        assertNotNull(driver1.poll(5, TimeUnit.SECONDS), "司機本人沒收到");
        assertNull(driver2.poll(1, TimeUnit.SECONDS), "別的司機收到了");
    }

    @Test
    void 管理員回覆_司機收到() throws Exception {
        BlockingQueue<String> driver1 = subscribe(bearer(1L, AuthService.ROLE_DRIVER), "/user/queue/messages");

        driverMessagesService.sendFromAdmin(1L, 1L, MARKER + " 收到");

        String push = driver1.poll(5, TimeUnit.SECONDS);
        assertNotNull(push, "司機沒收到回覆");
        assertEquals("ADMIN", JsonPath.read(push, "$.message.senderType"));
        assertFalse(push.contains("senderAdminId"), "推播外洩了是哪位管理員回的");
    }

    @Test
    void 管理員標已讀_推READ() throws Exception {
        driverMessagesService.sendFromDriver(1L, MARKER + " 未讀");
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), "/topic/admin/driver-messages");

        driverMessagesService.markReadByAdmin(1L);

        String push = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(push, "標已讀沒有推播");
        assertEquals("READ", JsonPath.read(push, "$.type"));
        assertEquals(1, (int) JsonPath.read(push, "$.driverId"));
        assertEquals("DRIVER", JsonPath.read(push, "$.readSenderType"));
    }

    @Test
    void 沒東西可標就不推() throws Exception {
        driverMessagesService.markReadByAdmin(1L); // 先把可能有的未讀清掉
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), "/topic/admin/driver-messages");

        assertEquals(0, driverMessagesService.markReadByAdmin(1L));
        assertNull(admin.poll(1, TimeUnit.SECONDS), "沒有標到任何訊息卻推播了");
    }

    /** 最關鍵的一項：交易沒成功，就不能推出去 */
    @Test
    void 交易rollback就不推() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), "/topic/admin/driver-messages");

        transactionTemplate.executeWithoutResult(status -> {
            driverMessagesService.sendFromDriver(1L, MARKER + " 會被 rollback");
            status.setRollbackOnly();
        });

        assertNull(admin.poll(1, TimeUnit.SECONDS), "rollback 的訊息被推出去了");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM driver_messages WHERE content = ?", Integer.class, MARKER + " 會被 rollback"));
    }

    private BlockingQueue<String> subscribe(String authorization, String destination) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // 推播內容是 JSON（content-type: application/json），預設的 StringMessageConverter 只收 text/plain，
        // 會直接丟掉 JSON 訊框；這裡改成任何型別都當字串收，測試再用 JsonPath 讀欄位
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
                .subject("push-test").claim("userId", userId).claim("name", "測試").claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}

package com.example.backend.controller;

import com.example.backend.dao.OrdersDAO;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.service.AuthService;
import com.example.backend.service.DispatchBoardPushService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 看板推播接上真的 Hibernate 與 WebSocket：監聽器有被呼叫、rollback 不推、只有管理員能訂閱。
 *
 * <p>不留下任何資料：改訂單的測試都在 rollback 的交易裡做，flush 只是讓 Hibernate 觸發監聽器。
 * commit 後推出去的那一段由 DispatchBoardPushServiceTest 測。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class DispatchBoardPushTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private DispatchBoardPushService dispatchBoardPushService;

    @Autowired
    private OrdersDAO ordersDAO;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
    }

    /** 監聽器是 Hibernate 建的，這裡確認它真的拿到了 Spring 的 DispatchBoardPushService、而且有被呼叫 */
    @Test
    void 改訂單_監聽器記下配送日_rollback後不推() throws Exception {
        Long orderId = anyOrderId();
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), DispatchBoardPushService.ADMIN_TOPIC);

        Set<LocalDate> pending = transactionTemplate.execute(status -> {
            OrdersEntity order = ordersDAO.findById(orderId).orElseThrow();
            order.setNotes(order.getNotes() == null ? "[DispatchBoardPushTest]" : order.getNotes() + " ");
            entityManager.flush();
            Set<LocalDate> dates = dispatchBoardPushService.pendingDates();
            assertTrue(dates.contains(order.getDeliveryDate()), "監聽器沒有記下配送日：" + dates);
            status.setRollbackOnly();
            return dates;
        });

        assertFalse(pending.isEmpty());
        assertNull(admin.poll(1, TimeUnit.SECONDS), "rollback 的變動被推出去了");
    }

    @Test
    void 管理員收得到看板推播() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), DispatchBoardPushService.ADMIN_TOPIC);

        messagingTemplate.convertAndSend(DispatchBoardPushService.ADMIN_TOPIC,
                new com.example.backend.dto.respones.DispatchBoardPushResponse(LocalDate.of(2026, 9, 26)));

        String push = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(push, "管理員沒收到");
        // 日期要是 yyyy-MM-dd 字串，跟 REST 的格式一樣，前端才能直接拿去比對
        assertTrue(push.contains("\"date\":\"2026-09-26\""), push);
    }

    @Test
    void 司機訂閱看板頻道會被踢掉() throws Exception {
        StompSession driver = connect(bearer(1L, AuthService.ROLE_DRIVER));
        BlockingQueue<String> inbox = subscribe(driver, DispatchBoardPushService.ADMIN_TOPIC);

        messagingTemplate.convertAndSend(DispatchBoardPushService.ADMIN_TOPIC, "secret");

        assertNull(inbox.poll(1, TimeUnit.SECONDS), "司機收到了看板推播");
        assertFalse(driver.isConnected(), "違規訂閱後連線應該被切斷");
    }

    private Long anyOrderId() {
        List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM orders LIMIT 1", Long.class);
        assumeTrue(!ids.isEmpty(), "資料庫沒有訂單，略過");
        return ids.get(0);
    }

    private BlockingQueue<String> subscribe(String authorization, String destination) throws Exception {
        return subscribe(connect(authorization), destination);
    }

    private StompSession connect(String authorization) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
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
        return session;
    }

    private BlockingQueue<String> subscribe(StompSession session, String destination) throws Exception {
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

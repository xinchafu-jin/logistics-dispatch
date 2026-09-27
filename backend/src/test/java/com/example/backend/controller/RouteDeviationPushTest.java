package com.example.backend.controller;

import com.example.backend.constants.RouteDeviationEndReason;
import com.example.backend.dto.respones.RouteDeviationPushResponse;
import com.example.backend.entity.RouteDeviationsEntity;
import com.example.backend.service.AuthService;
import com.example.backend.service.RouteDeviationPushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 偏離推播接上真的交易與 WebSocket：commit 後才推、rollback 不推、只有管理員能訂閱。
 *
 * <p>事件用 ApplicationEventPublisher 在 TransactionTemplate 裡發，跟 RouteDeviationService 實際的用法一樣；
 * 偏離紀錄只在記憶體組出來，不寫資料庫。STOMP 連線的寫法同 DispatchBoardPushTest。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class RouteDeviationPushTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
    }

    @Test
    void 交易commit後_管理員收到推播_種類與時間格式跟REST一樣() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), RouteDeviationPushService.ADMIN_TOPIC);

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(RouteDeviationPushResponse.started(deviation())));

        String push = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(push, "管理員沒收到");
        assertTrue(push.contains("\"type\":\"STARTED\""), push);
        assertTrue(push.contains("\"driverId\":7"), push);
        assertTrue(push.contains("\"legSequence\":2"), push);
        // 時間要是 ISO 字串（跟 REST 一樣），前端才能直接 new Date() 算已偏離幾分鐘
        assertTrue(push.contains("\"startedAt\":\"2026-09-27T10:15:30\""), push);
    }

    @Test
    void 結束的推播帶結束原因() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), RouteDeviationPushService.ADMIN_TOPIC);
        RouteDeviationsEntity ended = deviation();
        ended.setEndedAt(LocalDateTime.of(2026, 9, 27, 10, 27, 0));
        ended.setEndReason(RouteDeviationEndReason.DELIVERING);

        transactionTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(RouteDeviationPushResponse.ended(ended)));

        String push = admin.poll(5, TimeUnit.SECONDS);
        assertNotNull(push, "管理員沒收到");
        assertTrue(push.contains("\"type\":\"ENDED\""), push);
        assertTrue(push.contains("\"endReason\":\"DELIVERING\""), push);
    }

    @Test
    void 交易rollback就不推() throws Exception {
        BlockingQueue<String> admin = subscribe(bearer(1L, AuthService.ROLE_ADMIN), RouteDeviationPushService.ADMIN_TOPIC);

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(RouteDeviationPushResponse.started(deviation()));
            status.setRollbackOnly();
        });

        assertNull(admin.poll(1, TimeUnit.SECONDS), "rollback 的偏離被推出去了");
    }

    @Test
    void 司機訂閱偏離頻道會被踢掉() throws Exception {
        StompSession driver = connect(bearer(1L, AuthService.ROLE_DRIVER));
        BlockingQueue<String> inbox = subscribe(driver, RouteDeviationPushService.ADMIN_TOPIC);

        messagingTemplate.convertAndSend(RouteDeviationPushService.ADMIN_TOPIC, "secret");

        assertNull(inbox.poll(1, TimeUnit.SECONDS), "司機收到了偏離推播");
        assertFalse(driver.isConnected(), "違規訂閱後連線應該被切斷");
    }

    private RouteDeviationsEntity deviation() {
        RouteDeviationsEntity deviation = new RouteDeviationsEntity();
        deviation.setId(99L);
        deviation.setRouteId(11L);
        deviation.setDriverId(7L);
        deviation.setLegSequence(2);
        deviation.setStartedAt(LocalDateTime.of(2026, 9, 27, 10, 15, 30));
        deviation.setStartLat(22.6273);
        deviation.setStartLng(120.3014);
        deviation.setStartDistanceMeters(236.5);
        return deviation;
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
                .subject("deviation-push-test").claim("userId", userId).claim("name", "測試").claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}

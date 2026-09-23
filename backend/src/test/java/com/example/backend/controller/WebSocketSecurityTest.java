package com.example.backend.controller;

import com.example.backend.service.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
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
 * 用真的 WebSocket 客戶端連上測試用後端，驗證攔截器的三道檢查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class WebSocketSecurityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final List<StompSession> sessions = new ArrayList<>();

    @AfterEach
    void disconnect() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
    }

    @Test
    void 沒帶token連不上() {
        assertThrows(Exception.class, () -> connect(null));
    }

    @Test
    void 亂填token連不上() {
        assertThrows(Exception.class, () -> connect("Bearer not-a-jwt"));
    }

    @Test
    void 管理員可以收到管理員頻道的廣播() throws Exception {
        StompSession admin = connect(bearer(1L, AuthService.ROLE_ADMIN));
        BlockingQueue<String> inbox = subscribe(admin, "/topic/admin/driver-messages");
        Thread.sleep(300);
        messagingTemplate.convertAndSend("/topic/admin/driver-messages", "hello-admin");
        assertEquals("hello-admin", inbox.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void 司機訂閱管理員頻道會被踢掉_也收不到() throws Exception {
        StompSession driver = connect(bearer(1L, AuthService.ROLE_DRIVER));
        BlockingQueue<String> inbox = subscribe(driver, "/topic/admin/driver-messages");
        Thread.sleep(500);
        messagingTemplate.convertAndSend("/topic/admin/driver-messages", "secret");
        assertNull(inbox.poll(1, TimeUnit.SECONDS), "司機收到了管理員頻道的訊息");
        assertFalse(driver.isConnected(), "違規訂閱後連線應該被切斷");
    }

    @Test
    void 私人頻道只有本人收得到() throws Exception {
        StompSession d1 = connect(bearer(1L, AuthService.ROLE_DRIVER));
        StompSession d2 = connect(bearer(2L, AuthService.ROLE_DRIVER));
        BlockingQueue<String> inbox1 = subscribe(d1, "/user/queue/messages");
        BlockingQueue<String> inbox2 = subscribe(d2, "/user/queue/messages");
        Thread.sleep(300);
        messagingTemplate.convertAndSendToUser("DRIVER:1", "/queue/messages", "for-driver-1");
        assertEquals("for-driver-1", inbox1.poll(5, TimeUnit.SECONDS));
        assertNull(inbox2.poll(1, TimeUnit.SECONDS), "司機 2 收到了司機 1 的私人訊息");
    }

    @Test
    void 客戶端直接送訊息會被擋() throws Exception {
        StompSession admin = connect(bearer(1L, AuthService.ROLE_ADMIN));
        BlockingQueue<String> inbox = subscribe(admin, "/topic/admin/driver-messages");
        StompSession driver = connect(bearer(1L, AuthService.ROLE_DRIVER));
        Thread.sleep(300);
        driver.send("/topic/admin/driver-messages", "forged");
        assertNull(inbox.poll(1, TimeUnit.SECONDS), "偽造的訊息被廣播出去了");
    }

    private StompSession connect(String authorization) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        if (authorization != null) {
            connectHeaders.add("Authorization", authorization);
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/api/ws",
                        new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<String> subscribe(StompSession session, String destination) {
        BlockingQueue<String> inbox = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
            @Override public void handleFrame(StompHeaders headers, Object payload) { inbox.add((String) payload); }
        });
        return inbox;
    }

    private String bearer(Long userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(AuthService.TOKEN_ISSUER).issuedAt(now).expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .subject("stomp-test").claim("userId", userId).claim("name", "測試").claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}

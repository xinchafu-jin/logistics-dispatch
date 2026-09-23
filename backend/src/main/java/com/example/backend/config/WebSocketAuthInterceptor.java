package com.example.backend.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * WebSocket 的檢查站：瀏覽器送進來的每一個 STOMP 訊框都會先經過 preSend。
 *
 * <p>SecurityConfig 只管 HTTP 路徑，管不到 STOMP 的連線、訂閱、送出，所以權限要在這裡自己檢查：</p>
 * <ul>
 *   <li>CONNECT：驗 JWT，驗過才能連</li>
 *   <li>SUBSCRIBE：照角色開白名單</li>
 *   <li>SEND：一律拒絕。寫入一律走 REST；不擋的話，客戶端送到 /topic/... 的訊息會被 broker 直接廣播，等於能偽造訊息</li>
 * </ul>
 * 丟出例外時，Spring 會回一個 ERROR 訊框給瀏覽器並斷線。
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    /** 兩個都是 SecurityConfig 裡現成的 bean，跟 REST API 用同一套規則驗 token */
    public WebSocketAuthInterceptor(JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        // 心跳不是 STOMP 指令，沒有 command，直接放行
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticate(accessor);
        } else if (command == StompCommand.SUBSCRIBE) {
            checkSubscription(accessor);
        } else if (command == StompCommand.SEND) {
            throw new AccessDeniedException("不接受從 WebSocket 送訊息，請改用 REST API");
        }
        return message;
    }

    /**
     * 驗 JWT，驗過後把「這條連線是誰」記在連線上，之後的訂閱都查得到。
     *
     * <p>名字用「角色:userId」（例如 DRIVER:12），不用 JWT 的 subject（account）：
     * 管理員和司機的 account 分在兩張表、沒有跨表唯一，同名時推給某位司機的私人訊息會推錯人。</p>
     */
    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw new AccessDeniedException("連線沒有帶 token");
        }
        // 過期、簽章不對、issuer 不對都會在這裡丟 JwtException，連線直接被拒
        Jwt jwt = jwtDecoder.decode(header.substring(BEARER.length()));
        Authentication fromJwt = jwtAuthenticationConverter.convert(jwt);

        String name = jwt.getClaimAsString("role") + ":" + jwt.getClaim("userId");
        accessor.setUser(new UsernamePasswordAuthenticationToken(name, null, fromJwt.getAuthorities()));
    }

    /** 白名單：只有列出來的組合可以訂閱，其他一律拒絕 */
    private void checkSubscription(StompHeaderAccessor accessor) {
        Authentication user = (Authentication) accessor.getUser();
        String destination = accessor.getDestination();
        if (user == null || destination == null) {
            throw new AccessDeniedException("尚未連線驗證");
        }

        boolean isAdmin = false;
        for (GrantedAuthority authority : user.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                isAdmin = true;
            }
        }

        // 所有管理員共用的廣播頻道
        if (isAdmin && destination.equals("/topic/admin/driver-messages")) {
            return;
        }
        // 私人頻道：Spring 只會把推給「自己名字」的訊息送進來，不用再比對是誰
        if (destination.equals("/user/queue/messages")) {
            return;
        }
        throw new AccessDeniedException("不能訂閱 " + destination);
    }
}

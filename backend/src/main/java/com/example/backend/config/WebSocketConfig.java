package com.example.backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final List<String> allowedOrigins;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private TaskScheduler heartbeatScheduler;

    // 跟 CORS 共用同一份白名單
    public WebSocketConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins,
                           WebSocketAuthInterceptor webSocketAuthInterceptor) {
        this.allowedOrigins = allowedOrigins;
        this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    }

    // 用 Spring 開 WebSocket 時自動建的排程器來送心跳。
    // 必須 @Lazy：這個排程器是 WebSocket 設定建的，而 WebSocket 設定又要先讀這個類別，直接注入會互相等待
    @Autowired
    public void setHeartbeatScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.heartbeatScheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws")
                .setAllowedOrigins(allowedOrigins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler);
        registry.setUserDestinationPrefix("/user");
    }

    /** 瀏覽器送進來的每一個 STOMP 指令，都要先經過這個檢查站；少了這段，攔截器寫了也不會執行 */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
